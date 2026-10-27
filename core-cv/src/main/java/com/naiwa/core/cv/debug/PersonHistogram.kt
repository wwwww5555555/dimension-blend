package com.naiwa.core.cv.debug

import android.graphics.Bitmap
import android.util.Log
import com.naiwa.core.common.debug.DebugReport

/**
 * 人物区域直方图（调试度量）。
 *
 * 【为什么需要它】
 * “人物泛白”和“内部发平”这两个症状靠肉眼很难量化，但它们在直方图上有确定特征：
 *
 * | 症状 | 直方图特征 | 判据 |
 * | --- | --- | --- |
 * | 亮部平顶（高光被削成一块） | `p99` 与 `p95` 几乎相等 | `p99 - p95 <= 2`（0~255 刻度） |
 * | 黑场被抬起（暗部发灰） | `p1` 明显大于 0 | `p1 >= 14`（≈5%） |
 * | 整体过曝 / 泛白 | 高亮度像素占比过大 | `luma >= 250` 的占比 `> 5%` |
 * | 对比度不足 | `p95 - p5` 偏小 | `< 90` |
 *
 * 【采样策略】
 * 只统计 `mask` 的 alpha > [alphaThreshold] 的像素（真正的人物实心区），并按步长抽样，
 * 上限 [MAX_SAMPLES] 个样本：2048² 全量遍历在低端机上要几十毫秒，而分位数在 30 万样本上
 * 已经稳定到 ±1/255，没有理由全量扫。
 *
 * 【为什么分位数用 8bit 直方图而不是排序】
 * 排序 30 万个 Float 要多分配 1.2MB 并 O(n log n)；256 桶直方图一次遍历即可，
 * 分位数误差 ≤ 1/255，肉眼不可分辨。
 */
object PersonHistogram {

    private const val TAG = "NaiwaCamera"

    /** p99 达到该值才算"画面里有高光"，低于它不做平顶判定。 */
    private const val HIGHLIGHT_PRESENT = 200

    /** p99 与 p95 相差不超过该值（0~255 刻度）即判为亮部平顶。 */
    private const val FLAT_TOLERANCE = 2

    /** 最多统计的采样点。 */
    private const val MAX_SAMPLES = 300_000

    /** Rec.709 亮度权重（在 sRGB 空间上取，与 shader 里 `lumaAt` 一致）。 */
    private const val WR = 0.2126f
    private const val WG = 0.7152f
    private const val WB = 0.0722f

    /**
     * 测量结果。
     *
     * @param p1/p5/p50/p95/p99 sRGB 亮度分位（0~255）
     * @param mean 平均亮度（0~255）
     * @param std 标准差（0~255）
     * @param clipRatio 亮度 ≥ 250 的占比（0~1）
     * @param hasHighlight 画面里是否真有高光（p99 ≥ 200）
     * @param highlightFlat p99 与 p95 的差 ≤ 2 且**确有高光** → 亮部被削平
     * @param blackLifted p1 ≥ 14 → 黑场被抬起
     */
    data class Result(
        val samples: Int,
        val p1: Int,
        val p5: Int,
        val p50: Int,
        val p95: Int,
        val p99: Int,
        val mean: Int,
        val std: Int,
        val clipRatio: Float,
        val hasHighlight: Boolean,
        val highlightFlat: Boolean,
        val blackLifted: Boolean,
    ) {
        /** 一行结论（给 UI 小面板）。 */
        fun summary(): String {
            val flat = when {
                !hasHighlight -> "无高光"
                highlightFlat -> "亮部平顶"
                else -> "亮部OK"
            }
            val black = if (blackLifted) "黑场抬升" else "黑场OK"
            return "人物 p1=$p1 p50=$p50 p95=$p95 p99=$p99 " +
                "Δ95→99=${p99 - p95} 过曝${(clipRatio * 100).toInt()}% $flat $black"
        }

        /** 多行详细报告（给日志）。 */
        fun report(): String = buildString {
            appendLine("[hista] 人物区域直方图（样本 $samples，sRGB 0~255）")
            appendLine(" p1=$p1  p5=$p5  p50=$p50  p95=$p95  p99=$p99")
            appendLine(" mean=$mean  std=$std  过曝(≥250)=${"%.1f".format(clipRatio * 100)}%")
            appendLine(
                " 亮部平顶: " + when {
                    !hasHighlight -> "无高光（p99=$p99 < 200，该图本身没有亮部，不做平顶判定）"
                    highlightFlat -> "是（p99-p95=${p99 - p95} ≤ 2）"
                    else -> "否"
                },
            )
            appendLine(" 黑场抬升: ${if (blackLifted) "是（p1=$p1 ≥ 14）" else "否"}")
            append(" 动态范围 p5→p95 = ${p95 - p5}${if (p95 - p5 < 90) "（偏小，对比不足）" else ""}")
        }
    }

