package com.naiwa.core.common.model

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 边缘发光源的颜色整形（纯函数，无 Android/GL 依赖，可 JVM 单测）。
 *
 * ## 为什么需要它
 * 规格 1 明确要求「**不要用纯白**」，但真实场景里主光色经常就是接近纯白：
 * 正午阴天的天空、白墙、雪地、过曝的窗户 —— 从"背景最亮 5%"统计出来的均值会是
 * `(252, 250, 247)` 这种几乎无彩度的值。直接拿它当发光色，渲染出来仍然是
 * **一圈白色描边**，与"物理光源"毫无关系。
 *
 * 这个函数做两件事，都在 sRGB 8bit 空间、只改颜色不改亮度级别：
 *
 * 1. **保色相压亮**：把最亮通道压到 [MAX_CHANNEL]（235）以下 —— 物理上光源核心
 *    可以到 1.0，但**边缘光**是散射产物，永远达不到光源本身的白点。压亮本身也
 *    顺手保证了「绝不会输出纯白」。
 * 2. **补色度**：若 chroma（max-min）低于 [MIN_CHROMA]，沿**环境色的色相方向**
 *    把彩度补回来（环境色也无彩度时退回默认暖调）。暖夕阳 → 橙偏红，冷夜景 →
 *    青偏蓝，永远跟着场景走，而不是硬编码一个暖色。
 *
 * ## 为什么不在 shader 里做
 * 这段逻辑有分支、有归一化、有两次钳制，放进每个像素的 fragment shader 是纯浪费
 * （2048² 要跑 400 万次），而且**没法单测**。颜色是每帧一个的标量，CPU 上算一次
 * 传 uniform 才是正确位置。
 */
object EmissiveColor {

    /** 最亮通道上限（sRGB 8bit）。235/255 ≈ 0.92，压掉 8% 就是为了不出现纯白。 */
    const val MAX_CHANNEL: Int = 235

    /** 最低彩度（max-min，sRGB 8bit）。低于它就被认为"白得没有色相"。 */
    const val MIN_CHROMA: Int = 20

    /** 环境色本身也几乎无彩度时的兜底暖调方向（夕阳橙，不是纯黄的 1,0.78,0.52）。 */
    private val DEFAULT_WARM = floatArrayOf(1.00f, 0.78f, 0.52f)

    /**
     * @param keyArgb     主光色（背景亮度前 5% 均值，sRGB）
     * @param ambientArgb 环境色（背景暗部均值，sRGB）—— 只借用它的**色相方向**
     * @return 可直接送进 shader 的发光色（ARGB，alpha 恒为 FF）
     */
    fun sanitize(keyArgb: Int, ambientArgb: Int): Int {
        var r = (keyArgb shr 16) and 0xFF
        var g = (keyArgb shr 8) and 0xFF
        var b = keyArgb and 0xFF

        // ---- ① 先压亮：保证"绝不纯白"，同时给补色度留出余量 ----
        val mx = maxOf(r, g, b)
        if (mx > MAX_CHANNEL) {
            val k = MAX_CHANNEL.toFloat() / mx
            r = (r * k).roundToInt()
            g = (g * k).roundToInt()
            b = (b * k).roundToInt()
        }

        // ---- ② 补色度：沿环境色色相方向（无彩度则用默认暖调）----
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        if (chroma < MIN_CHROMA) {
            val dir = hueDirection(ambientArgb)
            val need = (MIN_CHROMA - chroma).toFloat()
            // 补满差量（不乘 0.5）：紧跟着的"压亮"会把补偿量等比缩小 ~8%，
            // 若这里只补一半，压完就又不达标了（实测 20 → 14，正是漏掉这一步的表现）。
            r = (r + dir[0] * need).roundToInt().coerceIn(0, 255)
            g = (g + dir[1] * need).roundToInt().coerceIn(0, 255)
            b = (b + dir[2] * need).roundToInt().coerceIn(0, 255)

            // 补色度可能把某通道顶过上限 → 再压一次（等比，色相不变）
            val mx2 = maxOf(r, g, b)
            if (mx2 > MAX_CHANNEL) {
                val k = MAX_CHANNEL.toFloat() / mx2
                r = (r * k).roundToInt()
                g = (g * k).roundToInt()
                b = (b * k).roundToInt()
            }
        }

        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    /**
     * 取颜色的**色相方向**：减去自身均值后归一化。
     *
     * 为什么不用 HSV 的 hue 角再转回来：这一段只关心"往哪个方向偏"，RGB 空间的
     * 减均值向量就是最直接的答案，且对灰轴完全退化（长度 0）→ 自然会落到兜底暖调。
     */
    fun hueDirection(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val m = (r + g + b) / 3f
        val dr = r - m
        val dg = g - m
        val db = b - m
        val len = sqrt(dr * dr + dg * dg + db * db)
        // 彩度低于 8/255 视为灰轴：方向不可信，退回默认暖调
        if (len < 8f || abs(len) < 1e-4f) {
            // ⚠️ 必须减 DEFAULT_WARM 自身均值（0.7667），不能硬编码 0.85 ——
            //    后者会让 R 分量正好变成 0、B 变成最大，把"暖调兜底"变成冷调。
            val wm = (DEFAULT_WARM[0] + DEFAULT_WARM[1] + DEFAULT_WARM[2]) / 3f
            val wr = DEFAULT_WARM[0] - wm
            val wg = DEFAULT_WARM[1] - wm
            val wb = DEFAULT_WARM[2] - wm
            val wl = sqrt(wr * wr + wg * wg + wb * wb)
            return floatArrayOf(wr / wl, wg / wl, wb / wl)
        }
        return floatArrayOf(dr / len, dg / len, db / len)
    }
}
