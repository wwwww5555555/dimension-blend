package com.naiwa.core.common.model

/**
 * 逆光压暗与阴影熔断的规则层（纯函数，可 JVM 单测）。
 *
 * ## 这个文件解决的两个问题
 *
 * ### 问题 1：逆光下人物正面不够暗
 * 逆光是"背景远亮于主体"。人眼看到的剪影不是因为主体变黑，而是因为
 * **背景亮到过曝、衬托出主体只剩环境光**。所以正确的做法是两级压暗：
 * - 全局：主体整体压到 0.8~0.85（逆光场景主体本来就在阴影里）；
 * - 背光侧：再压到 0.32 左右（`dot(N,L) < 0` 的那半边只接收环境散射）。
 *
 * ⚠️ **不能只压"背光侧"**：二次元平涂人物几乎没有表面法向变化，只压背光侧
 * 会把人物切成"一半正常一半黑"的生硬色块 —— 这是本项目在 v0.19 已经踩过的
 * "径向伪法线"陷阱。必须"全局压 + 背光侧额外压"。
 *
 * ### 问题 2：阴影边缘漏光
 * 阴影边缘出现光包裹，根因是**伪法线在阴影处不可信**：alpha 梯度在投影与人物
 * 交界处几乎没有变化，`N·L` 于是落在 0 附近，被 `max(0, N·L)` 当成"略微迎光"。
 * 再叠加一层"遮罩上升沿"，就漏出一圈光。
 *
 * 解法是**双保险取最大值**：`occlusion = max(背光权重, 阴影遮罩)`。
 * 两条判据任意一条成立就熔断——不看伪法线的脸色。
 *
 * 这里的所有函数都只做算术，不碰 GL，因此可以被单测钉死；shader 里调用的是
 * **同一组常量**（`FRAG_P2` / `FRAG_LIGHTWRAP` / `FRAG_EMIT` 的 uniform 默认值
 * 必须与这里的常量一致）。
 */
object BacklitShade {

    /**
     * 背光面的**基准暗度**（v0.29 起从 0.32 调到 0.20）。
     *
     * 它现在是 `absorb = mix(DARKEN_BACK, deepShadowColor, CHROMA_WEIGHT)` 里的
     * "无环境吸收时的暗度"（对应规格公式里的 `color * 0.2`），
     * 再乘三级高度系数（0.40 / 0.20 / 0.05）才是最终亮度。
     * ⚠️ 不要因为它变小了就往回改：真正的深度来自高度阶梯，不是这一个系数。
     */
    const val DARKEN_BACK: Float = 0.20f

    /** 逆光时的全局压暗（所有朝向都乘一次）。 */
    const val DARKEN_GLOBAL: Float = 0.82f

    /** 环境吸收混合比（0 = 只压暗不吸色，1 = 完全用背景暗部色相）。 */
    const val CHROMA_WEIGHT: Float = 0.70f

    /**
     * 熔断强度（1 = 处于阴影/背光时光效完全归零）。
     *
     * 与"环境光地板"配合：主光被熔断，但环境散射仍留一点，否则背光侧会变成
     * 一块死黑，与"人物融进背景"没有区别。
     */
    const val CUT_STRENGTH: Float = 1.0f

    /** 熔断后保留的环境光比例（0 = 完全归零）。 */
    const val AMBIENT_FLOOR: Float = 0.15f

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 背光权重：`N·L` → 0（迎光）/ 1（背光）。
     *
     * ⚠️ 规格里的伪代码是 `smoothstep(0.2, -0.2, N·L)` —— **edge0 > edge1 在 GLSL
     * 里是未定义行为**，部分驱动直接返回 0，于是压暗/熔断**整段静默失效**。
     * 这里用数学等价的 `1 - smoothstep(-0.2, 0.2, x)`，shader 里也必须是这个写法。
     */
    fun backWeight(nDotL: Float): Float = 1f - smoothstep(-0.2f, 0.2f, nDotL)

    /**
     * 压暗系数（乘到 RGB 上）。
     *
     * ⚠️ **参数是"背光权重"而不是 N·L**。这不是随意的签名选择：
     * 逆光下二次元平涂人物的**内部就是背光面**（它面朝相机、背着光源），
     * 如果直接用 `N·L`（只能靠径向伪法算，人物内部根本没有法向）去插值，
     * 会在画面上切出一条生硬的斜分界线 —— 实测对照图里非常明显。
     *
     * 正确做法由调用方算好 `backWeight`：
     * ```
     * 内部（alpha 梯度≈0）   → backWeight = 1  （整体压暗）
     * 迎光侧轮廓（梯度大且 N·L>0）→ backWeight = 0（保留金色轮廓光）
     * ```
     *
     * @param backWeight 0 = 迎光 / 1 = 背光
     * @param darken     背光面系数（[DARKEN_BACK]）
     * @param global     迎光面系数（[DARKEN_GLOBAL]）；逆光下迎光面也只是"略微不那么暗"
     */
    fun darkenFactor(backWeight: Float, darken: Float = DARKEN_BACK, global: Float = DARKEN_GLOBAL): Float {
        val b = backWeight.coerceIn(0f, 1f)
        val d = darken.coerceIn(0f, 1f)
        val g = global.coerceIn(0f, 1f)
        return g + (d - g) * b
    }

    /**
     * 暗部染色权重：越背光、越暗，越明显。
     *
     * 只染"确实很暗"的像素（luma < 0.35），避免把中间调也染成深蓝 —— 那会让
     * 整个人物像隔着一层滤镜。
     */
    fun tintWeight(
        backWeight: Float,
        luma: Float,
        weight: Float = CHROMA_WEIGHT,
    ): Float = backWeight.coerceIn(0f, 1f) * (1f - smoothstep(0.0f, 0.35f, luma)) * weight.coerceIn(0f, 1f)

    /**
     * 光效熔断后的可用权重。
     *
     * @param shadowAmt 阴影遮罩（0 = 无遮挡 / 1 = 全黑），来自 P2-c 的 Multiply 因子
     * @param cut       熔断强度（[CUT_STRENGTH]）
     * @param floor     环境光地板（[AMBIENT_FLOOR]）
     */
    fun wrapWeight(
        nDotL: Float,
        shadowAmt: Float,
        cut: Float = CUT_STRENGTH,
        floor: Float = AMBIENT_FLOOR,
    ): Float {
        val occ = maxOf(backWeight(nDotL), shadowAmt.coerceIn(0f, 1f))
        val keep = 1f - occ * cut.coerceIn(0f, 1f)
        return floor.coerceIn(0f, 1f) + (1f - floor.coerceIn(0f, 1f)) * keep
    }

    /** 发光源在阴影里必须完全熄灭（没有"环境地板"，发光是主光行为）。 */
    fun emitWeight(shadowAmt: Float, cut: Float = CUT_STRENGTH): Float =
        1f - shadowAmt.coerceIn(0f, 1f) * cut.coerceIn(0f, 1f)
}
