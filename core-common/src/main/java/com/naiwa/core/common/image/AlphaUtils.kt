package com.naiwa.core.common.image

import android.graphics.Bitmap
import com.naiwa.core.common.util.BitmapGuard

/**
 * alpha 通道工具。
 *
 * 用途：判断「用户导入的奶蛙图是否已经带透明通道」。判断错了的后果很直观 ——
 * 把白底图当透明图直接融合，场景里会出现一个白色方块。
 */
object AlphaUtils {

    /**
     * 位图是否带**可用**的透明度。
     *
     * 判定标准不是「有 alpha 通道」（ARGB_8888 永远有），而是「确实存在接近透明的像素」：
     * 白底 JPG 转成 ARGB_8888 后 alpha 全为 255，属于无透明通道，必须走抠图流程。
     *
     * @param transparentThreshold alpha 低于该值视为透明，默认 16（留一点容差避免误判半透明边缘）
     * @param minRatio 透明像素占比超过该比例才认为"可用"，默认 2%
     *                —— 太低会把「边缘有个别透明像素」的图误判为已抠好
     */
    fun hasUsableAlpha(
        bitmap: Bitmap?,
        transparentThreshold: Int = 16,
        minRatio: Float = 0.02f,
    ): Boolean {
        if (!BitmapGuard.isUsable(bitmap)) return false
        val bmp = bitmap!!
        if (!bmp.hasAlpha()) return false

        val width = bmp.width
        val height = bmp.height
        // 大图稀疏采样即可：透明背景一定成片出现，不需要逐像素扫
        val step = maxOf(1, minOf(width, height) / 128)
        var sampled = 0
        var transparent = 0
        val row = IntArray(width)
        var y = 0
        while (y < height) {
            bmp.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                sampled++
                if ((row[x] ushr 24) < transparentThreshold) transparent++
                x += step
            }
            y += step
        }
        if (sampled == 0) return false
        return transparent.toFloat() / sampled >= minRatio
    }

    /**
     * 计算不透明像素的包围盒，用于裁掉多余透明边距。
     *
     * @param alphaThreshold 视为"有效内容"的 alpha 下限
     * @return `intArrayOf(left, top, right, bottom)`（含边界，right/bottom 为开区间）；全透明时返回 null
     */
    fun opaqueBounds(bitmap: Bitmap?, alphaThreshold: Int = 8): IntArray? {
        if (!BitmapGuard.isUsable(bitmap)) return null
        val bmp = bitmap!!
        val width = bmp.width
        val height = bmp.height
        val row = IntArray(width)
        var left = width
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            bmp.getPixels(row, 0, width, 0, y, width, 1)
            var rowMin = -1
            var rowMax = -1
            for (x in 0 until width) {
                if ((row[x] ushr 24) >= alphaThreshold) {
                    if (rowMin < 0) rowMin = x
                    rowMax = x
                }
            }
            if (rowMin >= 0) {
                if (top < 0) top = y
                bottom = y
                if (rowMin < left) left = rowMin
                if (rowMax > right) right = rowMax
            }
        }
        if (right < 0) return null
        return intArrayOf(left, top, right + 1, bottom + 1)
    }
}
