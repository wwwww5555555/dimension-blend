package com.naiwa.core.cv.wrap

import android.graphics.Bitmap
import com.naiwa.core.cv.scene.ScenePalette

/**
 * 四色渐变环境光着色（Light Wrap 扩展）—— 规格 7.4。
 *
 * 【为什么需要它】单一的色温偏移只是把奶蛙整体推向暖或冷；而真实环境里，
 * "上方是蓝天、下方是绿草"的场景会让物体**上蓝下绿**。用四区采样 + 二维渐变场近似这件事，
 * 比任何单一色温都更贴合复杂场景，而且成本只是一次逐像素查色 + 软光混合。
 *
 * 【三种渐变如何合成】规格同时要求"上→下垂直渐变""左→右水平渐变"和"四角双线性插值"。
 * 单用任一项都会让另一维失效（例如只用垂直渐变，左右两侧的反射色就丢了），
 * 所以三者加权平均；权重由**各方向的实测亮度差**决定 —— 上下差大就加强垂直分量，
 * 这样不是拍脑袋的 1/3 : 1/3 : 1/3。
 *
 * 【混合方式】Soft Light（pegtop 近似）：
 * ```
 * C' = (1 - 2B)·C² + 2B·C        // C = 原色，B = 环境色，均归一化到 [0,1]
 * ```
 * 选软光而不是 Overlay：软光不会把深色区域压死也不会让亮部过曝，正适合"着色"而非"染色"。
 * 只在奶蛙前景（alpha > 0.02）上生效，alpha 不变。
 */
object LightWrapGradient {

    /** 有效 alpha 下限。 */
    private const val MIN_ALPHA = 0.02f

    /** Soft Light 强度再乘一个系数：环境色本身往往很浅，直接用满强度会像蒙了一层膜。 */
    private const val SOFT_LIGHT_GAIN = 1.6f

    /**
     * 对奶蛙做四色环境光着色。
     *
     * @param frog 已做颜色协调的奶蛙（带 alpha）
     * @param palette 场景调色板（由 [com.naiwa.core.cv.scene.ScenePaletteExtractor] 提取）
     * @param strength [0, 0.4]；**0 直接返回原图**
     * @return 新位图（调用方负责回收）
     */
    fun apply(frog: Bitmap, palette: ScenePalette, strength: Float): Bitmap {
        val w = frog.width
        val h = frog.height
        if (w <= 0 || h <= 0 || strength <= 0f) {
            return frog.copy(Bitmap.Config.ARGB_8888, false) ?: frog
        }
        val pixels = IntArray(w * h)
        frog.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = wrapPixels(pixels, w, h, palette, strength)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 纯计算版（可 JVM 单测）。
     *
     * @param pixels ARGB；alpha 原样保留
     */
    fun wrapPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: ScenePalette,
        strength: Float,
    ): IntArray {
        val out = pixels.copyOf()
        if (width <= 0 || height <= 0 || strength <= 0f || pixels.size < width * height) return out

        // 方向权重：由实测亮度差决定（差越大，那一维越重要）
        val vDiff = kotlin.math.abs(palette.topLuma - palette.bottomLuma) / 255f
        val hDiff = kotlin.math.abs(palette.leftLuma - palette.rightLuma) / 255f
        val wV = 1f + 2f * vDiff
        val wH = 1f + 2f * hDiff
        val wC = 1f
        val wSum = wV + wH + wC

        val s = strength.coerceIn(0f, 0.4f)
        val invW = 1f / (width - 1).coerceAtLeast(1)
        val invH = 1f / (height - 1).coerceAtLeast(1)

        for (y in 0 until height) {
            val t = y * invH
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val c = pixels[i]
                if (((c ushr 24) and 0xFF) / 255f <= MIN_ALPHA) continue
                val u = x * invW

                // 垂直 / 水平 / 四角双线性三路合成
                val vRgb = lerpColor(palette.topColor, palette.bottomColor, t)
                val hRgb = lerpColor(palette.leftColor, palette.rightColor, u)
                val cRgb = cornerBilinear(palette, u, t)

                val fr = (vRgb[0] * wV + hRgb[0] * wH + cRgb[0] * wC) / wSum
                val fg = (vRgb[1] * wV + hRgb[1] * wH + cRgb[1] * wC) / wSum
                val fb = (vRgb[2] * wV + hRgb[2] * wH + cRgb[2] * wC) / wSum

                val nr = softLight(((c shr 16) and 0xFF), fr, s)
                val ng = softLight(((c shr 8) and 0xFF), fg, s)
                val nb = softLight((c and 0xFF), fb, s)
                out[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    /** 四角双线性：四个角 = 相邻两区的中值，然后按 (u,t) 双线性插值。 */
    private fun cornerBilinear(palette: ScenePalette, u: Float, t: Float): FloatArray {
        val tl = mix(palette.topColor, palette.leftColor, 0.5f)
        val tr = mix(palette.topColor, palette.rightColor, 0.5f)
        val bl = mix(palette.bottomColor, palette.leftColor, 0.5f)
        val br = mix(palette.bottomColor, palette.rightColor, 0.5f)

        val topRgb = lerpColor(tl, tr, u)
        val bottomRgb = lerpColor(bl, br, u)
        return floatArrayOf(
            topRgb[0] + (bottomRgb[0] - topRgb[0]) * t,
            topRgb[1] + (bottomRgb[1] - topRgb[1]) * t,
            topRgb[2] + (bottomRgb[2] - topRgb[2]) * t,
        )
    }

    /** 归一化软光（pegtop）。返回值 0~255。 */
    private fun softLight(channel: Int, field: Float, strength: Float): Int {
        val c = (channel / 255f).coerceIn(0f, 1f)
        val b = (field / 255f).coerceIn(0f, 1f)
        val mixed = ((1f - 2f * b) * c * c + 2f * b * c).coerceIn(0f, 1f)
        // 软光本身很轻，乘个增益让它"看得出来"，再用 strength 收回来
        val lifted = (c + (mixed - c) * SOFT_LIGHT_GAIN).coerceIn(0f, 1f)
        val result = c + (lifted - c) * strength
        return (result * 255f).toInt().coerceIn(0, 255)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): FloatArray {
        val k = t.coerceIn(0f, 1f)
        return floatArrayOf(
            ((a shr 16) and 0xFF) + (((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * k,
            ((a shr 8) and 0xFF) + (((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * k,
            (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * k,
        )
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val c = lerpColor(a, b, t)
        return (0xFF shl 24) or
            (c[0].toInt().coerceIn(0, 255) shl 16) or
            (c[1].toInt().coerceIn(0, 255) shl 8) or
            c[2].toInt().coerceIn(0, 255)
    }
}
