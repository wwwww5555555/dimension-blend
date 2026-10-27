package com.naiwa.core.cv.fusion

import kotlin.math.max

/**
 * **结构重参数化卷积** —— 训练/设计期用多分支，推理期折叠成单路径。
 *
 * 这是 MobileFusion（ICML 2026, arXiv 见 docs/ADVANCED-FUSION-2026.md）的核心机制：
 * 「训练时的复杂度」带来更强的表达力，但**推理时不需要**——把多个并行的线性分支
 * 数学等价地合并成一个卷积，延迟与访存立刻降下来，而输出完全不变。
 *
 * ## 为什么这个机制值得在离线项目里落地
 * 它是一个**纯数学恒等变换**，不需要模型、不需要训练、不需要网络：
 * 因此可以在本仓库里完整实现并用单测证明"折叠前后逐像素一致"。
 * 换句话说：这一条不是"押注某个生成式模型"，而是把已经验证过的工程手法拿过来。
 *
 * ## 结构
 * ```
 * 输入 ──┬─ 5×5 conv ─┐
 *        ├─ 3×3 conv ─┤
 *        ├─ 1×3 conv ─┼─ concat(通道维) ─ 1×1 conv ─ 输出
 *        ├─ 3×1 conv ─┤
 *        └─ 1×1 conv ─┘
 * ```
 * 训练期各分支**拼接**（而不是相加）后再用 1×1 投影 —— 论文的消融结论是拼接显著优于相加，
 * 因为不同核形状提取的是互补特征（1×3 / 3×1 专门抓水平/垂直结构）。
 *
 * ## 折叠数学（[fold]）
 * 拼接 + 1×1 投影整体仍是**线性**的，所以可以精确合并：
 * ```
 * W_fold[o][c][i][j] = Σ_b Σ_m  P[o][b·M + m] · pad_K(W_b[m][c])[i][j]
 * b_fold[o]          = b_P[o] + Σ_b Σ_m P[o][b·M + m] · b_b[m]
 * ```
 * 其中 `pad_K` 是把 (kh×kw) 的核居中嵌进 K×K（K = 最大核尺寸）并四周补零。
 * 注意：**分支之间不能有激活函数**，否则不再线性、不可折叠 —— 需要非线性时，
 * 把激活放在折叠块**之间**（见 [RepBlock]）。
 *
 * ## 权重约定
 * 权重数组是**公开可变**的：设计期/训练期写入，推理期只读（[fold] 是纯函数，不改状态）。
 * 布局：
 *  - [branchWeights]`[b]`：形状 `[mid][in][kh][kw]` 扁平化
 *  - [branchBiases]`[b]`：形状 `[mid]`
 *  - [projection]：形状 `[out][midTotal]`，`midTotal = midChannels × 分支数`
 *  - [projectionBias]：形状 `[out]`
 *
 * 所有卷积都是 **same padding + 边缘钳制**（越界读边缘像素）。多分支版与折叠版必须用
 * 完全一致的填充约定，否则 [fold] 的等价性无从谈起。
 */
