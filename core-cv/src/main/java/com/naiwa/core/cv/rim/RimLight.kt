package com.naiwa.core.cv.rim

import android.graphics.Bitmap
import com.naiwa.core.common.model.LightParams
import com.naiwa.core.cv.util.DistanceField
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 边缘光（Rim Light）—— 规格 7.1 / 7.1a 的精细化实现。
 *
 * 【7.1a 的核心升级】上一版有两个物理偏差，本轮修正：
 *  1. **法向改用距离变换梯度**（`DistanceField.outwardNormal`）—— 不再依赖"模糊 alpha 的中心差分"，
 *     对锯齿轮廓更稳健，也不因羽化半径不同而漂移。距离场在物体内部单调增大，梯度指向内部，
 *     取负即外法向。
 *  2. **点积方向升级为 3D** —— 上一版只用 `keyAzimuthDeg` 的 2D 方向，完全忽略 `keyElevationDeg`。
 *     现在光源方向是 3D 单位向量，elevation 通过 `cos(elevation)` 调制光源在图像平面内的分量长度：
 *     **光源越接近正上方（elevation→90°），方向性越弱、亮边越趋向均匀**；低角度光（elevation≈0°）
 *     方向性最强。这是 elevation 的真实物理作用。
 *  3. **权重 = 点积^1.5** —— 避免半影区（点积略大于 0）过亮，让亮边集中在真正朝向光源的窄带内。
 *
 * 【2D/3D 的诚实说明】奶蛙是 2D 抠图，没有深度信息，边缘像素的真实 3D 法向 z 分量**不可恢复**。
 * 但边缘像素的法向基本躺在图像平面内（nz≈0），所以 3D 点积退化为 `n2d · L_xy`，其中
 * `L_xy = (cos(elev)·cos(az), cos(elev)·sin(az))`。elevation 的作用不是引入一个臆造的 nz，
 * 而是**衰减光源的水平分量**——这比"完全忽略 elevation"更物理，且不引入无法验证的伪参数。
 *
 * 【叠加方式】Add（规格 7.1）：`out = clamp(c + rimColor × weight × strength)`。
 * 颜色取场景高光区中位色（由调用方传入）；强度 = keyIntensity × 0.3（由 LightFxRules 算好）。
 * 宽度 2~4px，沿法向 1D 高斯衰减。
 *
 * 【无动画】纯静态光效，逐像素计算光照贡献，输出静态图。**不存在**沿轮廓移动的动态光带。
 */
object RimLight {

    /** 环带 alpha 下界与过渡宽度（规格 7.1：0.3~0.9）。 */
    private const val RING_MIN = 0.30f
    private const val RING_MIN_FEATHER = 0.10f
    private const val RING_MAX = 0.90f
    private const val RING_MAX_FEATHER = 0.10f

    /** 1D 高斯核（沿法向）。3 抽头在 2~4px 宽度下足够，5 抽头会明显变钝。 */
    private val NORMAL_KERNEL = floatArrayOf(0.25f, 0.5f, 0.25f)

    /** 点积的幂（规格 7.1a：^1.5，避免半影区过亮）。 */
    private const val FACING_POWER = 1.5f

