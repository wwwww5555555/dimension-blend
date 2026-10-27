package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逆光压暗与阴影熔断的验收。
 *
 * 两条规格要求都被翻译成可断言的数字：
 * 1. 逆光时背光面亮度必须"乘以 0.3"（`[0.20, 0.32]` 视为合格）；
 * 2. 处于阴影中的像素，光包裹必须归零（只剩环境地板）。
 */
class BacklitShadeTest {

    private val eps = 1e-4f

    // ---------------------------------------------------------------- 压暗

    @Test
    fun `背光面被压到 0_3 左右`() {
        // backWeight = 1 表示"平涂内部 / 背光侧" → 必须落到规格要求的 0.3 附近
        val k = BacklitShade.darkenFactor(backWeight = 1f)
        assertEquals("背光面系数必须等于 DARKEN_BACK", BacklitShade.DARKEN_BACK, k, eps)
        // v0.29 起 DARKEN_BACK 是"环境吸收前的基准暗度"（规格 color*0.2），
        // 真正的深度来自 DeepShadowTint 的三级高度阶梯 —— 这里只兜住"基准必须够暗"。
        assertTrue("基准暗度不能高于 0.32，实际 $k", k <= 0.32f)
    }

    @Test
    fun `迎光侧轮廓只吃全局压暗`() {
        val k = BacklitShade.darkenFactor(backWeight = 0f)
        assertEquals(BacklitShade.DARKEN_GLOBAL, k, eps)
    }

    @Test
    fun `压暗随背光权重单调递减`() {
        var prev = 2f
        var b = 0f
        while (b <= 1f) {
            val k = BacklitShade.darkenFactor(b)
            assertTrue("backWeight=$b 时压暗系数 $k 不该高于上一档 $prev", k <= prev + eps)
            prev = k
            b += 0.1f
        }
    }

    @Test
    fun `规格伪代码的 smoothstep 反序已被改写为合法形式`() {
        // smoothstep(0.2,-0.2,x) 在 GLSL 里是 UB，部分驱动返回 0 → 压暗整段失效。
        // 正确的 1-smoothstep(-0.2,0.2,x) 在 x=0 处必须恰好是 0.5。
        assertEquals(0.5f, BacklitShade.backWeight(0f), eps)
        assertEquals(1f, BacklitShade.backWeight(-1f), eps)
        assertEquals(0f, BacklitShade.backWeight(1f), eps)
    }

    @Test
    fun `参数越界不会产生负亮度或放大`() {
        listOf(-2f, 0f, 1f, 5f).forEach { bad ->
            val k = BacklitShade.darkenFactor(0.5f, darken = bad, global = bad)
            assertTrue("非法参数不能把亮度放大或变负：$k", k in 0f..1f)
        }
    }

    // ---------------------------------------------------------------- 染色

    @Test
    fun `只有背光且很暗的像素才染色`() {
        assertTrue("背光暗部要染色", BacklitShade.tintWeight(1f, 0.02f) > 0.5f)
        assertEquals("迎光侧不染", 0f, BacklitShade.tintWeight(0f, 0.02f), eps)
        assertEquals("中间调不染", 0f, BacklitShade.tintWeight(1f, 0.5f), eps)
    }

    // ---------------------------------------------------------------- 熔断

    @Test
    fun `背光像素的光包裹归零到环境地板`() {
        val w = BacklitShade.wrapWeight(nDotL = -1f, shadowAmt = 0f)
        assertEquals(BacklitShade.AMBIENT_FLOOR, w, eps)
    }

    @Test
    fun `迎光且无遮挡时完全保留`() {
        val w = BacklitShade.wrapWeight(nDotL = 1f, shadowAmt = 0f)
        assertEquals(1f, w, eps)
    }

    @Test
    fun `阴影里就算 N dot L 是正的也要熔断`() {
        // 这是"阴影边缘漏光"的核心用例：伪法线在阴影处不可信，可能给出 N·L > 0。
        // 投影遮罩必须能独立把光效掐掉。
        val w = BacklitShade.wrapWeight(nDotL = 1f, shadowAmt = 1f)
        assertEquals(BacklitShade.AMBIENT_FLOOR, w, eps)
    }

    @Test
    fun `地板可关到 0 实现绝对熔断`() {
        val w = BacklitShade.wrapWeight(nDotL = -1f, shadowAmt = 1f, floor = 0f)
        assertEquals(0f, w, eps)
    }

    @Test
    fun `cut 强度为 0 时熔断不生效`() {
        val w = BacklitShade.wrapWeight(nDotL = -1f, shadowAmt = 1f, cut = 0f)
        assertEquals(1f, w, eps)
    }

    @Test
    fun `发光源在阴影里完全熄灭（没有环境地板）`() {
        assertEquals(0f, BacklitShade.emitWeight(1f), eps)
        assertEquals(1f, BacklitShade.emitWeight(0f), eps)
        assertEquals(0.5f, BacklitShade.emitWeight(0.5f), eps)
    }

    @Test
    fun `所有输出都落在 0 到 1`() {
        var n = -1f
        while (n <= 1f) {
            var s = 0f
            while (s <= 1f) {
                val w = BacklitShade.wrapWeight(n, s)
                val e = BacklitShade.emitWeight(s)
                val d = BacklitShade.darkenFactor(s)
                val t = BacklitShade.tintWeight(s, 0.05f)
                assertTrue("wrapWeight 越界：$w", w in 0f..1f)
                assertTrue("emitWeight 越界：$e", e in 0f..1f)
                assertTrue("darkenFactor 越界：$d", d in 0f..1f)
                assertTrue("tintWeight 越界：$t", t in 0f..1f)
                s += 0.25f
            }
            n += 0.25f
        }
    }
}