class RepConv(
    val inChannels: Int,
    val midChannels: Int,
    val outChannels: Int,
    /** 每个分支的核尺寸 (kh, kw)，必须都是奇数 */
    val branchKernels: List<Pair<Int, Int>>,
) {

    init {
        require(branchKernels.isNotEmpty()) { "至少要有一个分支" }
        require(branchKernels.all { it.first > 0 && it.second > 0 && it.first % 2 == 1 && it.second % 2 == 1 }) {
            "核尺寸必须为正奇数（保证中心对齐）：$branchKernels"
        }
        require(inChannels > 0 && midChannels > 0 && outChannels > 0)
    }

    /** K：折叠后单路径卷积的核尺寸 = 最大分支核尺寸。 */
    val kernelSize: Int = branchKernels.maxOf { max(it.first, it.second) }

    /** 拼接后的中间通道总数。 */
    val midTotal: Int = midChannels * branchKernels.size

    val branchWeights: Array<FloatArray> = Array(branchKernels.size) { b ->
        FloatArray(midChannels * inChannels * branchKernels[b].first * branchKernels[b].second)
    }

    val branchBiases: Array<FloatArray> = Array(branchKernels.size) { FloatArray(midChannels) }

    val projection: FloatArray = FloatArray(outChannels * midTotal)

    val projectionBias: FloatArray = FloatArray(outChannels)

    /** 参数总数（不含激活）。折叠不改变参数量，只改变算子的形态。 */
    val parameterCount: Int
        get() = branchWeights.sumOf { it.size } +
            branchBiases.sumOf { it.size } +
            projection.size +
            projectionBias.size

    /**
     * 用一个**确定性**的伪随机分布初始化（不使用 Random 的默认种子，保证跨机器可复现）。
     *
     * 用途：折叠等价性的单测需要在"所有分支都被真正用到"的权重下比对，
     * 因此不能给全零权重（全零时任何实现都能对上，测不出问题）。
     *
     * @param scale 初始化幅度
     */
    fun initDeterministic(seed: Long = 1L, scale: Float = 0.3f) {
        var s = seed
        fun next(): Float {
            // xorshift64*：确定性强、无需依赖 java.util.Random 的实现细节
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
        }
        branchWeights.forEach { arr -> for (i in arr.indices) arr[i] = next() * scale }
        branchBiases.forEach { arr -> for (i in arr.indices) arr[i] = next() * scale }
        for (i in projection.indices) projection[i] = next() * scale
        for (i in projectionBias.indices) projectionBias[i] = next() * scale
    }

    /**
     * 多分支前向（训练期形态）。
     *
     * @param input 平面布局 `[inChannels][height × width]`
     * @return 平面布局 `[outChannels][height × width]`
     */
    fun forwardMultiBranch(input: FloatArray, height: Int, width: Int): FloatArray {
        val hw = height * width
        val concat = FloatArray(midTotal * hw)

        for (b in branchKernels.indices) {
            val kh = branchKernels[b].first
            val kw = branchKernels[b].second
            val oh = kh / 2
            val ow = kw / 2
            val wB = branchWeights[b]
            val biasB = branchBiases[b]
            val midOff = b * midChannels

            for (m in 0 until midChannels) {
                val outBase = (midOff + m) * hw
                val wBase = m * inChannels * kh * kw
                val bias = biasB[m]
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) {
                        var sum = bias
                        for (ci in 0 until inChannels) {
                            val inBase = ci * hw
                            val wBase2 = wBase + ci * kh * kw
                            for (ky in 0 until kh) {
                                val sy = (y + ky - oh).coerceIn(0, height - 1)
                                val inRow = inBase + sy * width
                                val wRow = wBase2 + ky * kw
                                for (kx in 0 until kw) {
                                    val sx = (x + kx - ow).coerceIn(0, width - 1)
                                    sum += wB[wRow + kx] * input[inRow + sx]
                                }
                            }
                        }
                        concat[outBase + row + x] = sum
                    }
                }
            }
        }

        // 1×1 投影：把拼接后的 midTotal 个通道压到 outChannels
        //
        // 【踩坑记录】这里**不能**再累加各分支的偏置：`concat` 里存的是 `conv + branchBias`，
        // 偏置已经随 concat 走了一遍投影。早期版本在下面又加了一次 `p * branchBiases`，
        // 结果是"偏置翻倍"，而 `fold()` 只加一次 —— 折叠前后立刻对不上（实测差 0.2 量级）。
        // 折叠式里的 `bAcc += p * branchBiases` 才是必需的，因为那时 conv 与偏置已经分离。
        val out = FloatArray(outChannels * hw)
        for (o in 0 until outChannels) {
            val ob = o * hw
            val pb = o * midTotal
            for (i in 0 until hw) {
                var sum = projectionBias[o]
                for (m in 0 until midTotal) {
                    sum += projection[pb + m] * concat[m * hw + i]
                }
                out[ob + i] = sum
            }
        }
        return out
    }

    /**
     * 折叠成单路径卷积（推理期形态）。
     *
     * 这是"结构重参数化"的关键一步：**输出与 [forwardMultiBranch] 完全一致**（有单测逐点断言），
     * 但推理时只有一个 K×K 卷积，没有 concat、没有额外分支。
     *
     * @return 折叠后的卷积（纯数据，可缓存复用）
     */
    fun fold(): FoldedConv {
        val k = kernelSize
        val weight = FloatArray(outChannels * inChannels * k * k)
        val bias = FloatArray(outChannels)

        for (o in 0 until outChannels) {
            val pb = o * midTotal
            var bAcc = projectionBias[o]

            for (m in 0 until midTotal) {
                val p = projection[pb + m]
                val branch = m / midChannels
                val mi = m % midChannels
                bAcc += p * branchBiases[branch][mi]
                if (p == 0f) continue

                val kh = branchKernels[branch].first
                val kw = branchKernels[branch].second
                val oh = (k - kh) / 2
                val ow = (k - kw) / 2
                val wB = branchWeights[branch]
                val wBase = mi * inChannels * kh * kw

                for (ci in 0 until inChannels) {
                    val src = wBase + ci * kh * kw
                    val dst = (o * inChannels + ci) * k * k
                    for (ky in 0 until kh) {
                        val dRow = dst + (ky + oh) * k
                        for (kx in 0 until kw) {
                            weight[dRow + kx + ow] += p * wB[src + ky * kw + kx]
                        }
                    }
                }
            }
            bias[o] = bAcc
        }
        return FoldedConv(weight, bias, k, inChannels, outChannels)
    }
}

/**
 * 折叠后的单路径卷积。
 *
 * @param weight 布局 `[out][in][K][K]`
 * @param kernel K（奇数）
 */