    /**
     * 给奶蛙加边缘光。
     *
     * @param frog 已做颜色协调的奶蛙（带 alpha，不被修改）
     * @param light 用 [LightParams.keyAzimuthDeg]（方位）+ [LightParams.keyElevationDeg]（高度角）
     *   换算的 3D 光源方向决定亮边在哪一侧
     * @param strength 强度，[0, 0.5]；**0 = 直接返回原图**（省一次整图循环）
     * @param rimColor 边缘光颜色（场景高光区中位色）；null 时退回按色温取
     * @param widthPx 亮边宽度（像素），规格 2~4px
     * @return 新位图（调用方负责回收）
     */
    fun apply(
        frog: Bitmap,
        light: LightParams,
        strength: Float,
        rimColor: Int? = null,
        widthPx: Float = 3f,
    ): Bitmap {
        val w = frog.width
        val h = frog.height
        if (w <= 0 || h <= 0 || strength <= 0f) {
            return frog.copy(Bitmap.Config.ARGB_8888, false) ?: frog
        }
        val pixels = IntArray(w * h)
        frog.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = rimPixels(
            pixels = pixels,
            width = w,
            height = h,
            azimuthDeg = light.keyAzimuthDeg,
            elevationDeg = light.keyElevationDeg,
            strength = strength,
            rimColor = rimColor ?: autoRimColor(light.colorTempK),
            widthPx = widthPx,
        )

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 纯计算版（可 JVM 单测）。
     *
     * @param pixels ARGB 像素；alpha 与输入**完全一致**（光效不能改透明度，否则融合会出错）
     * @param azimuthDeg 光源方位角（图像坐标：0°=右、90°=下、180°=左）
     * @param elevationDeg 光源高度角（0°=平射、90°=正上方）
     */
    fun rimPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        azimuthDeg: Float,
        elevationDeg: Float = 45f,
        strength: Float,
        rimColor: Int,
        widthPx: Float = 3f,
    ): IntArray {
        val out = pixels.copyOf()
        if (width <= 0 || height <= 0 || strength <= 0f || pixels.size < width * height) return out

        val alpha = FloatArray(width * height) { ((pixels[it] ushr 24) and 0xFF) / 255f }

        // --- 距离变换法向（规格 7.1a）---
        // 用二值掩膜（alpha ≥ 0.5 视为前景）求距离场，梯度即外法向。
        // 阈值 0.5 让"环带内的半透明像素"也算作前景，从而在环带里也能取到法向。
        val binary = IntArray(width * height) { if (alpha[it] >= 0.5f) 1 else 0 }
        val distField = DistanceField.compute(binary, width, height)

        // 沿法向的 1D 高斯：抽样间距 = 宽度的一半，核固定 3 抽头
        val step = (widthPx.coerceIn(2f, 4f) * 0.5f).coerceAtLeast(0.5f)

        // --- 3D 光源方向（规格 7.1a）---
        // 光源 3D 单位向量：L = (cos(elev)·cos(az), cos(elev)·sin(az), sin(elev))。
        // 边缘像素 nz≈0，点积退化为 n2d · L_xy；elevation 通过 cos(elev) 衰减水平分量。
        val elevRad = Math.toRadians(elevationDeg.coerceIn(0f, 90f).toDouble())
        val cosElev = cos(elevRad).toFloat()
        val lx = cosElev * cos(Math.toRadians(azimuthDeg.toDouble())).toFloat()
        val ly = cosElev * sin(Math.toRadians(azimuthDeg.toDouble())).toFloat()

        val s = strength.coerceIn(0f, 0.5f)
        val rr = (rimColor shr 16) and 0xFF
        val rg = (rimColor shr 8) and 0xFF
        val rb = rimColor and 0xFF

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val a = alpha[i]
                if (a <= 0.02f) continue

                // --- 环带权重（规格 7.1 的 alpha ∈ [0.3, 0.9]）---
                val ring = smoothStep(RING_MIN - RING_MIN_FEATHER, RING_MIN, a) *
                    (1f - smoothStep(RING_MAX - RING_MAX_FEATHER, RING_MAX + RING_MAX_FEATHER, a))
                if (ring <= 0.01f) continue

                // --- 外法向：距离变换梯度（规格 7.1a）---
                val n = DistanceField.outwardNormal(distField, width, height, x, y)
                val nx = n[0]
                val ny = n[1]
                if (nx * nx + ny * ny < 1e-4f) continue

                // --- 朝向：点积 = 外法向 · 光源水平方向；权重 = 点积^1.5（规格 7.1a）---
                val dot = nx * lx + ny * ly
                if (dot <= 0.02f) continue
                // 点积^1.5：只有真正朝向光源的窄带才显著，半影区（dot 略大于 0）被压得更暗
                val facing = positivePow(dot, FACING_POWER)

                // --- 沿法向的 1D 高斯（让亮边有厚度而不是一条硬线）---
                var smoothed = 0f
                var weightSum = 0f
                for (k in NORMAL_KERNEL.indices) {
                    val offset = (k - 1) * step
                    val sx = (x + nx * offset).toInt()
                    val sy = (y + ny * offset).toInt()
                    if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue
                    // 用 alpha 而非距离场做 1D 高斯：alpha 在环带内平滑，能产生自然的厚度过渡
                    smoothed += NORMAL_KERNEL[k] * alpha[coerceIdx(sx, sy, width, height)]
                    weightSum += NORMAL_KERNEL[k]
                }
                if (weightSum <= 0f) continue
                smoothed /= weightSum

                val k = (ring * facing * (0.5f + smoothed * 0.5f) * s).coerceIn(0f, 1f)
                if (k <= 0.002f) continue

                // --- Add 混合 ---
                val c = out[i]
                val nr = (((c shr 16) and 0xFF) + rr * k).toInt().coerceIn(0, 255)
                val ng = (((c shr 8) and 0xFF) + rg * k).toInt().coerceIn(0, 255)
                val nb = ((c and 0xFF) + rb * k).toInt().coerceIn(0, 255)
                out[i] = ((c ushr 24) shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    /** 正数的幂（dot 已保证 > 0，这里只做 `pow` 的安全包装）。 */
    private fun positivePow(x: Float, p: Float): Float = Math.pow(x.toDouble(), p.toDouble()).toFloat()

    private fun coerceIdx(x: Int, y: Int, width: Int, height: Int): Int =
        y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)

    /** 按色温取轮廓光颜色（无场景高光色可用时的兜底）。 */
    private fun autoRimColor(colorTempK: Float): Int {
        val t = ((colorTempK - 3000f) / 6000f).coerceIn(0f, 1f)
        val r = (255f + (214f - 255f) * t).toInt()
        val g = 232
        val b = (196f + (255f - 196f) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