    /**
     * 测量 [render] 中人物区域（[mask] 的 alpha > [alphaThreshold]）的亮度分布。
     *
     * @param render 融合结果位图
     * @param mask 人物图层（placed 后的全画布尺寸），用它的 alpha 定义人物区域
     * @param alphaThreshold 判定为"人物实心区"的 alpha 下限（0~255）
     * @return 结果；尺寸不匹配或没有人物像素时返回 null（不抛异常）
     */
    fun measure(render: Bitmap, mask: Bitmap?, alphaThreshold: Int = 230): Result? {
        if (render.isRecycled || render.width <= 0 || render.height <= 0) return null
        if (mask == null || mask.isRecycled) return null
        if (mask.width != render.width || mask.height != render.height) return null

        return try {
            val w = render.width
            val h = render.height
            val total = w * h
            val maskPx = IntArray(total)
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)
            val px = IntArray(total)
            render.getPixels(px, 0, w, 0, 0, w, h)

            val stride = (total / MAX_SAMPLES).coerceAtLeast(1)
            val hist = IntArray(256)
            var n = 0
            var sum = 0.0
            var sumSq = 0.0
            var clip = 0

            var i = 0
            while (i < total) {
                // 先看 mask：非人物像素连亮度都不用算
                if (((maskPx[i] ushr 24) and 0xFF) >= alphaThreshold) {
                    val c = px[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val luma = WR * r + WG * g + WB * b
                    val li = luma.toInt().coerceIn(0, 255)
                    hist[li]++
                    sum += luma
                    sumSq += luma.toDouble() * luma
                    if (li >= 250) clip++
                    n++
                }
                i += stride
            }
            if (n == 0) return null

            fromHistogram(hist, n, clip, sum, sumSq)
        } catch (t: Throwable) {
            Log.w(TAG, "人物直方图测量失败：${t.message}")
            null
        }
    }

    /**
     * 由 256 桶直方图算出分位数与判据（**纯函数，可 JVM 单测**）。
     *
     * 单独拆出来是因为阈值判据本身会出错（比如 p99-p95 的容差取 0 就永远判"平顶"），
     * 而 `measure()` 依赖 Bitmap，在本地单测里拿不到真实位图。
     */
    internal fun fromHistogram(
        hist: IntArray,
        n: Int,
        clip: Int,
        sum: Double,
        sumSq: Double,
    ): Result {
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        fun pct(p: Double): Int {
            val want = Math.round(p * n).toInt().coerceIn(1, n)
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= want) return v
            }
            return 255
        }
        val p1 = pct(0.01)
        val p5 = pct(0.05)
        val p50 = pct(0.50)
        val p95 = pct(0.95)
        val p99 = pct(0.99)
        return Result(
            samples = n,
            p1 = p1,
            p5 = p5,
            p50 = p50,
            p95 = p95,
            p99 = p99,
            mean = mean.toInt(),
            std = kotlin.math.sqrt(variance).toInt(),
            clipRatio = clip.toFloat() / n,
            // 只有"确实存在高光"时 p95≈p99 才等于平顶；整张图最亮只有 120 时 p95==p99 是正常的
            hasHighlight = p99 >= HIGHLIGHT_PRESENT,
            highlightFlat = p99 >= HIGHLIGHT_PRESENT && (p99 - p95) <= FLAT_TOLERANCE,
            blackLifted = p1 >= 14,
        )
    }

    /**
     * 测量并发布到 [DebugReport]（同时打日志）。
     *
     * 失败静默 —— 调试设施绝不允许影响渲染结果。
     */
    fun publish(render: Bitmap, mask: Bitmap?): Result? {
        if (!DebugReport.enabled) return null
        val r = measure(render, mask) ?: return null
        DebugReport.publish(r.report(), r.summary())
        Log.i(TAG, r.report())
        return r
    }
}
