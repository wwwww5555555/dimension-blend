package com.naiwa.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文本滤镜链（EffectString）解析契约测试。 */
class EffectStringParserTest {

    @Test
    fun `解析多键滤镜链`() {
        val e = EffectStringParser.parse("brightness(0.10); contrast(1.25); saturation(1.30)")
        assertEquals(3, e.size)
        assertEquals("brightness", e[0].name)
        assertEquals(0.10f, e[0].value, 1e-4f)
        assertEquals(1.25f, e[1].value, 1e-4f)
        assertEquals(1.30f, e[2].value, 1e-4f)
    }

    @Test
    fun `逗号分隔也支持`() {
        val e = EffectStringParser.parse("sepia(0.4), vignette(0.2)")
        assertEquals(2, e.size)
        assertEquals("sepia", e[0].name)
    }

    @Test
    fun `裸键按1处理`() {
        val e = EffectStringParser.parse("sepia")
        assertEquals(1, e.size)
        assertEquals(1f, e[0].value, 1e-4f)
    }

    @Test
    fun `历史gp前缀兼容`() {
        val sepia = EffectStringParser.parse("gp:sepia")
        assertEquals(1, sepia.size)
        assertEquals("sepia", sepia[0].name)
        assertEquals(1f, sepia[0].value, 1e-4f)

        val contrast = EffectStringParser.parse("gp:contrast:1.4")
        assertEquals("contrast", contrast[0].name)
        assertEquals(1.4f, contrast[0].value, 1e-4f)
    }

    @Test
    fun `未知键被忽略而不破坏整条链`() {
        val e = EffectStringParser.parse("brightness(0.2); 不存在的键(9); contrast(1.1)")
        assertEquals("未知键应被丢弃，其余保留", 2, e.size)
        assertEquals("brightness", e[0].name)
        assertEquals("contrast", e[1].name)
    }

    @Test
    fun `空串与空白返回空链`() {
        assertTrue(EffectStringParser.parse("").isEmpty())
        assertTrue(EffectStringParser.parse("   ").isEmpty())
    }

    @Test
    fun `uniform默认值等于恒等变换`() {
        val u = EffectStringParser.uniformsFor(emptyList())
        assertEquals(0f, u["uBrightness"]!!, 1e-6f)
        assertEquals(1f, u["uContrast"]!!, 1e-6f)
        assertEquals(1f, u["uSaturation"]!!, 1e-6f)
        assertEquals(0f, u["uSepia"]!!, 1e-6f)
        assertEquals(0f, u["uVignette"]!!, 1e-6f)
        assertEquals(1f, u["uExposure"]!!, 1e-6f)
    }

    @Test
    fun `参数被钳制在安全区间`() {
        val u = EffectStringParser.uniformsFor(EffectStringParser.parse("contrast(99); brightness(-9)"))
        assertEquals("对比度上限 3", 3f, u["uContrast"]!!, 1e-6f)
        assertEquals("亮度下限 -1", -1f, u["uBrightness"]!!, 1e-6f)
    }

    @Test
    fun `shader包含全部uniform且UV被clamp`() {
        val src = EffectStringParser.SHADER_EFFECT
        listOf(
            "uBrightness", "uContrast", "uSaturation", "uSepia",
            "uVignette", "uExposure", "uTempR", "uTempG", "uTempB",
        ).forEach { assertTrue("shader 缺少 $it", src.contains(it)) }
        assertTrue("UV 必须 clamp（规格四.2）", src.contains("clamp(vTexCoord"))
    }
}
