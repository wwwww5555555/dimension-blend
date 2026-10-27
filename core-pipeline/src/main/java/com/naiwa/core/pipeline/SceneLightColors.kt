package com.naiwa.core.pipeline

/**
 * 光包裹的冷暖双色提取（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * ## 两个颜色分别代表什么
 * - **主光色**（key）：亮度最高的约 5% 像素均值 —— 光源直射进画面的那部分。
 *   夕阳偏暖黄，正午偏暖白。
 * - **环境色**（ambient）：亮度低于中位的像素均值 —— 背光面反射的是**环境漫反射**，
 *   天空/阴影主导，通常偏冷（深蓝、青灰）。
 *
 * 两者在 P2-w 里按 `N·L` 插值，形成 Teal & Orange 的互补边缘光。
 *
 * ## 为什么不直接用 p95 / p50 阈值
 * 边界上会有大量同亮度像素。若画面正好 5% 是亮部、其余是中调，升序累计到 95% 分位时
 * `luma >= p95` 会把整块中调也算进"主光"，颜色立刻被稀释成灰。
 * 这里改为**按数量从亮端/暗端累计**找截断亮度：
 *  - 主光：从 255 往下累加到 5% 处的亮度，取 `luma >= cut`（同亮度的像素同色，多算不影响色调）；
 *  - 环境：从 0 往上累加到 50% 处的亮度，取 `luma < cut`（**严格小于**，把中调排除在外）。
 *
 * 输入是长边 480 的缩略图，整段 < 1ms。
 */
object SceneLightColors {

    /** 主光色兜底：中性暖白（背景纯色/过曝时用）。 */
    const val KEY_NEUTRAL: Int = 0xFFFFE3C0.toInt()

    /** 环境色兜底：中性冷灰（背景没有暗部时用）。 */
    const val AMBIENT_NEUTRAL: Int = 0xFF9AA6B4.toInt()

    /** 提取结果（ARGB，alpha 恒为 FF）。 */
    data class Colors(val key: Int, val ambient: Int)

    /**
     * @param px ARGB 像素数组（**sRGB**，不是线性）
     * @return 主光色与环境色；空数组返回中性兜底
     */
    fun extract(px: IntArray): Colors {
        if (px.isEmpty()) return Colors(KEY_NEUTRAL, AMBIENT_NEUTRAL)

        val hist = IntArray(256)
        for (c in px) hist[luma8(c)]++

        val n = px.size
        val kKey = Math.max(1, Math.round(n * 0.05).toInt())
        val kAmb = Math.max(1, Math.round(n * 0.50).toInt())
        val cutKey = cutFromTop(hist, kKey)
        val cutAmb = cutFromBottom(hist, kAmb)

        var kr = 0L; var kg = 0L; var kb = 0L; var kn = 0
        var ar = 0L; var ag = 0L; var ab = 0L; var an = 0
        for (c in px) {
            val l = luma8(c)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (l >= cutKey) { kr += r; kg += g; kb += b; kn++ }
            if (l < cutAmb) { ar += r; ag += g; ab += b; an++ }
        }

        // 极端情况（整张图同一亮度）：严格小于取不到像素，退回"包含截断亮度"
        if (an == 0) {
            for (c in px) {
                if (luma8(c) <= cutAmb) {
                    ar += (c shr 16) and 0xFF
                    ag += (c shr 8) and 0xFF
                    ab += c and 0xFF
                    an++
                }
            }
        }

        val key = if (kn > 0) pack(kr / kn, kg / kn, kb / kn) else KEY_NEUTRAL
        val ambient = if (an > 0) pack(ar / an, ag / an, ab / an) else AMBIENT_NEUTRAL
        return Colors(key, ambient)
    }

    private fun cutFromTop(hist: IntArray, k: Int): Int {
        var acc = 0
        for (v in 255 downTo 0) {
            acc += hist[v]
            if (acc >= k) return v
        }
        return 0
    }

    private fun cutFromBottom(hist: IntArray, k: Int): Int {
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= k) return v
        }
        return 255
    }

    private fun luma8(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
    }

    private fun pack(r: Long, g: Long, b: Long): Int =
        (0xFF shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
}
