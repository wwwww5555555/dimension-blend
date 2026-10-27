package com.naiwa.core.cv.matte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色键抠图的纯计算测试（不需要 Android 运行时 —— [ColorKeyMatte.computeMask] 只吃 IntArray）。
 *
 * 用合成图而不是真实照片：真实照片的结果无法写死断言，而合成图能精确知道"前景应该是哪些像素"，
 * 从而用 IoU 量化正确性。
 *
 * ⚠️ 测试设计上的两个约定（踩过坑，写下来免得后人再踩）：
 *  1. **测「分割本身」时必须关掉 edgeShrinkPx / featherRadius**。
 *     这两个步骤是**故意**让掩膜小一圈并软化边缘的（为 P2 融合留过渡带），
 *     开着它们去和"理想二值掩膜"比 IoU，必然只有 0.7 左右 —— 那是设计使然，不是 bug。
 *     它们的效果在 [边缘内缩与羽化确实会缩小掩膜] 里单独验证。
 *  2. 色距公式**故意把亮度权重减半**（抵抗光照渐变），代价是同色相、亮度差很小的前景/背景更难分开。
 *     所以"绿幕"用例用的是真实场景里常见的组合：亮绿背景 + 明显更暗的深绿主体，
 *     而不是只差 7% 亮度的极端配对（那属于已知取舍，不在本测试的断言范围）。
 */
class ColorKeyMatteTest {

    private val white = 0xFFFFFFFF.toInt()
    private val brightGreen = 0xFF3FBF52.toInt()  // 绿幕
    private val darkGreen = 0xFF1D5C28.toInt()    // 深绿主体（与绿幕同色系，但亮度差明显）
    private val frogGreen = 0xFF1F8A30.toInt()

    /** 测纯分割时用这组参数：关掉刻意的内缩与羽化。 */
    private val pureMaskConfig = ColorKeyMatte.ColorKeyConfig(
        edgeShrinkPx = 0,
        featherRadius = 0,
    )

    /** 造一张「纯色背景 + 中间一个矩形前景」的图。 */
    private fun syntheticScene(
        width: Int,
        height: Int,
        background: Int,
        foreground: Int,
        inset: Int,
    ): IntArray {
        val pixels = IntArray(width * height) { background }
        for (y in inset until height - inset) {
            for (x in inset until width - inset) {
                pixels[y * width + x] = foreground
            }
        }
        return pixels
    }

