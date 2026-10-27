package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D LUT 选择规则（规格 8.1）契约测试。
 */
class LutChoiceTest {

    @Test
    fun `低色温选暖光室内`() {
        val sel = LutChoice.select(2800f, 0.3f, backlit = false)
        assertEquals(LutPreset.WARM_INDOOR, sel.primary)
        assertFalse(sel.isBlend)
    }

    @Test
    fun `高色温选冷光夜景`() {
        val sel = LutChoice.select(9000f, 0.3f, backlit = false)
        assertEquals(LutPreset.COOL_NIGHT, sel.primary)
        assertFalse(sel.isBlend)
    }

    @Test
    fun `中性色温选阴天户外`() {
        val sel = LutChoice.select(6500f, 0.3f, backlit = false)
        assertEquals(LutPreset.OVERCAST, sel.primary)
    }

    @Test
    fun `逆光且主光强时选逆光LUT`() {
        val sel = LutChoice.select(6500f, 0.8f, backlit = true)
        assertEquals(LutPreset.BACKLIT, sel.primary)
    }

    @Test
    fun `过渡带产生两套LUT插值`() {
        // 4500K 在暖室内(3500)与阴天(5500)之间，应产生插值
        val sel = LutChoice.select(4500f, 0.3f, backlit = false)
        assertTrue("过渡带应插值", sel.isBlend)
        assertEquals(LutPreset.WARM_INDOOR, sel.primary)
        assertEquals(LutPreset.OVERCAST, sel.secondary)
        // blend 在 (0,1) 之间
        assertTrue("blend 在 (0,1)", sel.blend > 0f && sel.blend < 1f)
    }

    @Test
    fun `过渡带端点不插值`() {
        // 恰好 3500K 应直接选暖室内，不插值
        val sel = LutChoice.select(3500f, 0.3f, backlit = false)
        assertEquals(LutPreset.WARM_INDOOR, sel.primary)
        assertFalse(sel.isBlend)
    }
}
