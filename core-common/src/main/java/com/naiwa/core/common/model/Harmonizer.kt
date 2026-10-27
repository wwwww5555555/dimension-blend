package com.naiwa.core.common.model

/**
 * AI 和谐化契约（规格 8.2 修订）—— 三轨制的统一接口。
 *
 * 三个实现，按设备能力 + 超时降级：
 *  1. [HarmonizerBackend.MKL] —— 纯 Kotlin 闭式解（最优传输 MKL 滤波，12 参数仿射变换），
 *     **无需训练权重、永不失败**，是所有设备的兜底。
 *  2. [HarmonizerBackend.PCT_NET] —— PCT-Net（CVPR 2023，GitHub: rakutentech/PCT-Net-Image-Harmonization），
 *     低分辨率预测逐像素颜色变换参数、应用到全分辨率前景，计算量小。权重免申请直接下载。
 *     仅高端机（RAM ≥ 8GB 且核数 ≥ 8）启用，8s 超时回退 MKL。
 *
 * 契约：**不抛异常**。任何失败（模型缺失/加载失败/超时）都返回 null，
 * 由调用方回退到 MKL 或直接跳过（仅传统颜色迁移）。
 */
enum class HarmonizerBackend {
    /** 纯 Kotlin 最优传输 MKL 滤波（闭式解，无需权重）。 */
    MKL,

    /** PCT-Net（CVPR 2023，高端机可选，权重免申请，8s 超时回退）。 */
    PCT_NET,
}

/**
 * 和谐化结果。
 *
 * @param pixels 和谐化后的前景 RGB（alpha 由调用方保留）；长度 = width × height
 * @param backend 实际生效的后端（如实回报，可能因降级与请求不同）
 * @param params 生效的 12 维变换参数（A 9 个 + S 3 个）；PCT_NET 为 null（它输出像素而非闭式参数）
 */
data class HarmonizeResult(
    val pixels: IntArray,
    val backend: HarmonizerBackend,
    val params: FloatArray?,
)

/**
 * 和谐化输入。
 *
 * @param foregroundPixels 奶蛙前景 RGB（已做颜色协调）
 * @param backgroundPixels 场景背景（用于统计/条件）
 * @param width / height 图像尺寸（前景与背景同尺寸）
 * @param mask 前景掩膜（0/255），限定作用域
 */
data class HarmonizeRequest(
    val foregroundPixels: IntArray,
    val backgroundPixels: IntArray,
    val width: Int,
    val height: Int,
    val mask: IntArray,
)
