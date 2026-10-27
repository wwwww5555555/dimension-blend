package com.naiwa.feature.editor.ui

import androidx.compose.ui.graphics.ColorMatrix

/**
 * 调色参数（调色 Tab 的四件套）。
 *
 * 【为什么独立于 [com.naiwa.core.common.model.LightOverrides]】
 * 这四个参数走的是**两条不同路径**：
 *  - 实时预览：直接作为 Compose `ColorFilter.colorMatrix` 作用在 Image 上 ——
 *    **零位图处理、零渲染管线**，拖动时逐帧跟手（8.4 的要求）；
 *  - 保存：把同一个矩阵烧进位图一次（纯矩阵运算，不是重渲染）。
 * 它们不需要进光照/融合管线，也就不该污染 LightOverrides 的语义。
 */
data class ColorAdjust(
    /** 色温偏移，-1（冷）~ +1（暖）。 */
    val temperature: Float = 0f,
    /** 亮度，-1 ~ +1。 */
    val brightness: Float = 0f,
    /** 对比度，-1 ~ +1。 */
    val contrast: Float = 0f,
    /** 饱和度，-1 ~ +1。 */
    val saturation: Float = 0f,
) {
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * 合成 4×5 ColorMatrix。组合顺序与 Photoshop 的"色温→对比度→饱和度→亮度"一致，
     * 顺序不对会让高光先溢出再压暗，出现死白。
     */
    fun toColorMatrix(): ColorMatrix {
        val m = ColorMatrix()

        // 1) 色温：暖 = R 增益 / B 衰减，冷反之（以中性 1.0 为轴）
        val warm = temperature.coerceIn(-1f, 1f) * 0.12f
        val temp = ColorMatrix(
            floatArrayOf(
                1f + warm, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - warm, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        m.timesAssign(temp)

        // 2) 饱和度（Rec.601 亮度权重，色相不变）
        m.setToSaturation((1f + saturation.coerceIn(-1f, 1f)).coerceIn(0f, 2f))

        // 3) 对比度：绕 128 中灰缩放
        val c = 1f + contrast.coerceIn(-1f, 1f) * 0.8f
        val t = (1f - c) * 128f
        m.timesAssign(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        // 4) 亮度：RGB 通道统一 offset
        val b = brightness.coerceIn(-1f, 1f) * 60f
        m.values[4] += b
        m.values[9] += b
        m.values[14] += b
        return m
    }

    companion object {
        val DEFAULT = ColorAdjust()
    }
}

/** 把 4×5 矩阵烧进像素（保存路径专用：一次 matrix 运算，不触碰渲染管线）。 */
fun applyColorMatrixToPixels(pixels: IntArray, matrix: ColorMatrix): IntArray {
    if (matrix == ColorMatrix()) return pixels.copyOf()
    val v = matrix.values
    val out = IntArray(pixels.size)
    for (i in pixels.indices) {
        val c = pixels[i]
        val a = (c ushr 24) and 0xFF
        if (a == 0) { out[i] = c; continue }
        val r = ((c shr 16) and 0xFF).toFloat()
        val g = ((c shr 8) and 0xFF).toFloat()
        val b = (c and 0xFF).toFloat()
        val nr = (v[0] * r + v[1] * g + v[2] * b + v[3] * a + v[4]).toInt().coerceIn(0, 255)
        val ng = (v[5] * r + v[6] * g + v[7] * b + v[8] * a + v[9]).toInt().coerceIn(0, 255)
        val nb = (v[10] * r + v[11] * g + v[12] * b + v[13] * a + v[14]).toInt().coerceIn(0, 255)
        out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    return out
}
