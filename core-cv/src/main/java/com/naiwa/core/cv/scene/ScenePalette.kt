package com.naiwa.core.cv.scene

import kotlin.math.sqrt

/**
 * 场景取色（规格 7.1 / 7.2 / 7.3 / 7.4 的共同数据源）。
 *
 * 为什么集中在一处：边缘光的颜色要"场景高光区的中位色"、阴影要"暗部中位色再压暗"、
 * 四色渐变要"四个区域各自的中位色"。三处都在做"分组 + 中位色"，各写一遍必然出现
 * 三套不同的"过曝"定义，同一张图会出现"边缘光是纯白、阴影偏红"这类不自洽的结果。
 *
 * 全部为纯 IntArray 运算，不依赖 Android / OpenCV，可在 JVM 单测里直接验证。
 *
 * @param highlightColor 高光中位色（亮度 p95 以上像素的 RGB 中位数）—— 用作逆光边缘光颜色
 * @param shadowColor 暗部中位色再压暗（[SHADOW_DARKEN]）—— 用作阴影颜色
 * @param topColor / bottomColor / leftColor / rightColor 四区中位色（已排除过曝与死黑）
 * @param topLuma ... 四区的平均亮度（0~255），用于渐变场的强度调制
 */
data class ScenePalette(
    val highlightColor: Int,
    val shadowColor: Int,
    val topColor: Int,
    val bottomColor: Int,
    val leftColor: Int,
    val rightColor: Int,
    val topLuma: Float,
    val bottomLuma: Float,
    val leftLuma: Float,
    val rightLuma: Float,
) {
    companion object {
        /** 取不到任何像素时的兜底（中性灰阶，避免出现纯黑/纯白的极端耦合）。 */
        val NEUTRAL = ScenePalette(
            highlightColor = 0xFFF2F2F2.toInt(),
            shadowColor = 0xFF26221C.toInt(),
            topColor = 0xFFBFBFBF.toInt(),
            bottomColor = 0xFFBFBFBF.toInt(),
            leftColor = 0xFFBFBFBF.toInt(),
            rightColor = 0xFFBFBFBF.toInt(),
            topLuma = 191f,
            bottomLuma = 191f,
            leftLuma = 191f,
            rightLuma = 191f,
        )
    }
}

object ScenePaletteExtractor {

    /** 过曝阈值：亮度 ≥ 此值的像素不参与"区域主色"统计（但可以参与高光色统计）。 */
    const val OVEREXPOSED_LUMA = 250f

    /** 死黑阈值：亮度 ≤ 此值的像素不参与区域主色统计。 */
    const val CRUSHED_LUMA = 5f

    /** 高光分位：取亮度 p95 以上作为"场景高光区"。 */
    const val HIGHLIGHT_PERCENTILE = 0.95f

    /** 暗部分位：取亮度 p20 以下作为"场景暗部"。 */
    const val DARK_PERCENTILE = 0.20f

    /** 暗部再压暗的比例 —— 纯用暗部原色当阴影色会偏亮，纯黑又会发灰。 */
    const val SHADOW_DARKEN = 0.55f

    /**
     * 从场景像素提取调色板。
     *
     * 采样策略：按步长抽样（最多约 8k 个样本）。整图排序 400 万像素在移动端是几百毫秒的浪费，
     * 而"中位色/分位数"这类统计量对采样密度极不敏感。
     */
    fun extract(pixels: IntArray, width: Int, height: Int): ScenePalette {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return ScenePalette.NEUTRAL

        val step = (pixels.size / MAX_SAMPLES).coerceAtLeast(1)
        val samples = ArrayList<Int>(MAX_SAMPLES)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            // 完全透明的像素不参与（场景理论上不透明，但导出的位图可能带 alpha）
            if (((c ushr 24) and 0xFF) > 8) samples += c
            i += step
        }
        if (samples.isEmpty()) return ScenePalette.NEUTRAL

        val sorted = samples.sortedBy { lumaOf(it) }
        val darkCount = (sorted.size * DARK_PERCENTILE).toInt().coerceAtLeast(1)
        val highlightStart = (sorted.size * HIGHLIGHT_PERCENTILE).toInt().coerceAtMost(sorted.size - 1)

