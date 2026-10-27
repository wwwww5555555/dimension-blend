package com.naiwa.core.cv.color

import com.naiwa.core.common.model.HarmonizeRequest
import com.naiwa.core.common.model.HarmonizeResult
import com.naiwa.core.common.model.HarmonizerBackend
import kotlin.math.sqrt

/**
 * MKL（Monge-Kantorovich Linear）最优传输颜色迁移 —— 规格 8.2 的**数学核心**。
 *
 * 背景：MKL-Harmonizer（AAAI 2026）的完整方案是「神经网络编码器预测 12 个参数」，
 * 但它的理论根基是经典最优传输：当源/目标颜色分布都近似多元高斯时，最优传输映射有**闭式线性解**：
 * ```
 *   T(x) = μ1 + A·(x − μ0)
 *   A = Σ0^(−1/2) · (Σ0^(1/2) · Σ1 · Σ0^(1/2))^(1/2) · Σ0^(−1/2)
 * ```
 * 即一个 3×3 矩阵 A + 3 维偏移 S（共 12 个参数），对前景像素做仿射颜色变换。
 *
 * 【本类能做什么 / 不能做什么】本类实现的是**闭式解本身**——给定源（奶蛙）与目标（场景背景）
 * 的颜色统计量，直接算出 A 和 S 并应用。这不需要任何训练权重，是**传统 CV 的颜色迁移升级版**，
 * 相比现有的 Reinhard（只匹配均值+方差的对角缩放），MKL 还匹配了**通道间协方差**（色度耦合），
 * 对"二次元前景 + 写实背景"这种跨域场景，协方差匹配能压掉"高饱和贴纸感"。
 *
 * 它**不是**神经网络编码器（EfficientNet-B0 预测 A/S 那部分），那是训练权重，本仓库不提供
 * （与 MobileFusion / UHD-MFF 同一原则：结构就位、权重可原位替换）。
 *
 * 【数值稳定性】Σ0^(−1/2) 需要对协方差矩阵开方求逆，用特征分解实现，并加 jitter 防止奇异。
 * 协方差对角元素若接近 0（纯色区域），会得到病态矩阵——此时退化为对角缩放（Reinhard 行为）。
 */
object MklHarmonizer {

    /** 协方差正则化 jitter，防止奇异矩阵求逆爆炸。 */
    private const val JITTER = 1e-3f

    /** 协方差对角元素低于此阈值时，判定为"退化分布"，退化为对角缩放。 */
    private const val DEGENERATE_EPS = 1e-4f

