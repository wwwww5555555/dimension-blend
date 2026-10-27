package com.naiwa.core.cv.color

import com.naiwa.core.common.model.HarmonizeRequest
import com.naiwa.core.common.model.HarmonizerBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MKL 最优传输颜色迁移（规格 8.2 数学核心）契约测试。
 */
class MklHarmonizerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `harmonize高层入口只改前景不碰背景`() {
        val w = 4
        val h = 4
        // 前景（左上 2×2）暖色，背景（其余）冷色
        val fg = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x < 2 && y < 2) argb(255, 200, 120, 60) else argb(0, 0, 0, 0)
        }
        val bg = IntArray(w * h) { argb(255, 60, 80, 160) }
        val mask = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x < 2 && y < 2) 255 else 0
        }
        val req = HarmonizeRequest(fg, bg, w, h, mask)
        val result = MklHarmonizer.harmonize(req)

        assertEquals("后端应为 MKL", HarmonizerBackend.MKL, result.backend)
        assertEquals("参数为 12 维", 12, result.params?.size ?: 0)

        // 前景像素 RGB 被改动（朝冷色背景迁移）
        val fgIdx = 0
        val before = fg[fgIdx]
        val after = result.pixels[fgIdx]
        // 蓝色分量应上升（朝背景的蓝迁移）
        val beforeB = before and 0xFF
        val afterB = after and 0xFF
        assertTrue("前景蓝色分量应上升（before=$beforeB after=$afterB）", afterB > beforeB)

        // alpha 保持不变
        assertEquals("alpha 不变", before ushr 24, after ushr 24)

        // mask 外的像素（背景，alpha=0）不变
        val bgIdx = w * h - 1
        assertEquals("背景像素不变", fg[bgIdx], result.pixels[bgIdx])
    }

    @Test
    fun `相同分布时变换近似恒等`() {
        val mean = floatArrayOf(0.5f, 0.5f, 0.5f)
        val cov = floatArrayOf(
            0.05f, 0f, 0f,
            0f, 0.05f, 0f,
            0f, 0f, 0.05f,
        )
        val (a, s) = MklHarmonizer.computeParams(mean, cov, mean, cov)
        // A 应接近单位矩阵，S 应接近 0
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val expected = if (i == j) 1f else 0f
                assertEquals("A[$i][$j] 接近 $expected", expected, a[i * 3 + j], 0.1f)
            }
            assertEquals("S[$i] 接近 0", 0f, s[i], 0.05f)
        }
    }

    @Test
    fun `均值平移被正确迁移`() {
        // 源均值 0.3，目标均值 0.7，协方差相同 → S 应 ≈ 0.4
        val srcMean = floatArrayOf(0.3f, 0.3f, 0.3f)
        val dstMean = floatArrayOf(0.7f, 0.7f, 0.7f)
        val cov = floatArrayOf(
            0.05f, 0f, 0f,
            0f, 0.05f, 0f,
            0f, 0f, 0.05f,
        )
        val (a, s) = MklHarmonizer.computeParams(srcMean, cov, dstMean, cov)
        // A 接近单位阵，S 接近 0.4
        assertTrue("A 对角接近 1", a[0] in 0.9f..1.1f)
        assertEquals("S 接近 0.4", 0.4f, s[0], 0.05f)
    }

    @Test
    fun `方差缩放被正确迁移`() {
        // 源方差小（0.01），目标方差大（0.16）→ A 对角应 ≈ 4（σ1/σ0 = 0.4/0.1）
        val mean = floatArrayOf(0.5f, 0.5f, 0.5f)
        val srcCov = floatArrayOf(
            0.01f, 0f, 0f,
            0f, 0.01f, 0f,
            0f, 0f, 0.01f,
        )
        val dstCov = floatArrayOf(
            0.16f, 0f, 0f,
            0f, 0.16f, 0f,
            0f, 0f, 0.16f,
        )
        val (a, _) = MklHarmonizer.computeParams(mean, srcCov, mean, dstCov)
        // 对角缩放：A[i][i] = σ1/σ0 = 0.4/0.1 = 4
        assertTrue("A 对角 ≈ 4（a00=${a[0]}）", a[0] in 3.5f..4.5f)
    }

    @Test
    fun `退化分布退化为对角缩放不崩溃`() {
        // 源协方差全 0（纯色）→ 退化为对角缩放，A 对角 = 1，S = dstMean - srcMean
        val srcMean = floatArrayOf(0.2f, 0.2f, 0.2f)
        val dstMean = floatArrayOf(0.6f, 0.6f, 0.6f)
        val zeroCov = FloatArray(9)
        val (a, s) = MklHarmonizer.computeParams(srcMean, zeroCov, dstMean, zeroCov)
        // 对角缩放：A 对角 ≈ 1（因为 σ0 加 jitter 后 σ1/σ0 ≈ 1），S ≈ 0.4
        assertTrue("退化时 A 对角接近 1", a[0] in 0.9f..1.1f)
        assertEquals("S ≈ 0.4", 0.4f, s[0], 0.1f)
    }

    @Test
    fun `应用像素变换结果在合法范围`() {
        val srcMean = floatArrayOf(0.3f, 0.4f, 0.5f)
        val dstMean = floatArrayOf(0.6f, 0.5f, 0.4f)
        val cov = floatArrayOf(
            0.04f, 0.01f, 0f,
            0.01f, 0.04f, 0.01f,
            0f, 0.01f, 0.04f,
        )
        val (a, s) = MklHarmonizer.computeParams(srcMean, cov, dstMean, cov)
        // 任意输入像素映射后应在 [0,1]
        for (r in 0..255 step 51) {
            for (g in 0..255 step 51) {
                for (b in 0..255 step 51) {
                    val out = MklHarmonizer.applyPixel(
                        floatArrayOf(r / 255f, g / 255f, b / 255f), a, s,
                    )
                    for (c in out) {
                        assertTrue("输出 $c 在 [0,1]", c in 0f..1f)
                    }
                }
            }
        }
    }
}
