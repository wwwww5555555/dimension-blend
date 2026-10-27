package com.naiwa.core.common.model

/**
 * 3D LUT 选择规则（规格 8.1）—— 纯函数，可 JVM 单测。
 *
 * 根据光照估计的色温（[LightParams.colorTempK]）与主光强度（[LightParams.keyIntensity]）
 * 选择最接近的内置 LUT，或在两套 LUT 之间三线性插值。
 *
 * 四套内置 LUT 的命名与语义（对应 assets/luts/ 下的 .cube 文件）：
 *  - [WARM_INDOOR] 暖光室内（低色温）
 *  - [COOL_NIGHT]  冷光夜景（高色温）
 *  - [OVERCAST]    阴天户外（中性、低饱和）
 *  - [BACKLIT]     逆光场景（高对比、冷阴影）
 */
enum class LutPreset(val assetName: String, val label: String) {
    WARM_INDOOR("warm_indoor.cube", "暖光室内"),
    COOL_NIGHT("cool_night.cube", "冷光夜景"),
    OVERCAST("overcast.cube", "阴天户外"),
    BACKLIT("backlit.cube", "逆光场景"),
}

/**
 * LUT 选择结果：主 LUT + 可选的次 LUT 与插值权重。
 *
 * @param primary 主 LUT（必定命中）
 * @param secondary 次 LUT（仅在"两套之间插值"时非 null）
 * @param blend 主 LUT 的权重，[0,1]；次 LUT 权重 = 1 - blend
 */
data class LutSelection(
    val primary: LutPreset,
    val secondary: LutPreset? = null,
    val blend: Float = 1f,
) {
    /** 是否需要在两套 LUT 之间插值。 */
    val isBlend: Boolean get() = secondary != null && blend < 1f
}

object LutChoice {

    /** 色温分界：低于此值走暖光室内，高于走冷光夜景。 */
    const val WARM_KELVIN = 3500f

    /** 色温分界：低于此值仍是暖室内，高于进入"中性/阴天"区。 */
    const val NEUTRAL_KELVIN = 5500f

    /** 色温分界：高于此值判定为冷光夜景。 */
    const val COOL_KELVIN = 7500f

    /** 主光强度分界：keyIntensity 高 + 逆光（由外部传入）才走 backlit。 */
    const val KEY_INTENSITY_FOR_BACKLIT = 0.5f

    /** 插值过渡带宽度（色温 K）。在分界附近做平滑切换，避免 LUT 突然跳变。 */
    const val BLEND_BAND_KELVIN = 800f

    /**
     * 选择 LUT。
     *
     * @param colorTempK 场景色温
     * @param keyIntensity 主光强度
     * @param backlit 是否判定为逆光（由 [LightFxRules.isBacklit] 给出，避免两处各算一套）
     */
    fun select(colorTempK: Float, keyIntensity: Float, backlit: Boolean): LutSelection {
        // 逆光优先：主光够强且逆光时直接走 backlit
        if (backlit && keyIntensity > KEY_INTENSITY_FOR_BACKLIT) {
            return LutSelection(LutPreset.BACKLIT)
        }

        // 色温分档 + 过渡带插值
        return when {
            colorTempK <= WARM_KELVIN -> LutSelection(LutPreset.WARM_INDOOR)
            colorTempK >= COOL_KELVIN -> LutSelection(LutPreset.COOL_NIGHT)

            // 暖室内 ↔ 阴天 过渡带
            colorTempK <= NEUTRAL_KELVIN -> blendBetween(
                LutPreset.WARM_INDOOR, LutPreset.OVERCAST,
                (colorTempK - WARM_KELVIN) / (NEUTRAL_KELVIN - WARM_KELVIN),
            )

            // 阴天 ↔ 冷夜 过渡带
            else -> blendBetween(
                LutPreset.OVERCAST, LutPreset.COOL_NIGHT,
                (colorTempK - NEUTRAL_KELVIN) / (COOL_KELVIN - NEUTRAL_KELVIN),
            )
        }
    }

    /** 在 a、b 之间按 t∈[0,1] 插值：t=0 → a，t=1 → b。 */
    private fun blendBetween(a: LutPreset, b: LutPreset, t: Float): LutSelection {
        val k = t.coerceIn(0f, 1f)
        return if (k <= 0f) LutSelection(a) else if (k >= 1f) LutSelection(b) else {
            LutSelection(a, b, blend = 1f - k)
        }
    }
}
