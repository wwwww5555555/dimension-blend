package com.naiwa.core.cv.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阴影投影轴 + uniform 打包的契约。
 * 防的是"影子朝光源投""影子向上飞""uniform 参数错位"三类一眼看出错的 bug。
 */
class ShadowShearTest {

    private fun axis(lightX: Float, lightY: Float) =
        ShadowShear.calculateShadowAxis(anchorX = 0.5f, anchorY = 0.9f, lightX = lightX, lightY = lightY)

    @Test
    fun `光源在上方时影子朝画面下方`() {
        val a = axis(0.5f, 0.2f)
        assertTrue("dirY 应向下为正（实际 ${a.dirY}）", a.dirY > 0.9f)
        assertTrue("正顶光不该有横向偏移（实际 ${a.dirX}）", kotlin.math.abs(a.dirX) < 0.15f)
    }

    @Test
    fun `光源在左侧时影子朝右下`() {
        val a = axis(0.15f, 0.2f)
        assertTrue("影子应向右（实际 ${a.dirX}）", a.dirX > 0.1f)
        assertTrue("且仍向下（实际 ${a.dirY}）", a.dirY > 0f)
    }

    @Test
    fun `光源在右侧时影子朝左下`() {
        val a = axis(0.85f, 0.2f)
        assertTrue("影子应向左（实际 ${a.dirX}）", a.dirX < -0.1f)
    }

    @Test
    fun `光源低于锚点时强制朝下而不是朝上`() {
        val a = ShadowShear.calculateShadowAxis(0.5f, 0.9f, 0.5f, 0.98f)
        assertTrue("绝不能朝上（实际 dirY=${a.dirY}）", a.dirY > 0f)
    }

    @Test
    fun `方向始终是单位向量`() {
        listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f).forEach { lx ->
            val a = axis(lx, 0.2f)
            val len = kotlin.math.sqrt((a.dirX * a.dirX + a.dirY * a.dirY).toDouble()).toFloat()
            assertEquals("光源 x=$lx 的轴应为单位向量", 1f, len, 1e-3f)
        }
    }

    @Test
    fun `侧光比顶光错切更大`() {
        val top = axis(0.5f, 0.2f)
        val side = axis(0.05f, 0.2f)
        assertTrue("侧光应展宽更多（顶=${top.shear} 侧=${side.shear}）", side.shear > top.shear)
    }

    @Test
    fun `错切矩阵保持 y 不变并向右下方拉长`() {
        val a = axis(0.2f, 0.2f)
        val m = ShadowShear.shearMatrix(a)
        // 行优先 2×3：[1, shear, tx, 0, 1, ty]
        assertEquals(1f, m[0], 0f)
        assertEquals(a.shear, m[1], 1e-4f)
        assertEquals(0f, m[3], 0f)
        assertEquals(1f, m[4], 0f)
        assertEquals(0f, m[5], 0f)
        val bottomX = 1f * m[1] + m[2]     // y=1
        val topX = 0f * m[1] + m[2]        // y=0
        assertTrue("底部应比顶部更向右（bottom=$bottomX top=$topX）", bottomX > topX)
    }

    // ---------------------------------------------------------------- uniform 打包

    @Test
    fun `打包后的 uniform 与轴一致`() {
        val a = axis(0.2f, 0.25f)
        val u = a.pack(
            anchorX = 0.5f, anchorY = 0.9f,
            reachPx = 40f, falloff = 0.65f,
            blurBasePx = 2.5f, blurGrowPx = 18f,
        )
        assertEquals(a.dirX, u.dirX, 1e-6f)
        assertEquals(a.dirY, u.dirY, 1e-6f)
        assertEquals(a.shear, u.shear, 1e-6f)
        assertEquals(0.5f, u.anchorX, 1e-6f)
        assertEquals(0.9f, u.anchorY, 1e-6f)
        assertEquals(40f, u.reachPx, 1e-6f)
        assertEquals(0.65f, u.falloff, 1e-6f)
        assertEquals(2.5f, u.blurBasePx, 1e-6f)
        assertEquals(18f, u.blurGrowPx, 1e-6f)
    }

    @Test
    fun `打包时越界参数被钳制`() {
        val u = axis(0.5f, 0.2f).pack(
            anchorX = 0.5f, anchorY = 0.9f,
            reachPx = -10f, falloff = 5f,
            blurBasePx = 0f, blurGrowPx = -3f,
        )
        assertEquals("reach 不能为负", 0f, u.reachPx, 0f)
        assertEquals("falloff 上限 1", 1f, u.falloff, 0f)
        assertEquals("模糊基准下限 0.5", 0.5f, u.blurBasePx, 0f)
        assertEquals("增长量不能为负", 0f, u.blurGrowPx, 0f)
    }
}