    /** 前景交并比：衡量抠图结果与期望区域的吻合度。 */
    private fun iou(mask: BooleanArray, expected: BooleanArray): Float {
        var intersection = 0
        var union = 0
        for (i in mask.indices) {
            val a = mask[i]
            val b = expected[i]
            if (a && b) intersection++
            if (a || b) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun expectedMask(width: Int, height: Int, inset: Int): BooleanArray =
        BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            x in inset until width - inset && y in inset until height - inset
        }

    @Test
    fun `纯色背景上的前景被正确抠出且 IoU 高于 0_9`() {
        val w = 200
        val h = 150
        val inset = 40
        val pixels = syntheticScene(w, h, background = white, foreground = frogGreen, inset = inset)

        val result = ColorKeyMatte.computeMask(pixels, w, h, pureMaskConfig)

        val score = iou(result.mask, expectedMask(w, h, inset))
        assertTrue("IoU 过低：$score", score > 0.9f)
        assertTrue("背景色候选不该为空", result.backgroundColors.isNotEmpty())
    }

    @Test
    fun `绿幕背景与同色系但更暗的主体能被区分`() {
        val w = 160
        val h = 160
        val inset = 30
        val pixels = syntheticScene(
            w, h,
            background = brightGreen,
            foreground = darkGreen,
            inset = inset,
        )

        val result = ColorKeyMatte.computeMask(pixels, w, h, pureMaskConfig)

        val score = iou(result.mask, expectedMask(w, h, inset))
        assertTrue("同色系区分失败，IoU=$score", score > 0.85f)
    }

    @Test
    fun `背景噪点被形态学与连通域过滤清掉`() {
        val w = 120
        val h = 120
        val inset = 30
        val pixels = syntheticScene(w, h, background = white, foreground = frogGreen, inset = inset)
        // 在背景里撒一条孤立噪点（模拟 JPEG 压缩伪影 / 传感器噪点）
        for (i in 0 until 30) {
            pixels[2 * w + (2 + i)] = frogGreen
        }

        val result = ColorKeyMatte.computeMask(pixels, w, h, pureMaskConfig)

        assertTrue("左上角噪点被误判为前景", !result.mask[2 * w + 4])
        assertTrue(iou(result.mask, expectedMask(w, h, inset)) > 0.9f)
    }

    @Test
    fun `前景内部孔洞被填充`() {
        val w = 120
        val h = 120
        val inset = 30
        val pixels = syntheticScene(w, h, background = white, foreground = frogGreen, inset = inset)
        // 在前景中央挖一个 6×6 的"背景色洞"（模拟奶蛙肚子上的白斑）
        for (y in 55 until 61) {
            for (x in 55 until 61) {
                pixels[y * w + x] = white
            }
        }

        val result = ColorKeyMatte.computeMask(pixels, w, h, pureMaskConfig)

        // 洞必须被填成前景，否则融合后奶蛙肚子上会漏出场景
        assertTrue("孔洞未被填充", result.mask[58 * w + 58])
    }

    /**
     * 边缘内缩 + 羽化是**故意**的设计（给 P2 融合留过渡带），
     * 这里把它当作特性来验证：开启后掩膜必须比关闭时小，且不会凭空扩张。
     */
    @Test
    fun `边缘内缩与羽化确实会缩小掩膜`() {
        val w = 120
        val h = 120
        val inset = 30
        val pixels = syntheticScene(w, h, background = white, foreground = frogGreen, inset = inset)

        val pure = ColorKeyMatte.computeMask(pixels, w, h, pureMaskConfig)
        val shrunk = ColorKeyMatte.computeMask(
            pixels, w, h,
            ColorKeyMatte.ColorKeyConfig(edgeShrinkPx = 2, featherRadius = 2),
        )

        fun count(mask: BooleanArray) = mask.count { it }

        assertTrue(
            "开启内缩+羽化后前景应更少：pure=${count(pure.mask)} shrunk=${count(shrunk.mask)}",
            count(shrunk.mask) < count(pure.mask),
        )
        // 内缩+羽化不会让掩膜扩张到原前景之外
        var outsideForeground = 0
        for (i in pure.mask.indices) {
            if (shrunk.mask[i] && !pure.mask[i]) outsideForeground++
        }
        assertEquals("内缩+羽化后不应出现原前景之外的像素", 0, outsideForeground)
    }

    /**
     * 前景占比是 UI 用来判断"这张图要不要手动修"的依据。
     * 注意：与边框同色的纯色图在客观定义上就是**全背景**（前景占比 ≈ 0），
     * 而不是"全前景"——早期版本把这条断言写反了。
     */
    @Test
    fun `前景占比能区分整体是背景与主体占多数`() {
        val w = 100
        val h = 100

        val uniform = ColorKeyMatte.computeMask(IntArray(w * h) { white }, w, h, pureMaskConfig)
        assertTrue("纯色图应判定为几乎全背景，实测 ${uniform.foregroundRatio}", uniform.foregroundRatio < 0.05f)

        // 主体占画面 70% 以上：前景占比应明显过半
        val bigSubject = syntheticScene(w, h, background = white, foreground = frogGreen, inset = 8)
        val big = ColorKeyMatte.computeMask(bigSubject, w, h, pureMaskConfig)
        assertTrue("大主体图的前景占比应过半，实测 ${big.foregroundRatio}", big.foregroundRatio > 0.5f)
    }
}
