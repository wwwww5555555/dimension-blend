package com.naiwa.core.ai.light

import com.naiwa.core.common.color.KelvinRgb
import com.naiwa.core.common.model.LightParams
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 传统 CV 光照估计（**纯 Kotlin 实现，不依赖 OpenCV、不依赖模型**）。
 *
 * 它的定位是「兜底中的兜底」：模型缺失、模型加载失败、模型置信度不足时都由它接手，
 * 而且**永不抛异常** —— 任何一步出问题都退化为 [LightParams.NEUTRAL]。
 * 这条路径的存在让"自动分析光源"这个卖点永远不会因为模型问题而整个失效。
 *
 * 算法分四块，每块都有明确的物理依据（而不是"调参调出来的"）：
 *
 * **1. 白平衡 → 色温**
 *  - 灰度世界假设（全图均值应为灰）+ 白点法（最亮 1% 像素应为白）各算一组增益再折中。
 *    只用灰度世界时，大片单色场景（草地、蓝天）会严重偏色 —— 这是最常见的翻车点；
 *    只用白点法又容易被高光噪点带偏，所以两者各 0.5 权重。
 *  - 色温由 [KelvinRgb.rgbGainToKelvin] 反解，与手动滑杆共用同一套映射。
 *
 * **2. 亮度统计 → 曝光/对比度/阴影**
 *  - p50（中位亮度）与目标 0.45 的比值 → 亮度增益、曝光档位；
 *  - p1~p99 跨度 → 对比度；
 *  - 暗部/中位亮度比 → 阴影浓度；暗部到中位的过渡宽度 → 阴影软硬。
 *
 * **3. 亮度梯度场 → 主光方位角与高度角**
 *  - 先做大半径模糊（σ ≈ 短边/12）得到"光照低频分量"，再算 Scharr 梯度；
 *    不模糊的话纹理边缘会贡献大量噪声梯度，方向估计会随机跳。
 *  - 梯度方向即"越来越亮的方向"，也就是**光源来向**。
 *  - 高度角用「亮部质心偏离画面中心的距离」估计：亮部越居中说明光越接近正上方。
 *
 * **4. 交叉验证 → 置信度**
 *  - 暗部质心方向应当与亮部大致相反，夹角越小越可信；
 *  - 梯度场的"方向一致性"（合成向量长度 / 总能量）也是强指标：噪声图接近 0，真实方向光接近 1。
 */
