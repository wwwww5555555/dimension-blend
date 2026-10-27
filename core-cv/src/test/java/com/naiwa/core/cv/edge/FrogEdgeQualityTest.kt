package com.naiwa.core.cv.edge

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **规格 6.7 的验收测试：把"人工放大看边缘"变成可执行的断言。**
 *
 * 规格给的验证方法是"在深色背景（#1a1a1a）上叠加处理后的奶蛙，放大到 400% 检查边缘，
 * 目标：边缘 RGB 值与相邻前景像素差异 < 15"。手工看只能看到"好像还有白边"，
 * 这里把它量化为：
 *
 * ```
 * 对每个半透明边缘像素：
 *   实际值 = 把该像素按它的 alpha 合成到 #1a1a1a 上
 *   理想值 = 把"前景本体色"按同样的 alpha 合成到 #1a1a1a 上
 *   差异   = 三通道最大绝对差
 * ```
 * 于是"有白边"⇔ 差异大，"修好了"⇔ 差异小，且可以断言。
 *
 * 测试里刻意构造了**真实的缺陷模型**：抗锯齿边缘的 RGB 保留着被抠掉的白色背景，
 * 而内部是奶蛙绿 —— 这正是"透明玻璃白边"的产生方式。
 */
class FrogEdgeQualityTest {

