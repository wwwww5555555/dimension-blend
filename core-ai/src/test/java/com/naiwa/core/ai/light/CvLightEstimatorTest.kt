package com.naiwa.core.ai.light

import com.naiwa.core.common.model.LightParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传统 CV 光照估计的测试（纯 JVM，不需要 Android 运行时）。
 *
 * 用合成图而不是真实照片：只有合成图才能**精确知道正确答案**
 * （"亮的一侧在右边" → 方位角必须是 0°），从而做出可证伪的断言。
 */
class CvLightEstimatorTest {

    private val estimator = CvLightEstimator()

    /** 造一张左暗右亮的水平渐变图（模拟光从右侧来）。 */
    private fun horizontalGradient(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val v = (40 + 200 * x / (width - 1)).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

    /** 造一张上亮下暗的垂直渐变图（模拟光从上方来）。 */
    private fun verticalGradient(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val y = i / width
            val v = (240 - 200 * y / (height - 1)).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

    private fun solid(width: Int, height: Int, r: Int, g: Int, b: Int): IntArray =
        IntArray(width * height) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    @Test
    fun `右侧来光的方位角接近 0 度`() {
        val w = 128
        val h = 96
        val result = estimator.estimate(horizontalGradient(w, h), w, h)

        // 约定：0° = 光源在画面正右方
        val delta = angleDistance(result.keyAzimuthDeg, 0f)
        assertTrue("方位角 ${result.keyAzimuthDeg}° 偏离 0° 超过 15°", delta < 15f)
    }

    @Test
    fun `上方来光的方位角接近 270 度`() {
        val w = 96
        val h = 128
        val result = estimator.estimate(verticalGradient(w, h), w, h)

        // 图像坐标 y 向下：光从上方来 = 270°
        val delta = angleDistance(result.keyAzimuthDeg, 270f)
        assertTrue("方位角 ${result.keyAzimuthDeg}° 偏离 270° 超过 20°", delta < 20f)
    }

    @Test
    fun `偏暖的图估计出更低的色温`() {
        val w = 64
        val h = 64
        // 明显偏红（相当于钨丝灯下的白墙）
        val warm = estimator.estimate(solid(w, h, 220, 170, 120), w, h)
        // 明显偏蓝（相当于阴天阴影）
        val cool = estimator.estimate(solid(w, h, 150, 180, 235), w, h)

        assertTrue("暖色图应估出 < 6500K，实测 ${warm.colorTempK}", warm.colorTempK < 6500f)
        assertTrue("冷色图应估出 > 6500K，实测 ${cool.colorTempK}", cool.colorTempK > 6500f)
    }

    @Test
    fun `中性灰图估出接近 6500K`() {
        val result = estimator.estimate(solid(64, 64, 128, 128, 128), 64, 64)
        assertEquals(6500f, result.colorTempK, 300f)
    }

    @Test
    fun `暗部与亮部反差大时阴影更强`() {
        val w = 128
        val h = 128
        val highContrast = estimator.estimate(horizontalGradient(w, h), w, h)
        val flat = estimator.estimate(solid(w, h, 150, 150, 150), w, h)

        assertTrue(
            "高反差图阴影强度应更大：${highContrast.shadowStrength} vs ${flat.shadowStrength}",
            highContrast.shadowStrength > flat.shadowStrength,
        )
    }

    @Test
    fun `估计结果全部落在合法区间`() {
        val result = estimator.estimate(horizontalGradient(100, 100), 100, 100)
        assertTrue(result.colorTempK in LightParams.MIN_TEMP_K..LightParams.MAX_TEMP_K)
        assertTrue(result.tint in -1f..1f)
        assertTrue(result.keyAzimuthDeg in 0f..360f)
        assertTrue(result.keyElevationDeg in 0f..90f)
        assertTrue(result.keyIntensity in 0f..1f)
        assertTrue(result.shadowStrength in 0f..1f)
        assertTrue(result.confidence in 0f..1f)
    }

    @Test
    fun `输入非法时返回中性参数而不抛异常`() {
        assertEquals(LightParams.NEUTRAL, estimator.estimate(IntArray(0), 0, 0))
        assertEquals(LightParams.NEUTRAL, estimator.estimate(IntArray(10), 100, 100))
    }

    /** 角度最小距离（处理 0/360 环绕）。 */
    private fun angleDistance(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }
}
