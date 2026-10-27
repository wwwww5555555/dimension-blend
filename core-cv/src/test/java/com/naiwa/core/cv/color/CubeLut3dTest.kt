package com.naiwa.core.cv.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D LUT（规格 8.1）契约测试：.cube 解析 + 三线性采样 + 恒等 LUT 不变性。
 */
class CubeLut3dTest {

    /** 构造一个恒等 LUT（map 后颜色不变）。size=2 时 8 个顶点。 */
    private fun identityLut(size: Int = 2): CubeLut3d {
        val sb = StringBuilder()
        sb.appendLine("TITLE \"identity\"")
        sb.appendLine("LUT_3D_SIZE $size")
        sb.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        sb.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        val step = 1.0 / (size - 1)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    sb.appendLine("${r * step} ${g * step} ${b * step}")
                }
            }
        }
        return CubeLut3d.parse(sb.toString())!!
    }

    @Test
    fun `解析合法cube返回非空`() {
        assertNotNull(identityLut())
    }

    @Test
    fun `恒等LUT映射后颜色不变`() {
        val lut = identityLut()
        val out = lut.map(128, 64, 32)
        // 恒等 LUT 的顶点就是输入本身，三线性插值应近似还原（顶点处精确）
        assertEquals("R 不变", 128, out[0])
        assertEquals("G 不变", 64, out[1])
        assertEquals("B 不变", 32, out[2])
    }

    @Test
    fun `格式非法返回null不抛异常`() {
        assertNull(CubeLut3d.parse("这是垃圾内容"))
        assertNull(CubeLut3d.parse(""))
        // 缺 LUT_3D_SIZE
        assertNull(CubeLut3d.parse("TITLE \"x\"\n1.0 1.0 1.0"))
    }

    @Test
    fun `暖色LUT让红色通道增强`() {
        // 构造一个"把 R 通道抬升"的 LUT：每个顶点的 R 分量 +0.2
        val size = 2
        val sb = StringBuilder()
        sb.appendLine("TITLE \"warm\"")
        sb.appendLine("LUT_3D_SIZE $size")
        sb.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        sb.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val rr = (r * 1.0 + 0.2).coerceAtMost(1.0)
                    sb.appendLine("$rr ${g * 1.0} ${b * 1.0}")
                }
            }
        }
        val lut = CubeLut3d.parse(sb.toString())!!
        val out = lut.map(0, 0, 0)
        // 黑色顶点 (0,0,0) 映射后 R 应为 0.2*255 ≈ 51
        assertTrue("暖 LUT 抬升 R 通道（out=${out[0]}）", out[0] > out[1])
        assertTrue("R 通道被抬升到 ~51", out[0] in 40..65)
    }

    @Test
    fun `采样值被钳制在0到255`() {
        val lut = identityLut(3)
        val out = lut.map(255, 255, 255)
        for (c in out) {
            assertTrue("通道 $c 在 [0,255]", c in 0..255)
        }
    }
}
