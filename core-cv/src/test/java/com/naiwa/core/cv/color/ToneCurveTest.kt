package com.naiwa.core.cv.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reinhard 肩部曲线测试。
 *
 * 这些性质决定"泛白"是否真的被解决、以及中间调是否被误伤：
 *  - 膝点以下**恒等**（原图色彩分布不被改动）；
 *  - 肩部**单调**且**渐近 1.0**（不会裁切成纯白）；
 *  - `knee = 0` 时退化为规格原式 `c/(1+c)`。
 */
class ToneCurveTest {

    private val eps = 1e-5f

    @Test
    fun `膝点以下严格恒等`() {
        val knee = 0.6f
        listOf(0f, 0.02f, 0.18f, 0.35f, 0.5f, 0.599f).forEach { x ->
            assertEquals("x=$x 应完全不变", x, ToneCurve.reinhardShoulder(x, knee), eps)
        }
    }

    @Test
    fun `肩部单调不减`() {
        var prev = -1f
        var x = 0f
        while (x <= 4f) {
            val y = ToneCurve.reinhardShoulder(x, 0.6f)
            assertTrue("x=$x 处不单调：$prev → $y", y >= prev - eps)
            prev = y
            x += 0.01f
        }
    }

    @Test
    fun `任意输入都不超过1且严格小于1`() {
        listOf(1f, 2f, 10f, 1000f).forEach { x ->
            val y = ToneCurve.reinhardShoulder(x, 0.6f)
            assertTrue("x=$x → $y 应 < 1", y < 1f)
            assertTrue("x=$x → $y 应 ≥ 0", y >= 0f)
        }
    }

    @Test
    fun `过曝部分被明显压缩但保留层次`() {
        // 这三个值必须彼此可区分，否则高光又是"一块平"
        val a = ToneCurve.reinhardShoulder(0.787f, 0.6f)
        val b = ToneCurve.reinhardShoulder(1.20f, 0.6f)
        val c = ToneCurve.reinhardShoulder(2.00f, 0.6f)
        assertTrue("0.787 应被压到 0.6~0.7：$a", a in 0.62f..0.70f)
        assertTrue("1.20 → $b", b in 0.73f..0.78f)
        assertTrue("2.00 → $c", c in 0.82f..0.86f)
        assertTrue("高光之间必须有差距", (c - a) > 0.1f)
    }

    @Test
    fun `膝点为0时等于规格原式`() {
        listOf(0f, 0.1f, 0.5f, 1f, 3f).forEach { x ->
            assertEquals(
                "knee=0 应退化为 c/(1+c)",
                ToneCurve.reinhardFull(x),
                ToneCurve.reinhardShoulder(x, 0f),
                eps,
            )
        }
    }

    @Test
    fun `膝点越大对中间调越温和`() {
        val x = 0.787f
        val soft = ToneCurve.reinhardShoulder(x, 0.8f)
        val hard = ToneCurve.reinhardShoulder(x, 0.4f)
        assertTrue("膝点 0.8 应比 0.4 保留更多亮度：$soft vs $hard", soft > hard)
    }

    @Test
    fun `负值被钳到0而不是产生NaN`() {
        assertEquals(0f, ToneCurve.reinhardShoulder(-1f, 0.6f), eps)
        assertEquals(0f, ToneCurve.reinhardFull(-3f), eps)
    }
}
