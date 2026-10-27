package com.naiwa.core.cv.fusion

import kotlin.math.max
import kotlin.math.min

/**
 * **细节保真修正** —— 用结构重参数化的机制，修过渡带"糊块"这个已知缺陷。
 *
 * ## 它修的是什么问题
 * 架构文档 §6.6 记录过：`seamlessClone` 在高频背景（草地、木纹、砂石）上会把一片像素
 * 求解成低频结果，上采样回贴后就是一块"糊"。α 加权回贴只决定了"糊的那部分占多少权重"，
 * 并没有把丢掉的**高频细节**找回来。
 *
 * 本类做的事就是**把原始背景的高频细节补回过渡带**：
 * ```
 * hp    = 多尺度高通(sceneLuma)        // DC 增益 = 0
 * out   = blended + strength × band × hp
 * ```
 * 因为 `hp` 的直流增益严格为 0，所以这一步**不改变整体亮度**，只在过渡带恢复了纹理。
 *
 * ## 为什么用结构重参数化的写法
 * `hp` 是一个**线性算子**，因此正好可以完整地走 [RepConv] 的多分支 → 折叠单路径流程：
 * ```
 * 分支（设计期）                          折叠后（推理期）
 *  1×1  : +（w3 + w5）·δ                →   一个 K×K 卷积
 *  3×3  : −w3 × box3（9 个抽头各 −w3/9）
 *  5×5  : −w5 × box5（25 个抽头各 −w5/25）
 *  1×3  : 0（预留：水平方向细节增益）
 *  3×1  : 0（预留：垂直方向细节增益）
 * ```
 * 折叠后的核 DC 增益 = (w3+w5) − w3 − w5 = **0**，这一点有单测直接断言。
 *
 * ## 契约（与 [com.naiwa.core.cv.blend.PoissonBlender.fuse] 一致）
 * **不抛异常**：参数不合法或没有有效区域时返回 `null`，由调用方退回未修正的结果。
 * 本步骤是**增强**而不是主链路 —— 它失败绝不能让预览或导出失败。
 *
 * ## 诚实的边界
 * 这里落地的**不是**"训练出来的、比泊松更自然的融合网络"：
 * 权重是按经典多尺度高通**闭式设计**的，写进多分支形态只为了让折叠路径真实可用。
 * 论文里 MobileFusion 的 4K 参数是用 MSRS/FMB/LLVIP 数据集训练出来的，
 * 本仓库不提供未经验证的训练结果。要换成训练权重，只需写 [repConv] 的权重数组，
 * [fold] 与调用方一行都不用改 —— 这正是这个结构存在的意义。
 *
 * 参数量：见 [parameterCount]。
 */
object RepDetailRefiner {

    /** 默认修正强度。0.5 是"能明显找回纹理、又不会把融合边界重新变硬"的折中。 */
    const val DEFAULT_STRENGTH = 0.5f

    private const val W3 = 0.7f
    private const val W5 = 0.3f

    /**
     * 闭式构造的多分支卷积。
     *
     * 布局约定见 [RepConv] 的 KDoc。分支顺序与 [RepBlock.DEFAULT_BRANCHES] 一致：
     * `[0] 5×5, [1] 3×3, [2] 1×3, [3] 3×1, [4] 1×1`。
     */
    val repConv: RepConv = RepConv(
        inChannels = 1,
        midChannels = 1,
        outChannels = 1,
        branchKernels = RepBlock.DEFAULT_BRANCHES,
    ).also { rc ->
        // 分支 0：5×5 box 的负权重
        fill(rc.branchWeights[0], -W5 / 25f)
        // 分支 1：3×3 box 的负权重
        fill(rc.branchWeights[1], -W3 / 9f)
        // 分支 2 / 3：方向性细节增益，默认 0（保留结构，接入训练权重时直接写这里）
        fill(rc.branchWeights[2], 0f)
        fill(rc.branchWeights[3], 0f)
        // 分支 4：1×1 恒等，系数取 box 权重之和以保证 DC 增益为 0
        fill(rc.branchWeights[4], W3 + W5)
        // 所有偏置为 0：偏置会引入直流分量，破坏"不改整体亮度"这条性质
        rc.branchBiases.forEach { fill(it, 0f) }
        // 投影：直接求和（等价于 concat 后 1×1 全 1 权重）
        fill(rc.projection, 1f)
        fill(rc.projectionBias, 0f)
    }