    private val bg = intArrayOf(0x1a, 0x1a, 0x1a)          // 规格指定的深色背景
    private val frogColor = intArrayOf(96, 138, 82)        // 前景本体色
    private val pollutedEdge = intArrayOf(246, 246, 246)   // 被污染的边缘色（残留白底）

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * 造一个抗锯齿圆盘，**按真实成因建模**：素材是"奶蛙叠在白底上"的合成结果。
     *
     * ```
     * coverage = clamp((R + band - d) / (2·band), 0, 1)   // 覆盖率 = alpha
     * alpha    = coverage
     * RGB      = 本体色 × coverage + 白底 × (1 - coverage)   // ← 污染发生在这里
     * ```
     *
     * 这个模型有两个必须保留的性质：
     *  1. **半透明边缘的 RGB 是"被白底稀释过的本体色"**（不是纯白），这才是白边的真实观感；
     *  2. **不透明内部是干净的本体色** —— 去白正是靠"从干净内部取参考"来工作的，
     *     这一点同时是算法的前提（见 `EdgeDefringe` 的说明）。
     */
    private fun frogWithWhiteFringe(size: Int, radius: Float): IntArray {
        val c = (size - 1) / 2f
        return IntArray(size * size) { i ->
            val x = (i % size).toFloat()
            val y = (i / size).toFloat()
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c))
            val band = 2.0f
            val coverage = ((radius + band - d) / (2 * band)).coerceIn(0f, 1f)
            if (coverage <= 0f) {
                argb(0, 0, 0, 0)
            } else {
                val a = (coverage * 255f + 0.5f).toInt().coerceIn(0, 255)
                val r = (frogColor[0] * coverage + pollutedEdge[0] * (1 - coverage) + 0.5f).toInt()
                val g = (frogColor[1] * coverage + pollutedEdge[1] * (1 - coverage) + 0.5f).toInt()
                val b = (frogColor[2] * coverage + pollutedEdge[2] * (1 - coverage) + 0.5f).toInt()
                argb(a, r, g, b)
            }
        }
    }

    /** 合成到背景上：`out = src × a + bg × (1 - a)`（要求 src 是**非预乘**的 RGB）。 */
    private fun over(src: IntArray, index: Int): IntArray {
        val a = ((src[index] ushr 24) and 0xFF) / 255f
        return intArrayOf(
            (((src[index] shr 16) and 0xFF) * a + bg[0] * (1 - a)).toInt(),
            (((src[index] shr 8) and 0xFF) * a + bg[1] * (1 - a)).toInt(),
            ((src[index] and 0xFF) * a + bg[2] * (1 - a)).toInt(),
        )
    }

    /** 理想合成：同样 alpha，但颜色用前景本体色。 */
    private fun idealOver(alpha: Int): IntArray {
        val a = alpha / 255f
        return intArrayOf(
            (frogColor[0] * a + bg[0] * (1 - a)).toInt(),
            (frogColor[1] * a + bg[1] * (1 - a)).toInt(),
            (frogColor[2] * a + bg[2] * (1 - a)).toInt(),
        )
    }

    /**
     * 边缘差异：只统计 alpha ∈ [0.2, 0.8] 的半透明边缘像素（规格 6.2 的目标区间）。
     * @return `[平均差异, 95 分位差异, 样本数]`
     */
    private fun edgeDiff(pixels: IntArray): DoubleArray {
        val diffs = ArrayList<Int>()
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            if (a < 51 || a > 204) continue // 0.2 ~ 0.8
            val observed = over(pixels, i)
            val ideal = idealOver(a)
            diffs += maxOf(
                abs(observed[0] - ideal[0]),
                abs(observed[1] - ideal[1]),
                abs(observed[2] - ideal[2]),
            )
        }
        if (diffs.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0)
        val sorted = diffs.sorted()
        val mean = sorted.sum().toDouble() / sorted.size
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()].toDouble()
        return doubleArrayOf(mean, p95, sorted.size.toDouble())
    }

    @Test
    fun `深色背景上的边缘差异在修复后降到15以内`() {
        val size = 64
        val src = frogWithWhiteFringe(size, radius = 22f)

        val before = edgeDiff(src)
        println("[6.7] 修复前：平均差异 %.1f / 95分位 %.1f（%.0f 个边缘像素）".format(before[0], before[1], before[2]))
        assertTrue("样本数应足够", before[2] > 100)
        assertTrue("修复前应当**确实存在**白边（否则这个测试没意义）：平均差异 ${before[0]}", before[0] > 15)

        val prepared = FrogEdgePreparer.prepare(src, size, size)
        // 6.5 契约：返回的像素是**预乘**格式，要合成得先反预乘（这条本身就是对边界的检查）
        val unpremultiplied = PremultipliedAlpha.unpremultiply(prepared.pixels)
        val after = edgeDiff(unpremultiplied)
        println("[6.7] 修复后：平均差异 %.1f / 95分位 %.1f（%.0f 个边缘像素）".format(after[0], after[1], after[2]))
        println("[6.7] 报告：${prepared.report.summary}")

        assertTrue("修复后仍应有样本", after[2] > 100)
        assertTrue("规格 6.7 的验收线：边缘 RGB 与前景差异 < 15，实测平均 ${after[0]}", after[0] < 15)
        // 分位数而不是最大值：低 alpha 端"预乘↔反预乘"的量化误差会被 255/alpha 放大，
        // 用最大值做断言会把量化噪声误判成算法缺陷
        assertTrue("95% 的边缘像素都应在验收线附近，实测 ${after[1]}", after[1] < 20)
    }

    @Test
    fun `预乘素材不做反预乘会留下偏差_做了就修好`() {
        val size = 64
        val unpremultipliedFrog = frogWithWhiteFringe(size, radius = 22f)
        // 模拟 Android BitmapFactory 的默认行为：解码出来就是预乘的
        val decoded = PremultipliedAlpha.premultiply(unpremultipliedFrog)

        // ① 不做反预乘、只按预乘解释去合成 → 边缘会被"再乘一次 alpha"，整体偏暗
        val naive = edgeDiff(decoded)
        println("[6.1] 预乘素材按非预乘直接合成：平均差异 %.1f".format(naive[0]))
        assertTrue("不做反预乘应留下明显偏差，实测 ${naive[0]}", naive[0] > 15)

        // ② 走完整流程
        val prepared = FrogEdgePreparer.prepare(decoded, size, size)
        val after = edgeDiff(PremultipliedAlpha.unpremultiply(prepared.pixels))
        println("[6.1] 完整流程后：平均差异 %.1f；反预乘=${prepared.report.unPremultiplied}".format(after[0]))
        assertTrue("应识别出预乘格式", prepared.report.unPremultiplied)
        assertTrue("完整流程后应收敛到验收线内，实测 ${after[0]}", after[0] < 15)
    }

    @Test
    fun `输出必须回到预乘格式`() {
        val size = 32
        val src = frogWithWhiteFringe(size, radius = 12f)
        val prepared = FrogEdgePreparer.prepare(src, size, size)
        // 预乘的硬性不变式：每个通道 ≤ alpha
        for (c in prepared.pixels) {
            val a = (c ushr 24) and 0xFF
            assertTrue("输出必须是预乘格式（通道 ≤ alpha）", ((c shr 16) and 0xFF) <= a)
            assertTrue("输出必须是预乘格式（通道 ≤ alpha）", ((c shr 8) and 0xFF) <= a)
            assertTrue("输出必须是预乘格式（通道 ≤ alpha）", (c and 0xFF) <= a)
        }
    }

    @Test
    fun `去白不会吃掉前景本体`() {
        val size = 48
        val src = frogWithWhiteFringe(size, radius = 16f)
        val prepared = FrogEdgePreparer.prepare(src, size, size)
        val out = PremultipliedAlpha.unpremultiply(prepared.pixels)

        // 圆心处应是本体色（去白只碰边缘，不该改到内部）
        val center = (size / 2) * size + size / 2
        val err = maxOf(
            abs(((out[center] shr 16) and 0xFF) - frogColor[0]),
            abs(((out[center] shr 8) and 0xFF) - frogColor[1]),
            abs((out[center] and 0xFF) - frogColor[2]),
        )
        assertTrue("圆心应仍是本体色，实测误差 $err", err <= 3)

        // 前景面积不应因为柔化而明显缩小（实心区不动）
        var solidBefore = 0
        var solidAfter = 0
        for (i in src.indices) {
            if (((src[i] ushr 24) and 0xFF) == 255) solidBefore++
            if (((out[i] ushr 24) and 0xFF) == 255) solidAfter++
        }
        assertEquals("实心前景像素数必须不变（柔化只在边缘带生效）", solidBefore, solidAfter)
    }

    @Test
    fun `尺寸不合法时按契约跳过而不是抛异常`() {
        val prepared = FrogEdgePreparer.prepare(IntArray(4), 0, 0)
        assertTrue("应标记为跳过", prepared.report.skipped)
        assertEquals(4, prepared.pixels.size)
        assertTrue(prepared.report.summary.contains("跳过"))
    }
}
