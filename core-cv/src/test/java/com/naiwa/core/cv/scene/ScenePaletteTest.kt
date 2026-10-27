package com.naiwa.core.cv.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景取色（规格 7.1/7.3/7.4 的共同数据源）契约测试。
 */
class ScenePaletteTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `空输入返回中性兜底不抛异常`() {
        val p = ScenePaletteExtractor.extract(IntArray(0), 0, 0)
        assertEquals("空输入返回中性", ScenePalette.NEUTRAL, p)
    }

    @Test
    fun `纯色图高光与暗部同色`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { argb(128, 128, 128) }
        val p = ScenePaletteExtractor.extract(pixels, w, h)
        // 纯灰图：高光/暗部都应该是 128（暗部会被压暗，高光应接近原色）
        val hr = (p.highlightColor shr 16) and 0xFF
        assertEquals("高光为原灰", 128, hr)
        assertTrue("暗部被压暗", ((p.shadowColor shr 16) and 0xFF) < 128)
    }

    @Test
    fun `上蓝下绿场景能分别取到对应主色`() {
        val w = 60
        val h = 60
        val pixels = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 2) argb(80, 120, 220) else argb(40, 180, 80)
        }
        val p = ScenePaletteExtractor.extract(pixels, w, h)
        val topB = p.topColor and 0xFF
        val topR = (p.topColor shr 16) and 0xFF
        val bottomG = (p.bottomColor shr 8) and 0xFF
        val bottomB = p.bottomColor and 0xFF
        // 上区蓝色分量占优、下区绿色分量占优
        assertTrue("上区偏蓝", topB > topR)
        assertTrue("下区偏绿", bottomG > bottomB)
    }

    @Test
    fun `过曝纯白不污染区域主色`() {
        // 下半部分过曝白、上半部分纯绿，验证过曝被排除
        val w = 40
        val h = 40
        val pixels = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 2) argb(0, 200, 0) else argb(255, 255, 255)
        }
        val p = ScenePaletteExtractor.extract(pixels, w, h)
        // 顶部应为绿色（不是被白拉高）
        val topG = (p.topColor shr 8) and 0xFF
        val topR = (p.topColor shr 16) and 0xFF
        assertTrue("顶部保留绿色、不被过曝污染", topG > topR + 80)
    }

    @Test
    fun `中位色逐通道取中位数`() {
        val colors = listOf(
            argb(10, 200, 10),
            argb(200, 10, 10),
            argb(10, 10, 200),
        )
        val mid = ScenePaletteExtractor.medianColor(colors)
        assertEquals("R 中位", 10, (mid shr 16) and 0xFF)
        assertEquals("G 中位", 10, (mid shr 8) and 0xFF)
        assertEquals("B 中位", 10, mid and 0xFF)
    }

    /**
     * 四色渐变环境光靠 left/right 两个区取色 —— 必须验证左右分区确实按 x 切分，
     * 否则 shader 里的双线性插值会退化成"上下渐变"，左红右蓝的场景会串成一片。
     */
    @Test
    fun `左红右蓝场景能分别取到左右主色`() {
        val w = 60
        val h = 30
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (x < w / 2) argb(200, 20, 20) else argb(20, 20, 200)
            }
        }
        val p = ScenePaletteExtractor.extract(px, w, h)
        val leftR = (p.leftColor shr 16) and 0xFF
        val leftB = p.leftColor and 0xFF
        val rightR = (p.rightColor shr 16) and 0xFF
        val rightB = p.rightColor and 0xFF
        assertTrue("左侧应偏红（R=$leftR B=$leftB）", leftR > leftB + 60)
        assertTrue("右侧应偏蓝（R=$rightR B=$rightB）", rightB > rightR + 60)
    }
}
