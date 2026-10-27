package com.naiwa.core.cv.color

/**
 * 把 3D LUT 应用到奶蛙前景（规格 8.1）。
 *
 * 作用域：**仅 alpha > 0 的前景像素**，背景（alpha=0）完全不碰。
 * 支持单 LUT，也支持两套 LUT 按 [blend] 插值（规格 8.1 的"两套 LUT 三线性插值"）。
 *
 * @param lut 主 LUT
 * @param secondary 次 LUT（null = 只用主 LUT）
 * @param blend 主 LUT 权重 [0,1]；次 LUT 权重 = 1 - blend
 */
object LutApply {

    /**
     * 高层入口：对奶蛙位图应用 LUT（仅前景 RGB，alpha 不变）。
     *
     * @param frog 带 alpha 的奶蛙位图（不被修改）
     * @return 新位图（调用方负责回收）
     */
    fun apply(
        frog: android.graphics.Bitmap,
        lut: CubeLut3d,
        secondary: CubeLut3d? = null,
        blend: Float = 1f,
    ): android.graphics.Bitmap {
        val w = frog.width
        val h = frog.height
        if (w <= 0 || h <= 0) return frog.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: frog
        val pixels = IntArray(w * h)
        frog.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = applyPixels(pixels, w, h, lut, secondary, blend)
        val result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 纯计算版（可 JVM 单测）。
     *
     * @param pixels ARGB 像素；alpha 原样保留，仅 RGB 被映射
     * @return 新数组
     */
    fun applyPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        lut: CubeLut3d,
        secondary: CubeLut3d? = null,
        blend: Float = 1f,
    ): IntArray {
        val out = pixels.copyOf()
        if (width <= 0 || height <= 0 || pixels.size < width * height) return out

        val b = blend.coerceIn(0f, 1f)
        val useBlend = secondary != null && b < 1f

        for (i in out.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a == 0) continue // 背景不碰

            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bl = c and 0xFF

            val mapped = lut.map(r, g, bl)
            if (useBlend) {
                val mapped2 = secondary!!.map(r, g, bl)
                for (ch in 0 until 3) {
                    mapped[ch] = (mapped[ch] * b + mapped2[ch] * (1f - b)).toInt()
                        .coerceIn(0, 255)
                }
            }

            out[i] = (a shl 24) or (mapped[0] shl 16) or (mapped[1] shl 8) or mapped[2]
        }
        return out
    }
}
