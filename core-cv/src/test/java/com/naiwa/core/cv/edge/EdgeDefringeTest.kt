package com.naiwa.core.cv.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.2 边缘去白的测试。
 *
 * 核心契约有两条，缺一不可：
 *  1. **只改 RGB，alpha 逐像素不变** —— 一旦动了 alpha，"修颜色"就变成了"改形状"；
 *  2. **实心前景与完全透明区一个像素都不动** —— 前者颜色本来就对，后者不可见。
 */
class EdgeDefringeTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private val frogColor = intArrayOf(120, 160, 90)     // 前景本体色
    private val polluteColor = intArrayOf(245, 245, 245) // 白底污染色

    /**
     * 造一个方块：内部 alpha=255 用本体色；边缘一圈 alpha 递减但 RGB 是**白色污染**。
     * 这正是"白底抠图后残留灰白边"的模型。
     */
    private fun fixture(w: Int, h: Int, edgeWidth: Int = 2): IntArray {
        val left = 10
        val top = 10
        val right = w - 10
        val bottom = h - 10
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val distToEdge = minOf(x - left, y - top, right - 1 - x, bottom - 1 - y)
            when {
                distToEdge < 0 -> argb(0, 0, 0, 0)
                distToEdge >= edgeWidth -> argb(255, frogColor[0], frogColor[1], frogColor[2])
                else -> {
                    // 越靠外 alpha 越低；RGB 保持白色污染
                    val a = 60 + distToEdge * 80   // 60 / 140
                    argb(a, polluteColor[0], polluteColor[1], polluteColor[2])
                }
            }
        }
    }

    @Test
    fun `alpha 通道逐像素不变`() {
        val w = 40
        val h = 40
        val src = fixture(w, h)
        val out = EdgeDefringe.defringe(src, w, h).pixels
        for (i in src.indices) {
            assertEquals(
                "去白只改 RGB，alpha 必须逐像素不变（下标 $i）",
                (src[i] ushr 24) and 0xFF,
                (out[i] ushr 24) and 0xFF,
            )
        }
    }

    @Test
    fun `半透明边缘的RGB被换成前景本体色`() {
        val w = 40
        val h = 40
        val src = fixture(w, h)
        val result = EdgeDefringe.defringe(src, w, h)
        val out = result.pixels

        var checked = 0
        for (i in src.indices) {
            val a = (src[i] ushr 24) and 0xFF
            if (a == 0 || a >= 243) continue
            // 边缘像素的 RGB 应已接近本体色
            val err = maxOf(
                kotlin.math.abs(((out[i] shr 16) and 0xFF) - frogColor[0]),
                kotlin.math.abs(((out[i] shr 8) and 0xFF) - frogColor[1]),
                kotlin.math.abs((out[i] and 0xFF) - frogColor[2]),
            )
            assertTrue("边缘像素去白后应接近本体色，实测误差 $err（下标 $i）", err <= 3)
            checked++
        }
        println("[6.2] 去白像素 $checked 个，changedPixels=${result.changedPixels}，unresolved=${result.unresolvedPixels}")
        assertTrue("应有边缘像素参与", checked > 100)
        assertEquals(0, result.unresolvedPixels)
    }

    @Test
    fun `实心前景与完全透明区不被改动`() {
        val w = 40
        val h = 40
        val src = fixture(w, h)
        val out = EdgeDefringe.defringe(src, w, h).pixels
        for (i in src.indices) {
            val a = (src[i] ushr 24) and 0xFF
            if (a == 0 || a == 255) {
                assertEquals("alpha=$a 的像素不应被改动（下标 $i）", src[i], out[i])
            }
        }
    }

    @Test
    fun `细结构靠半径扩张找到参考`() {
        // 一条 1px 宽的半透明竖线，两侧都是完全透明 —— 半径 2 内没有实心像素
        val w = 21
        val h = 21
        val src = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x == 10 && y in 4..16 -> argb(180, polluteColor[0], polluteColor[1], polluteColor[2])
                y in 8..12 && x in 6..14 && x != 10 -> argb(255, frogColor[0], frogColor[1], frogColor[2])
                else -> argb(0, 0, 0, 0)
            }
        }
        val result = EdgeDefringe.defringe(src, w, h)
        assertEquals("1px 细线应通过半径扩张找到参考", 0, result.unresolvedPixels)

        // 竖线上的像素应变成本体色
        val idx = 10 * w + 10
        assertEquals(frogColor[0], (result.pixels[idx] shr 16) and 0xFF)
    }

    @Test
    fun `孤立半透明像素如实报告未解决`() {
        // 整个画面只有一个半透明像素，周围没有任何实心参考
        val w = 11
        val h = 11
        val src = IntArray(w * h) { argb(0, 0, 0, 0) }
        src[5 * w + 5] = argb(128, 240, 240, 240)

        val result = EdgeDefringe.defringe(src, w, h)
        assertEquals("找不到参考时必须如实报告，不能假装处理过", 1, result.unresolvedPixels)
        assertEquals(0, result.changedPixels)
        assertEquals("未解决的像素保持原值", src[5 * w + 5], result.pixels[5 * w + 5])
    }

    @Test
    fun `尺寸不合法时原样返回且不抛异常`() {
        val src = IntArray(4)
        val r = EdgeDefringe.defringe(src, 0, 0)
        assertEquals(0, r.changedPixels)
        assertEquals(4, r.pixels.size)
    }
}
