package com.naiwa.core.cv.shadow

/**
 * 从背景提取"环境暗色"（投射阴影 / 接触阴影的颜色源）。
 *
 * 【为什么不能填纯黑】
 * `base * vec3(0.0) = 0` —— 纯黑的 Multiply 会把底图细节**整块抹掉**，阴影区变成死黑平面，
 * 观感是脏、闷、发飘。真实阴影是"环境光的缺席"：背光面仍被天光/地面反射照亮，
 * 所以颜色应该是**背景暗部的中位色**（例如偏蓝的冷灰 0.10/0.10/0.15），只是亮度更低。
 *
 * 算法：按 Rec.709 亮度排序，取最暗 [DARK_PERCENTILE] 的样本，逐通道取中位色，
 * 再做两道钳制 —— 下界防止"宇宙黑"，上界防止"提取到一片灰"。
 */
object ShadowTint {

    /** 参与统计的最暗样本比例。12% 比 5% 稳（5% 容易只抓到单个暗角像素）。 */
    const val DARK_PERCENTILE = 0.12f

    /** 单通道下限（0~255）：低于它的"阴影"在 Multiply 里等同于纯黑。 */
    const val MIN_CHANNEL = 10

    /**
     * 单通道上限（0~255）：超过它的颜色做 Multiply 不再压暗，
     * 说明这张"背景"本身没有暗部（比如全白底），此时宁可用中性灰。
     */
    const val MAX_CHANNEL = 92

    /** 采样上限（步长采样，与分辨率无关）。 */
    private const val MAX_SAMPLES = 4096

    /**
     * @return sRGB 的 ARGB 色值（alpha 恒为 255），保证落在 [MIN_CHANNEL, MAX_CHANNEL] 区间
     */
    fun fromBackground(pixels: IntArray, width: Int, height: Int): Int {
        val total = width * height
        if (total <= 0 || pixels.isEmpty()) return NEUTRAL
        val usable = minOf(total, pixels.size)
        val step = (usable / MAX_SAMPLES).coerceAtLeast(1)

        // 收集 (luma, argb)，按亮度升序后取最暗一批
        val n = ((usable + step - 1) / step).coerceAtLeast(1)
        val lumas = FloatArray(n)
        val colors = IntArray(n)
        var i = 0
        var idx = 0
        while (i < usable && idx < n) {
            val c = pixels[i]
            colors[idx] = c
            lumas[idx] = 0.2126f * ((c shr 16) and 0xFF) +
                0.7152f * ((c shr 8) and 0xFF) +
                0.0722f * (c and 0xFF)
            idx++
            i += step
        }
        val count = idx
        if (count == 0) return NEUTRAL

        val order = (0 until count).sortedBy { lumas[it] }
        val darkCount = (count * DARK_PERCENTILE).toInt().coerceIn(1, count)
        val dark = IntArray(darkCount) { colors[order[it]] }

        return pack(
            medianChannel(dark, 16),
            medianChannel(dark, 8),
            medianChannel(dark, 0),
        )
    }

    /** 无法取色时的兜底：偏冷的深灰（比纯黑有信息量，比中灰更像阴影）。 */
    const val NEUTRAL = 0xFF1A1C22.toInt()

    private fun medianChannel(colors: IntArray, shift: Int): Int {
        val v = IntArray(colors.size) { (colors[it] shr shift) and 0xFF }
        v.sort()
        val m = v[v.size / 2].coerceIn(MIN_CHANNEL, MAX_CHANNEL)
        return m
    }

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
