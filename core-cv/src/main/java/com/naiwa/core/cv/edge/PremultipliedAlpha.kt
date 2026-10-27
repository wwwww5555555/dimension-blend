package com.naiwa.core.cv.edge

import kotlin.math.min

/**
 * **6.1 预乘 Alpha 检查与反预乘**（白边的根本原因之一）。
 *
 * ## 为什么必须做
 * Android 的 `BitmapFactory` 默认按 **premultiplied** 解码（`inPremultiplied = true`），
 * 于是 `Bitmap.getPixels()` 拿到的 RGB **已经被 alpha 乘过**。如果不还原就拿去做
 * Lab 统计、颜色迁移、去白，会出现两件事：
 *  1. 半透明边缘的 RGB 被 alpha 压暗 → 颜色统计被拉向黑，边缘得不到正确的目标色；
 *  2. 处理完再写回一个同样按预乘解释的位图 → **Canvas 合成时会再乘一次 alpha**，
 *     边缘进一步变暗/发灰。
 *
 * 反过来，如果素材本身是**非预乘**的（RGB 是"本真色"，常见于手工抠图导出的 PNG），
 * 却按预乘去反解，会把边缘 RGB 反向放大到过亮 —— 白边就是这么来的。
 * **所以第一步是"判断"，不是"处理"。**
 *
 * ## 判定方法
 * 预乘格式有一条硬性不变式：**每个通道都必须 ≤ alpha**（因为 RGB = 本真色 × alpha/255）。
 * 于是统计"半透明像素里有几个通道大于 alpha"的违规比例即可 —— 违规比例低就是预乘。
 * 这个方法不依赖文件元数据，对任意来源的位图都成立。
 *
 * 容差 [VIOLATION_TOLERANCE] 用来吸收取整误差：预乘时 `R·A/255` 可能比 A 大 1~2。
 */
object PremultipliedAlpha {

    /** 判定结果。 */
    data class Assessment(
        /** 是否判定为预乘格式。 */
        val premultiplied: Boolean,
        /** 违规比例（通道值 > alpha 的半透明像素占比）。 */
        val violatingRatio: Float,
        /** 参与判定的半透明像素数。为 0 表示无法判定（通常是全不透明图）。 */
        val sampled: Int,
    )

    /** 通道允许超过 alpha 的额度，用于吸收取整误差。 */
    private const val VIOLATION_TOLERANCE = 2

    /** 违规比例低于此值即认定预乘。 */
    private const val PREMULTIPLIED_MAX_VIOLATION_RATIO = 0.02f

    /** 判定所需的最少半透明像素数（低于此数无法可靠判断，返回"非预乘"＝不动）。 */
    private const val MIN_SAMPLES = 32

    /**
     * 判断像素数组是否为预乘格式。
     *
     * @param alphaLow alpha 低于此值的像素被忽略（太暗，RGB 已无可信信息）
     * @param alphaHigh alpha 高于此值的像素被忽略（接近不透明，两种格式几乎无差）
     */
    fun assess(
        pixels: IntArray,
        alphaLow: Int = 6,
        alphaHigh: Int = 250,
    ): Assessment {
        var sampled = 0
        var violating = 0
        for (c in pixels) {
            val a = (c ushr 24) and 0xFF
            if (a <= alphaLow || a >= alphaHigh) continue
            sampled++
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > a + VIOLATION_TOLERANCE || g > a + VIOLATION_TOLERANCE || b > a + VIOLATION_TOLERANCE) {
                violating++
            }
        }
        if (sampled < MIN_SAMPLES) {
            // 采样不足（例如整图不透明）时**不做任何改动**：宁可不动，也不要凭猜测反转
            return Assessment(premultiplied = false, violatingRatio = 0f, sampled = sampled)
        }
        val ratio = violating.toFloat() / sampled
        return Assessment(
            premultiplied = ratio <= PREMULTIPLIED_MAX_VIOLATION_RATIO,
            violatingRatio = ratio,
            sampled = sampled,
        )
    }

    /**
     * 反预乘：`本真色 = 通道 × 255 / alpha`（**四舍五入**，不是截断）。
     *
     * 用截断会让误差上限从 `255/(2·alpha)` 翻倍到 `255/alpha` —— 在 alpha=16 时就是
     * "误差 13 而不是 6"，对本来就最脆弱的低 alpha 边缘是实打实的质量损失。
     *
     * 结果会被钳制到 255 —— 若输入其实已是非预乘，这一步会把亮部放大并钳制，
     * 这正是必须先 [assess] 再决定要不要调用的原因。
     * 完全透明的像素 RGB 置 0（不可见，且回到预乘时必须是 0）。
     */
    fun unpremultiply(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            when (a) {
                0 -> out[i] = 0
                255 -> out[i] = c
                else -> {
                    val half = a / 2
                    val r = min(255, (((c shr 16) and 0xFF) * 255 + half) / a)
                    val g = min(255, (((c shr 8) and 0xFF) * 255 + half) / a)
                    val b = min(255, ((c and 0xFF) * 255 + half) / a)
                    out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return out
    }

    /**
     * 重新预乘：`通道 = 本真色 × alpha / 255`（四舍五入）。
     *
     * **必须在把像素交还给 Android 之前调用**：`Bitmap.createBitmap(...ARGB_8888)` + `setPixels`
     * 的位图默认按预乘解释，而 `Canvas.drawBitmap` 合成时也会再乘一次 alpha。
     * 内部处理用非预乘、交付用预乘，这条边界必须守住。
     */
    fun premultiply(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            when (a) {
                0 -> out[i] = 0
                255 -> out[i] = c
                else -> {
                    val r = (((c shr 16) and 0xFF) * a + 127) / 255
                    val g = (((c shr 8) and 0xFF) * a + 127) / 255
                    val b = ((c and 0xFF) * a + 127) / 255
                    out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return out
    }
}
