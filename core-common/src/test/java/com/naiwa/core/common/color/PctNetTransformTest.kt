package com.naiwa.core.common.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PctNetTransform 的单元测试：钉住"恒等参数不变性 + 平移 + 缩放 + 边界/非法输入"。
 *
 * 这些用例不依赖 TFLite 权重，纯函数即可验证——正是"参数应用"这一步的正确性，
 * 与 PCT-Net 论文"参数空间上采样 + 逐像素仿射"的契约一一对应。
 */
class PctNetTransformTest {

    private fun identityParams(): FloatArray {
        // 12 参数：A = I（对角 1），b = 0
        val p = FloatArray(256 * 256 * 12)
        for (i in 0 until 256 * 256) {
            val base = i * 12
            p[base + 0] = 1f // A[0][0]
            p[base + 4] = 1f // A[1][1]
            p[base + 8] = 1f // A[2][2]
        }
        return p
    }

    @Test
    fun identityParamsLeavePixelsUnchanged() {
        val w = 4
        val h = 4
        val image = IntArray(w * h)
        val mask = IntArray(w * h) { 255 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                image[y * w + x] = (255 shl 24) or (x * 10 shl 16) or (y * 10 shl 8) or ((x + y) * 10)
            }
        }
        val out = PctNetTransform.apply(image, mask, identityParams(), w, h)
        // 恒等参数：前景像素应逐位不变（bicubic 采样恒等参数仍恒等，因参数空间恒定）
        for (i in image.indices) {
            if (mask[i] != 0) {
                assertEquals("前景像素 $i 应不变", image[i], out[i])
            }
        }
    }

    @Test
    fun pureTranslationShiftsByOffset() {
        // 只设 b = [50, 0, 0]，A = I → R 通道 +50
        val w = 4
        val h = 4
        val image = IntArray(w * h)
        val mask = IntArray(w * h) { 255 }
        for (i in image.indices) {
            image[i] = (255 shl 24) or (100 shl 16) or (100 shl 8) or 100
        }
        val p = identityParams()
        for (i in 0 until 256 * 256) {
            p[i * 12 + 9] = 50f // b[0]
        }
        val out = PctNetTransform.apply(image, mask, p, w, h)
        val r = (out[0] ushr 16) and 0xFF
        assertEquals(150, r) // 100 + 50，未超 255
    }

    @Test
    fun scaleByMatrixDiagonal() {
        // A = diag(2,2,2)，b=0 → 每个通道 ×2
        val w = 2
        val h = 2
        val image = IntArray(w * h)
        val mask = IntArray(w * h) { 255 }
        for (i in image.indices) {
            image[i] = (255 shl 24) or (50 shl 16) or (60 shl 8) or 70
        }
        val p = FloatArray(256 * 256 * 12)
        for (i in 0 until 256 * 256) {
            p[i * 12 + 0] = 2f
            p[i * 12 + 4] = 2f
            p[i * 12 + 8] = 2f
        }
        val out = PctNetTransform.apply(image, mask, p, w, h)
        val idx = 0
        assertEquals(100, (out[idx] ushr 16) and 0xFF) // 50*2
        assertEquals(120, (out[idx] ushr 8) and 0xFF) // 60*2
        assertEquals(140, out[idx] and 0xFF) // 70*2
    }

    @Test
    fun backgroundPixelsUnaffected() {
        // 前景 mask=255，背景 mask=0。只改前景，背景逐位不变。
        val w = 4
        val h = 4
        val image = IntArray(w * h)
        val mask = IntArray(w * h)
        for (i in image.indices) {
            image[i] = (255 shl 24) or (40 shl 16) or (50 shl 8) or 60
            mask[i] = if (i % 2 == 0) 255 else 0 // 隔一个前景
        }
        val p = identityParams()
        for (i in 0 until 256 * 256) p[i * 12 + 9] = 100f // b[0]=100
        val out = PctNetTransform.apply(image, mask, p, w, h)
        for (i in image.indices) {
            if (mask[i] == 0) {
                assertEquals("背景像素 $i 应不变", image[i], out[i])
            } else {
                assertEquals("前景 R 通道应 +100", 140, (out[i] ushr 16) and 0xFF)
            }
        }
    }

    @Test
    fun clampToRange() {
        // A = diag(10,10,10) 会让值爆表，应 clamp 到 255
        val w = 2
        val h = 2
        val image = IntArray(w * h)
        val mask = IntArray(w * h) { 255 }
        for (i in image.indices) image[i] = (255 shl 24) or (100 shl 16) or (100 shl 8) or 100
        val p = FloatArray(256 * 256 * 12)
        for (i in 0 until 256 * 256) {
            p[i * 12 + 0] = 10f
            p[i * 12 + 4] = 10f
            p[i * 12 + 8] = 10f
        }
        val out = PctNetTransform.apply(image, mask, p, w, h)
        assertEquals(255, (out[0] ushr 16) and 0xFF)
        assertEquals(255, (out[0] ushr 8) and 0xFF)
        assertEquals(255, out[0] and 0xFF)
    }

    @Test
    fun invalidInputReturnsCopy() {
        // 参数图尺寸不对 → 返回原图副本，不抛异常
        val w = 2
        val h = 2
        val image = intArrayOf(1, 2, 3, 4)
        val mask = intArrayOf(255, 255, 255, 255)
        val badParams = FloatArray(10) // 尺寸错误
        val out = PctNetTransform.apply(image, mask, badParams, w, h)
        assertArrayEquals(image, out)
    }

    @Test
    fun transparentPixelsUnaffected() {
        val w = 2
        val h = 2
        val image = IntArray(w * h) { (0 shl 24) or (10 shl 16) or 20 } // alpha=0
        val mask = IntArray(w * h) { 255 }
        val p = identityParams()
        for (i in 0 until 256 * 256) p[i * 12 + 9] = 100f
        val out = PctNetTransform.apply(image, mask, p, w, h)
        assertArrayEquals(image, out) // 全透明，不做任何改动
    }
}
