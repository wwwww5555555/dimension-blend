package com.naiwa.core.cv.color

/**
 * Reinhard 色调映射的**肩部版**（参考实现，与 `FRAG_FXAA` 中的 GLSL 逐字对应）。
 *
 * ## 为什么不是整幅 `c/(1+c)`
 * 整幅 Reinhard 会把中间调一起压灰：
 *
 * | 线性输入 | 整幅 Reinhard | 转 sRGB（原 → 压缩后） |
 * | --- | --- | --- |
 * | 0.05 | 0.048 | 0.26 → 0.25（几乎不变） |
 * | 0.36 | 0.264 | 0.63 → 0.55（明显变暗） |
 * | 0.69 | 0.408 | 0.85 → 0.67（**大幅变暗**） |
 *
 * 也就是说它并不只是"压高光"，而是把整个中高调往下拉 —— 观感是发闷、发灰。
 *
 * ## 肩部版
 * 膝点 `knee` 以下**严格恒等**，只对超出部分做 `x/(1+x)` 并放回剩余区间：
 *
 * ```
 * over     = max(c - knee, 0)
 * shoulder = (1 - knee) * over / (over + 1)
 * out      = c - over + shoulder
 * ```
 *
 * - `c ≤ knee`：`over = 0` → `out = c`（**零改动**，原图色彩分布完整保留）
 * - `c → ∞`：`out → knee + (1-knee) = 1.0`（渐近，永不裁切）
 * - `knee = 0`：`out = c/(1+c)`，即规格给的原式（特例）
 *
 * 数值示例（knee = 0.6）：
 * `0.50 → 0.500`、`0.787 → 0.663`、`1.20 → 0.750`、`2.00 → 0.833`、`∞ → 1.0`
 */
object ToneCurve {

    /** 默认膝点：线性 0.6 ≈ sRGB 0.81（只压真正接近过曝的部分）。 */
    const val DEFAULT_KNEE = 0.6f

    /** 单通道压缩。 */
    fun reinhardShoulder(x: Float, knee: Float = DEFAULT_KNEE): Float {
        val k = knee.coerceIn(0f, 0.95f)
        val v = x.coerceAtLeast(0f)
        val over = (v - k).coerceAtLeast(0f)
        if (over <= 0f) return v
        val shoulder = (1f - k) * (over / (over + 1f))
        return v - over + shoulder
    }

    /** 整幅 Reinhard（`knee = 0` 的特例，保留为显式入口便于对比测试）。 */
    fun reinhardFull(x: Float): Float {
        val v = x.coerceAtLeast(0f)
        return v / (v + 1f)
    }
}
