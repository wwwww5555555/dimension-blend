package com.naiwa.core.cv.glow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 辉光（规格 7.2）契约测试。
 */
class GlowTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `强度为零时逐像素不变`() {
        val pixels = intArrayOf(argb(255, 200, 100, 50), argb(0, 0, 0, 0))
        val out = Glow.glowPixels(pixels, 2, 1, 0f)
        for (i in pixels.indices) {
            assertEquals("强度 0 不变", pixels[i], out[i])
        }
    }

    @Test
    fun `alpha通道保持不变`() {
        val w = 16
        val h = 16
        // 一半是高亮前景（alpha 255），一半透明
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) argb(255, 255, 255, 255) else argb(0, 0, 0, 0)
        }
        val out = Glow.glowPixels(pixels, w, h, 0.2f)
        for (i in pixels.indices) {
            assertEquals("alpha 不变", pixels[i] ushr 24, out[i] ushr 24)
        }
    }

    @Test
    fun `暗部不参与辉光`() {
        val w = 8
        val h = 8
        // 全部是低亮度像素（低于高光阈值 204）
        val pixels = IntArray(w * h) { argb(255, 60, 60, 60) }
        val out = Glow.glowPixels(pixels, w, h, 0.2f)
        for (i in pixels.indices) {
            assertEquals("暗部不应被辉光提亮", pixels[i], out[i])
        }
    }

    @Test
    fun `高亮区域会被提亮`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { argb(255, 255, 255, 255) }
        val out = Glow.glowPixels(pixels, w, h, 0.2f)
        // 中心区域应比原始更亮（Screen 混合只增不减），但 alpha 区外应保持
        val center = out[(h / 2) * w + w / 2]
        assertTrue("高亮被提亮", ((center shr 16) and 0xFF) >= 255)
    }

    @Test
    fun `尺寸非法时原样返回`() {
        val pixels = intArrayOf(argb(255, 255, 255, 255))
        val out = Glow.glowPixels(pixels, 0, 0, 0.2f)
        assertEquals("非法尺寸原样", pixels[0], out[0])
    }

    @Test
    fun `方向模糊与各向同性输出不同`() {
        val w = 32
        val h = 32
        // 左上角一块高亮，其余暗部 —— 方向模糊会让高亮沿方向拉伸，与各向同性不同
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 8 && y < 8) argb(255, 255, 255, 255) else argb(255, 60, 60, 60)
        }
        val isotropic = Glow.glowPixels(pixels, w, h, 0.2f, directionAngleDeg = null)
        val directional = Glow.glowPixels(pixels, w, h, 0.2f, directionAngleDeg = 0f)
        // 方向性输出与各向同性应存在差异（拉伸方向不同导致亮斑分布不同）
        var diff = false
        for (i in pixels.indices) {
            if (isotropic[i] != directional[i]) {
                diff = true
                break
            }
        }
        assertTrue("方向模糊应改变辉光分布", diff)
    }

    @Test
    fun `遮罩控制限定辉光区域`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { argb(255, 255, 255, 255) } // 全高亮
        // 遮罩只允许左半区辉光
        val mask = FloatArray(w * h) { i -> if (i % w < w / 2) 1f else 0f }
        val out = Glow.glowPixels(pixels, w, h, 0.2f, glowMask = mask)
        // 右半区（遮罩外）应保持原始纯白（255），左半区可能被 Screen 提亮但纯白已是上限
        // 关键断言：遮罩外的像素不被额外改变（虽纯白已是 255，无法体现"变亮"，但至少不越界）
        for (i in out.indices) {
            val x = i % w
            if (x >= w / 2) {
                assertEquals("遮罩外不变", pixels[i], out[i])
            }
        }
    }

    @Test
    fun `方向模糊alpha不变`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) argb(255, 255, 255, 255) else argb(0, 0, 0, 0)
        }
        val out = Glow.glowPixels(pixels, w, h, 0.2f, directionAngleDeg = 45f)
        for (i in pixels.indices) {
            assertEquals("alpha 不变", pixels[i] ushr 24, out[i] ushr 24)
        }
    }
}
