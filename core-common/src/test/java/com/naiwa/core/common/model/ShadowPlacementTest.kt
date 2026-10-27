package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格 7.3 阴影投位的契约测试：逆光 / 侧光 / 顺光三种情形。
 */
class ShadowPlacementTest {

    private fun resolve(azimuthDeg: Float, keyIntensity: Float = 0.8f): ShadowPlacement {
        val light = LightParams(keyAzimuthDeg = azimuthDeg, keyIntensity = keyIntensity)
        return ShadowPlacementRule.resolve(light, LightFxRules.isBacklit(light))
    }

    @Test
    fun `逆光阴影投向前景且浓度减半`() {
        val p = resolve(azimuthDeg = 220f)
        // 投向前景 = 画面下方 = 90°
        assertEquals("逆光投向前景", ShadowPlacementRule.FRONT_OF_VIEW_AZIMUTH_DEG, p.azimuthDeg, 0.5f)
        assertEquals("逆光浓度 ×0.5", 0.5f, p.strengthScale, 1e-4f)
        assertFalse("逆光不是接触阴影", p.contactOnly)
        // 逆光长度 ×0.6，应比同高度的侧光短、比顺光接触阴影长
        val side = resolve(azimuthDeg = 150f)
        val front = resolve(azimuthDeg = 90f)
        assertTrue("逆光比侧光短", p.lengthPerHeight < side.lengthPerHeight)
        assertTrue("逆光比顺光接触阴影长", p.lengthPerHeight > front.lengthPerHeight)
    }

    @Test
    fun `顺光只剩接触阴影`() {
        // 光源在相机同侧（90°）→ 夹角 0° < 30°
        val p = resolve(azimuthDeg = 90f)
        assertTrue("顺光为接触阴影", p.contactOnly)
        assertTrue("顺光阴影很短", p.lengthPerHeight <= ShadowPlacementRule.FRONT_MAX_LENGTH + 1e-3f)
        assertTrue("顺光浓度减弱", p.strengthScale < 1f)
    }

    @Test
    fun `侧光沿光方向投射且浓度不缩放`() {
        // 夹角 60°（介于 30°~120°）→ 光源在 150° 或 30°
        val p = resolve(azimuthDeg = 150f)
        assertFalse("侧光不是接触阴影", p.contactOnly)
        assertEquals("侧光浓度不缩放", 1f, p.strengthScale, 1e-4f)
        // 阴影沿光方向（背光方向 = 光源来向反向），此处只验证方向合法即可
        assertEquals("方向沿用光方位角", 150f, p.azimuthDeg, 0.5f)
    }

    @Test
    fun `高度角极低时长度系数被钳制不会爆掉`() {
        val light = LightParams(keyAzimuthDeg = 150f, keyElevationDeg = 1f, keyIntensity = 0.8f)
        val p = ShadowPlacementRule.resolve(light, backlit = false)
        // 1/tan(1°) ≈ 57，但必须被钳制到 MAX_LENGTH_PER_HEIGHT = 6
        assertTrue("长度系数被钳制", p.lengthPerHeight <= 6f + 1e-3f)
    }
}
