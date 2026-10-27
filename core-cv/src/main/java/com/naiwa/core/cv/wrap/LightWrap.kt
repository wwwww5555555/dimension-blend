package com.naiwa.core.cv.wrap

import com.naiwa.core.cv.blend.BlendModes
import com.naiwa.core.cv.util.BoxBlur
import com.naiwa.core.cv.util.DistanceField

/**
 * 光包裹（Light Wrap）—— 规格 8.2，"消除贴图感的终极武器"。
 *
 * 【与 LightWrapGradient 的分工】四色渐变（规格 7.4）给奶蛙**整体**着环境色（Soft Light，
 * 低频大范围）；本算子给奶蛙**轮廓边缘**叠加"背景溢出光"（Screen，高频窄带）。
 * 两步同源不同带：一个让色调融合，一个让边缘融合。
 *
 * 【四步流程】
 *  1. 背景高强度模糊（σ 按分辨率缩放，1080p 基准 22px，规格 15~30）—— 提取背景的光源颜色
 *     与氛围，抖动细节全部滤掉，只剩"这里大概是草地绿/天空蓝"；
 *  2. Wrap Mask：奶蛙轮廓的**环绕带** —— 轮廓内侧 3px（渐出）+ 外侧 4px（渐出），
 *     用两次 Chamfer 距离场构造（mask 内到边界 / mask 外到边界），再 σ=3 模糊平滑衔接；
 *  3. 溢出光 = 模糊背景 × Wrap Mask；
 *  4. Screen 叠加：`out = screen(composite, spill × opacity)`。
 *
 * 【执行时机：合成层而非奶蛙图层（与规格 8.5 步骤 4 的顺序差异，有意为之）】
 * 环绕带有一部分落在奶蛙 alpha 外侧（外扩的意义就在这），而 alpha 外没有像素可承载 ——
 * 若在融合前对奶蛙图层做，要么破坏 alpha 契约（融合掩膜跟着变），要么被 mask 裁掉失去
 * "外扩"的意义。因此在**融合回贴后的合成结果**上以 Screen 叠加：落在奶蛙内侧的部分给
 * 边缘染环境色（规格的诉求），落在背景外侧的部分就是自然的镜头光晕。
 *
 * 【性能】背景模糊在 1/4 分辨率做（downsample → blur → upsample），1080p 全程
 * 两次距离场 + 降采样模糊约 100~200ms 级；只在 FINAL_PREVIEW / EXPORT 执行。
 *
 * 全部为纯 IntArray/FloatArray 运算，可 JVM 单测。
 */
object LightWrap {

    /** 背景模糊 σ 基准（1080p 工作域；规格 15~30）。 */
    private const val BG_SIGMA_AT_1080 = 22f

    /** 环绕带：轮廓内侧渐出宽度（px）。 */
    const val INNER_FADE_PX = 3f

    /** 环绕带：轮廓外侧渐出宽度（px，规格 dilate 2~4 取上限）。 */
    const val OUTER_FADE_PX = 4f

    /** Wrap Mask 平滑 σ。 */
    private const val MASK_SIGMA = 3f

    /** 默认不透明度（规格 8.2：0.4）。 */
    const val DEFAULT_OPACITY = 0.4f

