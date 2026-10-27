package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 发光色整形的验收。
 *
 * 核心诉求只有一句：**永远不要输出纯白**。但"不是纯白"还能用三种方式作弊
 * （整体压暗到灰、丢掉色相、输出黑），所以每个用例都同时钉住不可退化项。
 */
class EmissiveColorTest {

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun chroma(c: Int) = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c))

    @Test
    fun `纯白主光不会被原样输出`() {
        // 阴天/白墙/过曝窗户的典型统计结果
        val out = EmissiveColor.sanitize(0xFFFFFFFF.toInt(), 0xFF6A8FBF.toInt())
        assertTrue("纯白必须被打破", out != 0xFFFFFFFF.toInt())
        assertTrue("最亮通道必须 ≤ 235，实际 ${maxOf(r(out), g(out), b(out))}",
            maxOf(r(out), g(out), b(out)) <= EmissiveColor.MAX_CHANNEL)
        assertTrue("必须补出可辨识的彩度，实际 ${chroma(out)}",
            chroma(out) >= EmissiveColor.MIN_CHROMA - 4)
    }

    @Test
    fun `纯白主光跟随环境色的色相（冷环境 → 偏蓝）`() {
        // v0.30 的原始契约：主光接近中性时，沿**环境色色相**补彩度。
        // 蓝调背景 → 主光被补成偏蓝的白。这是 v0.33 想改掉的行为，v0.35 按要求回退。
        val out = EmissiveColor.sanitize(0xFFFFFFFF.toInt(), 0xFF5B86C8.toInt())
        assertTrue("冷环境下补色后 B 应高于 R（实际 r=${r(out)} b=${b(out)}）", b(out) > r(out))
    }

    @Test
    fun `纯白主光在无彩环境回落到暖调`() {
        val out = EmissiveColor.sanitize(0xFFFFFFFF.toInt(), 0xFF808080.toInt())
        assertTrue("无色相时应有暖调：R > B（实际 r=${r(out)} b=${b(out)}）", r(out) > b(out))
        assertTrue(chroma(out) >= EmissiveColor.MIN_CHROMA - 4)
    }

    @Test
    fun `已经足够暖的夕阳色基本保持`() {
        val key = 0xFFFFA040.toInt()          // 暖橙，chroma = 191
        val out = EmissiveColor.sanitize(key, 0xFF6A8FBF.toInt())
        assertTrue("色相必须保持 R 最大、B 最小", r(out) >= g(out) && g(out) >= b(out))
        // 允许压亮带来的整体缩放，但通道比例不能乱
        val kIn = r(key).toFloat() / b(key)
        val kOut = r(out).toFloat() / maxOf(b(out), 1)
        assertTrue("通道比例漂移过大：$kIn → $kOut", abs(kIn - kOut) < 0.6f)
    }

    @Test
    fun `已压亮时不做额外色度补偿`() {
        // 0xE08040：max=224 < 235，chroma=160 → 两个分支都不该触发
        val out = EmissiveColor.sanitize(0xFFE08040.toInt(), 0xFF000000.toInt())
        assertEquals(0xE0, r(out))
        assertEquals(0x80, g(out))
        assertEquals(0x40, b(out))
    }

    @Test
    fun `alpha 恒为不透明`() {
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x00123456, 0xFF7F7F7F.toInt()).forEach {
            assertEquals("alpha 必须为 FF", 0xFF, (EmissiveColor.sanitize(it, it) shr 24) and 0xFF)
        }
    }

    @Test
    fun `hueDirection 对灰轴退化安全`() {
        // 灰轴 → 不能返回 NaN/零向量
        val d = EmissiveColor.hueDirection(0xFF808080.toInt())
        val len = Math.sqrt((d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).toDouble()).toFloat()
        assertTrue("必须是单位向量，实际 $len", abs(len - 1f) < 1e-3f)
        d.forEach { assertTrue("不能有 NaN：$it", !it.isNaN()) }
    }
}