    /** 折叠后的单路径卷积（进程内复用，纯数据，推理期只读）。 */
    val folded: FoldedConv = repConv.fold()

    /** 折叠后实际使用的核尺寸（外圈为零时会被剥掉）。 */
    val effectiveKernelSize: Int get() = folded.effectiveKernelSize()

    /** 参数总数，用于文档与诊断。 */
    val parameterCount: Int get() = repConv.parameterCount

    private fun fill(arr: FloatArray, v: Float) {
        for (i in arr.indices) arr[i] = v
    }

    /**
     * 对已混合的结果做细节保真修正。
     *
     * @param scenePixels 原始场景像素（细节来源）。只读
     * @param blendedPixels 已经过 α 加权回贴的结果。只读，不会被修改
     * @param bandWeight **区域局部坐标**下的修正权重：`[(y - region.y0) * bandWidth + (x - region.x0)]`，
     *   取值 0~1（过渡带接近 1，中心区与外部为 0）。
     *   之所以不是整图尺寸：4096² 的浮点数组要 67MB，而真正非零的只有过渡带那一圈
     * @param width height 图像尺寸
     * @param region 过渡带包围盒 `[x0, y0, x1, y1)`（左闭右开）。卷积会把上下文向四周各扩
     *   `kernelSize / 2`，所以边缘处的读取不会越界
     * @param strength 修正强度，见 [DEFAULT_STRENGTH]
     * @return 修正后的新数组；**参数不合法或没有有效区域时返回 `null`**（调用方应退回未修正结果）
     */
    fun refine(
        scenePixels: IntArray,
        blendedPixels: IntArray,
        bandWeight: FloatArray,
        width: Int,
        height: Int,
        region: IntArray,
        strength: Float = DEFAULT_STRENGTH,
    ): IntArray? {
        if (strength <= 0f) return null
        if (width <= 0 || height <= 0) return null
        if (region.size < 4) return null

        val pixels = width * height
        if (scenePixels.size < pixels || blendedPixels.size < pixels) return null

        // 过渡带包围盒（裁剪到图像内）
        val bx0 = region[0].coerceIn(0, width)
        val by0 = region[1].coerceIn(0, height)
        val bx1 = region[2].coerceIn(bx0, width)
        val by1 = region[3].coerceIn(by0, height)
        val bw = bx1 - bx0
        val bh = by1 - by0
        if (bw <= 0 || bh <= 0) return null
        if (bandWeight.size < bw * bh) return null

        // 卷积上下文区域（含 margin）
        val margin = repConv.kernelSize / 2
        val cx0 = max(0, bx0 - margin)
        val cy0 = max(0, by0 - margin)
        val cx1 = min(width, bx1 + margin)
        val cy1 = min(height, by1 + margin)
        val rw = cx1 - cx0
        val rh = cy1 - cy0
        if (rw <= 0 || rh <= 0) return null

        // 只取上下文区域内的亮度平面，避免为 4096² 全图分配浮点平面
        val luma = FloatArray(rw * rh)
        for (yy in 0 until rh) {
            val srcRow = (cy0 + yy) * width
            val dstRow = yy * rw
            for (xx in 0 until rw) {
                val c = scenePixels[srcRow + cx0 + xx]
                luma[dstRow + xx] = 0.2126f * ((c shr 16) and 0xFF) +
                    0.7152f * ((c shr 8) and 0xFF) +
                    0.0722f * (c and 0xFF)
            }
        }

        val hp = folded.forward(luma, rh, rw, effectiveKernelSize)

        val out = blendedPixels.copyOf()
        for (y in by0 until by1) {
            val srcRow = y * width
            val planeRow = (y - cy0) * rw
            val bandRow = (y - by0) * bw
            for (x in bx0 until bx1) {
                val band = bandWeight[bandRow + (x - bx0)]
                if (band <= 0f) continue
                val delta = strength * band * hp[planeRow + (x - cx0)]
                if (delta == 0f) continue
                val idx = srcRow + x
                val c = out[idx]
                val r = ((c shr 16) and 0xFF) + delta
                val g = ((c shr 8) and 0xFF) + delta
                val b = (c and 0xFF) + delta
                out[idx] = (c and 0xFF000000.toInt()) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
