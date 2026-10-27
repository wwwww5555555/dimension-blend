package com.naiwa.core.common.model

import android.graphics.Bitmap

/**
 * 一次渲染请求。
 *
 * feature 模块（编辑页）只构造这个对象交给 [com.naiwa.core.common.pipeline.ImagePipeline]，
 * **不接触任何 CV/AI 的具体实现类**（架构文档 §3 依赖纪律第 4 条）。
 *
 * @param scene     场景位图。调用方需保证已是 sRGB（见 §6.7），且方向已按 EXIF 校正
 * @param frog      奶蛙位图，必须带有效 alpha（无 alpha 的素材先走抠图流程）
 * @param placement 归一化摆放参数
 * @param light     自动估计出的光照参数
 * @param overrides 用户手动覆盖（默认为「全自动」）
 * @param mode      渲染模式，决定工作分辨率与需要执行到哪一步（三段式预览 + 导出）
 * @param requestedTier 融合档位；`null` = 由管线按设备能力自适应选择（推荐）
 * @param requestedBackend 融合后端（双轨制选路结果）；`null` = 走 [FusionBackend.DEFAULT]。
 *   调用方（编辑页）负责结合设备档位与用户开关算出这个值，并**传进来**——
 *   管线不读用户设置，否则会破坏"管线无状态、可复现"这个前提
 */
data class RenderRequest(
    val scene: Bitmap,
    val frog: Bitmap,
    val placement: Placement,
    val light: LightParams,
    val overrides: LightOverrides = LightOverrides.DEFAULT,
    val mode: RenderMode = RenderMode.FINAL_PREVIEW,
    val requestedTier: FusionTier? = null,
    val requestedBackend: FusionBackend? = null,
    /**
     * Debug 开关：为 true 时跳过 AI 和谐化（PCT-Net）与泊松融合，只做颜色迁移 + α 直贴。
     * 用于排查"失真到底来自 AI 还是融合"。
     */
    val debugSkipAI: Boolean = false,
    /**
     * AI 和谐化的全局混合强度（0~1）。最终图 = (1-strength)*原像素 + strength*AI 像素。
     * 默认 0.8；色彩断层严重时调用方会降到 0.5。
     */
    val harmonizeStrength: Float = 0.8f,
) {

    /** 合并手动覆盖后的最终光照参数 —— 管线内部统一用这个，不要直接用 [light]。 */
    val effectiveLight: LightParams get() = light.withOverrides(overrides)

    /** 颜色迁移力度（来自手动覆盖或默认值）。 */
    val fusionStrength: Float get() = overrides.effectiveFusionStrength
}

/**
 * 渲染模式 —— 对应架构文档 §7.1 的「预览三段式 + 导出」。
 *
 * 拆成四种模式而不是一个布尔开关，是因为它们的工作分辨率与计算内容都不同：
 * 拖动时每帧都要重算，必须最省；导出时只算一次，必须最全。
 *
 * 性能目标（2026-10 放宽后的标准，旧标准已作废）：
 *  · 预览拖动 ≥ 30 fps（单帧 ≤ 33 ms）
 *  · 松手后 ≤ 300 ms 切到"带阴影的中间质量预览"
 *  · 1080p 导出端到端 < 5 s；4K 长边 4096 导出 < 12 s
 */
enum class RenderMode {

    /** 拖动中：长边 ≤720，仅变换合成 + α 直贴（无阴影、无融合）。目标 ≤33ms/帧 → 拖动 ≥30fps。 */
    DRAG,

    /** 松手后 ≤300ms：长边 ≤720（低清快预览，8.4 的"松手重渲染"档），含颜色协调 + 阴影。 */
    SETTLE,

    /** 静止 1.2s 后或点「保存」前：长边 ≤1080，含融合，接近成品。目标 ≤1.5s。 */
    FINAL_PREVIEW,

    /** 导出：全分辨率 + 全链路。目标 < 5s（1080p，档 1/2）；4096 长边 < 12s。 */
    EXPORT,
    ;

    /** 是否执行方向性阴影合成。 */
    val includesShadow: Boolean get() = this != DRAG

    /** 是否执行颜色/亮度协调。 */
    val includesColorTransfer: Boolean get() = this != DRAG

    /** 是否执行融合（泊松/多频段）。 */
    val includesFusion: Boolean get() = this == FINAL_PREVIEW || this == EXPORT

    /** 工作副本长边上限。 */
    val maxShortSide: Int
        get() = when (this) {
            DRAG -> 720
            // 8.4：松手重渲染用 720p 低清快预览（1080p 只留给一键溶图/导出）
            SETTLE -> 720
            FINAL_PREVIEW -> 1080
            EXPORT -> Int.MAX_VALUE
        }
}

/**
 * 融合档位（架构文档 §7.2）。
 *
 * 三档的存在理由是 `seamlessClone` 的泊松求解在移动端远比直觉慢，必须能被降级。
 * 2026-10 起性能标准放宽（1080p < 5s、内存 < 600MB），因此**档位优先级整体上移**：
 *
 * - [FUSION_720]：**首选档（默认）**。720p 域无缝克隆，质量最好。中端机 1.5~3.0s，
 *   加上其余阶段总耗时约 3.5s，仍在 5s 验收线内，所以不再"只在实测够快时才升"。
 * - [FUSION_540]：**保底档**。540p 域克隆 + 上采样回贴，0.6~1.3s。
 *   档 1 实测超过 5s 时自动降到这一档。
 * - [LAPLACIAN]：**备选档**。纯拉普拉斯金字塔多频段融合，0.25~0.6s，低端机/超时自动切换。
 * - [TILED]：**全分辨率分块档（可选，高端机）**。不做全局降采样，把图切成 2048 的 tile
 *   （25% 重叠 + 汉宁窗加权合并），每块在自己的工作域上做泊松求解。
 *   质量上限最高（不做"降采样融合 + 上采样回贴"，因此没有上采样带来的过渡带发糊），
 *   代价是耗时随 tile 数线性增长 —— 所以**只作为用户显式选择**，永不进入自动升降档路径。
 * - [NONE]：**不融合**。仅用于 [RenderMode.DRAG] 的实时直贴路径。
 *   它存在的意义是：`RenderResult.actualTier` 必须如实反映"这次到底做了什么"，
 *   谎报成 LAPLACIAN 会让性能回归测试得出错误结论。
 *
 * @param shortSide 该档位的工作域短边（0 = 不使用降采样工作域，见各档说明）
 * @param label 面向用户的一行说明。**放在枚举里而不是散在 UI 层**，
 *   否则新增档位时 `when` 漏一个分支就只会显示空白，而编译不会报错。
 * @param shortLabel 选择器上的短标签（同样放在枚举里，理由同上）
 */
enum class FusionTier(val shortSide: Int, val label: String, val shortLabel: String) {
    FUSION_720(720, "720p 泊松融合（高质量）", "720p 高质量"),
    FUSION_540(540, "540p 泊松融合（验收线）", "540p 验收线"),
    LAPLACIAN(0, "多频段融合（保底）", "多频段保底"),
    TILED(0, "全分辨率分块融合", "全分辨率分块"),
    NONE(0, "直贴（无融合）", "直贴"),
    ;

    /** 是否为用户可显式选择的档位（[NONE] 只属于拖动预览的内部状态，不该出现在选择器里）。 */
    val userSelectable: Boolean get() = this != NONE
}
