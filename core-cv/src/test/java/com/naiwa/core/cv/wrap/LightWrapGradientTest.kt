package com.naiwa.core.cv.wrap

import com.naiwa.core.cv.scene.ScenePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四色渐变环境光（规格 7.4）契约测试。
 */
class LightWrapGradientTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun neutralPalette(): ScenePalette = ScenePalette.NEUTRAL

    @Test
    fun `强度为零时逐像素不变`() {
        val pixels = intArrayOf(argb(255, 180, 120, 80), argb(0, 0, 0, 0))
        val out = LightWrapGradient.wrapPixels(pixels, 2, 1, neutralPalette(), 0f)
        for (i in pixels.indices) {
            assertEquals("强度 0 不变", pixels[i], out[i])
        }
    }

    @Test
    fun `alpha通道保持不变且只作用于前景`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) argb(255, 150, 150, 150) else argb(0, 0, 0, 0)
        }
        val out = LightWrapGradient.wrapPixels(pixels, w, h, neutralPalette(), 0.2f)
        for (i in pixels.indices) {
            assertEquals("alpha 不变", pixels[i] ushr 24, out[i] ushr 24)
            if ((pixels[i] ushr 24) == 0) {
                assertEquals("透明区不动", pixels[i], out[i])
            }
        }
    }

    @Test
    fun `上下颜色差异大会产生上蓝下绿趋势`() {
        val w = 20
        val h = 20
        // 纯灰奶蛙
        val pixels = IntArray(w * h) { argb(255, 128, 128, 128) }
        // 上蓝下绿的场景调色板
        val palette = ScenePalette(
            highlightColor = 0xFFFFFFFF.toInt(),
            shadowColor = 0xFF26221C.toInt(),
            topColor = argb(255, 60, 90, 220),
            bottomColor = argb(255, 40, 180, 60),
            leftColor = argb(255, 128, 128, 128),
            rightColor = argb(255, 128, 128, 128),
            topLuma = 100f,
            bottomLuma = 100f,
            leftLuma = 128f,
            rightLuma = 128f,
        )
        val out = LightWrapGradient.wrapPixels(pixels, w, h, palette, 0.25f)

        // 顶部像素应比底部像素更蓝
        val topPixel = out[1 * w + w / 2]
        val bottomPixel = out[(h - 2) * w + w / 2]
        val topBlueBias = (topPixel and 0xFF) - ((topPixel shr 16) and 0xFF)
        val bottomGreenBias = ((bottomPixel shr 8) and 0xFF) - (bottomPixel and 0xFF)
        assertTrue("顶部偏蓝", topBlueBias > 0)
        assertTrue("底部偏绿", bottomGreenBias > 0)
    }

    @Test
    fun `尺寸非法时原样返回`() {
        val pixels = intArrayOf(argb(255, 128, 128, 128))
        val out = LightWrapGradient.wrapPixels(pixels, 0, 0, neutralPalette(), 0.2f)
        assertEquals("非法尺寸原样", pixels[0], out[0])
    }
}
