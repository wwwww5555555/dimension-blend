package com.naiwa.core.common.model

/**
 * 手动调节覆盖值（P3 滑杆面板使用）。
 *
 * 与 [LightParams] 分开存放的原因：
 *  - `LightParams` 是**自动估计结果**，`LightOverrides` 是**用户意图**，两者语义不同；
 *  - 所有字段可空，`null` 表示「该项不覆盖，沿用自动估计」；
 *  - 因此「重置为自动」= 把本对象整体置为 [DEFAULT]，不会丢失原始估计值。
 *
 * @param lightIntensity   主光强度**倍率**，[0, 2]，作用于 [LightParams.keyIntensity]
 * @param azimuthDeg       阴影/主光方位角覆盖值，[0, 360)
 * @param elevationDeg     主光高度角覆盖值，[0, 90]
 * @param colorTempK       色温覆盖值，[2000, 12000]。必须走 core-cv 的 KelvinRgb 施加
 * @param fusionStrength   颜色迁移力度，[0, 1]。0 = 完全不迁移，1 = 完整 Reinhard 迁移
 * @param featherScale     边缘羽化倍率，[0.5, 2]，作用于 feather_px 基准值
 * @param shadowStrength   阴影浓度覆盖值，[0, 1]
 * @param shadowSoftness   阴影软硬覆盖值，[0, 1]
 * @param rimStrength      边缘光强度，[0, 0.5]（规格 7.1）。**0 = 不执行该步骤**，省一次像素循环
 * @param glowStrength     辉光强度，[0, 0.3]（规格 7.2）。0 = 关闭；预览阶段即使非 0 也不执行
 * @param lightWrapStrength 四色渐变环境光强度，[0, 0.4]（规格 7.4）。0 = 关闭
 */
data class LightOverrides(
    val lightIntensity: Float? = null,
    val azimuthDeg: Float? = null,
    val elevationDeg: Float? = null,
    val colorTempK: Float? = null,
    val fusionStrength: Float? = null,
    val featherScale: Float? = null,
    val shadowStrength: Float? = null,
    val shadowSoftness: Float? = null,
    val rimStrength: Float? = null,
    val glowStrength: Float? = null,
    val lightWrapStrength: Float? = null,
) {

    /** 是否处于「全自动」状态（没有任何手动覆盖）。 */
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * 把手动覆盖施加到自动估计结果上。
     * 注意 [lightIntensity] 是**乘性**的（相对主光强度做缩放），其余为**替换**。
     */
    fun applyTo(base: LightParams): LightParams = base.copy(
        keyAzimuthDeg = azimuthDeg?.let { LightParams.normalizeDegrees(it) } ?: base.keyAzimuthDeg,
        keyElevationDeg = elevationDeg?.coerceIn(0f, 90f) ?: base.keyElevationDeg,
        colorTempK = colorTempK?.coerceIn(LightParams.MIN_TEMP_K, LightParams.MAX_TEMP_K) ?: base.colorTempK,
        keyIntensity = lightIntensity?.let { (base.keyIntensity * it).coerceIn(0f, 1f) } ?: base.keyIntensity,
        shadowStrength = shadowStrength?.coerceIn(0f, 1f) ?: base.shadowStrength,
        shadowSoftness = shadowSoftness?.coerceIn(0f, 1f) ?: base.shadowSoftness,
    ).clamped()

    /** 颜色迁移力度（默认 0.8：既明显协调，又不把奶蛙的材质特征抹平）。 */
    val effectiveFusionStrength: Float get() = (fusionStrength ?: DEFAULT_FUSION_STRENGTH).coerceIn(0f, 1f)

    /** 边缘羽化倍率（默认 1.0，即使用架构文档 §6.6 算出的基准 feather）。 */
    val effectiveFeatherScale: Float get() = (featherScale ?: 1f).coerceIn(0.5f, 2f)

    /**
     * 三个光效滑杆的**生效值**。
     *
     * 与 [rimStrength] 等的区别：那些是"用户是否覆盖"，这些是"最终跑多少"。
     * 之所以不在这里就做自动兜底，是因为自动值依赖逆光判定（[LightFxRules]），
     * 而判定需要 [LightParams]。这里只负责把用户值钳到合法区间，自动值由规则层填。
     */
    val effectiveRimStrength: Float? get() = rimStrength?.coerceIn(0f, LightFxRules.RIM_MAX)
    val effectiveGlowStrength: Float? get() = glowStrength?.coerceIn(0f, LightFxRules.GLOW_MAX)
    val effectiveLightWrapStrength: Float? get() = lightWrapStrength?.coerceIn(0f, LightFxRules.WRAP_MAX)

    companion object {
        const val DEFAULT_FUSION_STRENGTH = 0.8f

        /** 全自动状态。 */
        val DEFAULT = LightOverrides()
    }
}
