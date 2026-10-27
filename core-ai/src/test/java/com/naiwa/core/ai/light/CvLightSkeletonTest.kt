package com.naiwa.core.ai.light

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 CV 测光（阶段二）契约测试。
 *
 * 用**合成图**验证物理正确性（而不是"跑通不崩"）：
 *  - 左上角放亮斑 → 光源坐标必须偏左上，方位角必须指向左上（约 225°±40°）；
 *  - 亮度沿 +X 递增 → 梯度指 +X ⇒ 光从 −X 来 ⇒ 方位角约 180°；
 *  - 全黑图 → 中性值且不崩；
 *  - 性能：320×240 必须在 50ms 内。
 */
class CvLightSkeletonTest {

    /** CvLightEstimator 是 class（不是 object）：所有调用走实例。 */
    private val cv = CvLightEstimator()

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** 左上角亮斑、右下角暗（模拟左上打光）。 */
    private fun topLeftLight(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        // 到左上角的距离越近越亮
        val d = kotlin.math.sqrt((x.toDouble() * x + y.toDouble() * y)).toFloat()
        val v = (255f * (1f - (d / (w * 0.9f))).coerceIn(0f, 1f)).toInt()
        argb(v, v, v)
    }

    /** 从左到右渐亮（右侧更好亮 → 光从右侧来）。 */
    private fun leftToRight(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val v = (255f * (i % w) / (w - 1)).toInt()
        argb(v, v, v)
    }

    @Test
    fun `光源坐标落在亮斑附近`() {
        val w = 120
        val h = 90
        val p = cv.estimateSkeleton(topLeftLight(w, h), w, h)
        assertTrue("posX 应偏左（实际 ${p.posX}）", p.posX < 0.35f)
        assertTrue("posY 应偏上（实际 ${p.posY}）", p.posY < 0.35f)
    }

    @Test
    fun `左上亮斑的方位角指向左上`() {
        val w = 120
        val h = 90
        val p = cv.estimateSkeleton(topLeftLight(w, h), w, h)
        // 左上方向 = 图像坐标的 (-x, -y) → 角度约 225°（或 -135°）
        val diff = angularDistance(p.angle, 225f)
        assertTrue("方位角应接近 225°（实际 ${p.angle}°）", diff < 45f)
    }

    @Test
    fun `左暗右亮时方位角约0度`() {
        // 右侧更亮 ⇒ 靠近光源的一侧在右 ⇒ 光从右来 ⇒ 0°
        // （教训：方位角是"光来的方向"，不是"亮度递增的方向"，两者差 180°）
        val w = 160
        val h = 60
        val p = cv.estimateSkeleton(leftToRight(w, h), w, h)
        val diff = angularDistance(p.angle, 0f)
        assertTrue("光从右来应为 0°（实际 ${p.angle}°）", diff < 30f)
    }

    @Test
    fun `方位角永远落在0到360`() {
        val cases = listOf(
            topLeftLight(64, 64),
            leftToRight(64, 64),
            IntArray(64 * 64) { argb(128, 128, 128) },
        )
        cases.forEach { px ->
            val a = cv.estimateSkeleton(px, 64, 64).angle
            assertTrue("angle=$a 越界", a >= 0f && a < 360f)
        }
    }

    @Test
    fun `全黑图返回中性值且不崩`() {
        val p = cv.estimateSkeleton(IntArray(64 * 64), 64, 64)
        assertEquals(0.5f, p.posX, 0.35f)
        assertEquals(0.5f, p.posY, 0.35f)
        assertFalse(p.isBacklit)
    }

    @Test
    fun `非法输入返回中性值`() {
        val p = cv.estimateSkeleton(IntArray(4), 100, 100)
        assertEquals(CvLightParams.NEUTRAL, p)
    }

    @Test
    fun `人物很暗而背景很亮时判为逆光`() {
        val w = 80
        val h = 80
        // 背景整体 240，人物均值 60 → 差 180 > 38
        val bright = IntArray(w * h) { argb(240, 240, 240) }
        val backlit = cv.estimateSkeleton(bright, w, h, personMeanLuma = 60f)
        assertTrue("应判为强逆光", backlit.isBacklit)

        // 人物也亮（180）→ 不是逆光
        val notBacklit = cv.estimateSkeleton(bright, w, h, personMeanLuma = 200f)
        assertFalse("人物与背景接近，不应判逆光", notBacklit.isBacklit)
    }

    @Test
    fun `不传人物亮度时永不判逆光`() {
        val bright = IntArray(64 * 64) { argb(250, 250, 250) }
        assertFalse(cv.estimateSkeleton(bright, 64, 64).isBacklit)
    }

    @Test
    fun `320x240在50毫秒预算内`() {
        val w = 320
        val h = 240
        val px = topLeftLight(w, h)
        // 预热（JIT）
        repeat(3) { cv.estimateSkeleton(px, w, h) }

        val t0 = System.nanoTime()
        val iterations = 5
        repeat(iterations) { cv.estimateSkeleton(px, w, h) }
        val avgMs = (System.nanoTime() - t0) / 1_000_000.0 / iterations
        println("cv.estimateSkeleton 320x240 平均 ${"%.2f".format(avgMs)} ms")
        assertTrue("单次测光必须 < 50ms（实际 ${"%.2f".format(avgMs)}ms）", avgMs < 50.0)
    }

    @Test
    fun `角度转三维向量是单位向量且水平分量随高度角衰减`() {
        val flat = cv.toLightVector(0f, elevationDeg = 0f)
        val high = cv.toLightVector(0f, elevationDeg = 80f)
        // 单位长度
        listOf(flat, high).forEach { v ->
            val len = kotlin.math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
            assertEquals("应为单位向量", 1f, len, 1e-3f)
        }
        // 高度角越大，水平分量越小、z 越大
        assertTrue("高角度看水平分量应更小", abs(high[0]) < abs(flat[0]) + 1e-4f)
        assertTrue("高角度 z 应更大", high[2] > flat[2])
        // 0° = 从右侧来
        assertEquals(1f, flat[0], 1e-3f)
        assertEquals(0f, flat[1], 1e-3f)
    }

    @Test
    fun `角度差计算正确`() {
        assertEquals(0f, angularDistance(10f, 10f), 1e-3f)
        assertEquals(20f, angularDistance(350f, 10f), 1e-3f)
        assertEquals(180f, angularDistance(0f, 180f), 1e-3f)
    }

    private fun angularDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
