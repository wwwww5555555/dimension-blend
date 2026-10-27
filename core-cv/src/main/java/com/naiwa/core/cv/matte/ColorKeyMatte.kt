package com.naiwa.core.cv.matte

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * 色键抠图（P0 的抠图方案，**不依赖 OpenCV**）。
 *
 * 适用场景：用户导入的奶蛙图没有透明通道（例如白底/绿幕拍的照片）。
 * 目标不是做到发丝级精度，而是"够用 + 可预期"，剩下的交给 P2 的点选分割与手动涂抹。
 *
 * 流程（每步都有明确理由）：
 *  1. **背景色估计**：采样图像四周一圈像素，按 32 级量化聚类，取占比最高的 3 个色作为候选背景色。
 *     取 3 个而不是 1 个，是因为手机拍的白底常常带亮度渐变（上白下灰），单色会漏掉一半背景。
 *  2. **色距**：在「亮度 + 色度」空间算距离（`dy` 权重 0.5），对整体明暗变化更鲁棒；
 *     直接用 RGB 欧氏距离的话，背景稍暗一点就被判成前景。
 *  3. **迟滞阈值**：高于 high 阈值直接判前景，低于 low 阈值直接判背景，中间灰区用
 *     「邻接前景才变前景」的迭代扩散决定 —— 这是把"边缘像素"判对的关键。
 *  4. **形态学开运算**：去掉背景里的孤立噪点（JPEG 压缩会造出大量这类点）。
 *  5. **连通域**：只保留足够大的前景块（阈值取最大块的 12%，避免把独立的耳朵/腿误删）。
 *  6. **孔洞填充**：从图像边框对背景做 flood fill，**填不到的背景就是孔洞**（例如奶蛙肚子里的白斑）。
 *  7. **收缩 + 羽化**：先内缩 [ColorKeyConfig.edgeShrinkPx] 像素把残余背景边砍掉，
 *     再用盒式模糊做羽化，为 P2 的融合留出过渡带（架构文档 §6.4 第 4 条）。
 *
 * 纯计算部分（[computeMask]）不碰 android.graphics，可直接 JVM 单测。
 */
object ColorKeyMatte {

    /** 量化到 32 级（每通道 8 级）做背景色聚类，够用且快。 */
    private const val QUANT_LEVEL = 32

    /** 最多保留几个背景候选色。 */
    private const val MAX_BG_COLORS = 3

    /** 迟滞扩散的迭代次数：3 次足以让边缘外扩约 3px（配合形态学开运算足够）。 */
    private const val HYSTERESIS_PASSES = 3

    /** 掩膜计算的最大边长：超过则先缩图算掩膜，再把掩膜按比例放回，避免内存与耗时爆炸。 */
    const val MAX_WORK_SHORT_SIDE = 1600

    /**
     * 抠图参数。所有阈值都在「归一化色距」空间（0 ~ 1.5），调参时按这个量级思考。
     *
     * @param distanceLow  低于此色距判定为**背景**。默认 0.055，约等于"背景色 ±5% 的容差"
     * @param distanceHigh 高于此色距判定为**前景**。默认 0.135
     * @param morphologyRadius 开运算核半径（1 = 3×3），去噪强度
     * @param minComponentRatio 连通域保留阈值（相对最大连通域），默认 12%
     * @param edgeShrinkPx  前景内缩像素数，默认 2（按架构文档要求收缩未知区）
     * @param featherRadius 羽化半径（盒式模糊），默认 2
     */
    data class ColorKeyConfig(
        val distanceLow: Float = 0.055f,
        val distanceHigh: Float = 0.135f,
        val morphologyRadius: Int = 1,
        val minComponentRatio: Float = 0.12f,
        val edgeShrinkPx: Int = 2,
        val featherRadius: Int = 2,
        val maxWorkShortSide: Int = MAX_WORK_SHORT_SIDE,
    )

