package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深度暗部（环境吸收 + 三级高度）的验收。
 *
 * 两份规格被翻译成数字：
 * 1. 头 0.40 / 腰 0.20 / 脚底 0.05 —— 必须有**可测的层次**，不能全身一个暗度；
 * 2. 暗部要"吸收"背景环境色（深蓝/暗紫），但**不能变成纯黑**。
 */
class DeepShadowTintTest {

    private val eps = 1e-4f

    // ---------------------------------------------------------------- 环境吸收色

    @Test
    fun `深蓝背景得到偏蓝的吸收因子`() {
        val f = DeepShadowTint.factor(0xFF1A1E30.toInt())   // 深蓝
        assertTrue("B 应该最大：${f.toList()}", f[2] > f[0] && f[2] > f[1])
        assertTrue("不能是纯黑：${f.toList()}", (f.maxOrNull() ?: 0f) > 0.3f)
    }

    @Test
    fun `中性灰背景三通道相等`() {
        val f = DeepShadowTint.factor(0xFF404040.toInt())
        assertEquals(f[0], f[1], eps)
        assertEquals(f[1], f[2], eps)
    }

    @Test
    fun `吸收因子的亮度落在 DEEP_LEVEL 附近`() {
        listOf(0xFF1A1E30.toInt(), 0xFF2A1A1A.toInt(), 0xFF101010.toInt()).forEach { c ->
            val f = DeepShadowTint.factor(c)
            val luma = 0.2126f * f[0] + 0.7152f * f[1] + 0.0722f * f[2]
            assertTrue("输入 ${Integer.toHexString(c)} 的亮度 $luma 偏离 DEEP_LEVEL 太多",
                kotlin.math.abs(luma - DeepShadowTint.DEEP_LEVEL) < 0.12f)
        }
    }

    @Test
    fun `纯黑输入退化为中性因子而不是除零`() {
        val f = DeepShadowTint.factor(0xFF000000.toInt())
        f.forEach { assertTrue("不能 NaN/越界：$it", it.isFinite() && it in 0f..1f) }
        assertEquals(DeepShadowTint.DEEP_LEVEL, f[0], eps)
    }

    @Test
    fun `极端色相不会放大到超过 1`() {
        val f = DeepShadowTint.factor(0xFF00FF00.toInt())   // 纯绿，chroma 极端
        f.forEach { assertTrue("必须是合法乘数：$it", it in 0f..1f) }
    }

    // ---------------------------------------------------------------- 三级高度

    @Test
    fun `高度三级衰减符合规格`() {
        assertEquals("头顶", 0.40f, DeepShadowTint.heightTier(0.10f), 0.01f)
        assertEquals("腰部", 0.20f, DeepShadowTint.heightTier(0.60f), 0.02f)
        assertEquals("脚底", 0.05f, DeepShadowTint.heightTier(0.98f), 0.01f)
    }

    @Test
    fun `从上到下单调不变亮`() {
        var prev = 2f
        var y = 0f
        while (y <= 1f) {
            val v = DeepShadowTint.heightTier(y)
            assertTrue("y=$y 的系数 $v 比上一档 $prev 更亮，层次反了", v <= prev + eps)
            prev = v
            y += 0.02f
        }
    }

    @Test
    fun `三级之间必须有可辨识的落差`() {
        val top = DeepShadowTint.heightTier(0.10f)
        val mid = DeepShadowTint.heightTier(0.60f)
        val feet = DeepShadowTint.heightTier(0.95f)
        assertTrue("头与腰差 ${top - mid} 太小（<0.12 就看不出层次）", top - mid > 0.12f)
        assertTrue("腰与脚差 ${mid - feet} 太小", mid - feet > 0.08f)
        assertTrue("脚底必须接近死黑", feet <= 0.08f)
    }

    // ---------------------------------------------------------------- 合成

    @Test
    fun `最终因子逐级变暗且都小于迎光侧`() {
        val deep = floatArrayOf(0.35f, 0.42f, 0.69f)
        val top = DeepShadowTint.darkenFactor(0.10f, deep)
        val mid = DeepShadowTint.darkenFactor(0.60f, deep)
        val feet = DeepShadowTint.darkenFactor(0.95f, deep)

        fun luma(a: FloatArray) = 0.2126f * a[0] + 0.7152f * a[1] + 0.0722f * a[2]
        assertTrue("头(${luma(top)}) > 腰(${luma(mid)})", luma(top) > luma(mid))
        assertTrue("腰(${luma(mid)}) > 脚(${luma(feet)})", luma(mid) > luma(feet))
        assertTrue("脚底必须极暗（<0.03）", luma(feet) < 0.03f)
        assertTrue("头部不能亮过 0.2", luma(top) < 0.20f)
    }

    @Test
    fun `吸收比为 0 时只压暗不串色`() {
        val deep = floatArrayOf(0.1f, 0.2f, 0.9f)
        val f = DeepShadowTint.darkenFactor(0.10f, deep, absorbMix = 0f)
        assertEquals(f[0], f[1], eps)
        assertEquals(f[1], f[2], eps)
    }

    @Test
    fun `吸收比为 1 时完全跟随环境色相`() {
        val deep = floatArrayOf(0.35f, 0.42f, 0.69f)
        val f = DeepShadowTint.darkenFactor(0.60f, deep, absorbMix = 1f)
        val tier = DeepShadowTint.heightTier(0.60f)
        assertTrue("应等于 tier × deep：${f.toList()}", kotlin.math.abs(f[2] - tier * 0.69f) < 0.01f)
    }

    @Test
    fun `任何高度与参数都不会越界`() {
        var y = -0.5f
        while (y <= 1.5f) {
            val f = DeepShadowTint.darkenFactor(y, floatArrayOf(0f, 0.5f, 1f))
            f.forEach { assertTrue("越界：y=$y → $it", it in 0f..1f) }
            y += 0.1f
        }
    }
}
