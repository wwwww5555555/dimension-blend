package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光照参数契约的边界测试。
 *
 * 这些用例的价值在于**锁住契约**：只要有人改动了字段范围或兜底逻辑，
 * 测试立刻会红，而不是等到"某个场景下奶蛙颜色突然不对"才发现。
 */
class LightParamsTest {

    @Test
    fun `全部字段被钳制到合法区间`() {
        val wild = LightParams(
            colorTempK = 99999f,
            tint = 5f,
            exposureEv = -99f,
            brightness = 0f,
            contrast = 10f,
            saturation = -3f,
            keyAzimuthDeg = 725f,
            keyElevationDeg = 200f,
            keyIntensity = 9f,
            ambientIntensity = -1f,
            shadowStrength = 3f,
            shadowSoftness = -0.4f,
            confidence = 4f,
        ).clamped()

        assertEquals(LightParams.MAX_TEMP_K, wild.colorTempK, 0.01f)
        assertEquals(1f, wild.tint, 0.001f)
        assertEquals(-3f, wild.exposureEv, 0.001f)
        assertEquals(0.5f, wild.brightness, 0.001f)
        assertEquals(1.5f, wild.contrast, 0.001f)
        assertEquals(0.5f, wild.saturation, 0.001f)
        // 725° 归一化到 5°（725 - 360 - 360 = 5）
        assertEquals(5f, wild.keyAzimuthDeg, 0.001f)
        assertEquals(90f, wild.keyElevationDeg, 0.001f)
        assertEquals(1f, wild.keyIntensity, 0.001f)
        assertEquals(0f, wild.ambientIntensity, 0.001f)
        assertEquals(1f, wild.shadowStrength, 0.001f)
        assertEquals(0f, wild.shadowSoftness, 0.001f)
        assertEquals(1f, wild.confidence, 0.001f)
    }

    @Test
    fun `负角度归一化到 0 到 360`() {
        assertEquals(350f, LightParams.normalizeDegrees(-10f), 0.001f)
        assertEquals(180f, LightParams.normalizeDegrees(180f), 0.001f)
        assertEquals(0f, LightParams.normalizeDegrees(720f), 0.001f)
    }

    @Test
    fun `可信度低于阈值时必须判定为不可信`() {
        // 阈值 0.45：这是"模型估计失败就改走传统 CV"的开关
        assertFalse(LightParams(confidence = 0.44f).isTrustworthy)
        assertTrue(LightParams(confidence = 0.45f).isTrustworthy)
        assertTrue(LightParams(confidence = 0.9f).isTrustworthy)
    }

    @Test
    fun `中性兜底参数符合架构约定`() {
        // 架构文档 §6.2：兜底参数固定为 6500K / EV 0 / 方位角 135° / 高度角 45°
        val neutral = LightParams.NEUTRAL
        assertEquals(6500f, neutral.colorTempK, 0.01f)
        assertEquals(0f, neutral.exposureEv, 0.001f)
        assertEquals(135f, neutral.keyAzimuthDeg, 0.001f)
        assertEquals(45f, neutral.keyElevationDeg, 0.001f)
        assertFalse(neutral.isTrustworthy)
    }

    @Test
    fun `手动覆盖只改指定项其余保持自动值`() {
        val auto = LightParams(colorTempK = 4200f, keyAzimuthDeg = 30f, shadowStrength = 0.7f)
        val merged = auto.withOverrides(LightOverrides(colorTempK = 8000f))

        assertEquals(8000f, merged.colorTempK, 0.01f)
        // 未覆盖的项必须原样保留，否则"重置为自动"就没有意义了
        assertEquals(30f, merged.keyAzimuthDeg, 0.001f)
        assertEquals(0.7f, merged.shadowStrength, 0.001f)
    }

    @Test
    fun `主光强度覆盖是乘性不是替换`() {
        val auto = LightParams(keyIntensity = 0.4f)
        val boosted = auto.withOverrides(LightOverrides(lightIntensity = 1.5f))
        assertEquals(0.6f, boosted.keyIntensity, 0.001f)
    }

    @Test
    fun `默认覆盖值为全自动状态`() {
        assertTrue(LightOverrides.DEFAULT.isDefault)
        assertFalse(LightOverrides(colorTempK = 5000f).isDefault)
        // 融合强度有默认值 0.8：强度归零意味着完全不迁移颜色
        assertEquals(0.8f, LightOverrides.DEFAULT.effectiveFusionStrength, 0.001f)
    }
}
