package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光源邻域取色的验收。
 *
 * 核心诉求：**光在哪边，就取哪边的颜色**。用一个"左红右蓝"的合成图验证。
 */
class LocalLightSampleTest {

    private val w = 120
    private val h = 120

    /** 左半暖橙、右半冷蓝；中间有一条过渡带。 */
    private fun splitImage(): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (x < w / 2) 0xFFE05A20.toInt() else 0xFF2050E0.toInt()
            }
        }
        return px
    }

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test
    fun `光源在左边取到暖色`() {
        val c = LocalLightSample.extract(splitImage(), w, h, lightX = 0.15f, lightY = 0.5f)
        assertTrue("应该取到右侧的暖橙：r=${r(c)} b=${b(c)}", r(c) > b(c) + 60)
        assertEquals("alpha 必须不透明", 0xFF, (c shr 24) and 0xFF)
    }

    @Test
    fun `光源在右边取到冷色`() {
        val c = LocalLightSample.extract(splitImage(), w, h, lightX = 0.85f, lightY = 0.5f)
        assertTrue("应该取到右侧的冷蓝：r=${r(c)} b=${b(c)}", b(c) > r(c) + 60)
    }

    @Test
    fun `左右两点的结果必须不同`() {
        val l = LocalLightSample.extract(splitImage(), w, h, 0.15f, 0.5f)
        val rr = LocalLightSample.extract(splitImage(), w, h, 0.85f, 0.5f)
        assertTrue("光源换边但取色一样，说明没按位置采样", l != rr)
    }

    @Test
    fun `半径越大取样越接近混合色`() {
        // v0.30 的语义：邻域**整圈平均**。小半径只吃左侧暖色；大半径横跨两半 → 更中性。
        // ⚠️ 两个采样点不能都放在分界线上，否则小半径也在跨边界，测不出差异。
        val small = LocalLightSample.extract(splitImage(), w, h, 0.15f, 0.5f, radiusPx = 6)
        val big = LocalLightSample.extract(splitImage(), w, h, 0.5f, 0.5f, radiusPx = 55)
        assertTrue("大半径应更中性：kdiff small=${kdiff(small)} big=${kdiff(big)}",
            kdiff(big) < kdiff(small))
    }

    private fun kdiff(c: Int): Int = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c))

    @Test
    fun `纯黑区域返回 0 让调用方回退`() {
        val black = IntArray(w * h) { 0xFF000000.toInt() }
        assertEquals("全黑无可信样本，必须返回 0（调用方回退全局高光色）",
            0, LocalLightSample.extract(black, w, h, 0.5f, 0.5f))
    }

    @Test
    fun `样本不足返回 0`() {
        // 半径 1px 只有 4~5 个像素，不足 12
        val px = splitImage()
        assertEquals(0, LocalLightSample.extract(px, w, h, 0.5f, 0.5f, radiusPx = 1))
    }

    @Test
    fun `非法输入不崩溃`() {
        assertEquals(0, LocalLightSample.extract(IntArray(0), 0, 0, 0.5f, 0.5f))
        assertEquals(0, LocalLightSample.extract(IntArray(10), 100, 100, 0.5f, 0.5f))
        assertEquals(0, LocalLightSample.extract(splitImage(), w, h, Float.NaN, 0.5f))
        assertEquals(0, LocalLightSample.extract(splitImage(), w, h, 0.5f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `越界坐标被钳到边界而不是返回 0`() {
        val px = splitImage()
        val c1 = LocalLightSample.extract(px, w, h, -5f, 0.5f)
        val c2 = LocalLightSample.extract(px, w, h, 9f, 0.5f)
        assertTrue("坐标越界也要能取到左侧的暖色", r(c1) > b(c1) + 60)
        assertTrue("坐标越界也要能取到右侧的冷色", b(c2) > r(c2) + 60)
    }

    @Test
    fun `亮部权重更高`() {
        // 左半亮红（240）、右半暗蓝（40）；光源取中间偏左
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (x < w / 2) 0xFFF02820.toInt() else 0xFF2828A0.toInt()
            }
        }
        val c = LocalLightSample.extract(px, w, h, 0.5f, 0.5f, radiusPx = 60)
        assertTrue("luma² 加权应让亮的一侧主导：r=${r(c)} b=${b(c)}", r(c) > b(c))
    }
}
