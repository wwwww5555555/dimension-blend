package com.naiwa.core.cv.blend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photoshop 混合模式参考实现的契约测试（规格 8.1）。 */
class BlendModesTest {

    @Test
    fun `screen黑加不上白加满`() {
        // "黑加不上"= b=0 时结果不变（=a），而不是 0
        assertEquals(0.5f, BlendModes.screen(0.5f, 0f), 1e-6f)
        assertEquals(1f, BlendModes.screen(0.5f, 1f), 1e-6f)
        assertEquals(0.75f, BlendModes.screen(0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun `screen恒大于等于两输入`() {
        // 0.5 + 0.2 - 0.5×0.2 = 0.6
        assertEquals(0.6f, BlendModes.screen(0.5f, 0.2f), 1e-6f)
        assertTrue(BlendModes.screen(0.3f, 0.4f) >= 0.4f)
        assertTrue(BlendModes.screen(0.3f, 0.4f) >= 0.3f)
    }

    @Test
    fun `multiply白乘不变黑乘压死`() {
        assertEquals(0.5f, BlendModes.multiply(0.5f, 1f), 1e-6f)
        assertEquals(0f, BlendModes.multiply(0.5f, 0f), 1e-6f)
        assertEquals(0.25f, BlendModes.multiply(0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun `multiply带opacity时b为1不变b为0按opacity压暗`() {
        assertEquals(0.5f, BlendModes.multiplyWithOpacity(0.5f, 1f, 0.6f), 1e-6f)
        assertEquals(0.5f * (1f - 0.6f), BlendModes.multiplyWithOpacity(0.5f, 0f, 0.6f), 1e-6f)
    }

    @Test
    fun `softlight中灰不移动极端收敛`() {
        // b=0.5 时 (1-2b)a² + 2ba = a（不动点）
        assertEquals(0.5f, BlendModes.softLight(0.5f, 0.5f), 1e-6f)
        // b=0（黑）→ a² 压暗；b=1（白）→ a 提亮
        assertEquals(0.25f, BlendModes.softLight(0.5f, 0f), 1e-6f)
        assertEquals(0.75f, BlendModes.softLight(0.5f, 1f), 1e-6f)
        assertTrue(BlendModes.softLight(0.5f, 0.8f) > 0.5f)
        assertTrue(BlendModes.softLight(0.5f, 0.2f) < 0.5f)
    }
}