class FoldedConv(
    val weight: FloatArray,
    val bias: FloatArray,
    val kernel: Int,
    val inChannels: Int,
    val outChannels: Int,
) {

    /**
     * 实际有效核尺寸：从最外圈往里剥，直到某一圈还有非零权重。
     *
     * 存在意义：多分支结构里常有关键分支权重为 0（例如只用 3×3 与 1×1 时，5×5 分支是零），
     * 折叠后会得到一个"外圈全零的 5×5 核"。此时按 3×3 计算能省 64% 的乘加，
     * 而结果**逐位相同**（零权重乘任何数都是 0）。
     */
    fun effectiveKernelSize(): Int {
        val center = kernel / 2
        var radius = center
        while (radius > 0 && !ringHasNonZero(radius, center)) {
            radius--
        }
        return 2 * radius + 1
    }

    /** 距中心 `radius` 的那一圈（方形环）上是否存在非零权重。 */
    private fun ringHasNonZero(radius: Int, center: Int): Boolean {
        val lo = center - radius
        val hi = center + radius
        for (o in 0 until outChannels) {
            for (ci in 0 until inChannels) {
                val base = (o * inChannels + ci) * kernel * kernel
                for (x in lo..hi) {
                    if (weight[base + lo * kernel + x] != 0f) return true
                    if (weight[base + hi * kernel + x] != 0f) return true
                }
                for (y in lo + 1 until hi) {
                    if (weight[base + y * kernel + lo] != 0f) return true
                    if (weight[base + y * kernel + hi] != 0f) return true
                }
            }
        }
        return false
    }

    /** 取权重（越界返回 0）。 */
    fun weightAt(o: Int, ci: Int, y: Int, x: Int): Float {
        if (y < 0 || x < 0 || y >= kernel || x >= kernel) return 0f
        return weight[(o * inChannels + ci) * kernel * kernel + y * kernel + x]
    }

    /**
     * 前向。填充约定必须与 [RepConv.forwardMultiBranch] 一致：same padding + 边缘钳制。
     *
     * @param effectiveK 实际使用的核尺寸；传 0 表示用 [effectiveKernelSize]
     */
    fun forward(
        input: FloatArray,
        height: Int,
        width: Int,
        effectiveK: Int = 0,
    ): FloatArray {
        val k = if (effectiveK > 0) effectiveK else kernel
        require(k % 2 == 1 && k <= kernel) { "有效核尺寸必须是 ≤ $kernel 的正奇数，当前 $k" }
        val half = k / 2
        val center = kernel / 2
        val hw = height * width
        val out = FloatArray(outChannels * hw)

        for (o in 0 until outChannels) {
            val ob = o * hw
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    var sum = bias[o]
                    for (ci in 0 until inChannels) {
                        val inBase = ci * hw
                        // 只遍历有效核窗口，但索引要落到原 K×K 的中心偏移上
                        for (ky in -half..half) {
                            val sy = (y + ky).coerceIn(0, height - 1)
                            val inRow = inBase + sy * width
                            val wy = ky + center
                            for (kx in -half..half) {
                                val w = weightAt(o, ci, wy, kx + center)
                                if (w == 0f) continue
                                val sx = (x + kx).coerceIn(0, width - 1)
                                sum += w * input[inRow + sx]
                            }
                        }
                    }
                    out[ob + row + x] = sum
                }
            }
        }
        return out
    }
}

/**
 * 折叠块：`RepConv → 激活 → RepConv`。
 *
 * 激活必须夹在两个可折叠卷积**之间**：一旦放进分支内部，分支就不再是线性的，
 * 也就无法折叠。这是结构重参数化的边界条件，不是实现偷懒。
 *
 * 本仓库没有用上这个块（细节保真算子本身是线性的，单个 [RepConv] 就够），
 * 但保留它是为了让"训练权重可原位替换"这件事成立：论文里的 RepFFN 需要非线性，
 * 接入时直接用这个块，折叠路径不变。
 */
class RepBlock(
    inChannels: Int,
    hiddenChannels: Int,
    outChannels: Int,
    branchKernels: List<Pair<Int, Int>> = DEFAULT_BRANCHES,
    /** PReLU 的负斜率。1 表示"取绝对值"（等价于 max(x,0) - min(x,0) = |x|）。 */
    val negativeSlope: Float = 0.25f,
) {
    val first = RepConv(inChannels, hiddenChannels, hiddenChannels, branchKernels)
    val second = RepConv(hiddenChannels, hiddenChannels, outChannels, branchKernels)

    fun forwardMultiBranch(input: FloatArray, height: Int, width: Int): FloatArray {
        val h = first.forwardMultiBranch(input, height, width)
        prelu(h)
        return second.forwardMultiBranch(h, height, width)
    }

    /** 折叠后仍然需要一次 PReLU —— 折叠只吃掉了线性部分。 */
    fun forwardFolded(input: FloatArray, height: Int, width: Int): FloatArray {
        val f1 = first.fold()
        val h = f1.forward(input, height, width, f1.effectiveKernelSize())
        prelu(h)
        val f2 = second.fold()
        return f2.forward(h, height, width, f2.effectiveKernelSize())
    }

    private fun prelu(v: FloatArray) {
        for (i in v.indices) if (v[i] < 0f) v[i] *= negativeSlope
    }

    companion object {
        /** 论文里的异构核组合：多尺度 + 水平/垂直专用 + 逐点。 */
        val DEFAULT_BRANCHES: List<Pair<Int, Int>> =
            listOf(5 to 5, 3 to 3, 1 to 3, 3 to 1, 1 to 1)
    }
}
