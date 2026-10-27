package com.naiwa.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双轨制选路规则的测试。
 *
 * 这套规则决定了"哪些设备会跑实验特性"，属于安全边界，必须有测试钉住 ——
 * 一旦被后人顺手放宽（比如把低端机判断改成只看核数），会静默地把未验证的路径推给低端机用户。
 */
class FusionTrackTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `低端机的三种判定都成立`() {
        // 厂商标记优先
        assertEquals(
            DeviceCapabilityTier.LOW_END,
            FusionTrack.tierFor(totalRamBytes = 12 * gb, cores = 8, lowRamFlag = true),
        )
        // 内存不足
        assertEquals(
            DeviceCapabilityTier.LOW_END,
            FusionTrack.tierFor(totalRamBytes = 3 * gb, cores = 8, lowRamFlag = false),
        )
        // 核数不足（4 核的中端机也很常见，泊松求解会明显吃力）
        assertEquals(
            DeviceCapabilityTier.LOW_END,
            FusionTrack.tierFor(totalRamBytes = 6 * gb, cores = 4, lowRamFlag = false),
        )
    }

    @Test
    fun `内存取不到时不按高端处理`() {
        // totalRamBytes = 0 表示"未知"。未知必须按能力不足走经典路径，
        // 否则在拿不到内存信息的设备上会默认启用实验特性。
        val tier = FusionTrack.tierFor(totalRamBytes = 0L, cores = 12, lowRamFlag = false)
        assertEquals(DeviceCapabilityTier.MID_RANGE, tier)
        assertFalse("未知内存必须不能判为高端", tier == DeviceCapabilityTier.HIGH_END)
    }

    @Test
    fun `中端与高端的边界`() {
        assertEquals(
            DeviceCapabilityTier.MID_RANGE,
            FusionTrack.tierFor(totalRamBytes = 6 * gb, cores = 8, lowRamFlag = false),
        )
        assertEquals(
            DeviceCapabilityTier.MID_RANGE,
            FusionTrack.tierFor(totalRamBytes = 12 * gb, cores = 6, lowRamFlag = false),
        )
        assertEquals(
            DeviceCapabilityTier.HIGH_END,
            FusionTrack.tierFor(totalRamBytes = 8 * gb, cores = 8, lowRamFlag = false),
        )
        assertEquals(
            DeviceCapabilityTier.HIGH_END,
            FusionTrack.tierFor(totalRamBytes = 12 * gb, cores = 8, lowRamFlag = false),
        )
    }

    @Test
    fun `低端机无条件走经典路径`() {
        // 即使用户在设置里开了开关，低端机也只能走经典路径
        assertEquals(
            FusionBackend.CLASSICAL,
            FusionTrack.backendFor(DeviceCapabilityTier.LOW_END, userOptIn = true),
        )
        assertFalse(DeviceCapabilityTier.LOW_END.supportsRepDetail)
    }

    @Test
    fun `中高端机需要用户开启才走高配轨道`() {
        assertEquals(
            FusionBackend.CLASSICAL,
            FusionTrack.backendFor(DeviceCapabilityTier.MID_RANGE, userOptIn = false),
        )
        assertEquals(
            FusionBackend.REP_DETAIL,
            FusionTrack.backendFor(DeviceCapabilityTier.MID_RANGE, userOptIn = true),
        )
        assertEquals(
            FusionBackend.REP_DETAIL,
            FusionTrack.backendFor(DeviceCapabilityTier.HIGH_END, userOptIn = true),
        )
        assertTrue(DeviceCapabilityTier.MID_RANGE.supportsRepDetail)
        assertTrue(DeviceCapabilityTier.HIGH_END.supportsRepDetail)
    }

    @Test
    fun `默认后端是经典路径`() {
        assertEquals(FusionBackend.CLASSICAL, FusionBackend.DEFAULT)
        assertFalse("经典路径不应标为实验特性", FusionBackend.CLASSICAL.experimental)
        assertTrue("高配轨道应如实标注为实验特性", FusionBackend.REP_DETAIL.experimental)
    }
}
