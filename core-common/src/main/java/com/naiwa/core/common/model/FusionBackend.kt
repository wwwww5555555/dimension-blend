package com.naiwa.core.common.model

/**
 * 设备能力档位。
 *
 * 存在的理由：本项目是「双轨制」——低端机走稳定的经典路径，高配机才允许启用实验性的
 * 重参数化细节保真路径。档位必须在**一个地方**算出来（[DeviceCapabilityProbe]），
 * 否则管线、设置、UI 各算一套，迟早出现"UI 显示可用但管线拒绝执行"这类自相矛盾的状态。
 */
enum class DeviceCapabilityTier(val label: String) {
    /** 总 RAM < 4GB 或核心数 ≤ 4（或厂商打了 low-ram 标记）：只走经典路径。 */
    LOW_END("低端"),

    /** 中间档：能跑重参数化路径，但不默认开启。 */
    MID_RANGE("中端"),

    /** 总 RAM ≥ 8GB 且核心数 ≥ 8：可开启实验路径。 */
    HIGH_END("高端"),
    ;

    /** 是否允许启用重参数化细节保真融合（低端机一律不允许）。 */
    val supportsRepDetail: Boolean get() = this != LOW_END
}

/**
 * 融合后端 —— 「双轨制」的两条轨。
 *
 * 离线约束下可落地的是**机制**而不是生成式模型：
 *  - [CLASSICAL]：泊松 seamlessClone（档 1/2）+ 拉普拉斯多频段（档 3）+ α 加权回贴。所有设备都走这条。
 *  - [REP_DETAIL]：在 [CLASSICAL] 的结果上加一步**结构重参数化**的细节保真修正
 *    （MobileFusion 的 mechanism：训练/设计期多分支 → 推理期折叠成单个卷积）。
 *    它修的是过渡带"糊块"这个已知缺陷，不改变中心区与外部区域。
 *
 * **诚实的边界**：这里落地的是重参数化的**工程机制 + 闭式设计的算子**。
 * 论文里"比泊松更自然"来自训练出来的权重，本仓库不提供未经验证的训练结果，
 * 因此 [REP_DETAIL] 的默认状态是**关闭**，由用户在支持的设备上显式开启。
 *
 * @param experimental 是否属于实验特性（UI 需要如实标注）
 */
enum class FusionBackend(val label: String, val experimental: Boolean) {
    CLASSICAL("经典融合", false),
    REP_DETAIL("细节保真（重参数化）", true),
    ;

    companion object {
        /** 默认后端：永远是可复现、已在所有设备验证过的经典路径。 */
        val DEFAULT = CLASSICAL
    }
}

/**
 * 双轨制的选路规则 —— **纯函数**，与 Android 无关，可直接单测。
 *
 * 规则刻意做成"能力只做否决、不做自动升级"：
 *  - 低端机 → 无论用户怎么点，都只走 [FusionBackend.CLASSICAL]（宁可慢一点，不可崩）；
 *  - 中/高端机 → 需要用户显式开启才走 [FusionBackend.REP_DETAIL]。
 *
 * 不自动升级的理由：实验特性在真机上的收益尚未实测，自动开启等于把未验证的风险
 * 默默施加给所有高配机用户。
 */
object FusionTrack {

    /** 低端机内存阈值：总 RAM < 4GB。 */
    const val LOW_END_RAM_BYTES = 4L * 1024 * 1024 * 1024

    /** 低端机核数阈值：≤ 4 核。 */
    const val LOW_END_CORES = 4

    /** 高端机内存阈值：总 RAM ≥ 8GB。 */
    const val HIGH_END_RAM_BYTES = 8L * 1024 * 1024 * 1024

    /** 高端机核数阈值：≥ 8 核。 */
    const val HIGH_END_CORES = 8

    /**
     * 判定设备档位。
     *
     * @param totalRamBytes 总物理内存；取不到传 0
     * @param cores 可用核心数
     * @param lowRamFlag 厂商标记（`ActivityManager.isLowRamDevice`）
     */
    fun tierFor(totalRamBytes: Long, cores: Int, lowRamFlag: Boolean): DeviceCapabilityTier {
        // 取不到内存信息（totalRamBytes = 0）时**不**按高配处理：0 表示"未知"，
        // 未知就按能力不足走经典路径，避免在没有依据的情况下启用实验特性。
        val ram = if (totalRamBytes > 0) totalRamBytes else 0L
        return when {
            lowRamFlag -> DeviceCapabilityTier.LOW_END
            ram in 1 until LOW_END_RAM_BYTES -> DeviceCapabilityTier.LOW_END
            cores <= LOW_END_CORES -> DeviceCapabilityTier.LOW_END
            ram >= HIGH_END_RAM_BYTES && cores >= HIGH_END_CORES -> DeviceCapabilityTier.HIGH_END
            else -> DeviceCapabilityTier.MID_RANGE
        }
    }

    /**
     * 选路。
     *
     * @param tier 设备档位
     * @param userOptIn 用户在设置里是否开启了实验路径
     * @return 实际要使用的后端
     */
    fun backendFor(tier: DeviceCapabilityTier, userOptIn: Boolean): FusionBackend =
        if (userOptIn && tier.supportsRepDetail) {
            FusionBackend.REP_DETAIL
        } else {
            FusionBackend.CLASSICAL
        }
}
