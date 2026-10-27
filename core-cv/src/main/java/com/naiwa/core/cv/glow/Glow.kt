package com.naiwa.core.cv.glow

import android.graphics.Bitmap
import com.naiwa.core.cv.util.BoxBlur
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 辉光 / 泛光（Glow / Bloom）—— 规格 7.2。
 *
 * 【为什么只做"内部泛光"】真正的 bloom 会溢出到背景上，但本算子的输入是**奶蛙自身**的位图，
 * 溢出到 alpha=0 的区域没有地方承载（也不能改 alpha，否则融合掩膜会跟着变）。
 * 所以这里让模糊后的高光场在轮廓内侧堆积：贴边的高光会把亮边"糊"出去一点，
 * 视觉上已经足够接近逆光泛光，且不破坏 alpha 契约。
 *
 * 【保守的参数】σ 在 1080p 工作域取 12px（规格 8~16），强度默认 0.2（上限 0.3）。
 * 加大到 0.5 以上就会变成"发光贴纸" —— 真实镜头的光晕是低频、低幅的。
 *
 * 【方向模糊（规格 9 增强）】辉光沿主光方向拉伸（椭圆高斯核），模拟逆光镜头光晕的方向性；
 * 横向/纵向半径由方向角分解。遮罩控制（[glowMask]）可限定辉光只作用于指定区域。
 */
object Glow {

    /** 高光阈值：Lab L 归一化后的 0.8 ≈ Rec.709 luma 204。 */
    const val HIGHLIGHT_LUMA = 204f

    /** 1080p 工作域的 σ 基准（规格 7.2：8~16px）。 */
    private const val SIGMA_AT_1080 = 12f

    /** σ 允许区间（小图不要糊成一团，大图不要变成整片白雾）。 */
    private const val SIGMA_MIN = 4f
    private const val SIGMA_MAX = 20f

    /**
     * 对奶蛙做泛光。
     *
     * @param frog 已做颜色协调与边缘光的奶蛙（带 alpha）
     * @param strength [0, 0.3]；**0 或负数直接返回原图**（省一次σ级模糊）
     * @param directionAngleDeg 方向模糊的主轴角度（度，0 = 水平向右，逆时针）；null = 各向同性
     * @param glowMask 可选辉光遮罩（0~1，限定辉光作用区域）；null = 全前景自动阈值掩膜
     * @return 新位图（调用方负责回收）
     */
    fun apply(
        frog: Bitmap,
        strength: Float,
        directionAngleDeg: Float? = null,
        glowMask: FloatArray? = null,
    ): Bitmap {
        val w = frog.width
        val h = frog.height
        if (w <= 0 || h <= 0 || strength <= 0f) {
            return frog.copy(Bitmap.Config.ARGB_8888, false) ?: frog
        }
        val pixels = IntArray(w * h)
        frog.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = glowPixels(pixels, w, h, strength, directionAngleDeg, glowMask)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 纯计算版（可 JVM 单测）。alpha 保持不变。
     *
     * 流程：高光掩膜（可被 [glowMask] 限制）→ 方向模糊（椭圆高斯）→ Screen 混合叠回。
     */
    fun glowPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        directionAngleDeg: Float? = null,
        glowMask: FloatArray? = null,
    ): IntArray {
        val out = pixels.copyOf()
        if (width <= 0 || height <= 0 || strength <= 0f || pixels.size < width * height) return out

        // --- 1) 高光掩膜：只保留超过阈值的部分，并按超出幅度加权（暗部完全不参与）---
        // 若传入显式 glowMask，则用它作为附加权重（遮罩控制）
        val mask = FloatArray(width * height)
        var any = false
        for (i in 0 until width * height) {
            val c = pixels[i]
            if (((c ushr 24) and 0xFF) < 8) continue
            val luma = 0.2126f * ((c shr 16) and 0xFF) +
                0.7152f * ((c shr 8) and 0xFF) +
                0.0722f * (c and 0xFF)
            if (luma <= HIGHLIGHT_LUMA) continue
            // 归一化到 [0,1]：刚过阈值时很弱，纯白时为 1
            val base = ((luma - HIGHLIGHT_LUMA) / (255f - HIGHLIGHT_LUMA)).coerceIn(0f, 1f)
            // 遮罩控制：显式 mask 乘进来，限定辉光作用区域
            val masked = if (glowMask != null && glowMask.size == width * height) {
                base * glowMask[i].coerceIn(0f, 1f)
            } else {
                base
            }
            if (masked <= 0f) continue
            mask[i] = masked
            any = true
        }
        if (!any) return out

        // --- 2) 方向模糊（椭圆高斯）：沿主光方向拉伸 ---
        val sigma = (SIGMA_AT_1080 * minOf(width, height) / 1080f).coerceIn(SIGMA_MIN, SIGMA_MAX)
        val bloom = if (directionAngleDeg != null) {
            // 方向角分解成横向/纵向半径：主轴方向半径更大（拉伸），垂直方向更小
            val angleRad = directionAngleDeg * (Math.PI / 180.0)
            val cosA = abs(cos(angleRad).toFloat())
            val sinA = abs(sin(angleRad).toFloat())
            // 主轴拉长 1.6×，副轴 0.6×（椭圆），半径分解到 x/y 轴
            val rx = BoxBlur.radiusForSigma(sigma * (1.6f * cosA + 0.6f * sinA))
            val ry = BoxBlur.radiusForSigma(sigma * (1.6f * sinA + 0.6f * cosA))
            BoxBlur.blurDirectional(mask, width, height, rx, ry, passes = 3)
        } else {
            BoxBlur.blur(mask, width, height, BoxBlur.radiusForSigma(sigma), passes = 3)
        }

        // --- 3) Screen 混合：out = 255 - (255-c)(255-glow)/255 ---
        val s = strength.coerceIn(0f, 0.3f)
        for (i in out.indices) {
            val g = bloom[i]
            if (g <= 0.004f) continue
            val c = out[i]
            // 辉光取自身颜色（白得越纯越亮），即"用奶蛙的高光去照亮它自己"
            val add = (g * s * 255f)
            val nr = screenBlend((c shr 16) and 0xFF, add)
            val ng = screenBlend((c shr 8) and 0xFF, add)
            val nb = screenBlend(c and 0xFF, add)
            out[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }

    /** Screen 混合（对单个通道）：结果恒 ≥ 两者，因此只会变亮，符合泛光语义。 */
    private fun screenBlend(base: Int, add: Float): Int {
        val a = base.coerceIn(0, 255)
        val b = add.coerceIn(0f, 255f)
        val v = 255f - (255f - a) * (255f - b) / 255f
        return v.toInt().coerceIn(0, 255)
    }
}
