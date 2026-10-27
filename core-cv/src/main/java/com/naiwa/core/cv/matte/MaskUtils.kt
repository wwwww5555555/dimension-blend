package com.naiwa.core.cv.matte

import android.graphics.Bitmap
import com.naiwa.core.cv.util.BoxBlur
import kotlin.math.max

/**
 * 掩膜工具：从位图取 alpha 掩膜、清理掩膜、以及"掩膜腐蚀"。
 *
 * 为什么要单独做"腐蚀"这一步：泊松融合的**色偏主要来自掩膜边缘那一圈背景残留**。
 * 先把掩膜内缩 2~4 px 把这一圈砍掉，融合结果的观感会有质的变化
 * （这是架构文档 §6.6 三件套的第一步）。
 */
object MaskUtils {

    /**
     * 从奶蛙位图提取 alpha 掩膜（0~255，行优先）。
     * 注意返回的是"当前位图的 alpha"，若素材本身透明边缘很糊，掩膜也会是糊的
     * —— 这没问题，模糊边缘恰好是融合需要的过渡带。
     */
    fun extractAlphaMask(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(w * h) { i -> (pixels[i] ushr 24) and 0xFF }
    }

    /**
     * 掩膜腐蚀（半径以像素计）。方形核，与 `ColorKeyMatte` 里的实现同源。
     *
     * @param radius 内缩像素数，建议 2~4（架构文档 §6.6）
     */
    fun shrink(mask: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return mask.copyOf()
        val out = IntArray(mask.size)
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = minOf(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = minOf(width - 1, x + radius)
                var minValue = 255
                var yy = y0
                while (minValue > 0 && yy <= y1) {
                    val row = yy * width
                    var xx = x0
                    while (xx <= x1) {
                        val v = mask[row + xx]
                        if (v < minValue) minValue = v
                        xx++
                    }
                    yy++
                }
                out[y * width + x] = minValue
            }
        }
        return out
    }

    /** 二值化（用于连通域/统计，不用于渲染）。 */
    fun binarize(mask: IntArray, threshold: Int = 128): BooleanArray =
        BooleanArray(mask.size) { mask[it] >= threshold }

    /** 前景占比（alpha 高于阈值的像素比例），用于诊断与 UI 提示。 */
    fun foregroundRatio(mask: IntArray, threshold: Int = 128): Float {
        if (mask.isEmpty()) return 0f
        var count = 0
        for (v in mask) if (v >= threshold) count++
        return count.toFloat() / mask.size
    }

    /** 对掩膜做羽化（用于"羽化直贴"路径）。 */
    fun feather(mask: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return mask.copyOf()
        val floatMask = FloatArray(mask.size) { mask[it].toFloat() }
        val blurred = BoxBlur.blur(floatMask, width, height, radius, passes = 2)
        return IntArray(mask.size) { blurred[it].toInt().coerceIn(0, 255) }
    }

    /**
     * 把掩膜缩放到目标尺寸（最近邻）。
     * 用于"奶蛙预备分辨率"与"场景工作分辨率"不一致时对齐。
     */
    fun resizeNearest(
        mask: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): IntArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return mask.copyOf()
        val out = IntArray(dstWidth * dstHeight)
        val scaleX = srcWidth.toFloat() / dstWidth
        val scaleY = srcHeight.toFloat() / dstHeight
        for (y in 0 until dstHeight) {
            val sy = (y * scaleY).toInt().coerceIn(0, srcHeight - 1)
            for (x in 0 until dstWidth) {
                val sx = (x * scaleX).toInt().coerceIn(0, srcWidth - 1)
                out[y * dstWidth + x] = mask[sy * srcWidth + sx]
            }
        }
        return out
    }
}
