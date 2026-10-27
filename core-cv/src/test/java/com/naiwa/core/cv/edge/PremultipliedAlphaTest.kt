package com.naiwa.core.cv.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 6.1 预乘 Alpha 的检查与反解测试。
 *
 * 这块最容易出的错不是崩溃，而是**判断反了** —— 把非预乘当预乘去反解，
 * 边缘 RGB 会被反向放大到过亮，直接制造白边。所以"判定"必须有测试钉住。
 */
class PremultipliedAlphaTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * 构造一张"预乘"图：RGB 已按 alpha 压暗。
     *
     * **注意这里用四舍五入而不是截断** —— 它模拟的是 Android/Skia 解码器的实际行为。
     * 用截断会让夹具自身的误差达到 1 LSB，而反预乘会把输入端误差放大 `255/alpha` 倍，
     * 于是测试测到的其实是"夹具的粗糙度"，不是被测代码的质量（实测差一倍：12 vs 6）。
     */
    private fun premultipliedImage(size: Int): IntArray {
        // 本真色 (200, 180, 140)，alpha 从 0 到 255 递变
        val truth = intArrayOf(200, 180, 140)
        return IntArray(size) { i ->
            val a = (i * 255 / (size - 1))
            argb(
                a,
                (truth[0] * a + 127) / 255,
                (truth[1] * a + 127) / 255,
                (truth[2] * a + 127) / 255,
            )
        }
    }

    /** 构造一张"非预乘"图：半透明像素 RGB 仍是本真值。 */
    private fun unpremultipliedImage(size: Int): IntArray {
        val truth = intArrayOf(200, 180, 140)
        return IntArray(size) { i ->
            val a = (i * 255 / (size - 1))
            argb(a, truth[0], truth[1], truth[2])
        }
    }

    @Test
    fun `能识别出预乘格式`() {
        val a = PremultipliedAlpha.assess(premultipliedImage(64))
        println("[6.1] 预乘图判定：premultiplied=${a.premultiplied} 违规率=%.4f 采样=%d".format(a.violatingRatio, a.sampled))
        assertTrue("应判定为预乘", a.premultiplied)
        assertTrue("违规率应接近 0", a.violatingRatio < 0.01f)
        assertTrue("应有足够采样", a.sampled > 32)
    }

    @Test
    fun `能识别出非预乘格式`() {
        val a = PremultipliedAlpha.assess(unpremultipliedImage(64))
        println("[6.1] 非预乘图判定：premultiplied=${a.premultiplied} 违规率=%.4f".format(a.violatingRatio))
        assertFalse("非预乘图不能被误判为预乘（否则会把边缘反解到过亮）", a.premultiplied)
        assertTrue("违规率应很高", a.violatingRatio > 0.5f)
    }

    @Test
    fun `采样不足时不冒险反转`() {
        // 全不透明图：没有半透明像素可判断，必须返回"非预乘"＝不做任何改动
        val opaque = IntArray(100) { argb(255, 10, 20, 30) }
        val a = PremultipliedAlpha.assess(opaque)
        assertFalse(a.premultiplied)
        assertEquals(0, a.sampled)
    }

    @Test
    fun `反预乘能还原本真色`() {
        val pre = premultipliedImage(256)
        val truth = unpremultipliedImage(256)
        val un = PremultipliedAlpha.unpremultiply(pre)

        // 预乘 → 反预乘是**有损**的：预乘时做了整数取整，
        // 反解时误差被放大 255/alpha 倍。用四舍五入后理论上界 ≈ 255/(2·alpha)。
        // 注意：高 alpha 段这个上界会小于 1（alpha=250 时只有 0.51），而整数误差最小就是 1 ——
        // 所以判据必须写成**绝对余量** `err - bound ≤ 1.5`，不能写成"误差/上界 ≤ 1"
        // （后者在高 alpha 段必然失败，是个错误的度量）。
        var worstExcess = Double.NEGATIVE_INFINITY
        var worstAlpha = 0
        for (i in 0 until 256) {
            val a = (pre[i] ushr 24) and 0xFF
            if (a < 16) continue
            var err = 0
            for (shift in intArrayOf(16, 8, 0)) {
                err = maxOf(err, abs(((un[i] shr shift) and 0xFF) - ((truth[i] shr shift) and 0xFF)))
            }
            val bound = 255.0 / (2.0 * a)
            val excess = err - bound
            if (excess > worstExcess) {
                worstExcess = excess
                worstAlpha = a
            }
        }
        println(
            "[6.1] 反预乘还原：最差「误差 − 理论上界」= %.2f（出现在 alpha=$worstAlpha，上界 %.2f）"
                .format(worstExcess, 255.0 / (2.0 * worstAlpha)),
        )
        assertTrue("超出理论上界的余量不应大于 1.5 个像素值，实测 $worstExcess", worstExcess <= 1.5)

        // 高 alpha 段必须几乎完全还原
        var highAlphaErr = 0
        for (i in 0 until 256) {
            val a = (pre[i] ushr 24) and 0xFF
            if (a < 128) continue
            for (shift in intArrayOf(16, 8, 0)) {
                highAlphaErr = maxOf(highAlphaErr, abs(((un[i] shr shift) and 0xFF) - ((truth[i] shr shift) and 0xFF)))
            }
        }
        println("[6.1] alpha ≥ 128 时最大还原误差 = $highAlphaErr")
        assertTrue("高 alpha 段应几乎完全还原，实测 $highAlphaErr", highAlphaErr <= 2)
    }

    @Test
    fun `预乘结果每个通道都不超过alpha`() {
        val un = unpremultipliedImage(256)
        val pre = PremultipliedAlpha.premultiply(un)
        for (c in pre) {
            val a = (c ushr 24) and 0xFF
            assertTrue("预乘后 R 必须 ≤ alpha", ((c shr 16) and 0xFF) <= a)
            assertTrue("预乘后 G 必须 ≤ alpha", ((c shr 8) and 0xFF) <= a)
            assertTrue("预乘后 B 必须 ≤ alpha", (c and 0xFF) <= a)
        }
        // 顺带验证：预乘后的图会被判为预乘（闭环自洽）
        assertTrue(PremultipliedAlpha.assess(pre).premultiplied)
    }

    @Test
    fun `全透明与全不透明像素不被改动`() {
        val pixels = intArrayOf(argb(0, 9, 9, 9), argb(255, 200, 180, 140))
        val un = PremultipliedAlpha.unpremultiply(pixels)
        assertEquals("全透明像素应归零", 0, un[0])
        assertEquals("全不透明像素应原样保留", pixels[1], un[1])
        val re = PremultipliedAlpha.premultiply(un)
        assertEquals(0, re[0])
        assertEquals(pixels[1], re[1])
    }
}
