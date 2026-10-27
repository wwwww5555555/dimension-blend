package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格 7 光效规则层的契约测试。
 *
 * 这些规则是"什么情况开哪个光效、开多强"的唯一来源，若这里出错，
 * UI 面板显示的强度会与管线实际执行对不上（面板显示 0.3、实际跑 0）。
 */
class LightFxRulesTest {

    private fun light(
        azimuthDeg: Float,
        keyIntensity: Float = 0.8f,
    ) = LightParams(keyAzimuthDeg = azimuthDeg, keyIntensity = keyIntensity)

    // ------------------------------------------------------------ 7.1 逆光判定

    @Test
    fun `方位角与相机侧夹角超过120度且主光够强判定为逆光`() {
        // 相机侧在图像下方 = 90°。光源在 90°+130°=220°（画面左上方）→ 夹角 130° > 120°
        val l = light(azimuthDeg = 220f, keyIntensity = 0.8f)
        assertTrue("夹角 130° 且主光 0.8 应为逆光", LightFxRules.isBacklit(l))
    }

    @Test
    fun `夹角超过120度但主光太弱不判定为逆光`() {
        val l = light(azimuthDeg = 220f, keyIntensity = 0.4f)
        assertFalse("主光 0.4 < 0.5 不应判为逆光", LightFxRules.isBacklit(l))
    }

    @Test
    fun `主光够强但夹角不足120度不判定为逆光`() {
        // 光源在 90°（相机同侧）→ 夹角 0°
        val l = light(azimuthDeg = 90f, keyIntensity = 0.9f)
        assertFalse("夹角 0° 不应判为逆光", LightFxRules.isBacklit(l))
    }

    @Test
    fun `夹角计算对0到180范围内的对称性`() {
        // 220° 与 90° 的夹角 = 130°；320°（等价 -40°）与 90° 的夹角也应为 130°
        val a = LightFxRules.angleToCameraDeg(220f)
        val b = LightFxRules.angleToCameraDeg(320f)
        assertEquals("130°", a, 130f, 0.5f)
        assertEquals("夹角对称", a, b, 0.5f)
    }

    // ------------------------------------------------------------ 7.1 边缘光强度

    @Test
    fun `逆光边缘光强度等于主光强度乘逆光增益`() {
        val l = light(azimuthDeg = 220f, keyIntensity = 0.8f)
        val plan = LightFxRules.plan(l, LightOverrides.DEFAULT, allowExpensiveFx = true)
        // 断言公式行为而非硬编码数值：增益本身是可调参数（参考图级调参时改动不应破坏本测试）
        assertEquals(
            "逆光边缘光基准 = keyIntensity × RIM_BACKLIT_GAIN",
            0.8f * LightFxRules.RIM_BACKLIT_GAIN,
            plan.rimStrength,
            1e-4f,
        )
    }

    @Test
    fun `非逆光边缘光强度减半`() {
        val l = light(azimuthDeg = 90f, keyIntensity = 0.8f)
        val plan = LightFxRules.plan(l, LightOverrides.DEFAULT, allowExpensiveFx = true)
        // 0.8 × 0.15 = 0.12
        assertEquals("侧光边缘光基准", 0.12f, plan.rimStrength, 1e-4f)
    }

    @Test
    fun `边缘光滑杆显式归零时不执行`() {
        val l = light(azimuthDeg = 220f, keyIntensity = 0.8f)
        val plan = LightFxRules.plan(l, LightOverrides(rimStrength = 0f), allowExpensiveFx = true)
        assertEquals("显式关闭边缘光", 0f, plan.rimStrength, 0f)
    }

    // ------------------------------------------------------------ 7.2 / 7.4 辉光与环境色

    @Test
    fun `逆光场景辉光取0点2普通场景取0点1`() {
        val backlit = light(azimuthDeg = 220f)
        val normal = light(azimuthDeg = 90f)
        val p1 = LightFxRules.plan(backlit, LightOverrides.DEFAULT, allowExpensiveFx = true)
        val p2 = LightFxRules.plan(normal, LightOverrides.DEFAULT, allowExpensiveFx = true)
        assertEquals("逆光辉光", 0.2f, p1.glowStrength, 1e-4f)
        assertEquals("普通辉光", 0.1f, p2.glowStrength, 1e-4f)
    }

    @Test
    fun `预览阶段即使用户开了辉光也不执行`() {
        val l = light(azimuthDeg = 220f)
        val plan = LightFxRules.plan(l, LightOverrides.DEFAULT, allowExpensiveFx = false)
        assertEquals("预览不跑辉光", 0f, plan.glowStrength, 0f)
        assertEquals("预览不跑环境色", 0f, plan.lightWrapStrength, 0f)
        // 但边缘光（便宜的）仍允许在预览执行
        assertTrue("预览仍跑边缘光", plan.rimStrength > 0f)
    }

    @Test
    fun `光效全零时hasAny为false`() {
        val l = light(azimuthDeg = 90f, keyIntensity = 0.1f)
        val plan = LightFxRules.plan(
            l,
            LightOverrides(rimStrength = 0f, glowStrength = 0f, lightWrapStrength = 0f),
            allowExpensiveFx = true,
        )
        assertFalse("全零光效", plan.hasAny)
    }

    // ------------------------------------------------------------ 强度上限

    @Test
    fun `强度被钳制在规格上限内`() {
        val l = light(azimuthDeg = 220f, keyIntensity = 1f)
        val plan = LightFxRules.plan(
            l,
            LightOverrides(rimStrength = 1f, glowStrength = 1f, lightWrapStrength = 1f),
            allowExpensiveFx = true,
        )
        assertTrue("边缘光上限 0.5", plan.rimStrength <= LightFxRules.RIM_MAX)
        assertTrue("辉光上限 0.3", plan.glowStrength <= LightFxRules.GLOW_MAX)
        assertTrue("环境色上限 0.4", plan.lightWrapStrength <= LightFxRules.WRAP_MAX)
    }

    // ------------------------------------------------------------ 7.x summary 摘要（供状态行如实展示）

    @Test
    fun `摘要如实列出生效的光效`() {
        // 逆光 + 导出（允许贵光效）→ 应有「逆光+边缘光+辉光+环境色」
        val backlit = LightFxRules.plan(light(220f), LightOverrides.DEFAULT, allowExpensiveFx = true)
        assertTrue("逆光摘要应含逆光", backlit.summary.contains("逆光"))
        assertTrue("应含边缘光", backlit.summary.contains("边缘光"))
        assertTrue("应含辉光", backlit.summary.contains("辉光"))
        assertTrue("应含环境色", backlit.summary.contains("环境色"))

        // 预览（禁贵光效）→ 只有逆光 + 边缘光
        val preview = LightFxRules.plan(light(220f), LightOverrides.DEFAULT, allowExpensiveFx = false)
        assertTrue("预览应含边缘光", preview.summary.contains("边缘光"))
        assertFalse("预览摘要不应含辉光", preview.summary.contains("辉光"))
        assertFalse("预览摘要不应含环境色", preview.summary.contains("环境色"))
    }

    @Test
    fun `全零光效时摘要给出可读结论而非空串`() {
        val none = LightFxRules.plan(
            light(90f, keyIntensity = 0.1f),
            LightOverrides(rimStrength = 0f, glowStrength = 0f, lightWrapStrength = 0f),
            allowExpensiveFx = true,
        )
        assertEquals("无光效", none.summary)
    }
}
