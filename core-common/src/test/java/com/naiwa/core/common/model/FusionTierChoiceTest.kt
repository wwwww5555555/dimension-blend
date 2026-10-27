package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位解析规则的契约测试。
 *
 * 这些用例钉的不是"实现细节"，而是三条**产品承诺**：
 *  1. 自动档永远不被这里改写（自适应逻辑在管线里，不能被 UI 层规则抢走）；
 *  2. 降级必须如实记录来源，UI 才能告诉用户"你选的分块被换成了 720p"；
 *  3. 用户选比自动更保守的档位时**不许降级** —— 那不是我们可以优化的对象。
 */
class FusionTierChoiceTest {

    @Test
    fun `自动档在任何设备上都返回空请求`() {
        DeviceCapabilityTier.entries.forEach { device ->
            val r = FusionTierChoice.resolve(null, device)
            assertNull("设备=$device 时自动档不应产生显式请求", r.requested)
            assertFalse(r.downgraded)
            assertNull(r.downgradeNotice())
        }
    }

    @Test
    fun `分块档只有高端机原样执行`() {
        assertEquals(
            FusionTier.TILED,
            FusionTierChoice.resolve(FusionTier.TILED, DeviceCapabilityTier.HIGH_END).requested,
        )
    }

    @Test
    fun `分块档在中端机降到 720p 并如实回报来源`() {
        val r = FusionTierChoice.resolve(FusionTier.TILED, DeviceCapabilityTier.MID_RANGE)
        assertEquals(FusionTier.FUSION_720, r.requested)
        assertEquals(FusionTier.TILED, r.downgradedFrom)
        assertTrue(r.downgraded)
        val notice = r.downgradeNotice()
        assertTrue("降级提示应说明改用了哪一档，实测：$notice", notice?.contains(FusionTier.FUSION_720.label) == true)
    }

    @Test
    fun `分块档在低端机降到 540p`() {
        val r = FusionTierChoice.resolve(FusionTier.TILED, DeviceCapabilityTier.LOW_END)
        assertEquals(FusionTier.FUSION_540, r.requested)
        assertEquals(FusionTier.TILED, r.downgradedFrom)
    }

    @Test
    fun `720p 档在低端机降级_其余设备原样`() {
        val low = FusionTierChoice.resolve(FusionTier.FUSION_720, DeviceCapabilityTier.LOW_END)
        assertEquals(FusionTier.FUSION_540, low.requested)
        assertEquals(FusionTier.FUSION_720, low.downgradedFrom)

        assertEquals(
            FusionTier.FUSION_720,
            FusionTierChoice.resolve(FusionTier.FUSION_720, DeviceCapabilityTier.MID_RANGE).requested,
        )
        assertEquals(
            FusionTier.FUSION_720,
            FusionTierChoice.resolve(FusionTier.FUSION_720, DeviceCapabilityTier.HIGH_END).requested,
        )
    }

    @Test
    fun `保守档位在任何设备上都不降级`() {
        DeviceCapabilityTier.entries.forEach { device ->
            val r540 = FusionTierChoice.resolve(FusionTier.FUSION_540, device)
            assertEquals(FusionTier.FUSION_540, r540.requested)

            val rLap = FusionTierChoice.resolve(FusionTier.LAPLACIAN, device)
            assertEquals(FusionTier.LAPLACIAN, rLap.requested)

            assertFalse("设备=$device 选保守档不该被降级", r540.downgraded || rLap.downgraded)
        }
    }

    @Test
    fun `脏数据里的 NONE 按自动处理`() {
        // NONE 是拖动预览的内部状态，正常不会写进设置。真出现了也不能让它透传给管线
        // （管线收到 NONE 会当成"用户要直贴"，把融合整个跳过）。
        DeviceCapabilityTier.entries.forEach { device ->
            assertNull(FusionTierChoice.resolve(FusionTier.NONE, device).requested)
        }
    }

    @Test
    fun `每个档位都有非空标签且 NONE 不可被用户选择`() {
        FusionTier.entries.forEach { tier ->
            assertTrue("$tier 缺少完整标签", tier.label.isNotBlank())
            assertTrue("$tier 缺少短标签", tier.shortLabel.isNotBlank())
        }
        assertFalse("NONE 不该出现在档位选择器里", FusionTier.NONE.userSelectable)
        assertEquals(
            "可选档位数量应与 enum 数量减去 NONE 一致",
            FusionTier.entries.size - 1,
            FusionTier.entries.count { it.userSelectable },
        )
    }

    @Test
    fun `分块档不使用降采样工作域`() {
        // shortSide = 0 表示"不通过全局降采样实现"，与 NONE/LAPLACIAN 一致；
        // 若将来有人误填一个正数，PoissonBlender 会按那个工作域处理，分块就失去意义了。
        assertEquals(0, FusionTier.TILED.shortSide)
        assertEquals(540, FusionTier.FUSION_540.shortSide)
        assertEquals(720, FusionTier.FUSION_720.shortSide)
    }
}
