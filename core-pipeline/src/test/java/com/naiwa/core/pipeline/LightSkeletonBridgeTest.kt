package com.naiwa.core.pipeline

import com.naiwa.core.ai.light.CvLightParams
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架 → GPU uniform 的换算契约。
 *
 * 这些断言钉住的是"**光向符号**"——最难靠肉眼发现、错了就整侧不发光的量：
 *  - 方位角是「光**来**的方向」，不是"亮度递增的方向"，两者差 180°；
 *  - 阴影方向必须 = **-lightDir**（朝背离光源那侧投影）。
 */
class LightSkeletonBridgeTest {

    private fun approx(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps

    @Test
    fun `中性值角度270指向上方`() {
        val g = LightSkeletonBridge.toGeometry(CvLightParams.NEUTRAL)
        // y 轴向下：光从上方来 ⇒ 指向光源的 y 分量为负
        assertTrue("dirY 应为负（光在上方），实际 ${g.dirY}", g.dirY < -0.5f)
        assertTrue("正顶光/顶光水平 x 应接近 0，实际 ${g.dirX}", abs(g.dirX) < 0.1f)
    }

    @Test
    fun `角度0时光从右侧来`() {
        val g = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, 0f, false))
        assertTrue("dirX 应为正（光在右），实际 ${g.dirX}", g.dirX > 0.5f)
    }

    @Test
    fun `角度180时光从左侧来`() {
        val g = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, 180f, false))
        assertTrue("dirX 应为负（光在左），实际 ${g.dirX}", g.dirX < -0.5f)
    }

    @Test
    fun `角度90时光从下方来`() {
        val g = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, 90f, false))
        assertTrue("dirY 应为正（光在下方），实际 ${g.dirY}", g.dirY > 0.5f)
    }

    @Test
    fun `光向量必须是单位向量`() {
        listOf(0f, 37f, 135f, 250f, 359f).forEach { angle ->
            val g = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, angle, false))
            val len = sqrt((g.dirX * g.dirX + g.dirY * g.dirY + g.dirZ * g.dirZ).toDouble()).toFloat()
            assertEquals("角度 $angle 的光向量模应为 1", 1f, len, 1e-3f)
        }
    }

    @Test
    fun `仰角越高水平分量越短`() {
        val low = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, 0f, false), elevationDeg = 5f)
        val high = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, 0f, false), elevationDeg = 80f)
        assertTrue("低角度光水平分量应更强", low.dirX > high.dirX)
        assertEquals("低角度光的 z 应更小", true, low.dirZ < high.dirZ)
    }

    @Test
    fun `阴影方向与光向相反`() {
        listOf(0f, 45f, 90f, 200f, 315f).forEach { angle ->
            val g = LightSkeletonBridge.toGeometry(CvLightParams(0.5f, 0.5f, angle, false))
            val s = LightSkeletonBridge.shadowDirection(g)
            val dot = g.dirX * s[0] + g.dirY * s[1]
            assertTrue(
                "角度 $angle：阴影方向必须背离光源（点积应为负，实际 $dot）",
                dot < -0.5f,
            )
            val len = sqrt((s[0] * s[0] + s[1] * s[1]).toDouble()).toFloat()
            assertEquals("阴影方向应为单位向量", 1f, len, 1e-3f)
        }
    }

    @Test
    fun `位置越界会被钳制`() {
        val g = LightSkeletonBridge.toGeometry(CvLightParams(-3f, 9f, 0f, true))
        assertEquals(0f, g.posX, 0f)
        assertEquals(1f, g.posY, 0f)
        assertTrue(g.backlit)
    }

    @Test
    fun `逆光标记原样透传`() {
        assertTrue(LightSkeletonBridge.toGeometry(CvLightParams(0.2f, 0.8f, 120f, true)).backlit)
        assertTrue(!LightSkeletonBridge.toGeometry(CvLightParams(0.2f, 0.8f, 120f, false)).backlit)
    }

    @Test
    fun `方向退化时阴影方向为零向量而不是乱飘`() {
        // 构造 dirX≈dirY≈0 的退化几何（理论上只在正顶光 + 竖直倾角时出现）
        val g = LightSkeletonBridge.Geometry(0.5f, 0.5f, 0f, 0f, 1f, false)
        val s = LightSkeletonBridge.shadowDirection(g)
        assertEquals(0f, s[0], 0f)
        assertEquals(0f, s[1], 0f)
        assertTrue("水平方向应被判定为无效", !g.hasHorizontalDirection)
    }
}
