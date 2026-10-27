package com.naiwa.core.cv.blend

import android.graphics.Bitmap
import com.naiwa.core.cv.util.BoxBlur

/**
 * 对位图的 alpha 通道做羽化。
 *
 * 用途：生成"羽化直贴"结果 —— 即直接把奶蛙按软边画到场景上。
 * 它是 [EdgeHaloFix] 三段式里过渡带的一半权重来源：
 * 纯泊松融合在高频背景上会糊一块，纯直贴会在过渡带留下色温断层，两者加权才同时消掉。
 */
object AlphaFeather {

    /**
     * 返回 alpha 通道被羽化的**新位图**（RGB 不变，输入不被修改）。
     *
     * @param featherPx 羽化半径（像素）。用 [EdgeHaloFix.featherPx] 计算，带量纲。
     */
    fun feather(bitmap: Bitmap, featherPx: Int): Bitmap {
        if (featherPx <= 0) return bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val alpha = FloatArray(w * h) { i -> ((pixels[i] ushr 24) and 0xFF).toFloat() }
        val blurred = BoxBlur.blur(alpha, w, h, BoxBlur.radiusForSigma(featherPx.toFloat()), passes = 2)

        val out = IntArray(w * h)
        for (i in out.indices) {
            val a = blurred[i].toInt().coerceIn(0, 255)
            // RGB 保持原样：保留原色可以避免羽化边缘出现黑边（premultiply 后透明区的颜色不参与）
            out[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
