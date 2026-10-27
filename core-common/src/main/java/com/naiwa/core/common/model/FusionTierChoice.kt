package com.naiwa.core.common.model

/**
 * 融合档位的选择规则 —— **纯函数**，与 Android 无关，可直接单测。
 *
 * 【为什么需要一个"选择"层】用户可以在编辑页显式指定档位（高质量 / 验收线 / 保底 / 分块），
 * 但设备能力必须能一票否决。如果让 UI 自己判断"这台机器能不能跑分块"，
 * 就必然出现"三个地方各算一套"的老问题：UI 显示可用、管线拒绝执行、日志又是第三种说法。
 * 所以规则只写在这里一处，UI 只负责展示 [Resolution] 的结果。
 *
 * 【与自动档的关系】`preference == null` 表示**自动**：此时返回 `requested = null`，
 * 交给管线按设备能力与实测耗时自适应升降档（见 `DefaultImagePipeline.defaultTierFor` /
 * `updateAdaptiveTier`）。显式选择**不进入**自适应路径 —— 用户既然明确要了某一档，
 * 就不该被"上次跑得慢"这种历史状态悄悄改掉。
 *
 * 【分块为什么门槛最高】[FusionTier.TILED] 在合并阶段要同时持有一张全分辨率的累加结果，
 * 并对每个 tile 做一次泊松求解。它是唯一会在中端机上真正触碰 OOM 风险的档位，
 * 所以只有 [DeviceCapabilityTier.HIGH_END] 才原样执行；其余档位降级后**必须如实回报**。
 */
object FusionTierChoice {

    /**
     * 解析结果。
     *
     * @param requested 真正要传给管线的档位；`null` = 自动
     * @param downgradedFrom 因设备能力被降级时，记录**用户原本选的档位**；未降级为 `null`
     */
    data class Resolution(
        val requested: FusionTier?,
        val downgradedFrom: FusionTier? = null,
    ) {
        /** 是否发生了降级（UI 需要如实提示，不能默默改掉用户的意图）。 */
        val downgraded: Boolean get() = downgradedFrom != null

        /** 降级时给用户看的一句话；未降级返回 null。 */
        fun downgradeNotice(): String? = if (downgradedFrom != null && requested != null) {
            "${downgradedFrom!!.label}在当前设备上风险过高，已改用${requested.label}"
        } else {
            null
        }
    }

    /**
     * 解析用户偏好为实际档位。
     *
     * 规则表（全部可测）：
     * | 偏好 | 低端 | 中端 | 高端 |
     * | --- | --- | --- | --- |
     * | 自动（null） | 自动 | 自动 | 自动 |
     * | 720p 泊松 | 540p（降级） | 720p | 720p |
     * | 540p 泊松 | 540p | 540p | 540p |
     * | 多频段 | 多频段 | 多频段 | 多频段 |
     * | 全分辨率分块 | 540p（降级） | 720p（降级） | 分块 |
     *
     * 只有"更重"的档位会被降级：用户选一个比自动档更保守的档位（例如多频段），
     * 任何设备上都必须原样执行 —— 那是用户的明确选择，不是我们可以优化的对象。
     */
    fun resolve(preference: FusionTier?, device: DeviceCapabilityTier): Resolution =
        when (preference) {
            null -> Resolution(requested = null)

            // 分块：唯一会持有全分辨率累加缓冲的档位，按设备能力逐级降到"该设备的最佳经典档"
            FusionTier.TILED -> when (device) {
                DeviceCapabilityTier.HIGH_END -> Resolution(FusionTier.TILED)
                DeviceCapabilityTier.MID_RANGE ->
                    Resolution(FusionTier.FUSION_720, downgradedFrom = FusionTier.TILED)
                DeviceCapabilityTier.LOW_END ->
                    Resolution(FusionTier.FUSION_540, downgradedFrom = FusionTier.TILED)
            }

            // 720p 泊松：低端机（RAM < 4GB 或 ≤ 4 核）上单次求解就可能吃掉整个耗时预算
            FusionTier.FUSION_720 -> if (device == DeviceCapabilityTier.LOW_END) {
                Resolution(FusionTier.FUSION_540, downgradedFrom = FusionTier.FUSION_720)
            } else {
                Resolution(FusionTier.FUSION_720)
            }

            FusionTier.FUSION_540, FusionTier.LAPLACIAN -> Resolution(preference)

            // NONE 是拖动预览的内部状态，不是可选档位。用户偏好里出现它只可能来自脏数据，
            // 按"自动"处理比抛异常或原样透传都安全。
            FusionTier.NONE -> Resolution(requested = null)
        }
}
