package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 金字塔规格的验收。
 *
 * 这类算术错误的后果不是"效果差一点"，而是**尺寸算出 0 → glTexImage2D 失败 →
 * 整条图层管线静默降级回旧路径**（用户看到的是所有新效果消失）。所以边界必须钉死。
 */
class GlowPyramidTest {

    @Test
    fun `2048 方图在默认扩散半径下给 5 级`() {
        val lv = GlowPyramid.plan(2048, 2048, 28f)
        assertEquals("28px 应吃满 5 级", 5, lv.size)
        assertEquals(listOf(1024, 512, 256, 128, 64), lv.map { it.w })
        assertEquals(listOf(1024, 512, 256, 128, 64), lv.map { it.h })
    }

    @Test
    fun `每级尺寸严格递减且不小于 MIN_SIDE`() {
        listOf(4000 to 3000, 1080 to 1920, 640 to 480, 97 to 97).forEach { (w, h) ->
            val lv = GlowPyramid.plan(w, h, 40f)
            assertTrue("${w}x$h 至少两级", lv.size >= GlowPyramid.MIN_LEVELS)
            lv.forEach {
                assertTrue("尺寸不能为 0：${it.w}x${it.h}", it.w > 0 && it.h > 0)
                assertTrue("尺寸不能小于 MIN_SIDE：${it.w}x${it.h}",
                    it.w >= GlowPyramid.MIN_SIDE && it.h >= GlowPyramid.MIN_SIDE)
                assertTrue("必须真的比原图小", it.w <= w && it.h <= h)
            }
            // 严格递减
            for (i in 1 until lv.size) {
                assertTrue("必须严格递减：${lv[i - 1].w} → ${lv[i].w}", lv[i].w < lv[i - 1].w)
            }
        }
    }

    @Test
    fun `小图不会产生过多级数`() {
        val lv = GlowPyramid.plan(40, 40, 96f)
        assertTrue("40px 的图不可能有 5 级", lv.size < 5)
        assertEquals(listOf(20, 10, 5), lv.map { it.w })
    }

    @Test
    fun `扩散半径越大级数越多`() {
        val small = GlowPyramid.plan(2048, 2048, 6f).size
        val mid = GlowPyramid.plan(2048, 2048, 24f).size
        val big = GlowPyramid.plan(2048, 2048, 90f).size
        assertTrue("6px($small) < 24px($mid)", small < mid)
        assertTrue("24px($mid) <= 90px($big)", mid <= big)
        assertEquals("上限必须被钉在 MAX_LEVELS", GlowPyramid.MAX_LEVELS, big)
    }

    @Test
    fun `关闭时返回空列表`() {
        assertTrue(GlowPyramid.plan(2048, 2048, 0f).isEmpty())
        assertTrue(GlowPyramid.plan(2048, 2048, -5f).isEmpty())
        assertTrue(GlowPyramid.plan(0, 100, 24f).isEmpty())
        assertTrue(GlowPyramid.plan(100, 0, 24f).isEmpty())
    }

    @Test
    fun `极小扩散半径也至少两级`() {
        // 规格要求"多次高斯"，一级等于普通模糊，必须被 MIN_LEVELS 兜住
        val lv = GlowPyramid.plan(512, 512, 1f)
        assertEquals(GlowPyramid.MIN_LEVELS, lv.size)
    }

    @Test
    fun `sigma 恒为 texel 空间的 1`() {
        // 大半径由级数承担，核自身不放大 —— 这是整套方案成本可控的前提
        GlowPyramid.plan(1024, 1024, 30f).forEach {
            assertEquals(GlowPyramid.SIGMA, it.sigma, 1e-6f)
        }
    }
}
