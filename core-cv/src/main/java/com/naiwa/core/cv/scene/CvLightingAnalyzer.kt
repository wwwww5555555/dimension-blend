package com.naiwa.core.cv.scene

import android.graphics.Bitmap
import com.naiwa.core.common.model.LightingEstimate
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 传统 CV 光影分析器（规格：AI 多任务模型的**确定性兜底**）。
 *
 * 四个物理量的来源（全部可解释，不是玄学）：
 *  1. **主光方向**：灰度 Scharr 梯度。物体受光后，迎光面更亮 → 亮度梯度指向"背光方向"，
 *     取梯度场的**面积加权反向**作为光来向的水平分量；垂直分量由亮部重心相对质心的偏移
 *     估计高度角（亮部在上方 → 顶光，在侧面 → 侧光）。
 *  2. **光强**：高光占比 + 对比度（std/64）的加权。过曝图与平光图都应得到不同强度。
 *  3. **辉光强度**：亮度 > 235 的像素占比（越多越"有光晕"），映射到 0.1~0.4。
 *  4. **光包裹强度**：背景平均亮度 × 背景复杂度（拉普拉斯方差归一）。
 *     —— 明亮的蓝天/雪景需要更强的包裹，暗调室内则弱。
 *  5. **背景模糊 σ**：拉普拉斯方差越小（越平滑）σ 越小；细节越密 σ 越大（规格二.1）。
 *
 * 全部在**降采样域**（短边 ≤ 512）执行：统计量对采样密度不敏感，1080p 全量扫描纯属浪费。
 */
object CvLightingAnalyzer {

    /** 分析用的工作分辨率短边。 */
    private const val WORK_SHORT_SIDE = 512

    /** σ 映射区间（规格 15~30，与 LightWrap.BG_SIGMA_AT_1080 对齐）。 */
    private const val SIGMA_MIN = 15f
    private const val SIGMA_MAX = 30f

    /** 背光判定阈值：背景高光区比人物内部亮出这么多（0~255）即判为强逆光。 */
    private const val BACKLIT_LUMA_GAP = 38f

    /**
     * 分析背景光影（纯 CV 物理骨架，目标 < 50ms）。
     *
     * @param scene 写实背景（不被修改）
     * @param personMeanLuma 人物内部平均亮度（0~255，由调用方按 Mask 采样一次）。
     *   传入时用于**背光判定**：背景高光区显著亮于人物 → 强逆光。
     * @return 光影参数；位图无效时返回 [LightingEstimate.NEUTRAL]
     */
    fun analyze(scene: Bitmap, personMeanLuma: Float? = null): LightingEstimate {
        if (scene.isRecycled || scene.width <= 0 || scene.height <= 0) {
            return LightingEstimate.NEUTRAL
        }
        val (px, w, h) = downscale(scene)

        // ---- 灰度 + 亮度统计 ----
        val luma = FloatArray(w * h)
        var sumL = 0.0
        var sumL2 = 0.0
        var maxL = 0f
        for (i in luma.indices) {
            val c = px[i]
            val l = 0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)
            luma[i] = l
            sumL += l
            sumL2 += (l * l).toDouble()
            if (l > maxL) maxL = l
        }
        val n = luma.size.toFloat()
        val meanL = (sumL / n).toFloat()
        val stdL = sqrt((sumL2 / n - (sumL / n) * (sumL / n)).coerceAtLeast(0.0)).toFloat()

