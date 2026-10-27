package com.naiwa.core.cv.edge

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **6.3 Alpha 边缘柔化 + 6.4 方向性边缘柔化** —— 去掉透明过渡的硬边阶梯感。
 *
 * 两步都**只改 alpha、不改 RGB**。这与 [EdgeDefringe] 恰好互补，顺序也不能颠倒：
 * 先用 [EdgeDefringe] 把颜色修正干净，再来柔化 alpha；反过来会把污染颜色摊开（规格 6.1 的告诫）。
 *
 * ## 6.3 各向同性小半径
 * 半径取 `clamp(0.5 × (1 - softness), 0.3, 1.5)` px（规格给定），
 * 且**只在边缘带（alpha 0.01~0.99）生效**：实心区与完全透明区保持原值。
 * 这样做的意义是"只磨平过渡"，不会让主体变瘦或让透明区出现雾气。
 *
 * ## 6.4 沿边缘**切线**方向的方向性模糊
 * 用 alpha 的梯度定出边缘法线，取法线的**垂直方向**（即切线）做 1D 高斯卷积。
 * 侧向（切向）平滑能磨掉轮廓的锯齿/阶梯，同时**不blur 跨边界的梯度** ——
 * 跨边界模糊才是把轮廓"抹平"的原因。
 *
 * **关于规格里的一句歧义表述**：规格同时写了"沿边缘切线方向柔化"与
 * "水平走向的边缘用垂直模糊，竖直走向的边缘用水平模糊"。后者其实描述的是**法线方向**，
 * 与前者相反，而规格自己给出的理由是"更保边、避免轮廓被抹平" ——
 * 只有**切向**才满足这个理由（法向模糊会直接把轮廓抹平）。
 * 因此本实现按**切向**执行，并在此明确记录这一取舍。
 *
 * 梯度取自轻度模糊后的 alpha：原始 alpha 的阶梯会让梯度噪声很大，方向判断会抖。
 */
object AlphaEdgeSoften {

    /** 边缘带下界：alpha 低于此值视为完全透明。 */
    private const val EDGE_LOW = 0.01f

    /** 边缘带上界：alpha 高于此值视为实心。 */
    private const val EDGE_HIGH = 0.99f

    /** 6.3 半径区间（规格给定）。 */
    private const val SOFTEN_RADIUS_MIN = 0.3f
    private const val SOFTEN_RADIUS_MAX = 1.5f
    /** 6.4 方向性模糊的核半径（规格：1~2 px）。 */
    private const val DIRECTIONAL_RADIUS = 2

    /** 6.4 方向性核的 sigma。 */
    private const val DIRECTIONAL_SIGMA = 0.9f

    /** 梯度太弱就不做方向性模糊：纯色区的"边缘方向"是无意义的噪声。 */
    private const val MIN_GRADIENT = 0.02f

    /** 计算梯度前对 alpha 的轻度模糊 sigma，用于抑制阶梯造成的方向抖动。 */
    private const val GRADIENT_PRE_BLUR_SIGMA = 0.8f

    data class Result(
        /** 处理后的像素（新数组）。 */
        val pixels: IntArray,
        /** 6.3 实际改动的边缘像素数。 */
        val softenedPixels: Int,
        /** 6.4 实际改动的边缘像素数。 */
        val directionalPixels: Int,
    )

