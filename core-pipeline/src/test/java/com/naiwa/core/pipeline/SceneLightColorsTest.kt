package com.naiwa.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光包裹双色提取测试。
 *
 * 判据直接决定边缘光是"暖金迎光 + 冷青背光"还是"整圈一个色"：
 *  - 主光色必须来自**亮部**（≥p95），且偏暖（R > B）；
 *  - 环境色必须来自**暗部**（≤p50），且偏冷（B ≥ R）；
 *  - 纯灰场景两色都接近灰，不会凭空造出冷暖。
 */
class SceneLightColorsTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test
    fun `夕阳暖天加冷阴影能分出暖主光和冷环境色`() {
        // 2000 像素：顶部 5% 夕阳(255,190,110)，中调 60% (90,95,105)，暗部 35% 冷阴影(30,45,80)
        val px = ArrayList<Int>()
        repeat(100) { px.add(argb(255, 190, 110)) }
        repeat(1200) { px.add(argb(90, 95, 105)) }
        repeat(700) { px.add(argb(30, 45, 80)) }
        val c = SceneLightColors.extract(px.toIntArray())

        // 主光色 = 前 5% 的均值，应几乎等于夕阳色
        assertTrue("主光应偏暖：R=${r(c.key)} B=${b(c.key)}", r(c.key) > b(c.key) + 60)
        assertTrue("主光 R 应接近 255：${r(c.key)}", r(c.key) in 240..255)

        // 环境色 = ≤p50 的均值，应偏冷
        assertTrue("环境色应偏冷：R=${r(c.ambient)} B=${b(c.ambient)}", b(c.ambient) > r(c.ambient))
    }

    @Test
    fun `纯灰场景不会产生冷暖偏差`() {
        val px = IntArray(1000) { argb(120, 120, 120) }
        val c = SceneLightColors.extract(px)
        assertTrue("主光应中性：${c.key}", kotlin.math.abs(r(c.key) - b(c.key)) <= 1)
        assertTrue("环境色应中性：${c.ambient}", kotlin.math.abs(r(c.ambient) - b(c.ambient)) <= 1)
        assertEquals("灰场两色应一致", r(c.key), r(c.ambient))
    }

    @Test
    fun `空输入返回中性兜底`() {
        val c = SceneLightColors.extract(IntArray(0))
        assertEquals(SceneLightColors.KEY_NEUTRAL, c.key)
        assertEquals(SceneLightColors.AMBIENT_NEUTRAL, c.ambient)
    }

    @Test
    fun `主光色只取亮部不被大面积暗部拉低`() {
        // 5% 纯白 + 95% 纯黑：主光必须接近白（说明分位生效，不是全图均值）
        val px = IntArray(1000) { if (it < 50) argb(255, 255, 255) else argb(0, 0, 0) }
        val c = SceneLightColors.extract(px)
        assertTrue("主光应接近纯白：${r(c.key)}", r(c.key) >= 250)
        assertTrue("环境色应接近纯黑：${r(c.ambient)}", r(c.ambient) <= 5)
    }

    @Test
    fun `环境色取暗部而不是全图均值`() {
        // 一半冷蓝暗部 + 一半暖白亮部：全图均值会接近灰，暗部均值必须偏蓝
        val px = IntArray(1000) {
            if (it < 500) argb(20, 40, 90) else argb(240, 235, 225)
        }
        val c = SceneLightColors.extract(px)
        assertTrue("环境色应明显偏蓝：R=${r(c.ambient)} B=${b(c.ambient)}", b(c.ambient) > r(c.ambient) + 40)
        assertTrue("主光应偏暖：R=${r(c.key)} B=${b(c.key)}", r(c.key) > b(c.key))
    }

    @Test
    fun `输出始终是不透明ARGB`() {
        val px = IntArray(100) { argb(200, 100, 50) }
        val c = SceneLightColors.extract(px)
        assertEquals(0xFF, (c.key ushr 24) and 0xFF)
        assertEquals(0xFF, (c.ambient ushr 24) and 0xFF)
        listOf(r(c.key), g(c.key), b(c.key), r(c.ambient), g(c.ambient), b(c.ambient)).forEach {
            assertTrue("通道应在 0~255：$it", it in 0..255)
        }
    }
}