    /**
     * 高层入口：对奶蛙前景做 MKL 和谐化（实现 [HarmonizerBackend.MKL]）。
     *
     * 流程：从 [HarmonizeRequest] 统计源（前景）与目标（背景）的均值+协方差
     * → 计算 12 维仿射参数 → 应用到前景像素（仅 mask > 0 的像素）。
     * 不抛异常；mask 全空时返回原前景。
     */
    fun harmonize(request: HarmonizeRequest): HarmonizeResult {
        val w = request.width
        val h = request.height
        val fg = request.foregroundPixels
        val bg = request.backgroundPixels
        val mask = request.mask

        val src = computeStats(fg, mask, w, h)
        val dst = computeStats(bg, null, w, h)
        val (a, s) = computeParams(src.first, src.second, dst.first, dst.second)

        val out = fg.copyOf()
        val rgb = FloatArray(3)
        for (i in out.indices) {
            if (mask[i] == 0) continue
            val c = fg[i]
            rgb[0] = ((c shr 16) and 0xFF) / 255f
            rgb[1] = ((c shr 8) and 0xFF) / 255f
            rgb[2] = (c and 0xFF) / 255f
            val mapped = applyPixel(rgb, a, s)
            val nr = (mapped[0] * 255f).toInt().coerceIn(0, 255)
            val ng = (mapped[1] * 255f).toInt().coerceIn(0, 255)
            val nb = (mapped[2] * 255f).toInt().coerceIn(0, 255)
            out[i] = ((c ushr 24) shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        // params 展平为 12 维：A(9) + S(3)
        val params = FloatArray(12) { i -> if (i < 9) a[i] else s[i - 9] }
        return HarmonizeResult(out, HarmonizerBackend.MKL, params)
    }

    /** 统计 RGB 均值 + 协方差（3×3）。mask 非 null 时只统计 mask>0 的像素。 */
    private fun computeStats(
        pixels: IntArray,
        mask: IntArray?,
        width: Int,
        height: Int,
    ): Pair<FloatArray, FloatArray> {
        val mean = FloatArray(3)
        val cov = FloatArray(9)
        var n = 0
        val rgb = FloatArray(3)

        // 第一遍：算均值
        for (i in pixels.indices) {
            if (mask != null && mask[i] == 0) continue
            val c = pixels[i]
            rgb[0] = ((c shr 16) and 0xFF) / 255f
            rgb[1] = ((c shr 8) and 0xFF) / 255f
            rgb[2] = (c and 0xFF) / 255f
            mean[0] += rgb[0]
            mean[1] += rgb[1]
            mean[2] += rgb[2]
            n++
        }
        if (n == 0) {
            // 空区域兜底：中性灰 + 单位协方差
            return floatArrayOf(0.5f, 0.5f, 0.5f) to floatArrayOf(
                0.05f, 0f, 0f, 0f, 0.05f, 0f, 0f, 0f, 0.05f,
            )
        }
        mean[0] /= n
        mean[1] /= n
        mean[2] /= n

        // 第二遍：算协方差
        for (i in pixels.indices) {
            if (mask != null && mask[i] == 0) continue
            val c = pixels[i]
            rgb[0] = ((c shr 16) and 0xFF) / 255f - mean[0]
            rgb[1] = ((c shr 8) and 0xFF) / 255f - mean[1]
            rgb[2] = (c and 0xFF) / 255f - mean[2]
            for (a in 0 until 3) {
                for (b in 0 until 3) {
                    cov[a * 3 + b] += rgb[a] * rgb[b] / n
                }
            }
        }
        return mean to cov
    }

    /**
     * 从源/目标像素统计量计算 MKL 仿射变换参数。
     *
     * @param srcMean 源（奶蛙前景）的 RGB 均值（3 维）
     * @param srcCov 源协方差（3×3，行主序）
     * @param dstMean 目标（场景背景）的 RGB 均值
     * @param dstCov 目标协方差（3×3，行主序）
     * @return `[A(3×3 行主序, 9 个), S(偏移, 3 个)]`，共 12 个参数；退化时返回对角缩放
     */
    fun computeParams(
        srcMean: FloatArray,
        srcCov: FloatArray,
        dstMean: FloatArray,
        dstCov: FloatArray,
    ): Pair<FloatArray, FloatArray> {
        require(srcMean.size == 3 && dstMean.size == 3) { "均值必须为 3 维" }
        require(srcCov.size == 9 && dstCov.size == 9) { "协方差必须为 3×3" }

        // 检测退化：源协方差对角接近 0 → 退化为对角缩放（等价 Reinhard）
        val degenerate = (0 until 3).any { srcCov[it * 3 + it] < DEGENERATE_EPS }

        val a = FloatArray(9)
        val s = FloatArray(3)

        if (degenerate) {
            // 对角缩放：A = diag(σ1/σ0)，S = μ1 − A·μ0
            for (i in 0 until 3) {
                val s0 = sqrt((srcCov[i * 3 + i] + JITTER).toDouble()).toFloat()
                val s1 = sqrt((dstCov[i * 3 + i] + JITTER).toDouble()).toFloat()
                val scale = if (s0 < 1e-6f) 1f else s1 / s0
                a[i * 3 + i] = scale
                s[i] = dstMean[i] - scale * srcMean[i]
            }
            return a to s
        }

        // 加 jitter 到源协方差，保证可逆
        val src = FloatArray(9) { i ->
            if (i % 4 == 0) srcCov[i] + JITTER else srcCov[i]
        }

        // A = Σ0^(−1/2) · (Σ0^(1/2) · Σ1 · Σ0^(1/2))^(1/2) · Σ0^(−1/2)
        val sqrtInvSrc = matrixPowSym(src, -0.5f)      // Σ0^(−1/2)
        val sqrtSrc = matrixPowSym(src, 0.5f)          // Σ0^(1/2)
        val mid = matMul3(matMul3(sqrtSrc, dstCov), sqrtSrc)  // Σ0^(1/2)·Σ1·Σ0^(1/2)
        val sqrtMid = matrixPowSym(mid, 0.5f)          // 中括号开方
        val aFull = matMul3(matMul3(sqrtInvSrc, sqrtMid), sqrtInvSrc)

        for (i in 0 until 9) a[i] = aFull[i]
        // S = μ1 − A·μ0
        for (i in 0 until 3) {
            var ax = 0f
            for (j in 0 until 3) ax += a[i * 3 + j] * srcMean[j]
            s[i] = dstMean[i] - ax
        }
        return a to s
    }

    /**
     * 对单像素应用 MKL 仿射变换：`out = A·rgb + S`。
     */
    fun applyPixel(rgb: FloatArray, a: FloatArray, s: FloatArray): FloatArray {
        val out = FloatArray(3)
        for (i in 0 until 3) {
            var v = s[i]
            for (j in 0 until 3) v += a[i * 3 + j] * rgb[j]
            out[i] = v.coerceIn(0f, 1f)
        }
        return out
    }

    // ---------------------------------------------------------------- 线性代数（3×3，自实现避免引入库）

    /** 3×3 矩阵乘（行主序）。 */
    private fun matMul3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var v = 0f
                for (k in 0 until 3) v += a[i * 3 + k] * b[k * 3 + j]
                out[i * 3 + j] = v
            }
        }
        return out
    }

    /**
     * 对称正定矩阵的幂 `M^p`（通过特征分解：M = Q·Λ·Qᵀ，M^p = Q·Λ^p·Qᵀ）。
     * 用 3×3 的闭式 Jacobi 迭代求特征值/特征向量（对称矩阵，收敛快）。
     */
    private fun matrixPowSym(m: FloatArray, p: Float): FloatArray {
        val eigen = eigenDecomposeSym(m)
        val values = eigen.first   // 特征值
        val vectors = eigen.second // 特征向量（列）
        // Λ^p
        val diag = FloatArray(3) { i ->
            val v = values[i].coerceAtLeast(0f) // 半正定，钳掉数值误差的微小负值
            Math.pow(v.toDouble(), p.toDouble()).toFloat()
        }
        // 重建：M^p = V · diag(Λ^p) · Vᵀ
        val vt = FloatArray(9) // 先算 V·diag(Λ^p)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                vt[i * 3 + j] = vectors[i * 3 + j] * diag[j]
            }
        }
        // 再 × Vᵀ（即 vectors 的转置）
        val out = FloatArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                var v = 0f
                for (k in 0 until 3) v += vt[i * 3 + k] * vectors[j * 3 + k]
                out[i * 3 + j] = v
            }
        }
        return out
    }

    /**
     * 3×3 对称矩阵的特征分解（Jacobi 迭代），返回 `(特征值[3], 特征向量列主序[9])`。
     */
    private fun eigenDecomposeSym(m: FloatArray): Pair<FloatArray, FloatArray> {
        val a = m.copyOf()
        // 特征向量初始化为单位矩阵（列主序存放：v[i*3+k] 是第 k 个特征向量的第 i 分量）
        val v = FloatArray(9) { i -> if (i % 4 == 0) 1f else 0f }

        for (iter in 0 until 50) {
            // 找最大的非对角元素
            var p = 0
            var q = 1
            var max = -1f
            for (i in 0 until 3) {
                for (j in i + 1 until 3) {
                    val abs = kotlin.math.abs(a[i * 3 + j])
                    if (abs > max) { max = abs; p = i; q = j }
                }
            }
            if (max < 1e-8f) break

            val app = a[p * 3 + p]
            val aqq = a[q * 3 + q]
            val apq = a[p * 3 + q]
            val phi = 0.5f * kotlin.math.atan2(2f * apq, aqq - app)
            val c = kotlin.math.cos(phi)
            val s = kotlin.math.sin(phi)

            // 旋转 A
            val apq2 = c * c * apq - s * s * apq + c * s * (aqq - app)
            for (k in 0 until 3) {
                if (k == p || k == q) continue
                val akp = a[k * 3 + p]
                val akq = a[k * 3 + q]
                a[k * 3 + p] = c * akp + s * akq
                a[p * 3 + k] = a[k * 3 + p]
                a[k * 3 + q] = -s * akp + c * akq
                a[q * 3 + k] = a[k * 3 + q]
            }
            a[p * 3 + p] = c * c * app + 2f * c * s * apq + s * s * aqq
            a[q * 3 + q] = s * s * app - 2f * c * s * apq + c * c * aqq
            a[p * 3 + q] = apq2
            a[q * 3 + p] = apq2

            // 旋转 V（列）
            for (k in 0 until 3) {
                val vkp = v[k * 3 + p]
                val vkq = v[k * 3 + q]
                v[k * 3 + p] = c * vkp + s * vkq
                v[k * 3 + q] = -s * vkp + c * vkq
            }
        }

        val values = FloatArray(3) { a[it * 3 + it] }
        return values to v
    }
}