    /**
     * 依次执行 6.3 与 6.4。
     *
     * @param softness 阴影柔和度参数（0~1），只影响 6.3 的半径
     */
    fun soften(pixels: IntArray, width: Int, height: Int, softness: Float): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Result(pixels.copyOf(), softenedPixels = 0, directionalPixels = 0)
        }

        val alpha = FloatArray(width * height) { i -> ((pixels[i] ushr 24) and 0xFF) / 255f }

        // ---------- 6.3 各向同性 ----------
        // 规格公式：radius = clamp(0.5 × (1 - softness), 0.3, 1.5)
        //
        // ⚠️ 【两处必须记下来的事实】
        //  1. `0.5 × (1 - softness)` 的取值区间是 **[0, 0.5]**，所以 clamp 的**上界 1.5 永远到不了** ——
        //     这条公式实际只能产出 **[0.3, 0.5]**。也就是说 6.3 被设计成**极轻的一档**，
        //     可见的柔化主要来自 6.4（半径 1~2px）。想放大 6.3 只需改这里的乘数或上界（一处）。
        //  2. 这个值按 **σ 使用**，不再除以 2。早期实现把它当"核半径"、取 σ = value/2 = 0.15，
        //     结果高斯核在 float 精度下退化成单位脉冲（exp(-1/(2·0.0225)) ≈ 2e-10，
        //     归一化后中心项就是 1.0f）—— **6.3 等于完全没生效**（实测 softenedPixels = 0）。
        //     按 σ 使用后 σ ∈ [0.3, 0.5]，核为 [0.0038, 0.9924, 0.0038] 量级，是非退化的真实模糊。
        val sigma = (0.5f * (1f - softness.coerceIn(0f, 1f)))
            .coerceIn(SOFTEN_RADIUS_MIN, SOFTEN_RADIUS_MAX)
        var blurred = blurSeparable(alpha, width, height, gaussianKernel(sigma))

        var softened = 0
        val afterIso = FloatArray(alpha.size)
        for (i in alpha.indices) {
            val a = alpha[i]
            if (a > EDGE_LOW && a < EDGE_HIGH) {
                afterIso[i] = blurred[i]
                if (blurred[i] != a) softened++
            } else {
                // 实心区与完全透明区保持原值
                afterIso[i] = a
            }
        }

        // ---------- 6.4 方向性（沿切线） ----------
        val gradientSource = blurSeparable(afterIso, width, height, gaussianKernel(GRADIENT_PRE_BLUR_SIGMA))
        val kernel = gaussianKernel(DIRECTIONAL_SIGMA).let { k ->
            // 只保留半径内的抽头（规格：半径 1~2 px）
            val half = k.size / 2
            FloatArray(2 * DIRECTIONAL_RADIUS + 1) { idx ->
                val offset = idx - DIRECTIONAL_RADIUS
                if (kotlin.math.abs(offset) <= half) k[offset + half] else 0f
            }
        }
        val kernelSum = kernel.sum().takeIf { it > 0f } ?: 1f

        val out = IntArray(pixels.size)
        var directional = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                val a = afterIso[idx]
                if (a <= EDGE_LOW || a >= EDGE_HIGH) {
                    out[idx] = withAlpha(pixels[idx], a)
                    continue
                }

                // 梯度（中心差分）
                val fx = x.toFloat()
                val fy = y.toFloat()
                val gx = sample(gradientSource, width, height, fx + 1f, fy) -
                    sample(gradientSource, width, height, fx - 1f, fy)
                val gy = sample(gradientSource, width, height, fx, fy + 1f) -
                    sample(gradientSource, width, height, fx, fy - 1f)
                val magnitude = sqrt(gx * gx + gy * gy)
                if (magnitude < MIN_GRADIENT) {
                    out[idx] = withAlpha(pixels[idx], a)
                    continue
                }

                // 法线 = (gx, gy)；切线 = 法线旋转 90° = (-gy, gx)
                val inv = 1f / magnitude
                val tx = -gy * inv
                val ty = gx * inv

                var sum = 0f
                for (k in kernel.indices) {
                    if (kernel[k] == 0f) continue
                    val offset = (k - DIRECTIONAL_RADIUS).toFloat()
                    sum += kernel[k] * sample(
                        afterIso, width, height,
                        fx + tx * offset, fy + ty * offset,
                    )
                }
                val newA = sum / kernelSum
                if (newA != a) directional++
                out[idx] = withAlpha(pixels[idx], newA)
            }
        }

        return Result(pixels = out, softenedPixels = softened, directionalPixels = directional)
    }

    /** 只替换 alpha，RGB 原样保留（本步骤的契约：不碰颜色）。 */
    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /** 双线性采样（浮点坐标，越界钳制）。 */
    private fun sample(src: FloatArray, width: Int, height: Int, fx: Float, fy: Float): Float {
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val dx = fx - x0
        val dy = fy - y0
        val x1 = (x0 + 1).coerceIn(0, width - 1)
        val y1 = (y0 + 1).coerceIn(0, height - 1)
        val cx0 = x0.coerceIn(0, width - 1)
        val cy0 = y0.coerceIn(0, height - 1)
        val v00 = src[cy0 * width + cx0]
        val v10 = src[cy0 * width + x1]
        val v01 = src[y1 * width + cx0]
        val v11 = src[y1 * width + x1]
        return (v00 * (1 - dx) + v10 * dx) * (1 - dy) + (v01 * (1 - dx) + v11 * dx) * dy
    }

    /** 归一化一维高斯核。 */
    private fun gaussianKernel(sigma: Float): FloatArray {
        val s = max(0.05f, sigma)
        val half = max(1, ceil(2.5f * s).toInt())
        val kernel = FloatArray(2 * half + 1)
        var sum = 0f
        for (i in -half..half) {
            val v = exp(-(i * i).toFloat() / (2f * s * s))
            kernel[i + half] = v
            sum += v
        }
        if (sum > 0f) for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    /** 可分离高斯模糊（先横后纵，边缘钳制）。 */
    private fun blurSeparable(
        src: FloatArray,
        width: Int,
        height: Int,
        kernel: FloatArray,
    ): FloatArray {
        val half = kernel.size / 2
        val horizontal = FloatArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) {
                    val xx = (x + k - half).coerceIn(0, width - 1)
                    sum += kernel[k] * src[row + xx]
                }
                horizontal[row + x] = sum
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) {
                    val yy = (y + k - half).coerceIn(0, height - 1)
                    sum += kernel[k] * horizontal[yy * width + x]
                }
                out[row + x] = sum.coerceIn(0f, 1f)
            }
        }
        return out
    }
}
