package com.naiwa.core.cv.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直方图判据测试。
 *
 * 这些阈值直接决定"泛白 / 发平"是否被检出 —— 判据错了，调试面板会给出**相反结论**，
 * 比没有面板更糟。所以钉住三件事：
 *  1. 亮部平顶 = 确有高光（p99 ≥ 200）且 p99 - p95 ≤ 2；
 *  2. 黑场抬升 = p1 ≥ 14；
 *  3. 分位数单调、样本数正确。
 *
 * 用合成直方图而不是 Bitmap：Bitmap 在 JVM 单测里是未实现的桩，拿不到真实像素。
 */
class PersonHistogramTest {

    private data class Sample(
        val hist: IntArray,
        val n: Int,
        val sum: Double,
        val sumSq: Double,
        val clip: Int,
    )

    private fun build(vararg pairs: Pair<Int, Int>): Sample {
        val h = IntArray(256)
        pairs.forEach { (v, c) -> if (v in 0..255) h[v] += c }
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var clip = 0
        for (v in 0..255) {
            val c = h[v]
            if (c == 0) continue
            n += c
            sum += v.toDouble() * c
            sumSq += v.toDouble() * v * c
            if (v >= 250) clip += c
        }
        return Sample(h, n, sum, sumSq, clip)
    }

    private fun measure(vararg pairs: Pair<Int, Int>): PersonHistogram.Result {
        val s = build(*pairs)
        return PersonHistogram.fromHistogram(s.hist, s.n, s.clip, s.sum, s.sumSq)
    }

    @Test
    fun `正常分布的直方图两个判据都为否`() {
        // 20% 暗部 8、56% 中调 128、8% 200、6% 220、6% 240、4% 252 —— 高光有梯度
        val r = measure(8 to 200, 128 to 560, 200 to 80, 220 to 60, 240 to 60, 252 to 40)
        assertTrue("p99=${r.p99} 应判定为有高光", r.hasHighlight)
        assertFalse("p95=${r.p95} p99=${r.p99} 有梯度不该判平顶", r.highlightFlat)
        assertFalse("p1=${r.p1} 应接近 0 而不是被抬起", r.blackLifted)
        assertEquals("p50 应落在中调桶", 128, r.p50)
    }

    @Test
    fun `亮部被削成平顶时能检出`() {
        // 30% 的像素都挤在 252~255：p95 与 p99 必然相等 → 平顶
        val r = measure(60 to 300, 160 to 400, 252 to 150, 253 to 150, 254 to 150, 255 to 150)
        assertTrue("p95=${r.p95} p99=${r.p99} 应判为平顶", r.highlightFlat)
        assertTrue("过曝占比应超过 20%：${r.clipRatio}", r.clipRatio > 0.2f)
    }

    @Test
    fun `黑场被抬到20以上时能检出`() {
        // 最暗的 10% 都在 22 —— 典型的"阴影发灰"
        val r = measure(22 to 100, 100 to 500, 180 to 350, 245 to 50)
        assertTrue("p1=${r.p1} 应判为黑场抬升", r.blackLifted)
        assertFalse("亮部有梯度，不该判平顶", r.highlightFlat)
    }

    @Test
    fun `整张图没有高光时不判平顶`() {
        // 最亮只有 180（p99 < 200）：p95 与 p99 相等是正常的，不能报"亮部平顶"
        val r = measure(0 to 100, 90 to 400, 180 to 500)
        assertFalse("p99=${r.p99} < 200 应判无高光", r.hasHighlight)
        assertFalse("无高光时不得判平顶", r.highlightFlat)
        assertTrue("摘要应显示无高光：${r.summary()}", r.summary().contains("无高光"))
    }

    @Test
    fun `纯黑起始的分布不算黑场抬升`() {
        val r = measure(0 to 50, 30 to 100, 128 to 600, 230 to 250)
        assertFalse("p1=${r.p1} 应为 0~5", r.blackLifted)
        assertTrue("p1 应很低", r.p1 <= 5)
    }

    @Test
    fun `分位数单调不减且样本数正确`() {
        val r = measure(10 to 100, 80 to 300, 150 to 300, 240 to 200, 255 to 100)
        assertTrue(r.p1 <= r.p5)
        assertTrue(r.p5 <= r.p50)
        assertTrue(r.p50 <= r.p95)
        assertTrue(r.p95 <= r.p99)
        assertEquals("样本数应等于总计数", 1000, r.samples)
        assertTrue("标准差应大于 0", r.std > 0)
    }

    @Test
    fun `摘要与报告包含关键字段`() {
        val r = measure(20 to 200, 128 to 600, 230 to 180)
        val s = r.summary()
        assertTrue("摘要应含 p1：$s", s.contains("p1="))
        assertTrue("摘要应含 p95：$s", s.contains("p95="))
        assertTrue("摘要应含 p99：$s", s.contains("p99="))
        assertTrue("摘要应含 Δ95→99：$s", s.contains("Δ95→99="))

        val rep = r.report()
        assertTrue(rep.contains("人物区域直方图"))
        assertTrue(rep.contains("动态范围"))
        assertTrue(rep.contains("亮部平顶"))
        assertTrue(rep.contains("黑场抬升"))
    }
}
