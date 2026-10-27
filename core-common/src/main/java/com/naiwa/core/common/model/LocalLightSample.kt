package com.naiwa.core.common.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 光源**邻域**取色（纯函数，可 JVM 单测）。
 *
 * ## 为什么不能用全图平均色
 * 光包裹/边缘光取的是"当前主光源附近的颜色"，不是整张背景的均值：
 * - 夕阳在西边、东边是蓝天 → 全图均值是一坨灰粉，光在左边时应该是云的红橙；
 * - 舞台一侧有暖光筒、另一侧有冷月光 → 均值直接把冷暖抵消掉。
 *
 * 所以必须**以光源坐标为圆心、取一个小半径内的均值**（默认长边的 5%，1080p 下约 54px）。
 * 半径小是刻意的：光源核心往往是过曝的白，取太大反而稀释掉周围那圈"有颜色的光晕"。
 *
 * ## 两个实现细节
 * 1. **按亮度加权**：光源邻域里既有过曝核心（近白）也有色温明显的边缘，
 *    简单平均会把它们混成中间灰。用 `luma²` 加权会让亮的那一圈更主导，
 *    但又不是全取白点 —— 实测比算术均值更接近人眼看到的"光色"。
 * 2. **跳过极暗像素**：光源邻域可能横跨进阴影（比如光源在窗边），
 *    那些 8 以下的像素加进来会把颜色压脏。
 *
 * 输入是长边 480 的缩略图，整段 O(πr²) ≈ 8k 次，< 0.3ms。
 */
object LocalLightSample {

    /** 取样半径（相对图像长边）。5% 在 1080p 下约 54px，正好覆盖光晕的彩色部分。 */
    const val RADIUS_RATIO: Float = 0.05f

    /** 最小半径（像素）：缩略图上不能小到只剩几个像素。 */
    const val MIN_RADIUS: Int = 3

    /** 低于这个亮度（0~255）的像素不参与统计。 */
    const val MIN_LUMA: Int = 8


    /**
     * @param px      ARGB 像素（sRGB）
     * @param w       宽
     * @param h       高
     * @param lightX  光源中心 X（归一化 0~1）
     * @param lightY  光源中心 Y（归一化 0~1）
     * @param radiusPx 半径（像素）；≤0 时按 [RADIUS_RATIO] 自动算
     * @return 加权均值色（ARGB）；样本不足时返回 **0**（调用方必须回退到全局高光色）
     */
    fun extract(
        px: IntArray,
        w: Int,
        h: Int,
        lightX: Float,
        lightY: Float,
        radiusPx: Int = 0,
    ): Int {
        if (px.isEmpty() || w <= 0 || h <= 0) return 0
        if (px.size < w * h) return 0
        if (!lightX.isFinite() || !lightY.isFinite()) return 0

        val r = if (radiusPx > 0) radiusPx else max(MIN_RADIUS, (max(w, h) * RADIUS_RATIO).roundToInt())
        val cx = (lightX.coerceIn(0f, 1f) * (w - 1)).roundToInt()
        val cy = (lightY.coerceIn(0f, 1f) * (h - 1)).roundToInt()

        val x0 = max(0, cx - r)
        val x1 = min(w - 1, cx + r)
        val y0 = max(0, cy - r)
        val y1 = min(h - 1, cy + r)
        val r2 = r * r

        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var sw = 0.0
        var n = 0

        for (y in y0..y1) {
            val dy = y - cy
            val row = y * w
            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy * dy > r2) continue
                val c = px[row + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val luma = (0.2126f * cr + 0.7152f * cg + 0.0722f * cb)
                if (luma < MIN_LUMA) continue
                // luma² 加权：亮的一圈主导，但不像"只取最亮"那样退化成纯白
                val weight = (luma / 255.0) * (luma / 255.0)
                sr += cr * weight
                sg += cg * weight
                sb += cb * weight
                sw += weight
                n++
            }
        }

        // 样本太少（< 12 个像素）时不可信，交给调用方回退
        if (n < 12 || sw < 1e-6) return 0
        return pack((sr / sw), (sg / sw), (sb / sw))
    }

    private fun pack(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)
}
