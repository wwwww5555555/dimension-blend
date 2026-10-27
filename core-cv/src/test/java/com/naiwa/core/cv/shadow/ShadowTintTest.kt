package com.naiwa.core.cv.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境暗色提取的契约：**永远不能是纯黑**，永远不能比 [ShadowTint.MAX_CHANNEL] 更亮。
 */
class ShadowTintTest {

    private fun argb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test
    fun `全黑背景不会返回纯黑`() {
        val px = IntArray(64 * 64) { argb(0, 0, 0) }
        val c = ShadowTint.fromBackground(px, 64, 64)
        assertEquals("R 应被抬到下限", ShadowTint.MIN_CHANNEL, r(c))
        assertEquals("G 应被抬到下限", ShadowTint.MIN_CHANNEL, g(c))
        assertEquals("B 应被抬到下限", ShadowTint.MIN_CHANNEL, b(c))
    }

    @Test
    fun `偏蓝暗部会被保留下来`() {
        // 一半是暗蓝阴影、一半是明亮暖色：暗部中位色应保留蓝偏
        val px = IntArray(64 * 64)
        for (i in px.indices) {
            px[i] = if (i % 2 == 0) argb(18, 20, 46) else argb(240, 220, 180)
        }
        val c = ShadowTint.fromBackground(px, 64, 64)
        assertTrue("蓝通道应最大（实际 r=${r(c)} g=${g(c)} b=${b(c)}）", b(c) > r(c) && b(c) > g(c))
        assertTrue("蓝色仍应是暗色", b(c) <= 60)
    }

    @Test
    fun `纯白背景被压到上限`() {
        val px = IntArray(64 * 64) { argb(255, 255, 255) }
        val c = ShadowTint.fromBackground(px, 64, 64)
        assertEquals("没有暗部时应退化为中性暗灰", ShadowTint.MAX_CHANNEL, r(c))
    }

    @Test
    fun `结果始终在安全区间内`() {
        val rnd = java.util.Random(7)
        repeat(20) {
            val px = IntArray(128 * 128) {
                argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            }
            val c = ShadowTint.fromBackground(px, 128, 128)
            listOf(r(c), g(c), b(c)).forEach { v ->
                assertTrue("通道 $v 应在 [MIN, MAX] 内", v in ShadowTint.MIN_CHANNEL..ShadowTint.MAX_CHANNEL)
            }
        }
    }

    @Test
    fun `空数据返回兜底色而不是崩溃`() {
        assertEquals(ShadowTint.NEUTRAL, ShadowTint.fromBackground(IntArray(0), 0, 0))
        assertEquals(ShadowTint.NEUTRAL, ShadowTint.fromBackground(IntArray(0), 10, 10))
    }

    @Test
    fun `暗部占比不影响选中区域`() {
        // 只有 8% 的暗部像素，仍应被 12% 分位抓住（比 5% 分位稳）
        val px = IntArray(100 * 100) { argb(200, 200, 200) }
        for (i in 0 until 800) px[i] = argb(12, 14, 30)
        val c = ShadowTint.fromBackground(px, 100, 100)
        assertTrue("应抓到暗部而不是中灰（实际 ${r(c)}/${g(c)}/${b(c)}）", r(c) < 60)
    }
}
