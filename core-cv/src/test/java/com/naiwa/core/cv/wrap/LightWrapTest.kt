package com.naiwa.core.cv.wrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光包裹（规格 8.2）契约测试。
 *
 * 场景设计：32×32，下半屏为亮绿背景（草地）、上半屏暗蓝背景（天空），
 * 居中放一个 8×8 的前景方块。验证：环绕带提亮 / mask 外远处不动 / opacity=0 恒等 /
 * 溢出光颜色来自模糊背景（绿侧偏绿）。
 */
class LightWrapTest {

    private val w = 48
    private val h = 48

    private fun argb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b

    /** 下半亮绿、上半暗蓝的背景。 */
    private fun scene(): IntArray = IntArray(w * h) { i ->
        if (i / w >= h / 2) argb(40, 160, 60) else argb(20, 30, 80)
    }

    /** 居中 16×16 方块掩膜（y 16..31，x 16..31）。
     *  画布 48：环绕带外沿 4px + σ3 模糊渗入 5px = 9px 影响圈，
     *  "远离轮廓"断言点（x=0，距轮廓 16px）必须在圈外。 */
    private fun mask(): IntArray {
        val m = IntArray(w * h)
        for (y in 16..31) for (x in 16..31) m[y * w + x] = 255
        return m
    }

    @Test
    fun `opacity为零时逐像素不变`() {
        val scene = scene()
        val mask = mask()
        val composite = IntArray(w * h) { argb(100, 100, 100) }
        val out = LightWrap.apply(composite, scene, mask, w, h, opacity = 0f)
        for (i in composite.indices) assertEquals(composite[i], out[i])
    }

    @Test
    fun `环绕带被提亮screen只增不减`() {
        val scene = scene()
        val mask = mask()
        // 合成图在环绕带处是中性灰（提亮空间充足）
        val composite = IntArray(w * h) { argb(80, 80, 80) }

        // 轮廓外侧 2px 处（x=6, y=16 在环绕带内，方块 x 从 8 起）
        val idx = 24 * w + 14
        val out = LightWrap.apply(composite, scene, mask, w, h, opacity = 0.4f)
        val r = (out[idx] shr 16) and 0xFF
        val g = (out[idx] shr 8) and 0xFF
        val b = out[idx] and 0xFF
        assertTrue("Screen 只增不减：R", r >= 80)
        assertTrue("Screen 只增不减：G（$g）", g >= 80)
        assertTrue("Screen 只增不减：B", b >= 80)
        // 溢出光来自模糊背景（绿色草地 r=40,g=160,b=60 模糊后绿主导）→ G 分量增量最大
        assertTrue("溢出光带背景色倾向：G 增量应最大（r=$r,g=$g,b=$b）", g - 80 >= r - 80 && g - 80 >= b - 80)
    }

    @Test
    fun `远离轮廓处不受影响`() {
        val scene = scene()
        val mask = mask()
        val composite = IntArray(w * h) { argb(80, 80, 80) }

        val out = LightWrap.apply(composite, scene, mask, w, h, opacity = 0.4f)
        // x=0 列距轮廓 8px：环绕带外 4px + σ3 模糊半径 5px 都够不到
        for (y in 0 until h) {
            assertEquals("远离轮廓处不变（y=$y）", composite[y * w + 0], out[y * w + 0])
        }
    }

    @Test
    fun `奶蛙核心深处不被包裹`() {
        val scene = scene()
        val mask = mask()
        val composite = IntArray(w * h) { argb(80, 80, 80) }

        val out = LightWrap.apply(composite, scene, mask, w, h, opacity = 0.4f)
        // 方块中心 (24,24)：innerDist = 8 > INNER_FADE_PX(3) + σ3 渗入(5) → 权重≈0
        val g = (out[24 * w + 24] shr 8) and 0xFF
        assertTrue("核心深处不应被包裹（g=$g）", g <= 82)
    }

    @Test
    fun `强度越大提亮越多`() {
        val scene = scene()
        val mask = mask()
        val composite = IntArray(w * h) { argb(80, 80, 80) }

        val weak = LightWrap.apply(composite, scene, mask, w, h, opacity = 0.1f)
        val strong = LightWrap.apply(composite, scene, mask, w, h, opacity = 0.4f)
        val idx = 24 * w + 14
        val gWeak = (weak[idx] shr 8) and 0xFF
        val gStrong = (strong[idx] shr 8) and 0xFF
        assertTrue("强包裹应提亮更多（weak=$gWeak, strong=$gStrong）", gStrong >= gWeak)
    }

    @Test
    fun `非法尺寸原样返回`() {
        val composite = intArrayOf(1, 2, 3, 4)
        val out = LightWrap.apply(
            compositePixels = composite,
            scenePixels = IntArray(4),
            frogMask = IntArray(4),
            width = 4, height = 4, // 需要 16 像素 → 非法
            opacity = 0.4f,
        )
        assertEquals(4, out.size)
        assertEquals(1, out[0])
    }
}
