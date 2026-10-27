package com.naiwa.core.common.model

import kotlin.math.max

/**
 * 深度暗部的"环境吸收色"（纯函数，可 JVM 单测）。
 *
 * ## 为什么不能直接拿背景最暗部的像素值
 * 背景最暗部的中位色通常是 sRGB `(26, 30, 48)` 这种**很暗**的值（线性 ≈ 0.012）。
 * 直接拿它做 `color * uDeepShadowColor`，暗部会被乘成纯黑 —— 等于"死黑一片"，
 * 正是这一轮要修的问题之一。
 *
 * 真正需要的是它的**色相**，以及一个**可控的暗度**：
 * ```
 * factor = normalize(chroma) × DEEP_LEVEL
 * ```
 * 这样深蓝背景得到 `(0.35, 0.42, 0.69)`，暗部被"沉进蓝调"而不是"沉进黑"；
 * 中性灰背景得到 `(0.42, 0.42, 0.42)`，只变暗不串色。
 *
 * ## 输出是"乘法因子"不是颜色
 * 调用方用 `uni3f` 直接传归一化值（0~1），**不要**过 `uniSrgbLinear` ——
 * 它在这里是乘数，不是 sRGB 颜色。搞错了会把 0.42 变成线性 0.14，暗部直接塌掉。
 */
object DeepShadowTint {

    /**
     * 环境吸收后的亮度级别（0~1）。
     *
     * 0.42 是实测的折中：再低，暗部色相被压没（看起来就是黑）；再高，
     * 暗部开始发灰、和中间调区分不开。
     */
    const val DEEP_LEVEL: Float = 0.42f

    /** 色相比的钳制范围：防止极端背景把某个通道放大到 >2 倍（会染色成荧光）。 */
    const val CHROMA_MIN: Float = 0.45f
    const val CHROMA_MAX: Float = 2.0f

    /**
     * @param darkArgb 背景最暗部的中位色（sRGB，来自 `ShadowTint`）
     * @return 逐通道乘法因子（长度 3，均在 0~1），可直接 `uni3f` 传进 shader
     */
    fun factor(darkArgb: Int): FloatArray {
        val r = ((darkArgb shr 16) and 0xFF) / 255f
        val g = ((darkArgb shr 8) and 0xFF) / 255f
        val b = (darkArgb and 0xFF) / 255f
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        // 输入是全黑（无暗部可提取）→ 用中性因子，绝不除零
        if (luma < 1e-3f) return floatArrayOf(DEEP_LEVEL, DEEP_LEVEL, DEEP_LEVEL)

        return floatArrayOf(
            (r / luma * DEEP_LEVEL).coerceIn(0f, 1f),
            (g / luma * DEEP_LEVEL).coerceIn(0f, 1f),
            (b / luma * DEEP_LEVEL).coerceIn(0f, 1f),
        )
    }

    /**
     * 三级高度衰减的目标亮度（规格 2：头 0.4 / 腰 0.2 / 脚 0.05）。
     *
     * @param y 归一化高度，**本工程 uv.y = 1 是画面底部**
     */
    fun heightTier(y: Float): Float {
        val t = y.coerceIn(0f, 1f)
        // 上 → 腰
        var v = lerp(0.40f, 0.20f, smoothstep(0.28f, 0.60f, t))
        // 腰 → 脚底
        v = lerp(v, 0.05f, smoothstep(0.78f, 0.96f, t))
        return v
    }

    /** 最终压暗因子：高度三级 × (0.3×基准暗度 + 0.7×环境吸收色)，逐通道。 */
    fun darkenFactor(
        y: Float,
        deep: FloatArray,
        baseDarken: Float = BacklitShade.DARKEN_BACK,
        absorbMix: Float = 0.7f,
    ): FloatArray {
        val tier = heightTier(y)
        val m = absorbMix.coerceIn(0f, 1f)
        val plain = baseDarken.coerceIn(0f, 1f)
        return floatArrayOf(
            tier * ((1f - m) * plain + m * deep[0].coerceIn(0f, 1f)),
            tier * ((1f - m) * plain + m * deep[1].coerceIn(0f, 1f)),
            tier * ((1f - m) * plain + m * deep[2].coerceIn(0f, 1f)),
        )
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / max(e1 - e0, 1e-6f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
