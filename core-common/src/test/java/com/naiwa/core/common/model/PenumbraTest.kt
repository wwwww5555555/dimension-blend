package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明暗交界过渡带的验收。
 *
 * 关键不是"用了 smoothstep"（本来就在用），而是：
 * 1. 区间**跨过 0**（-0.2 ~ 0.3），让背光侧也有一点蔓延；
 * 2. 光/影权重**严格互补**，交界处不能两边都空；
 * 3. 桥梁权重是**钟形**，两端归零（纯光/纯暗处不该再加色）。
 */
class PenumbraTest {

    private val eps = 1e-4f

    // ---------------------------------------------------------------- litRatio

    @Test
    fun `背光端为 0 迎光端为 1`() {
        assertEquals(0f, Penumbra.litRatio(Penumbra.NDL_LO, 1f), eps)
        assertEquals(1f, Penumbra.litRatio(Penumbra.NDL_HI, 1f), eps)
    }

    @Test
    fun `区间中点恰好是 0_5`() {
        val mid = (Penumbra.NDL_LO + Penumbra.NDL_HI) / 2f
        assertEquals(0.5f, Penumbra.litRatio(mid, 1f), 1e-3f)
    }

    @Test
    fun `区间跨过 0（背光侧也有蔓延）`() {
        // N·L = 0 是几何上的明暗分界。若过渡区间是 [0, 0.35]，这里会恰好是 0，
        // 交界处就会"从 0 起步"，视觉上仍是硬边。
        val atZero = Penumbra.litRatio(0f, 1f)
        assertTrue("N·L=0 处的迎光比例应明显大于 0，实际 $atZero", atZero > 0.25f)
        assertTrue("但也不能接近 1（那会把背光面点亮）", atZero < 0.6f)
    }

    @Test
    fun `平涂内部强制按背光处理`() {
        // 内部没有法向，N·L 恒 0；不乘 edgeAmount 会给一个 0.35 的"微受光"，压不黑
        assertEquals(0f, Penumbra.litRatio(0f, 0f), eps)
        assertEquals(0f, Penumbra.litRatio(0.9f, 0f), eps)
    }

    @Test
    fun `从背光到迎光单调不减且没有跳变`() {
        var prev = -1f
        var x = -1f
        var maxJump = 0f
        while (x <= 1f) {
            val v = Penumbra.litRatio(x, 1f)
            assertTrue("x=$x 处 $v 低于上一档 $prev", v >= prev - eps)
            if (prev >= 0f) maxJump = maxOf(maxJump, v - prev)
            prev = v
            x += 0.01f
        }
        // smoothstep 的最大斜率是 1.5/(hi-lo)=3，步长 0.01 → 理论最大跳变 0.03。
        // 超过它说明有阶跃（比如混进了 step/硬分支）。
        assertTrue("相邻采样跳变 $maxJump 超过 smoothstep 的理论上限 0.03", maxJump < 0.035f)
    }

    @Test
    fun `输出恒在 0 到 1`() {
        listOf(-5f, -0.2f, 0f, 0.5f, 1f, 5f).forEach { n ->
            listOf(0f, 0.5f, 1f, 2f).forEach { e ->
                val v = Penumbra.litRatio(n, e)
                assertTrue("n=$n e=$e → $v", v in 0f..1f)
            }
        }
    }

    // ---------------------------------------------------------------- 互补

    @Test
    fun `阴影权重与迎光比例严格互补`() {
        var x = -0.5f
        while (x <= 1.5f) {
            val l = Penumbra.litRatio(x, 1f)
            assertEquals("lit + shadow 必须恒为 1", 1f, l + Penumbra.shadowWeight(l), eps)
            x += 0.1f
        }
    }

    // ---------------------------------------------------------------- 桥梁

    @Test
    fun `桥梁权重是钟形`() {
        assertEquals("纯光处不该再加色", 0f, Penumbra.bridge(0f), eps)
        assertEquals("纯暗处不该再加色", 0f, Penumbra.bridge(1f), eps)
        assertEquals("交界中点最强", 1f, Penumbra.bridge(0.5f), eps)
    }

    @Test
    fun `桥梁在两端快速归零`() {
        // 0.1 / 0.9 处应已明显衰减，否则会把纯光/纯暗区也染上环境色
        assertTrue("bridge(0.1)=${Penumbra.bridge(0.1f)} 应 < 0.4", Penumbra.bridge(0.1f) < 0.4f)
        assertTrue("bridge(0.9)=${Penumbra.bridge(0.9f)} 应 < 0.4", Penumbra.bridge(0.9f) < 0.4f)
    }

    @Test
    fun `桥梁越界输入被钳制`() {
        assertTrue(Penumbra.bridge(-3f) in 0f..1f)
        assertTrue(Penumbra.bridge(9f) in 0f..1f)
    }
}