        val shadow = darken(medianColor(sorted.subList(0, darkCount)))
        // 高光色取 p95~p100 的中位色；若该区间全是纯白（天空过曝），中位色仍是白，合理
        val highlight = medianColor(sorted.subList(highlightStart, sorted.size))

        val thirdW = (width / 3).coerceAtLeast(1)
        val thirdH = (height / 3).coerceAtLeast(1)
        val top = zone(pixels, width, height, 0, 0, width, thirdH)
        val bottom = zone(pixels, width, height, 0, height - thirdH, width, height)
        val left = zone(pixels, width, height, 0, 0, thirdW, height)
        val right = zone(pixels, width, height, width - thirdW, 0, width, height)

        return ScenePalette(
            highlightColor = highlight,
            shadowColor = shadow,
            topColor = top.first,
            bottomColor = bottom.first,
            leftColor = left.first,
            rightColor = right.first,
            topLuma = lumaOf(top.first),
            bottomLuma = lumaOf(bottom.first),
            leftLuma = lumaOf(left.first),
            rightLuma = lumaOf(right.first),
        )
    }

    /**
     * 取一个区域的主色与平均亮度。
     *
     * 排除过曝与死黑：天空的 255 白和阴影的 0 黑都不代表"这一区的环境色"，
     * 把它们算进去会让四色渐变退化成"上白下黑"，比不加还难看。
     * 若整区都被排除（例如整块是天空），退回"不过滤"的结果 —— 有总比没有好，且不会崩。
     */
    internal fun zone(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Pair<Int, Float> {
        val lx0 = x0.coerceIn(0, width - 1)
        val ly0 = y0.coerceIn(0, height - 1)
        val lx1 = x1.coerceIn(lx0 + 1, width)
        val ly1 = y1.coerceIn(ly0 + 1, height)

        // 区域最多采 2k 个点：区域统计比全图更不需要密采样
        val stride = (((lx1 - lx0) * (ly1 - ly0)) / 2048).coerceAtLeast(1)
        val kept = ArrayList<Int>(2048)
        val all = ArrayList<Int>(2048)

        var idx = 0
        for (y in ly0 until ly1) {
            val row = y * width
            for (x in lx0 until lx1) {
                if (idx++ % stride != 0) continue
                val c = pixels[row + x]
                all += c
                val l = lumaOf(c)
                if (l in CRUSHED_LUMA..(OVEREXPOSED_LUMA - 1f)) kept += c
            }
        }

        val source = if (kept.isNotEmpty()) kept else all
        if (source.isEmpty()) return ScenePalette.NEUTRAL.topColor to 191f

        val color = medianColor(source)
        var sum = 0.0
        for (c in source) sum += lumaOf(c)
        return color to (sum / source.size).toFloat()
    }

    /** 逐通道取中位数（对彩色的中位色比"按亮度取中位像素"更稳，不会被单通道极值带偏）。 */
    fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xFF808080.toInt()
        val rs = IntArray(colors.size) { (colors[it] shr 16) and 0xFF }.also { it.sort() }
        val gs = IntArray(colors.size) { (colors[it] shr 8) and 0xFF }.also { it.sort() }
        val bs = IntArray(colors.size) { colors[it] and 0xFF }.also { it.sort() }
        val mid = colors.size / 2
        return (0xFF shl 24) or (rs[mid] shl 16) or (gs[mid] shl 8) or bs[mid]
    }

    /** Rec.709 亮度，0~255。 */
    fun lumaOf(color: Int): Float =
        0.2126f * ((color shr 16) and 0xFF) +
            0.7152f * ((color shr 8) and 0xFF) +
            0.0722f * (color and 0xFF)

    private fun darken(color: Int): Int {
        val r = (((color shr 16) and 0xFF) * SHADOW_DARKEN).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * SHADOW_DARKEN).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * SHADOW_DARKEN).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 全图抽样上限。 */
    private const val MAX_SAMPLES = 8192

    /** 供测试与诊断：两个颜色的欧氏距离（0~441）。 */
    fun colorDistance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
}
