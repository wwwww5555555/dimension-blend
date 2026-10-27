package com.naiwa.core.cv.blend

import com.naiwa.core.cv.util.BoxBlur

/**
 * 拉普拉斯金字塔多频段融合 —— **保底档（档 3）**。
 *
 * 为什么它必须存在：
 *  - 泊松融合（seamlessClone）在中端机上 540p 域就要 0.6~1.3 s，低端机更慢，超时风险实打实；
 *  - 本实现是纯卷积 + 金字塔，0.25~0.6 s 能跑完，且**不依赖 OpenCV**（OpenCV 加载失败也照样融合）。
 * 质量比泊松略低（颜色梯度不是全局最优解），但绝不会出现接缝与高频糊块。
 *
 * 【算法】经典多频段融合，逐通道独立处理：
 * ```
 * G_0 = 原图，G_{i+1} = downsample(G_i)
 * L_i = G_i - upsample(G_{i+1})            // 拉普拉斯层：该层的高频细节
 * 各层按掩膜权重混合后，从最粗层向上重建
 * ```
 * 低频（颜色/光照过渡）在粗层按掩膜平滑过渡 → 消除接缝；
 * 高频（背景纹理）大量保留场景自身 → 避免"糊一块"。
 */
object LaplacianBlender {

    /**
     * 金字塔层数。4 层在 1080p 工作域下覆盖到约 1/8 分辨率，
     * 也就是"颜色过渡"的尺度；再多层收益很小（最粗层已经几乎是常数色）。
     */
    private const val LEVELS = 4

    /**
     * 把奶蛙按掩膜融合到场景上。
     *
     * @param scenePixels 场景（掩膜外区域保持原样）
     * @param frogPixels 已做颜色协调/轮廓光的奶蛙像素（ARGB，带 alpha）
     * @param mask 前景掩膜（0~255），与图像同尺寸
     * @return 融合结果（新数组）
     */
    fun blend(
        scenePixels: IntArray,
        frogPixels: IntArray,
        mask: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val r = blendOneChannel(scenePixels, frogPixels, mask, width, height, CHANNEL_R)
        val g = blendOneChannel(scenePixels, frogPixels, mask, width, height, CHANNEL_G)
        val b = blendOneChannel(scenePixels, frogPixels, mask, width, height, CHANNEL_B)

        val out = IntArray(width * height)
        for (i in out.indices) {
            val srcAlpha = (scenePixels[i] ushr 24) and 0xFF
            val a = if (srcAlpha > 0) srcAlpha else 255
            out[i] = (a shl 24) or
                (r[i].toInt().coerceIn(0, 255) shl 16) or
                (g[i].toInt().coerceIn(0, 255) shl 8) or
                b[i].toInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * 单通道的多频段融合（自建金字塔 → 逐层混合 → 重建）。
     *
     * 每个通道都重新构建自己的金字塔是**有意的**：
     * 早先为了省时间只建一次金字塔再复用到三个通道，结果是 G/B 通道实际上用的是 R 通道的数据，
     * 融合区域会出现明显色偏。金字塔构建是 O(n) 的若干次卷积，代价远小于正确性。
     */
    private fun blendOneChannel(
        scenePixels: IntArray,
        frogPixels: IntArray,
        mask: IntArray,
        width: Int,
        height: Int,
        channel: Int,
    ): FloatArray {
        val total = width * height
        val scenePyramid = ArrayList<FloatArray>(LEVELS)
        val frogPyramid = ArrayList<FloatArray>(LEVELS)
        val maskPyramid = ArrayList<FloatArray>(LEVELS)
        val sizes = ArrayList<IntArray>(LEVELS)

        var sceneLevel = extractChannel(scenePixels, total, channel)
        var frogLevel = extractChannel(frogPixels, total, channel)
        var maskLevel = FloatArray(total) { mask[it] / 255f }
        var w = width
        var h = height

        for (level in 0 until LEVELS) {
            scenePyramid += sceneLevel
            frogPyramid += frogLevel
            maskPyramid += maskLevel
            sizes += intArrayOf(w, h)

            if (level == LEVELS - 1 || w <= 2 || h <= 2) break
            val (dsScene, dims) = BoxBlur.downsample(sceneLevel, w, h)
            val (dsFrog, _) = BoxBlur.downsample(frogLevel, w, h)
            val (dsMask, _) = BoxBlur.downsample(maskLevel, w, h)
            sceneLevel = dsScene
            frogLevel = dsFrog
            maskLevel = dsMask
            w = dims[0]
            h = dims[1]
        }

        val levels = scenePyramid.size
        val coarsest = levels - 1
        val coarseW = sizes[coarsest][0]
        val coarseH = sizes[coarsest][1]

        // 最粗层：就是"颜色/光照"尺度，按掩膜线性过渡即可
        var accumulated = FloatArray(coarseW * coarseH) { i ->
            val m = maskPyramid[coarsest][i].coerceIn(0f, 1f)
            scenePyramid[coarsest][i] * (1f - m) + frogPyramid[coarsest][i] * m
        }

        // 自底向上重建：每层加回"该层的高频细节"，细节同样按掩膜混合
        for (level in coarsest - 1 downTo 0) {
            val lw = sizes[level][0]
            val lh = sizes[level][1]
            val upW = sizes[level + 1][0]
            val upH = sizes[level + 1][1]
            val upsampled = BoxBlur.upsample(accumulated, upW, upH, lw, lh)

            val sceneL = scenePyramid[level]
            val frogL = frogPyramid[level]
            val maskL = maskPyramid[level]
            val next = FloatArray(lw * lh)
            for (i in next.indices) {
                val m = maskL[i].coerceIn(0f, 1f)
                val sceneDetail = sceneL[i] - upsampled[i]
                val frogDetail = frogL[i] - upsampled[i]
                next[i] = upsampled[i] + sceneDetail * (1f - m) + frogDetail * m
            }
            accumulated = next
        }
        return accumulated
    }

    /** 抽取通道并转 Float（0~255）。 */
    private fun extractChannel(pixels: IntArray, total: Int, channel: Int): FloatArray {
        val shift = when (channel) {
            CHANNEL_R -> 16
            CHANNEL_G -> 8
            else -> 0
        }
        return FloatArray(total) { i -> ((pixels[i] shr shift) and 0xFF).toFloat() }
    }

    private const val CHANNEL_R = 0
    private const val CHANNEL_G = 1
    private const val CHANNEL_B = 2
}
