package com.naiwa.core.common.model

import kotlin.math.max

/**
 * 明暗交界过渡带（Penumbra）的权重规则（纯函数，可 JVM 单测）。
 *
 * ## 为什么"用了 smoothstep 还是刀切"
 * 本项目所有光效 pass 早就用了 `smoothstep`，但观感依然是硬边。原因不在数学，
 * 在**空间尺度**：法向由 1 texel 的 alpha 差分求出，而抠图边缘只有 2~3px 宽 ——
 * 在这 2~3px 里法向就从"朝外"翻到"朝内"，`N·L` 从 +1 跌到 -1。
 * smoothstep 只把这 2px 变成"斜率为 0 的 2px"，视觉上仍是一条线。
 *
 * 真正的半影来自**光源有面积**：宽度 ≈ (光源尺寸 / 距离) × 距离。
 * 2D 合成里没有这些量，能做的是把"过渡带的空间宽度"拉大到 8~14px，
 * 并在带内混入环境色，让亮部"融化"进暗部而不是被切断。
 *
 * ## 两个权重
 * ```
 * litRatio   = smoothstep(-0.2, 0.3, N·L)   // 0 背光 / 1 迎光
 * shadowW    = 1 - litRatio                 // 与 litRatio 严格互补
 * bridge     = 4·litRatio·(1-litRatio)      // 钟形，峰值 1 在交界中点
 * ```
 * `bridge` 是"环境光桥梁"的权重：两端为 0（纯光/纯暗处不需要填补），
 * 只在交界带中间最强 —— 那里正是纯色缺失、最容易显出断层的位置。
 */
object Penumbra {

    /** `N·L` 过渡下界：略小于 0，让背光侧也有一点蔓延（真实半影的特征）。 */
    const val NDL_LO: Float = -0.20f

    /** `N·L` 过渡上界：0.30 ≈ 迎光侧再往里一点，交界带更宽。 */
    const val NDL_HI: Float = 0.30f

    /** 环境光桥梁的最大不透明度（0.35：再高会让交界带发灰、丢失对比）。 */
    const val BRIDGE_STRENGTH: Float = 0.35f

    /**
     * 迎光比例。
     *
     * @param nDotL      法向与光向的点积
     * @param edgeAmount 该像素属于"轮廓带"的程度（0 = 平涂内部 / 1 = 轮廓）。
     *   **这个参数是必须的**：平涂人物内部没有法向，`N·L` 恒为 0，
     *   若直接用 `smoothstep(-0.2,0.3,0)` 会给内部一个 0.35 的"微受光"，
     *   结果整个人物压不黑。内部必须强制按背光处理。
     */
    fun litRatio(nDotL: Float, edgeAmount: Float): Float {
        val e = edgeAmount.coerceIn(0f, 1f)
        return if (e <= 0f) 0f else smoothstep(NDL_LO, NDL_HI, nDotL) * e
    }

    /** 背光阴影权重，与 [litRatio] 严格互补。 */
    fun shadowWeight(litRatio: Float): Float = 1f - litRatio.coerceIn(0f, 1f)

    /**
     * 环境光桥梁权重：钟形，两端 0、中点 1。
     *
     * 用 `4l(1-l)` 而不是线性 —— 线性会在纯光/纯暗两端各留一个台阶，
     * 那正是用户说的"断层"。
     */
    fun bridge(litRatio: Float): Float {
        val l = litRatio.coerceIn(0f, 1f)
        return 4f * l * (1f - l)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / max(e1 - e0, 1e-6f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
