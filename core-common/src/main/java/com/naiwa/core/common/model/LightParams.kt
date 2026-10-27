package com.naiwa.core.common.model

/**
 * 场景光照参数 —— 全项目共享的核心契约（v0.2）。
 *
 * 设计要点：
 *  1. 这是一个**纯数据契约**，不依赖 OpenCV / TFLite / Android UI，可独立单测。
 *  2. 所有字段都有物理含义与合法区间，[clamped] 保证越界值不会流到下游算法。
 *  3. [confidence] 是双实现的切换开关：模型估计低于 [TRUST_THRESHOLD] 时，
 *     管线必须改用传统 CV 兜底结果（见 docs/ARCHITECTURE.md §6.1/§6.2）。
 *
 * 角度约定（与图像坐标系一致，x 向右、y 向下）：
 *  keyAzimuthDeg = 0°   → 光源在画面正右方
 *  keyAzimuthDeg = 90°  → 光源在画面下方
 *  keyAzimuthDeg = 180° → 光源在画面正左方
 *  keyElevationDeg = 0° → 光与地面平齐（最平，阴影最长）
 *  keyElevationDeg = 90°→ 光从正上方来（阴影收缩到物体正下方）
 *
 * @param colorTempK      场景色温，开尔文。6500 为中性日光，数值越低越暖（烛光/白炽），越高越冷（阴天/蓝调）
 * @param tint            绿-品红偏移，[-1, 1]。负值偏绿（阴天/荧光灯常见），正值偏品红
 * @param exposureEv      相对正常曝光的档位数，[-3, 3]。每 +1 表示亮一倍
 * @param brightness      亮度增益，[0.5, 1.5]。可由 exposureEv 换算（2^EV 取部分权重），供 UI 直接使用
 * @param contrast        对比度系数，[0.5, 1.5]。1 = 不变
 * @param saturation      饱和度系数，[0.5, 1.5]。1 = 不变
 * @param keyIntensity    主光（方向光）占比，[0, 1]。0 = 纯环境光（无方向性），1 = 强方向光
 * @param ambientIntensity 环境光强度，[0, 1]。决定阴影区的亮度下限
 * @param shadowStrength  阴影浓度，[0, 1]。0 = 无阴影
 * @param shadowSoftness  阴影软硬，[0, 1]。0 = 硬边（小 σ），1 = 极柔（大 σ）
 * @param confidence      本次估计的可信度，[0, 1]。低于 [TRUST_THRESHOLD] 时应丢弃并改用兜底估计器
 */
data class LightParams(
    val colorTempK: Float = NEUTRAL_TEMP_K,
    val tint: Float = 0f,
    val exposureEv: Float = 0f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val keyAzimuthDeg: Float = NEUTRAL_AZIMUTH_DEG,
    val keyElevationDeg: Float = NEUTRAL_ELEVATION_DEG,
    val keyIntensity: Float = 0.5f,
    val ambientIntensity: Float = 0.5f,
    val shadowStrength: Float = 0.3f,
    val shadowSoftness: Float = 0.5f,
    val confidence: Float = 0f,
) {

    /** 把全部字段钳制回合法区间；角度归一化到 [0, 360)。 */
    fun clamped(): LightParams = copy(
        colorTempK = colorTempK.coerceIn(MIN_TEMP_K, MAX_TEMP_K),
        tint = tint.coerceIn(-1f, 1f),
        exposureEv = exposureEv.coerceIn(-3f, 3f),
        brightness = brightness.coerceIn(0.5f, 1.5f),
        contrast = contrast.coerceIn(0.5f, 1.5f),
        saturation = saturation.coerceIn(0.5f, 1.5f),
        keyAzimuthDeg = normalizeDegrees(keyAzimuthDeg),
        keyElevationDeg = keyElevationDeg.coerceIn(0f, 90f),
        keyIntensity = keyIntensity.coerceIn(0f, 1f),
        ambientIntensity = ambientIntensity.coerceIn(0f, 1f),
        shadowStrength = shadowStrength.coerceIn(0f, 1f),
        shadowSoftness = shadowSoftness.coerceIn(0f, 1f),
        confidence = confidence.coerceIn(0f, 1f),
    )

    /** 模型估计是否可信。不可信时调用方必须改用 [NEUTRAL] 或传统 CV 估计结果。 */
    val isTrustworthy: Boolean get() = confidence >= TRUST_THRESHOLD

    /** 施加手动调节（P3 滑杆）。未设置的项保持自动估计值不变。 */
    fun withOverrides(overrides: LightOverrides): LightParams = overrides.applyTo(this)

    companion object {
        /** 可信度阈值：低于该值视为估计失败，走兜底路径。 */
        const val TRUST_THRESHOLD = 0.45f

        /** 兜底参数：色温 6500K / EV 0 / 方位角 135° / 高度角 45°（见架构文档 §6.2）。 */
        const val NEUTRAL_TEMP_K = 6500f
        const val NEUTRAL_AZIMUTH_DEG = 135f
        const val NEUTRAL_ELEVATION_DEG = 45f

        const val MIN_TEMP_K = 2000f
        const val MAX_TEMP_K = 12000f

        /** 光照估计彻底失败时返回的中性参数，保证主流程永远能出图。 */
        val NEUTRAL = LightParams(
            colorTempK = NEUTRAL_TEMP_K,
            keyAzimuthDeg = NEUTRAL_AZIMUTH_DEG,
            keyElevationDeg = NEUTRAL_ELEVATION_DEG,
            confidence = 0f,
        )

        /** 任意角度归一化到 [0, 360)。 */
        fun normalizeDegrees(deg: Float): Float {
            val m = deg % 360f
            return if (m < 0f) m + 360f else m
        }
    }
}