        // ---- Scharr 梯度 → 主光水平方向（面积加权反向） ----
        var gxSum = 0.0
        var gySum = 0.0
        var lumaXSum = 0.0
        var lumaYSum = 0.0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                // Scharr 核（比 Sobel 更精确的各向同性）
                val gx = (3f * (luma[i - w + 1] - luma[i - w - 1]) +
                    10f * (luma[i + 1] - luma[i - 1]) +
                    3f * (luma[i + w + 1] - luma[i + w - 1])) / 16f
                val gy = (3f * (luma[i + w - 1] - luma[i - w - 1]) +
                    10f * (luma[i + w] - luma[i - w]) +
                    3f * (luma[i + w + 1] - luma[i - w + 1])) / 16f
                val mag = hypot(gx, gy)
                if (mag < 8f) continue           // 弱梯度不进统计（噪声）
                gxSum += gx * mag
                gySum += gy * mag
                // 亮部重心（按亮度加权）
                lumaXSum += x * luma[i]
                lumaYSum += y * luma[i]
            }
        }
        // 光来向 = 亮度上升方向的反向（亮面朝向光源）
        var nx = if (gxSum != 0.0) (-gxSum).toFloat() else 0f
        var ny = if (gySum != 0.0) (-gySum).toFloat() else -1f
        val len = hypot(nx.toDouble(), ny.toDouble()).toFloat().coerceAtLeast(1e-4f)
        nx /= len
        ny /= len

        // ---- 高度角：亮部重心相对画面中心的垂直偏移（在上方 → 顶光 → z 大） ----
        val totalW = (luma.sum()).coerceAtLeast(1e-4f).toDouble()
        val cy = (lumaYSum / totalW).toFloat() / h   // 0..1，越小越靠上
        val zFromCentroid = ((0.5f - cy) * 2f).coerceIn(-1f, 1f)
        var nz = (0.55f + 0.45f * zFromCentroid).coerceIn(0.05f, 1f)

        // 归一化三维方向
        val len3 = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        nx /= len3; ny /= len3; nz /= len3

        // ---- 光强：高光占比 + 对比度 ----
        var highlight = 0
        for (l in luma) if (l > 235f) highlight++
        val highlightRatio = highlight.toFloat() / n
        val lightIntensity = (0.35f * (stdL / 64f).coerceIn(0f, 1f) +
            0.35f * highlightRatio.coerceIn(0f, 1f) +
            0.30f * (meanL / 255f)).coerceIn(0.15f, 1f)

        // ---- 辉光强度：高光占比映射 0.1~0.4 ----
        val glowStrength = (0.1f + 0.3f * (highlightRatio * 4f).coerceIn(0f, 1f)).coerceIn(0.1f, 0.4f)

        // ---- 拉普拉斯方差 → 背景复杂度 → σ / 包裹强度 ----
        val lapVar = laplacianVariance(luma, w, h)
        val complexity = (lapVar / 800f).coerceIn(0f, 1f)   // 经验归一：800 ≈ 纹理密集
        val bgSigma = (SIGMA_MIN + (SIGMA_MAX - SIGMA_MIN) * complexity).coerceIn(SIGMA_MIN, SIGMA_MAX)
        val wrapStrength = (0.18f + 0.42f * (meanL / 255f) * (1f - 0.35f * complexity))
            .coerceIn(0.15f, 0.6f)

        // ---- 背景亮部前 5% 中位色（光包裹/边缘光颜色来源） ----
        val highlightColor = topPercentMedianColor(px, luma, 0.05f)

        // ---- 主光源定位（NMS：最亮的大面积区域中心） ----
        val lightPos = findLightSource(luma, w, h)
        val lightXN = lightPos[0] / w
        val lightYN = lightPos[1] / h

        // ---- 背光检测（规格一.3）：背景高光 vs 人物内部亮度 ----
        val highlightLuma = topPercentMeanLuma(luma, 0.05f)
        val backlit = personMeanLuma != null &&
            (highlightLuma - personMeanLuma) > BACKLIT_LUMA_GAP

        return LightingEstimate(
            lightDirX = nx,
            lightDirY = ny,
            lightDirZ = nz,
            lightIntensity = lightIntensity,
            glowStrength = glowStrength,
            wrapStrength = wrapStrength,
            bgHighlightColor = highlightColor,
            bgBlurSigma = bgSigma,
            lightX = lightXN.coerceIn(0f, 1f),
            lightY = lightYN.coerceIn(0f, 1f),
            backlit = backlit,
        )
    }

    /**
     * 主光源定位：灰度 → 大半径均值模糊去噪 → 网格候选 + **NMS 非极大值抑制** →
     * 最亮候选处做均值偏移找亮区质心。
     *
     * 为什么先大半径模糊：单像素高光（灯泡、反光点）会把"光源位置"吸走；
     * 大面积亮区（窗、天空、夕阳）才是真正的环境主光。
     *
     * @return `[x, y]`（工作域像素坐标）
     */
    private fun findLightSource(luma: FloatArray, w: Int, h: Int): FloatArray {
        // 1) 大半径均值模糊（box blur 半径随画面尺寸，O(n) 与半径无关）
        val radius = (minOf(w, h) / 8).coerceIn(6, 48)
        val smooth = com.naiwa.core.cv.util.BoxBlur.blur(luma, w, h, radius, passes = 1)

        // 2) 网格候选：每格取局部最大值（既是降采样也是 NMS 的第一层）
        val cell = (minOf(w, h) / 12).coerceAtLeast(6)
        val cands = ArrayList<FloatArray>(64) // [x, y, v]
        var gy = 0
        while (gy < h) {
            var gx = 0
            while (gx < w) {
                var bestV = -1f
                var bestX = gx
                var bestY = gy
                val yEnd = minOf(gy + cell, h)
                val xEnd = minOf(gx + cell, w)
                for (y in gy until yEnd) {
                    val row = y * w
                    for (x in gx until xEnd) {
                        val v = smooth[row + x]
                        if (v > bestV) {
                            bestV = v; bestX = x; bestY = y
                        }
                    }
                }
                cands += floatArrayOf(bestX.toFloat(), bestY.toFloat(), bestV)
                gx += cell
            }
            gy += cell
        }
        if (cands.isEmpty()) return floatArrayOf(w / 2f, h / 2f)

        // 3) NMS：按亮度降序，抑制半径内的次强候选（避免同一个大亮区被拆成多个）
        cands.sortByDescending { it[2] }
        val keep = ArrayList<FloatArray>(4)
        val suppressR = cell * 1.6f
        for (c in cands) {
            val dup = keep.any { k ->
                val dx = k[0] - c[0]
                val dy = k[1] - c[1]
                dx * dx + dy * dy < suppressR * suppressR
            }
            if (!dup) keep += c
            if (keep.size >= 8) break
        }
        val seed = keep.first()

        // 4) 均值偏移：在 seed 邻域内按"高于阈值的亮度"加权求质心，锁定亮区中心
        val thr = seed[2] * 0.85f
        val r = cell * 2
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        val y0 = (seed[1].toInt() - r).coerceAtLeast(0)
        val y1 = (seed[1].toInt() + r).coerceAtMost(h - 1)
        val x0 = (seed[0].toInt() - r).coerceAtLeast(0)
        val x1 = (seed[0].toInt() + r).coerceAtMost(w - 1)
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val v = smooth[row + x]
                if (v < thr) continue
                val wgt = (v - thr).toDouble() + 1.0
                sx += x * wgt; sy += y * wgt; sw += wgt
            }
        }
        return if (sw > 0.0) {
            floatArrayOf((sx / sw).toFloat(), (sy / sw).toFloat())
        } else {
            floatArrayOf(seed[0], seed[1])
        }
    }

    /** 亮度前 [ratio] 像素的平均亮度（背光判定用）。 */
    private fun topPercentMeanLuma(luma: FloatArray, ratio: Float): Float {
        val sorted = luma.sortedDescending()
        val take = (sorted.size * ratio).toInt().coerceAtLeast(1)
        var sum = 0.0
        for (i in 0 until take) sum += sorted[i]
        return (sum / take).toFloat()
    }

    /** 拉普拉斯方差（清晰度/纹理密度指标）。 */
    private fun laplacianVariance(luma: FloatArray, w: Int, h: Int): Float {
        var sum = 0.0
        var sum2 = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                sum += lap
                sum2 += (lap * lap).toDouble()
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        return sqrt((sum2 / count - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    /** 亮度前 [ratio] 比例的像素逐通道中位色。 */
    private fun topPercentMedianColor(px: IntArray, luma: FloatArray, ratio: Float): Int {
        val sortedIdx = px.indices.sortedByDescending { luma[it] }
        val take = (sortedIdx.size * ratio).toInt().coerceAtLeast(1)
        val sel = sortedIdx.take(take)
        val rs = IntArray(sel.size) { (px[sel[it]] shr 16) and 0xFF }.also { it.sort() }
        val gs = IntArray(sel.size) { (px[sel[it]] shr 8) and 0xFF }.also { it.sort() }
        val bs = IntArray(sel.size) { px[sel[it]] and 0xFF }.also { it.sort() }
        val m = sel.size / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    /** 等比降采样到短边 ≤ [WORK_SHORT_SIDE]（双线性，绝不 Nearest）。 */
    private fun downscale(scene: Bitmap): Triple<IntArray, Int, Int> {
        val shortSide = minOf(scene.width, scene.height)
        if (shortSide <= WORK_SHORT_SIDE) {
            val w = scene.width
            val h = scene.height
            val px = IntArray(w * h)
            scene.getPixels(px, 0, w, 0, 0, w, h)
            return Triple(px, w, h)
        }
        val scale = WORK_SHORT_SIDE.toFloat() / shortSide
        val tw = (scene.width * scale).toInt().coerceAtLeast(1)
        val th = (scene.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(scene, tw, th, true)
        val px = IntArray(tw * th)
        small.getPixels(px, 0, tw, 0, 0, tw, th)
        if (small !== scene) small.recycle()
        return Triple(px, tw, th)
    }
}
