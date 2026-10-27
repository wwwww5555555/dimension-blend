package com.naiwa.core.cv.blend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局部泊松"核心区保真拼接"（规格 7）的契约测试。
 *
 * 用一个简单的 40×40 方块前景验证三区语义：
 *  - 核心区（距边界 ≥4px）：必须等于奶蛙原像素（泊松值被替换）；
 *  - 环形缝合区（边界 ~4px 内）：取泊松值；
 *  - 过渡带：值介于两者之间。
 */
class RingSeamTest {

    private val w = 40
    private val h = 40

    /** 20×20 居中方块掩膜（0/255），前景中心距边界 10px > 4px，存在核心区。 */
    private fun squareMask(): IntArray {
        val m = IntArray(w * h)
        for (y in 10 until 30) {
            for (x in 10 until 30) {
                m[y * w + x] = 255
            }
        }
        return m
    }

    private fun argb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `核心区写回奶蛙原像素`() {
        val mask = squareMask()
        val poisson = IntArray(w * h) { argb(10, 20, 30) }   // 泊松重建色（被抹糊的假象）
        val frog = IntArray(w * h) { argb(200, 100, 50) }    // 奶蛙原图（清晰线稿）

        val out = RingSeam.apply(mask, poisson, frog, w, h)

        // 方块中心 (20,20) 距边界 10px ≥ 4px → 核心区，必须取奶蛙原像素
        val center = out[20 * w + 20]
        assertEquals("核心区必须保真", argb(200, 100, 50), center)
    }

    @Test
    fun `环形缝合区取泊松值`() {
        val mask = squareMask()
        val poisson = IntArray(w * h) { argb(10, 20, 30) }
        val frog = IntArray(w * h) { argb(200, 100, 50) }

        val out = RingSeam.apply(mask, poisson, frog, w, h)

        // 方块最外圈 (10..29 的边缘) 距边界 1px < 4px → 环形区，取泊松值
        // 注意 2px 软化会向内渗 2px：距边界 1~2px 处 t≈0，应仍接近泊松值
        val edge = out[10 * w + 15] // y=10 是最上排，距边界 1px
        assertEquals("环形区取泊松值", argb(10, 20, 30), edge)
    }

    @Test
    fun `过渡带介于两值之间`() {
        val mask = squareMask()
        val poisson = IntArray(w * h) { argb(0, 0, 0) }
        val frog = IntArray(w * h) { argb(255, 255, 255) }

        val out = RingSeam.apply(mask, poisson, frog, w, h)

        fun r(y: Int) = (out[y * w + 20] ushr 16) and 0xFF

        // 沿中心列的真实序列是"升→平台→降"（方块上下对称，核心区在两端各收缩 ~3px），
        // 所以分段断言：顶段单调升、核心平台=255、底段对称降。
        for (y in 10 until 15) {
            assertTrue("顶段过渡应单调不减（y=$y, r=${r(y)}）", r(y) <= r(y + 1))
        }
        for (y in 15..24) {
            assertEquals("核心深处=原像素（y=$y）", 255, r(y))
        }
        for (y in 25 until 29) {
            assertTrue("底段过渡应单调不增（y=$y, r=${r(y)}）", r(y) >= r(y + 1))
        }
        // 两端边界偏泊松侧（t < 0.5），全程值域合法
        assertTrue("顶边界偏泊松侧（r=${r(10)}）", r(10) < 128)
        assertTrue("底边界偏泊松侧（r=${r(29)}）", r(29) < 128)
        for (y in 10 until 30) {
            assertTrue("值域合法（y=$y, r=${r(y)}）", r(y) in 0..255)
        }
    }

    @Test
    fun `掩膜外保持泊松输出不变`() {
        val mask = squareMask()
        // mask 外每个位置的泊松值不同（可区分），验证不被 frog 污染
        val poisson = IntArray(w * h) { i -> if (mask[i] >= 128) argb(0, 0, 0) else argb(i % 251, 0, 0) }
        val frog = IntArray(w * h) { argb(200, 100, 50) }

        val out = RingSeam.apply(mask, poisson, frog, w, h)

        for (i in out.indices) {
            if (mask[i] < 128) {
                assertEquals("mask 外不受影响（i=$i）", poisson[i], out[i])
            }
        }
    }

    @Test
    fun `细线条无核心区时整条取泊松`() {
        // 3px 宽竖线：距边界最大 1~2px < 4px → 无核心区
        val m = IntArray(w * h)
        for (y in 5 until 35) {
            for (x in 18 until 21) {
                m[y * w + x] = 255
            }
        }
        val poisson = IntArray(w * h) { argb(10, 20, 30) }
        val frog = IntArray(w * h) { argb(200, 100, 50) }

        val out = RingSeam.apply(m, poisson, frog, w, h)

        for (y in 5 until 35) {
            assertEquals("细线整体取泊松（y=$y）", argb(10, 20, 30), out[y * w + 19])
        }
    }

    @Test
    fun `alpha透传奶蛙值`() {
        val mask = squareMask()
        val poisson = IntArray(w * h) { argb(10, 20, 30) } // argb 内含 255 alpha
        // 奶蛙半透明（直接拼位，不经 argb 以免 alpha 被覆盖成 255）
        val frog = IntArray(w * h) { (128 shl 24) or (200 shl 16) or (100 shl 8) or 50 }

        val out = RingSeam.apply(mask, poisson, frog, w, h)
        assertEquals("核心区 alpha 透传奶蛙", 128, out[20 * w + 20] ushr 24)
    }

    @Test
    fun `非法输入返回泊松拷贝`() {
        val poisson = intArrayOf(argb(1, 2, 3), argb(4, 5, 6), argb(7, 8, 9), argb(9, 9, 9))
        val out = RingSeam.apply(
            sceneMask = IntArray(4) { 255 },
            poissonPixels = poisson,
            frogPixels = IntArray(4), // 尺寸不够（2x2 需要 4 个，这里故意传 4 但 width=2,height=2 合法）
            width = 2,
            height = 2,
        )
        // 2x2 全前景，每像素距边界最大 1px < 4px → 无核心区 → 泊松原样
        for (i in poisson.indices) {
            assertEquals(poisson[i], out[i])
        }
    }

    @Test
    fun `尺寸不匹配返回泊松拷贝不抛异常`() {
        val poisson = intArrayOf(1, 2, 3, 4)
        val out = RingSeam.apply(
            sceneMask = IntArray(4) { 255 },
            poissonPixels = poisson,
            frogPixels = IntArray(4),
            width = 4,  // 4x4 需要 16 像素，输入只有 4 个 → 非法
            height = 4,
        )
        assertEquals(4, out.size)
        assertEquals(1, out[0])
    }
}
