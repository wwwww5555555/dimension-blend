package com.naiwa.core.common.model

/**
 * 多任务光影估计结果（规格：从"单纯调色"升级为"物理光影参数"）。
 *
 * 【数据来源优先级】轻量 AI 多任务模型（若权重到位）→ 传统 CV 兜底。
 * 两者都产出本结构，管线只消费本结构，因此**模型缺失时功能自动降级但不会缺失字段**。
 *
 * @param lightDirX 主光方向向量 X（图像坐标右为正，单位向量）
 * @param lightDirY 主光方向向量 Y（图像坐标下为正）
 * @param lightDirZ 主光方向向量 Z（法向朝镜头为正；2D 图不可恢复，按高度角换算）
 * @param lightIntensity 主光强度 0~1（模型 sigmoid 输出 / CV 由对比度估计）
 * @param glowStrength 辉光强度 0~1（模型 sigmoid 输出 / CV 按高光占比估计）
 * @param wrapStrength 光包裹强度 0~1（模型 sigmoid 输出 / CV 按背景亮度估计）
 * @param bgHighlightColor 背景亮度前 5% 像素的中位色（光包裹的颜色来源）
 * @param bgBlurSigma 背景模糊 σ（由拉普拉斯方差动态计算：细节越多 σ 越大）
 * @param sh9 二阶球谐系数（9 个，RGB 各 3 组时只取亮度组；无模型时为 null）
 * @param colorMatrix 3×3 颜色变换（行优先，9 个；null = 不做矩阵调色）
 */
data class LightingEstimate(
    val lightDirX: Float,
    val lightDirY: Float,
    val lightDirZ: Float,
    val lightIntensity: Float,
    val glowStrength: Float,
    val wrapStrength: Float,
    val bgHighlightColor: Int,
    val bgBlurSigma: Float,
    /** 主光源中心（归一化 0~1，画面坐标；由 NMS 找最亮大面积区域得到）。 */
    val lightX: Float = 0.5f,
    val lightY: Float = 0.3f,
    /** 是否强逆光（背景高光区显著亮于人物内部平均亮度）。 */
    val backlit: Boolean = false,
    val sh9: FloatArray? = null,
    val colorMatrix: FloatArray? = null,
) {
    /** 方位角（度），供诊断与旧参数兼容。 */
    val azimuthDeg: Float
        get() = ((Math.toDegrees(kotlin.math.atan2(lightDirY, lightDirX).toDouble()) + 360.0) % 360.0).toFloat()

    /** 高度角（度）：由 z 分量反算。 */
    val elevationDeg: Float
        get() = Math.toDegrees(kotlin.math.asin(lightDirZ.coerceIn(-1f, 1f).toDouble()).coerceIn(-1.0, 1.0)).toFloat()

    /** 兜底：中性参数（无方向性、中等强度）。 */
    companion object {
        val NEUTRAL = LightingEstimate(
            lightDirX = 0f,
            lightDirY = -1f,   // 顶光（图像 y 向下，向上为 -y）
            lightDirZ = 0.6f,
            lightIntensity = 0.6f,
            glowStrength = 0.2f,
            wrapStrength = 0.3f,
            bgHighlightColor = 0xFFF2F2F2.toInt(),
            bgBlurSigma = 22f,
        )
    }
}
