package com.naiwa.core.common.model

import android.graphics.Bitmap

/**
 * 渲染结果。
 *
 * 除了成品位图，还带上**诊断信息**，因为性能是验收项（1080p < 3s）：
 *  - [stageTimings]：分阶段耗时，出问题时能直接看出是哪一步炸了，不用靠猜；
 *  - [actualTier]：实际使用的融合档位，可能因设备能力/超时与请求档位不同；
 *  - [lightSource]：本次光照参数来自模型还是兜底，方便排查"为什么这次效果不对"。
 *
 * @param bitmap 成品位图（sRGB）
 * @param actualTier 实际使用的融合档位
 * @param lightSource 光照参数来源
 * @param stageTimings 各阶段耗时（毫秒）。未执行的阶段不会出现在 map 里
 * @param backend 实际使用的融合后端（双轨制）。可能因设备能力被否决而与请求值不同
 * @param fxPlan 本次实际执行的高级光效计划（逆光与否 + 三项光效强度）。null = 该次渲染未走光效
 *   （例如 DRAG 拖动直贴、或光效全 0）。用于把"这张图加了哪些光效"如实告诉用户
 */
data class RenderResult(
    val bitmap: Bitmap,
    val actualTier: FusionTier,
    val lightSource: LightSource,
    val stageTimings: Map<Stage, Long>,
    val backend: FusionBackend = FusionBackend.DEFAULT,
    val fxPlan: LightFxPlan? = null,
) {

    /** 全流程总耗时（毫秒）。验收断言直接用它。 */
    val totalMs: Long get() = stageTimings.values.sum()

    /** 管线执行阶段，按实际执行顺序排列。 */
    enum class Stage {
        /** 解码 + EXIF 方向校正 + sRGB 转换 */
        DECODE,

        /** 场景光照估计 */
        LIGHT_ESTIMATE,

        /** 奶蛙预处理（缩放、alpha 归一、掩膜准备） */
        FROG_PREP,

        /** 颜色/亮度协调（Reinhard + 分位数亮度匹配） */
        COLOR_TRANSFER,

        /** 方向性阴影合成 */
        SHADOW,

        /** 融合（泊松 / 拉普拉斯） */
        FUSION,

        /** 边缘三段式（腐蚀/羽化/α 加权回贴） */
        EDGE,

        /** JPEG 编码 + 写入相册 */
        ENCODE,
    }

    /** 光照参数的来源。 */
    enum class LightSource {
        /** 端侧模型回归结果（置信度达标） */
        MODEL,

        /** 传统 CV 兜底（纯 Kotlin，永不失败） */
        CV_FALLBACK,

        /** 中性兜底参数（估计彻底失败，出图但效果一般） */
        NEUTRAL_FALLBACK,

        /** 用户手动覆盖为主 */
        MANUAL,
    }
}