    /**
     * 把背景溢出光以 Screen 模式叠到合成结果上。
     *
     * @param compositePixels 合成输出（融合 + 回贴后的图，会被本函数基于其副本修改）
     * @param scenePixels 原始背景（提供模糊光色）
     * @param frogMask 前景掩膜（0~255），与图像同尺寸
     * @param opacity [0,1]，规格默认 0.4；**0 直接原样返回**
     * @return 新数组
     */
    fun apply(
        compositePixels: IntArray,
        scenePixels: IntArray,
        frogMask: IntArray,
        width: Int,
        height: Int,
        opacity: Float,
    ): IntArray {
        val total = width * height
        if (width <= 0 || height <= 0 ||
            compositePixels.size < total || scenePixels.size < total || frogMask.size < total
        ) {
            return compositePixels.copyOf()
        }
        val op = opacity.coerceIn(0f, 1f)
        if (op <= 0f) return compositePixels.copyOf()

        // ---- 步骤 2：Wrap Mask（环绕带） ----
        val bin = IntArray(total) { if (frogMask[it] >= 128) 1 else 0 }
        val innerDist = DistanceField.compute(bin, width, height)          // mask 内：到边界
        val outerBin = IntArray(total) { if (bin[it] == 1) 0 else 1 }
        val outerDist = DistanceField.compute(outerBin, width, height)     // mask 外：到边界

        val mask01 = FloatArray(total)
        for (i in 0 until total) {
            mask01[i] = when {
                bin[i] == 1 -> if (innerDist[i] < INNER_FADE_PX) 1f - innerDist[i] / INNER_FADE_PX else 0f
                else -> if (outerDist[i] < OUTER_FADE_PX) 1f - outerDist[i] / OUTER_FADE_PX else 0f
            }
        }
        val wrapMask = BoxBlur.blur(mask01, width, height, BoxBlur.radiusForSigma(MASK_SIGMA), passes = 1)

        // ---- 步骤 1：背景高强度模糊（1/4 分辨率降采样域） ----
        val ds = downsampleRgb(scenePixels, width, height)
        val bgSmall = ds.first
        val sw = ds.second[0]
        val sh = ds.second[1]
        // σ 在 1/4 域相应缩小，等效原域 σ
        val sigmaSmall = (BG_SIGMA_AT_1080 * minOf(width, height) / 1080f / 4f).coerceIn(2f, 8f)
        val radius = BoxBlur.radiusForSigma(sigmaSmall)
        val bgR = BoxBlur.blur(bgSmall[0], sw, sh, radius, passes = 2)
        val bgG = BoxBlur.blur(bgSmall[1], sw, sh, radius, passes = 2)
        val bgB = BoxBlur.blur(bgSmall[2], sw, sh, radius, passes = 2)

        // ---- 步骤 3 + 4：溢出光 = 模糊背景 × mask，Screen 叠加 ----
        val out = compositePixels.copyOf()
        for (y in 0 until height) {
            // 双线性上采样坐标（逐行只算一次 y 方向权重）
            val syF = y.toFloat() * (sh - 1) / (height - 1).coerceAtLeast(1)
            val sy0 = syF.toInt().coerceIn(0, sh - 1)
            val sy1 = (sy0 + 1).coerceIn(0, sh - 1)
            val wy = syF - sy0
            for (x in 0 until width) {
                val i = y * width + x
                val m = wrapMask[i]
                if (m <= 0.004f) continue

                val sxF = x.toFloat() * (sw - 1) / (width - 1).coerceAtLeast(1)
                val sx0 = sxF.toInt().coerceIn(0, sw - 1)
                val sx1 = (sx0 + 1).coerceIn(0, sw - 1)
                val wx = sxF - sx0

                val r00 = bgR[sy0 * sw + sx0]; val r01 = bgR[sy0 * sw + sx1]
                val r10 = bgR[sy1 * sw + sx0]; val r11 = bgR[sy1 * sw + sx1]
                val top = r00 + (r01 - r00) * wx
                val bottom = r10 + (r11 - r10) * wx
                val br = (top + (bottom - top) * wy) * m * op

                val g00 = bgG[sy0 * sw + sx0]; val g01 = bgG[sy0 * sw + sx1]
                val g10 = bgG[sy1 * sw + sx0]; val g11 = bgG[sy1 * sw + sx1]
                val gtop = g00 + (g01 - g00) * wx
                val gbottom = g10 + (g11 - g10) * wx
                val bg = (gtop + (gbottom - gtop) * wy) * m * op

                val b00 = bgB[sy0 * sw + sx0]; val b01 = bgB[sy0 * sw + sx1]
                val b10 = bgB[sy1 * sw + sx0]; val b11 = bgB[sy1 * sw + sx1]
                val btop = b00 + (b01 - b00) * wx
                val bbottom = b10 + (b11 - b10) * wx
                val bb = (btop + (bbottom - btop) * wy) * m * op

                if (br <= 0.001f && bg <= 0.001f && bb <= 0.001f) continue
                val c = out[i]
                val nr = BlendModes.screen((c shr 16 and 0xFF) / 255f, br.coerceIn(0f, 1f))
                val ng = BlendModes.screen((c shr 8 and 0xFF) / 255f, bg.coerceIn(0f, 1f))
                val nb = BlendModes.screen((c and 0xFF) / 255f, bb.coerceIn(0f, 1f))
                out[i] = (c and 0xFF000000.toInt()) or
                    ((nr * 255f).toInt().coerceIn(0, 255) shl 16) or
                    ((ng * 255f).toInt().coerceIn(0, 255) shl 8) or
                    ((nb * 255f).toInt().coerceIn(0, 255))
            }
        }
        return out
    }

    /** RGB 三通道拆分 + 1/4 下采样（每输出像素 = 原 4×4 块均值），返回 (三通道数组, [宽, 高])。 */
    private fun downsampleRgb(pixels: IntArray, width: Int, height: Int): Pair<Array<FloatArray>, IntArray> {
        val sw = (width / 4).coerceAtLeast(1)
        val sh = (height / 4).coerceAtLeast(1)
        val chR = FloatArray(sw * sh)
        val chG = FloatArray(sw * sh)
        val chB = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (dy in 0 until 4) {
                    val yy = (y * 4 + dy).coerceAtMost(height - 1)
                    for (dx in 0 until 4) {
                        val xx = (x * 4 + dx).coerceAtMost(width - 1)
                        val c = pixels[yy * width + xx]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                    }
                }
                val idx = y * sw + x
                chR[idx] = r / n; chG[idx] = g / n; chB[idx] = b / n
            }
        }
        return arrayOf(chR, chG, chB) to intArrayOf(sw, sh)
    }
}
