package com.naiwa.core.common.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色温正反解的**互逆性**测试 —— 这是"自动估计"与"手动滑杆"能对齐的前提。
 *
 * 如果反解另写一套近似公式，中间色温段会累积系统性偏移，
 * 用户看到的症状就是"自动说 4200K，滑杆拖到 4200K 效果却不一样"。
 */
class KelvinRgbTest {

    @Test
    fun `6500K 是中性参考点_增益恒为 1`() {
        val gain = KelvinRgb.kelvinToRgbGain(KelvinRgb.NEUTRAL_KELVIN)
        assertEquals(1f, gain[0], 0.001f)
        assertEquals(1f, gain[1], 0.001f)
        assertEquals(1f, gain[2], 0.001f)
    }

    @Test
    fun `往返互逆误差小于 50K`() {
        var worst = 0
        var worstK = 0
        for (k in 2000..12000 step 250) {
            val roundTrip = KelvinRgb.rgbGainToKelvin(KelvinRgb.kelvinToRgbGain(k))
            val error = kotlin.math.abs(roundTrip - k)
            if (error > worst) {
                worst = error
                worstK = k
            }
        }
        assertTrue("最大往返误差 ${worst}K（发生在 ${worstK}K），要求 < 50K", worst < 50)
    }

    @Test
    fun `低色温偏暖_相对红蓝比大于 1`() {
        val warm = KelvinRgb.kelvinToRgbGain(2600)
        // 注意：黑白体近似在 T ≤ 66 时 R 通道饱和到 255，
        // 因此 2600K 的红增益恰好等于 1.0 而不是 > 1（这是近似式的固有性质）。
        // 色温信息主要体现在**蓝通道被压低**上，所以要断言相对关系而不是绝对大小。
        assertTrue("2600K 时蓝增益应 < 1：${warm[2]}", warm[2] < 1f)
        assertTrue("2600K 时 R 应明显强于 B：${warm[0]} vs ${warm[2]}", warm[0] > warm[2])
        assertTrue("R/B 比值应大于 1：${warm[0] / warm[2]}", warm[0] / warm[2] > 1.2f)
    }

    @Test
    fun `高色温偏冷_蓝色增益大于红色`() {
        val cool = KelvinRgb.kelvinToRgbGain(11000)
        assertTrue("11000K 时蓝色应被增强：${cool[2]}", cool[2] > 1f)
        assertTrue("11000K 时红色应被压低：${cool[0]}", cool[0] < 1f)
        assertTrue(cool[2] > cool[0])
    }

    @Test
    fun `增益整体缩放不影响反解色温`() {
        // 增益只表达"相对偏移"，绝对亮度由 LightParams.brightness 负责，
        // 因此把增益整体乘 2 不应该改变反解出的色温
        val base = KelvinRgb.kelvinToRgbGain(3200)
        val scaled = floatArrayOf(base[0] * 2f, base[1] * 2f, base[2] * 2f)
        assertEquals(
            KelvinRgb.rgbGainToKelvin(base),
            KelvinRgb.rgbGainToKelvin(scaled),
        )
    }

    @Test
    fun `非法增益返回中性色温而不是抛异常`() {
        assertEquals(KelvinRgb.NEUTRAL_KELVIN, KelvinRgb.rgbGainToKelvin(floatArrayOf(0f, 1f, 1f)))
        assertEquals(KelvinRgb.NEUTRAL_KELVIN, KelvinRgb.rgbGainToKelvin(floatArrayOf(-1f, 1f, 1f)))
    }

    @Test
    fun `施加增益到像素时保持 alpha 不变`() {
        val pixel = (0x80 shl 24) or (200 shl 16) or (100 shl 8) or 50
        val out = KelvinRgb.applyGainToPixel(pixel, floatArrayOf(0.5f, 1f, 2f))
        assertEquals(0x80, (out ushr 24) and 0xFF)
        assertEquals(100, (out shr 16) and 0xFF)
        assertEquals(100, (out shr 8) and 0xFF)
        assertEquals(100, out and 0xFF)
    }
}