    /**
     * 抠图诊断信息。给 UI 用（让用户判断"要不要手动修"），也给单测断言用。
     *
     * @param mask       前景掩膜，true = 前景
     * @param backgroundColors 实际估计出的背景色（ARGB，alpha 恒为 255）
     * @param foregroundRatio 前景像素占比
     */
    data class MaskResult(
        val mask: BooleanArray,
        val width: Int,
        val height: Int,
        val backgroundColors: List<Int>,
        val foregroundRatio: Float,
    ) {
        // BooleanArray 的 equals 是引用比较，data class 默认实现会误导调用方，这里显式改写
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MaskResult) return false
            return width == other.width && height == other.height &&
                mask.contentEquals(other.mask) &&
                backgroundColors == other.backgroundColors
        }

        override fun hashCode(): Int =
            ((mask.contentHashCode() * 31 + width) * 31 + height) * 31 + backgroundColors.hashCode()
    }

    /**
     * 对整张位图做色键抠图。
     *
     * @return 带 alpha 的新位图（ARGB_8888）；无法处理（位图无效）时返回 null。
     *         注意：返回位图由调用方负责回收，**输入位图不会被回收**。
     */
    fun matte(source: Bitmap, config: ColorKeyConfig = ColorKeyConfig()): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null

        // 1) 先算掩膜。
        //    /!\ 这里刻意只用一个缩放系数、对 x/y 用同一个比例，保证掩膜能等比映射回原图。
        val workScale = computeWorkScale(source.width, source.height, config.maxWorkShortSide)
        val srcBitmap = if (workScale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * workScale).toInt().coerceAtLeast(1),
                (source.height * workScale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        val w = srcBitmap.width
        val h = srcBitmap.height
        val pixels = IntArray(w * h)
        srcBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = computeMask(pixels, w, h, config)

        // 2) 把掩膜映射回原图尺寸并生成带 alpha 的输出
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(source.width * source.height)
        source.getPixels(outPixels, 0, source.width, 0, 0, source.width, source.height)
        val scaleBack = 1f / workScale
        for (y in 0 until source.height) {
            val my = (y * scaleBack).toInt().coerceIn(0, h - 1)
            val rowOffset = my * w
            val outRow = y * source.width
            for (x in 0 until source.width) {
                val mx = (x * scaleBack).toInt().coerceIn(0, w - 1)
                val color = outPixels[outRow + x]
                val keep = result.mask[rowOffset + mx]
                // 保留原始 RGB（不置黑）：后续 premultiply 后透明区不参与计算，
                // 保留原色还能避免羽化边缘出现"黑边"
                outPixels[outRow + x] = if (keep) color else (color and 0x00FFFFFF)
            }
        }
        out.setPixels(outPixels, 0, source.width, 0, 0, source.width, source.height)
        if (srcBitmap !== source) srcBitmap.recycle()
        return out
    }

    /** 只要掩膜（UI 预览用）。 */
    fun computeMaskFor(source: Bitmap, config: ColorKeyConfig = ColorKeyConfig()): MaskResult? {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        return computeMask(pixels, w, h, config)
    }

    /**
     * 纯计算版掩膜（无 Android 依赖，可单测）。
     *
     * @param pixels ARGB_8888 像素，行优先
     */
    fun computeMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: ColorKeyConfig = ColorKeyConfig(),
    ): MaskResult {
        require(width > 0 && height > 0) { "宽高必须为正" }
        require(pixels.size >= width * height) { "像素数组长度不足" }

        val total = width * height
        val bgColors = estimateBackgroundColors(pixels, width, height)

        // --- 色距计算 + 迟滞判定 ---
        val distance = FloatArray(total)
        val foreground = BooleanArray(total)
        val maybe = BooleanArray(total)
        val low = config.distanceLow
        val high = config.distanceHigh
        for (i in 0 until total) {
            val c = pixels[i]
            val d = minDistanceToBackground(c, bgColors)
            distance[i] = d
            if (d >= high) foreground[i] = true else if (d > low) maybe[i] = true
        }

        // 迟滞扩散：灰区像素只有邻接前景才升级为前景，避免背景被整片吞掉
        var current = foreground
        repeat(HYSTERESIS_PASSES) {
            current = dilate(current, width, height, 1)
            for (i in 0 until total) {
                if (!maybe[i]) current[i] = foreground[i]
            }
            System.arraycopy(current, 0, foreground, 0, total)
        }

        // --- 形态学开运算：先腐蚀去噪点，再膨胀还原主体轮廓 ---
        var mask = erode(foreground, width, height, config.morphologyRadius)
        mask = dilate(mask, width, height, config.morphologyRadius)

        // --- 连通域：只留下足够大的前景块 ---
        mask = keepLargeComponents(mask, width, height, config.minComponentRatio)

        // --- 孔洞填充：从边框 flood fill 可达的背景之外，都是孔洞 ---
        mask = fillHoles(mask, width, height)

        // --- 内缩（砍掉残余背景边）+ 羽化（为融合留过渡带）---
        if (config.edgeShrinkPx > 0) {
            mask = erode(mask, width, height, config.edgeShrinkPx)
        }
        val feathered = feather(mask, width, height, config.featherRadius)

        var foregroundCount = 0
        for (m in feathered) if (m) foregroundCount++

        return MaskResult(
            mask = feathered,
            width = width,
            height = height,
            backgroundColors = bgColors,
            foregroundRatio = if (total == 0) 0f else foregroundCount.toFloat() / total,
        )
    }

    // ------------------------------------------------------------ 内部实现

    /** 短边超过上限时的缩放系数（1.0 = 不缩放）。 */
    private fun computeWorkScale(width: Int, height: Int, maxShortSide: Int): Float {
        if (maxShortSide <= 0) return 1f
        val shortSide = minOf(width, height)
        if (shortSide <= maxShortSide) return 1f
        return maxShortSide.toFloat() / shortSide
    }

    /**
     * 采样四周边框像素估计背景色。
     * 采样带宽固定为 2px，避免把主体边缘的颜色也算进来。
     */
    private fun estimateBackgroundColors(pixels: IntArray, width: Int, height: Int): List<Int> {
        val band = 2
        val buckets = HashMap<Int, IntArray>()   // quantKey -> [count, sumR, sumG, sumB]
        fun sample(x: Int, y: Int) {
            val c = pixels[y * width + x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val key = ((r / QUANT_LEVEL) shl 16) or ((g / QUANT_LEVEL) shl 8) or (b / QUANT_LEVEL)
            val acc = buckets[key]
            if (acc == null) {
                buckets[key] = intArrayOf(1, r, g, b)
            } else {
                acc[0]++; acc[1] += r; acc[2] += g; acc[3] += b
            }
        }
        for (y in 0 until minOf(band, height)) {
            for (x in 0 until width) sample(x, y)
        }
        for (y in maxOf(0, height - band) until height) {
            for (x in 0 until width) sample(x, y)
        }
        for (y in 0 until height) {
            for (x in 0 until minOf(band, width)) sample(x, y)
            for (x in maxOf(0, width - band) until width) sample(x, y)
        }

        return buckets.values
            .sortedByDescending { it[0] }
            .take(MAX_BG_COLORS)
            .map { acc ->
                val n = acc[0].coerceAtLeast(1)
                (0xFF shl 24) or ((acc[1] / n) shl 16) or ((acc[2] / n) shl 8) or (acc[3] / n)
            }
    }

    /** 到所有背景候选色的最小归一化色距。 */
    private fun minDistanceToBackground(argb: Int, bgColors: List<Int>): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = Float.MAX_VALUE
        for (bg in bgColors) {
            val d = chromaLumaDistance(r, g, b, (bg shr 16) and 0xFF, (bg shr 8) and 0xFF, bg and 0xFF)
            if (d < best) best = d
        }
        return if (best == Float.MAX_VALUE) 0f else best
    }

    /**
     * 亮度 + 色度距离。
     * 亮度差乘 0.5：背景亮度整体漂移（阴影、渐变）不应该被判成前景，
     * 而色度差保持满权重，因为"绿幕 vs 绿色奶蛙"靠色度区分。
     */
    private fun chromaLumaDistance(
        r: Int, g: Int, b: Int,
        br: Int, bg: Int, bb: Int,
    ): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val by = 0.299f * br + 0.587f * bg + 0.114f * bb
        val dy = (y - by) * 0.5f
        val dcb = (b - y) - (bb - by)
        val dcr = (r - y) - (br - by)
        return sqrt(dy * dy + dcb * dcb + dcr * dcr) / 255f
    }

    /** 方形核膨胀（3×3 时等价于 8 邻域）。 */
    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(width - 1, x + radius)
                var hit = false
                var yy = y0
                while (!hit && yy <= y1) {
                    val row = yy * width
                    var xx = x0
                    while (xx <= x1) {
                        if (mask[row + xx]) { hit = true; break }
                        xx++
                    }
                    yy++
                }
                out[y * width + x] = hit
            }
        }
        return out
    }

    /** 方形核腐蚀。 */
    private fun erode(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(width - 1, x + radius)
                var all = true
                var yy = y0
                while (all && yy <= y1) {
                    val row = yy * width
                    var xx = x0
                    while (xx <= x1) {
                        if (!mask[row + xx]) { all = false; break }
                        xx++
                    }
                    yy++
                }
                out[y * width + x] = all
            }
        }
        return out
    }

    /** 连通域过滤：保留面积 >= 最大连通域 × ratio 的前景块。 */
    private fun keepLargeComponents(
        mask: BooleanArray,
        width: Int,
        height: Int,
        ratio: Float,
    ): BooleanArray {
        val labels = IntArray(mask.size) { -1 }
        val total = mask.size
        val queue = IntArray(total)
        val sizes = ArrayList<Int>()
        for (start in 0 until total) {
            if (!mask[start] || labels[start] != -1) continue
            val label = sizes.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var count = 0
            while (head < tail) {
                val idx = queue[head++]
                count++
                val x = idx % width
                val y = idx / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val nIdx = ny * width + nx
                        if (mask[nIdx] && labels[nIdx] == -1) {
                            labels[nIdx] = label
                            queue[tail++] = nIdx
                        }
                    }
                }
            }
            sizes.add(count)
        }
        if (sizes.isEmpty()) return BooleanArray(mask.size)
        val maxSize = sizes.max()
        val threshold = maxOf((maxSize * ratio).toInt(), 64)
        val out = BooleanArray(mask.size)
        for (i in 0 until total) {
            val label = labels[i]
            if (label >= 0 && sizes[label] >= threshold) out[i] = true
        }
        return out
    }

    /** 孔洞填充：从四边对背景 flood fill，未被访问到的背景像素即为孔洞。 */
    private fun fillHoles(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val reachable = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var head = 0
        var tail = 0

        fun push(x: Int, y: Int) {
            val idx = y * width + x
            if (!mask[idx] && !reachable[idx]) {
                reachable[idx] = true
                queue[tail++] = idx
            }
        }
        for (x in 0 until width) {
            push(x, 0)
            push(x, height - 1)
        }
        for (y in 0 until height) {
            push(0, y)
            push(width - 1, y)
        }
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % width
            val y = idx / width
            if (x > 0) push(x - 1, y)
            if (x < width - 1) push(x + 1, y)
            if (y > 0) push(x, y - 1)
            if (y < height - 1) push(x, y + 1)
        }

        val out = BooleanArray(mask.size)
        for (i in mask.indices) {
            // 前景保留；背景但"到不了边框"的像素是内部孔洞，补成前景
            out[i] = mask[i] || !reachable[i]
        }
        return out
    }

    /** 盒式模糊羽化：对二值掩膜做 (2r+1)² 均值，得到 0/1 之间的软边。 */
    private fun feather(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val integral = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            for (x in 0 until width) {
                if (mask[y * width + x]) rowSum++
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }
        val kernel = (2 * radius + 1) * (2 * radius + 1)
        val half = kernel / 2
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(width - 1, x + radius)
                val sum = integral[(y1 + 1) * (width + 1) + (x1 + 1)] -
                    integral[y0 * (width + 1) + (x1 + 1)] -
                    integral[(y1 + 1) * (width + 1) + x0] +
                    integral[y0 * (width + 1) + x0]
                out[y * width + x] = sum >= half
            }
        }
        return out
    }
}
