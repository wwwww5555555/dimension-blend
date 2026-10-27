package com.naiwa.core.common.model

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 泛光高斯金字塔的规格计算（纯函数，可 JVM 单测）。
 *
 * ## 为什么不是"一个 shader 里循环做多次高斯"
 * 这是本次重构最关键的工程判断，写在这里作为结论（细节见 docs/RIM-GLOW.md）：
 *
 * 全分辨率上做一次半径 R 的**可分离**高斯需要 `2 × ceil(3σ) ≈ 6σ` 次纹理采样/像素。
 * 想要"24px 半径 + 大范围光晕"（σ≈12），就是 **72 次采样/像素**。2048² 一张图
 * 就是 3 亿次采样 —— 中端机直接 200ms+，并且"再叠一层更大半径"根本做不动。
 *
 * 正确解法是**降采样金字塔**（mip chain / dual filtering）：
 * - 每级把分辨率减半，在**该级 texel 空间**只做 σ=1 的 5-tap 高斯；
 * - 降采样时用双线性采样自带低通，等效半径随级数**指数增长**；
 * - 总采样数 = Σ(1/4^i) ≈ 1.33 × 全图（而不是 72 × 全图）。
 *
 * 也就是说：**大半径模糊靠"降低分辨率"买，而不是靠"加大核"买。**
 * 1/2 域上 σ=1 的 5-tap，等价于全分辨率 σ=2 的 11-tap；再叠 1/4 域就是 σ=4……
 * 四到五级下来，等效半径 24~60px，成本却只有一张全图的填充。
 *
 * ## 本对象的职责
 * 只回答两个问题：**要几级**、**每级多大**。跑不了 GL，纯算术，因此能被单测钉死
 * （级数算错一个，尺寸就会变成 0 → `glTexImage2D` 失败 → 整条管线降级）。
 */
object GlowPyramid {

    /** 级数上限。再多也不会有新的观感收益，只是多占纹理。 */
    const val MAX_LEVELS: Int = 5

    /** 至少两级 —— 规格要求"多次高斯"，一级退化成普通模糊。 */
    const val MIN_LEVELS: Int = 2

    /** 某一边小于这个值就不再往下采样（再降就只剩几个像素，全是台阶）。 */
    const val MIN_SIDE: Int = 4

    /** 每级在**该级 texel 空间**的高斯 σ。恒为 1：大半径由级数承担。 */
    const val SIGMA: Float = 1.0f

    /** 一级金字塔。 */
    data class Level(val w: Int, val h: Int, val sigma: Float)

    /**
     * 按目标扩散半径规划金字塔。
     *
     * 级数推导：第 i 级（i 从 0 起）在全分辨率上的等效半径 ≈ [SIGMA] × 2^(i+1)，
     * 因此 N 级能覆盖到 `SIGMA × 2^N`。反解即得 `N = round(log2(spread / σ))`。
     *
     * 例：spread=28 → log2(28)≈4.8 → 5 级，等效半径 32px，刚好覆盖 28px 需求。
     *
     * ⚠️ **不要在这里 +1 做"保险"**。曾经的实现多算了一级，连带把最粗一级推到
     * 1/32 分辨率：局部峰值被摊平到整幅画面，光晕看起来"有形状但没亮度"，
     * 而且多花一张纹理。级数多一级不是"更保险"，是**更暗更糊**。
     * 先用 `tools/sim_rim_glow.py` 出对照图确认，再改这里的公式。
     *
     * @param w        输入（全分辨率）宽
     * @param h        输入（全分辨率）高
     * @param spreadPx 期望的扩散半径（1080p 基准像素）；≤0 视为关闭，返回空列表
     */
    fun plan(w: Int, h: Int, spreadPx: Float): List<Level> {
        if (w <= 0 || h <= 0) return emptyList()
        if (spreadPx <= 0f) return emptyList()

        val raw = log2(max(spreadPx, 1f) / SIGMA).roundToInt()
        val want = raw.coerceIn(MIN_LEVELS, MAX_LEVELS)

        val out = ArrayList<Level>(want)
        var cw = w
        var ch = h
        for (i in 0 until want) {
            cw /= 2
            ch /= 2
            // 任一边降到 MIN_SIDE 以下就停：继续降只会得到 0 尺寸或纯色块
            if (cw < MIN_SIDE || ch < MIN_SIDE) break
            out += Level(cw, ch, SIGMA)
        }
        return out
    }
}