class CvLightEstimator(
    /** 目标中位亮度。手机照片正常曝光的中位亮度大约落在 0.4~0.5，取 0.45。 */
    private val targetMedianLuma: Float = 0.45f,
    /** 归一化对比度的目标跨度（p1~p99）。0.85 对应"主体明确但两端不溢出"。 */
    private val targetContrastSpan: Float = 0.85f,
) : LightEstimator {

    override val name: String = "cv-pure-kotlin"

    override fun estimate(pixels: IntArray, width: Int, height: Int): LightParams {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return LightParams.NEUTRAL

        return try {
            val luma = FloatArray(width * height)
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var maxLuma = 0f
            for (i in 0 until width * height) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sumR += r
                sumG += g
                sumB += b
                val y = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                luma[i] = y
                if (y > maxLuma) maxLuma = y
            }
            if (sumR <= 0.0 || sumG <= 0.0 || sumB <= 0.0 || maxLuma <= 0.001f) {
                return LightParams.NEUTRAL
            }

            val total = width * height
            val correctionGains = estimateWhiteBalanceGains(pixels, luma, total, sumR, sumG, sumB)
            // 【方向问题，容易搞反】上面算出来的是"把画面校正回中性"的增益；
            // 而 KelvinRgb 那对函数表达的是**光源自身的颜色**（低色温 = 偏暖 = 红增益 > 1）。
            // 两者互为倒数：暖光下校正增益的 R < 1，取倒数后才得到"暖光源"的 R > 1。
            // 方向搞反的症状很直观：暖光场景会被估成 12000K 的冷光。
            val illuminantGains = floatArrayOf(
                1f / correctionGains[0].coerceAtLeast(0.05f),
                1f / correctionGains[1].coerceAtLeast(0.05f),
                1f / correctionGains[2].coerceAtLeast(0.05f),
            )
            val colorTempK = KelvinRgb.rgbGainToKelvin(illuminantGains).toFloat()
            // tint 用"校正增益"判断方向：负值 = 画面偏绿（与 LightParams.tint 的约定一致）
            val tint = estimateTint(correctionGains)

            val stats = LumaStats.from(luma)
            val brightness = (targetMedianLuma / max(stats.p50, 0.02f)).coerceIn(0.5f, 1.5f)
            val exposureEv = log2(brightness).coerceIn(-3f, 3f)
            val contrast = (stats.span / targetContrastSpan).coerceIn(0.5f, 1.5f)
            val shadowStrength = (1f - stats.p10 / max(stats.p50, 0.05f)).coerceIn(0f, 1f)
            val shadowSoftness = ((stats.p50 - stats.p10) / max(stats.span, 0.05f)).coerceIn(0f, 1f)

            val direction = estimateDirection(luma, width, height)
            val keyIntensity = ((stats.p90 - stats.p10) / max(stats.p90 + stats.p10, 0.05f) * 1.6f)
                .coerceIn(0f, 1f)
            val ambientIntensity = (stats.p10 / max(stats.p50, 0.05f)).coerceIn(0f, 1f)

            val confidence = computeConfidence(direction, stats)

            LightParams(
                colorTempK = colorTempK,
                tint = tint,
                exposureEv = exposureEv,
                brightness = brightness,
                contrast = contrast,
                saturation = 1f,
                keyAzimuthDeg = direction.azimuthDeg,
                keyElevationDeg = direction.elevationDeg,
                keyIntensity = keyIntensity,
                ambientIntensity = ambientIntensity,
                shadowStrength = shadowStrength,
                shadowSoftness = shadowSoftness,
                confidence = confidence,
            ).clamped()
        } catch (t: Throwable) {
            // 契约：永不抛异常。任何意外都退化为中性参数（主流程仍能出图）
            LightParams.NEUTRAL
        }
    }

    // ------------------------------------------------------------------ 物理骨架（4 字段）

    /**
     * 纯 CV 测光（阶段二）：光源坐标 (X,Y) + 主光方位角 + 背光标记。
     *
     * 与 [estimate] 的关系：**同源不同用途**。`estimate()` 输出 12 个调色参数（色温/曝光/
     * 阴影…），本方法输出 4 个**几何骨架**字段，供 GPU 图层的 LightWrap/Shadow/LensFlare 节点使用。
     * 两者共用同一套 boxBlur / Scharr / normalizeDegrees，口径一致。
     *
     * 流程（需求阶段二逐条对应）：
     * ```
     * 灰度化(Rec.709) → 均值模糊(两趟 box) → 网格候选+NMS+加权质心 → posX/posY
     *                                      → Scharr 梯度 → 反方向能量加权 → angle
     *                                      → 背景 top5% 亮度 vs 人物均值 → isBacklit
     * ```
     *
     * 【性能】按 ≤320×320 工作域设计：全流程约 5ms 量级，远低于 50ms 预算。
     * 调用方应先把图降到短边 ≤320（见 `DefaultImagePipeline.estimateLight` 的降采样）。
     *
     * @param pixels ARGB（alpha 忽略）
     * @param personMeanLuma 人物内部平均亮度 0~255；传入才做背光判定（null → 恒 false）
     */
    fun estimateSkeleton(
        pixels: IntArray,
        width: Int,
        height: Int,
        personMeanLuma: Float? = null,
    ): CvLightParams {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return CvLightParams.NEUTRAL
        return try {
            // ① 灰度化（与 estimate() 同口径：BT.601，归一化到 0~1）
            val luma = FloatArray(width * height)
            for (i in luma.indices) {
                val c = pixels[i]
                luma[i] = (0.299f * ((c shr 16) and 0xFF) +
                    0.587f * ((c shr 8) and 0xFF) +
                    0.114f * (c and 0xFF)) / 255f
            }

            // ② 均值模糊（两趟 ≈ 高斯）：去掉纹理噪声，让亮区连成片
            val radius = max(1, min(width, height) / 12)
            val smooth = boxBlur(boxBlur(luma, width, height, radius), width, height, radius)

            // ③ NMS 找光源中心
            val center = findLightCenterNms(smooth, width, height)

            // ④ Scharr → 反方向能量加权 → 方位角
            val angle = scharrLightAngle(smooth, width, height)

            // ⑤ 背光判定：背景高光阈值 vs 人物均值
            val highlightLuma = percentileTop(smooth, 0.95f) * 255f
            val backlit = personMeanLuma != null &&
                (highlightLuma - personMeanLuma) > BACKLIT_LUMA_GAP

            CvLightParams(
                posX = center[0].coerceIn(0f, 1f),
                posY = center[1].coerceIn(0f, 1f),
                angle = angle,
                isBacklit = backlit,
            )
        } catch (t: Throwable) {
            // 契约：永不抛异常（测光失败不该阻断出图）
            CvLightParams.NEUTRAL
        }
    }

    /**
     * 光源中心：网格取局部最大 → **NMS 抑制邻近候选** → 最亮候选邻域内加权质心。
     *
     * 为什么要 NMS：一个大亮区（窗、天空、夕阳）在网格里会产出多个"局部最大"，
     * 直接取全局最大会被单个高光点（灯泡/反光）吸走；NMS 先合并同区域候选，
     * 再用"高于 85% 峰值"的加权质心把点峰磨成区域中心。
     *
     * @return `[x, y]`（归一化 0~1）
     */
    private fun findLightCenterNms(luma: FloatArray, width: Int, height: Int): FloatArray {
        val cell = max(4, min(width, height) / 12)
        val cands = ArrayList<FloatArray>(64)   // [x, y, lum]

        var gy = 0
        while (gy < height) {
            var gx = 0
            while (gx < width) {
                var best = -1f
                var bx = gx
                var by = gy
                val yEnd = min(gy + cell, height)
                val xEnd = min(gx + cell, width)
                for (y in gy until yEnd) {
                    val row = y * width
                    for (x in gx until xEnd) {
                        val v = luma[row + x]
                        if (v > best) { best = v; bx = x; by = y }
                    }
                }
                cands += floatArrayOf(bx.toFloat(), by.toFloat(), best)
                gx += cell
            }
            gy += cell
        }
        if (cands.isEmpty()) return floatArrayOf(0.5f, 0.5f)

        // NMS：按亮度降序保留，抑制半径内（1.6×cell）的次强候选
        cands.sortByDescending { it[2] }
        val keep = ArrayList<FloatArray>(8)
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

        // 无有效光源（极暗/纯色图）：峰值过低时返回画面中心，
        // 否则质心会退化成"第一个网格候选"（表现为光源永远在左上角 0.08,0.08）
        if (seed[2] < MIN_LIGHT_PEAK) return floatArrayOf(0.5f, 0.5f)

        // 加权质心（阈值 = 85% 峰值）
        val thr = seed[2] * 0.85f
        val r = cell * 2
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        val y0 = (seed[1].toInt() - r).coerceAtLeast(0)
        val y1 = (seed[1].toInt() + r).coerceAtMost(height - 1)
        val x0 = (seed[0].toInt() - r).coerceAtLeast(0)
        val x1 = (seed[0].toInt() + r).coerceAtMost(width - 1)
        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) {
                val v = luma[row + x]
                if (v < thr) continue
                val w = (v - thr).toDouble() + 1.0
                sx += x * w
                sy += y * w
                sw += w
            }
        }
        return if (sw > 0.0) {
            floatArrayOf((sx / sw / width).toFloat(), (sy / sw / height).toFloat())
        } else {
            floatArrayOf(seed[0] / width, seed[1] / height)
        }
    }

    /**
     * Scharr 梯度 → **反方向**能量加权 → 主光来向方位角（度）。
     *
     * 物理依据：亮度沿光**传播**方向衰减 ⇒ 梯度（指向变亮方向）**逆着传播方向**
     * = 正是指向光源。所以累加梯度本身，**不要取反**（取反会把方位角整体转 180°，
     * 症状是"光源明明在左上，边缘光却亮在右下"）。用幅值加权让强边缘主导。
     *
     * 必须累加**向量**再 atan2：直接平均角度会在 ±180° 处断裂（179° 与 -179° 平均成 0°）。
     */
    private fun scharrLightAngle(luma: FloatArray, width: Int, height: Int): Float {
        var vx = 0.0
        var vy = 0.0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val gx = (3f * (luma[i - width + 1] - luma[i - width - 1]) +
                    10f * (luma[i + 1] - luma[i - 1]) +
                    3f * (luma[i + width + 1] - luma[i + width - 1])) / 16f
                val gy = (3f * (luma[i + width - 1] - luma[i - width - 1]) +
                    10f * (luma[i + width] - luma[i - width]) +
                    3f * (luma[i + width + 1] - luma[i - width + 1])) / 16f
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < SCHARR_NOISE_FLOOR) continue
                // 梯度即指向光源（见 KDoc），按幅值能量加权累加
                vx += gx * mag
                vy += gy * mag
            }
        }
        if (abs(vx) < 1e-6 && abs(vy) < 1e-6) {
            return 270f   // 无梯度 → 顶光（y 向下，270° = 从上方来）
        }
        return normalizeDegrees(Math.toDegrees(atan2(vy, vx)).toFloat())
    }

    /**
     * 方位角 → 归一化 3D 光向量（供 Shader 的 `uLightDir`）。
     *
     * 2D 抠图**无法恢复真实深度**，所以 z 分量不臆造真实值，而是按物理直觉给出可调默认：
     * 光源越高（elevation 越大），水平分量按 cos(elev) 衰减、z 分量按 sin(elev) 增大 ——
     * 这正是"正顶光时边缘光最弱、低角度逆光时边缘光最强"的数学来源。
     *
     * @param angleDeg 来向方位角（0° = 从右，90° = 从下，180° = 从左，270° = 从上）
     * @param elevationDeg 主光高度角，默认 35°（略高于视平线，常见户外光）
     * @return `[x, y, z]` 单位向量
     */
    fun toLightVector(angleDeg: Float, elevationDeg: Float = 35f): FloatArray {
        val a = Math.toRadians(angleDeg.toDouble())
        val e = Math.toRadians(elevationDeg.coerceIn(0f, 89f).toDouble())
        val cosE = cos(e).toFloat()
        return floatArrayOf(
            cos(a).toFloat() * cosE,
            sin(a).toFloat() * cosE,
            sin(e).toFloat(),
        )
    }

    // ------------------------------------------------------------------ 1. 白平衡

    /**
     * 折中灰度世界与白点法得到的 RGB 增益。
     * 返回值已按绿通道归一（gain[1] = 1），因为色温只取决于 R/B 比值，
     * 而整体亮度由 brightness 字段单独表达，避免两处重复表达亮度。
     */
    private fun estimateWhiteBalanceGains(
        pixels: IntArray,
        luma: FloatArray,
        total: Int,
        sumR: Double,
        sumG: Double,
        sumB: Double,
    ): FloatArray {
        val meanR = (sumR / total).toFloat()
        val meanG = (sumG / total).toFloat()
        val meanB = (sumB / total).toFloat()
        val meanLuma = (meanR * 0.299f + meanG * 0.587f + meanB * 0.114f).coerceAtLeast(1f)

        // 灰度世界：让三通道均值趋同
        val gwR = meanLuma / meanR.coerceAtLeast(1f)
        val gwG = meanLuma / meanG.coerceAtLeast(1f)
        val gwB = meanLuma / meanB.coerceAtLeast(1f)

        // 白点法：取最亮的一部分像素的均值当作"白"
        var wpR = 0.0
        var wpG = 0.0
        var wpB = 0.0
        var wpCount = 0
        val lumaThreshold = percentileTop(luma, 0.99f)
        for (i in pixels.indices) {
            if (luma[i] >= lumaThreshold && luma[i] > 0.4f) {
                val c = pixels[i]
                wpR += (c shr 16) and 0xFF
                wpG += (c shr 8) and 0xFF
                wpB += c and 0xFF
                wpCount++
            }
        }
        val wpRg: Float
        val wpGg: Float
        val wpBg: Float
        if (wpCount >= 4) {
            val mR = (wpR / wpCount).toFloat().coerceAtLeast(1f)
            val mG = (wpG / wpCount).toFloat().coerceAtLeast(1f)
            val mB = (wpB / wpCount).toFloat().coerceAtLeast(1f)
            val target = max(mR, max(mG, mB))
            wpRg = target / mR
            wpGg = target / mG
            wpBg = target / mB
        } else {
            // 高光像素太少（夜景/暗图）：此时白点法不可靠，直接退化为灰度世界
            wpRg = gwR
            wpGg = gwG
            wpBg = gwB
        }

        val r = (gwR * GRAY_WORLD_WEIGHT + wpRg * (1f - GRAY_WORLD_WEIGHT)).coerceAtLeast(0.2f)
        val g = (gwG * GRAY_WORLD_WEIGHT + wpGg * (1f - GRAY_WORLD_WEIGHT)).coerceAtLeast(0.2f)
        val b = (gwB * GRAY_WORLD_WEIGHT + wpBg * (1f - GRAY_WORLD_WEIGHT)).coerceAtLeast(0.2f)

        // 按绿通道归一
        return floatArrayOf(r / g, 1f, b / g)
    }

    /** 绿-品红偏移：负值偏绿、正值偏品红（与 LightParams.tint 的约定一致）。 */
    private fun estimateTint(gains: FloatArray): Float =
        ((ln(gains[1]) - (ln(gains[0]) + ln(gains[2])) * 0.5f) * TINT_SCALE).coerceIn(-1f, 1f)

    // ------------------------------------------------------------------ 3. 光方向

    /** 方向估计结果。 */
    private data class DirectionResult(
        val azimuthDeg: Float,
        val elevationDeg: Float,
        /** 梯度场方向一致性，0~1。噪声图接近 0，方向光接近 1。 */
        val coherence: Float,
        /** 亮部与暗部方向的对立程度，0~1（1 = 完全相反，物理上最合理）。 */
        val opposition: Float,
    )

    private fun estimateDirection(luma: FloatArray, width: Int, height: Int): DirectionResult {
        val shortSide = min(width, height)
        // 模糊半径 ≈ 短边/12：滤掉纹理细节，只留下光照造成的低频明暗
        val radius = max(1, shortSide / 12)
        val blurred = boxBlur(boxBlur(luma, width, height, radius), width, height, radius)

        var sumGx = 0.0
        var sumGy = 0.0
        var sumMagnitude = 0.0
        var coherentX = 0.0
        var coherentY = 0.0

        // Scharr 核（比 Sobel 对细结构的响应更稳定，权重 3/10/3 的抗噪性更好）
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val gx = (3f * (blurred[i - width + 1] - blurred[i - width - 1]) +
                    10f * (blurred[i + 1] - blurred[i - 1]) +
                    3f * (blurred[i + width + 1] - blurred[i + width - 1])) / 16f
                val gy = (3f * (blurred[i + width - 1] - blurred[i - width - 1]) +
                    10f * (blurred[i + width] - blurred[i - width]) +
                    3f * (blurred[i + width + 1] - blurred[i - width + 1])) / 16f
                val magnitude = sqrt(gx * gx + gy * gy)
                if (magnitude < GRADIENT_NOISE_FLOOR) continue

                sumMagnitude += magnitude
                // 能量加权：强度大的方向对结果贡献更大
                coherentX += gx
                coherentY += gy
                sumGx += gx * magnitude
                sumGy += gy * magnitude
            }
        }

        if (sumMagnitude <= 1e-6 || (abs(sumGx) < 1e-6 && abs(sumGy) < 1e-6)) {
            return DirectionResult(
                azimuthDeg = LightParams.NEUTRAL_AZIMUTH_DEG,
                elevationDeg = LightParams.NEUTRAL_ELEVATION_DEG,
                coherence = 0f,
                opposition = 0f,
            )
        }

        // 光源来向 = 梯度指向的"变亮方向"（图像坐标：x 右、y 下，与 LightParams 角度约定一致）
        val azimuth = normalizeDegrees(Math.toDegrees(atan2(sumGy, sumGx)).toFloat())

        // 方向一致性：合成向量长度 / 各向量长度之和
        val coherence = (sqrt(coherentX * coherentX + coherentY * coherentY) /
            max(sumMagnitude, 1e-6)).toFloat().coerceIn(0f, 1f)

        val bright = centroidOf(luma, width, height, topPortion = 0.2f)
        val dark = centroidOf(luma, width, height, topPortion = 0.1f)

        // 高度角：亮部质心越居中 → 光越接近正上方（90°）；偏离越远 → 光越平（0°）
        val offset = sqrt(
            (bright.x - 0.5f) * (bright.x - 0.5f) + (bright.y - 0.5f) * (bright.y - 0.5f),
        )
        val elevation = (90f * (1f - (offset / ELEVATION_OFFSET_SCALE).coerceIn(0f, 1f)))
            .coerceIn(5f, 90f)

        // 交叉验证：暗部方向应当与亮部方向大致相反
        val brightVecX = bright.x - 0.5f
        val brightVecY = bright.y - 0.5f
        val darkVecX = 0.5f - dark.x
        val darkVecY = 0.5f - dark.y
        val opposition = cosineSimilarity(brightVecX, brightVecY, darkVecX, darkVecY)

        return DirectionResult(azimuth, elevation, coherence, opposition)
    }

    private fun computeConfidence(direction: DirectionResult, stats: LumaStats): Float {
        val clippedFraction = (stats.clippedHigh + stats.clippedLow).coerceIn(0f, 1f)
        val confidence = CONFIDENCE_COHERENCE_WEIGHT * direction.coherence +
            CONFIDENCE_OPPOSITION_WEIGHT * direction.opposition.coerceIn(0f, 1f) +
            CONFIDENCE_EXPOSURE_WEIGHT * (1f - clippedFraction)
        return confidence.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ 工具

    /** 亮度分位数统计。 */
    private data class LumaStats(
        val p1: Float,
        val p10: Float,
        val p50: Float,
        val p90: Float,
        val p99: Float,
        val clippedHigh: Float,
        val clippedLow: Float,
    ) {
        val span: Float get() = (p99 - p1).coerceAtLeast(0.01f)

        companion object {
            fun from(luma: FloatArray): LumaStats {
                // 稀疏采样后排序：全图排序在 4000 万像素下会明显拖慢估计
                val step = max(1, luma.size / SAMPLE_COUNT)
                val sampled = ArrayList<Float>(luma.size / step + 1)
                var i = 0
                while (i < luma.size) {
                    sampled += luma[i]
                    i += step
                }
                sampled.sort()
                var high = 0
                var low = 0
                for (v in sampled) {
                    if (v > 0.98f) high++
                    if (v < 0.02f) low++
                }
                return LumaStats(
                    p1 = pick(sampled, 0.01f),
                    p10 = pick(sampled, 0.10f),
                    p50 = pick(sampled, 0.50f),
                    p90 = pick(sampled, 0.90f),
                    p99 = pick(sampled, 0.99f),
                    clippedHigh = high.toFloat() / sampled.size.coerceAtLeast(1),
                    clippedLow = low.toFloat() / sampled.size.coerceAtLeast(1),
                )
            }

            private fun pick(sorted: List<Float>, q: Float): Float {
                if (sorted.isEmpty()) return 0f
                val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
                return sorted[idx]
            }

            private const val SAMPLE_COUNT = 40_000
        }
    }

    private data class Centroid(val x: Float, val y: Float)

    /**
     * 亮度最高（或最低）的一部分像素的质心，用归一化坐标表示。
     * @param topPortion 取亮度最高的比例；高度角用 0.2、交叉验证的暗部用 0.1
     */
    private fun centroidOf(
        luma: FloatArray,
        width: Int,
        height: Int,
        topPortion: Float,
    ): Centroid {
        val threshold = percentileTop(luma, 1f - topPortion)
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (luma[row + x] >= threshold) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        if (count == 0) return Centroid(0.5f, 0.5f)
        return Centroid(
            (sumX / count / width).toFloat(),
            (sumY / count / height).toFloat(),
        )
    }

    /** 取分位阈值（用于"最亮 X%"筛选）。 */
    private fun percentileTop(luma: FloatArray, q: Float): Float {
        val step = max(1, luma.size / SAMPLE_COUNT_FOR_THRESHOLD)
        val sampled = ArrayList<Float>(luma.size / step + 1)
        var i = 0
        while (i < luma.size) {
            sampled += luma[i]
            i += step
        }
        sampled.sort()
        if (sampled.isEmpty()) return 1f
        val idx = ((sampled.size - 1) * q).toInt().coerceIn(0, sampled.size - 1)
        return sampled[idx]
    }

    /** 可分离盒式模糊（两趟 = 近似高斯），O(n)，与半径无关。 */
    private fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val horizontal = FloatArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (x in -radius..radius) {
                sum += src[row + x.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / window
                val outIdx = (x - radius).coerceIn(0, width - 1)
                val inIdx = (x + radius + 1).coerceIn(0, width - 1)
                sum += src[row + inIdx] - src[row + outIdx]
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until width) {
            var sum = 0f
            for (y in -radius..radius) {
                sum += horizontal[y.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val outIdx = (y - radius).coerceIn(0, height - 1)
                val inIdx = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[inIdx * width + x] - horizontal[outIdx * width + x]
            }
        }
        return out
    }

    private fun cosineSimilarity(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val na = sqrt(ax * ax + ay * ay)
        val nb = sqrt(bx * bx + by * by)
        if (na < 1e-5f || nb < 1e-5f) return 0f
        return ((ax * bx + ay * by) / (na * nb)).coerceIn(-1f, 1f) * 0.5f + 0.5f
    }

    private fun normalizeDegrees(deg: Float): Float {
        val m = deg % 360f
        return if (m < 0f) m + 360f else m
    }

    private fun log2(v: Float): Float = (ln(v.toDouble()) / LN2).toFloat()

    private companion object {
        /** 灰度世界与白点法的权重：0.5 表示完全折中。 */
        const val GRAY_WORLD_WEIGHT = 0.5f

        /** tint 的放大系数，把对数域的小偏差映射到 [-1,1] 的可读区间。 */
        const val TINT_SCALE = 4f

        /** 梯度噪声下限：低于此值的方向不参与投票（抑制纹理与压缩噪点）。 */
        const val GRADIENT_NOISE_FLOOR = 0.002f

        /** 物理骨架版的梯度噪声下限（luma 归一化到 0~1，与 GRADIENT_NOISE_FLOOR 同量级但更敏感）。 */
        const val SCHARR_NOISE_FLOOR = 0.004f

        /**
         * 背光判定阈值：背景高光比人物均值亮这么多（0~255）即判强逆光（规格阶段二.3）。
         * 取 50 而不是 38：38 会让"人物 200 / 背景 240"这种正常明暗关系误判成逆光。
         */
        const val BACKLIT_LUMA_GAP = 50f

        /** 判定"图里有光源"的最小峰值亮度（0~1）；低于它说明是暗图/纯色图。 */
        const val MIN_LIGHT_PEAK = 0.06f

        /** 亮部质心偏移的归一化尺度：偏移达到 0.45 即认为光几乎与地面平齐。 */
        const val ELEVATION_OFFSET_SCALE = 0.45f

        const val CONFIDENCE_COHERENCE_WEIGHT = 0.5f
        const val CONFIDENCE_OPPOSITION_WEIGHT = 0.3f
        const val CONFIDENCE_EXPOSURE_WEIGHT = 0.2f

        const val SAMPLE_COUNT_FOR_THRESHOLD = 20_000
        val LN2 = ln(2.0)

        // 让 kotlin.math.sin/cos 的导入不被判为未使用（方向扩展时会用到）
        @Suppress("unused")
        fun polarToUnit(deg: Float): Pair<Float, Float> {
            val r = Math.toRadians(deg.toDouble())
            return cos(r).toFloat() to sin(r).toFloat()
        }
    }
}
