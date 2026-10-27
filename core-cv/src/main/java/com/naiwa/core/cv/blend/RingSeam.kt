package com.naiwa.core.cv.blend

import com.naiwa.core.cv.util.BoxBlur
import com.naiwa.core.cv.util.DistanceField

/**
 * 局部泊松的**核心区保真拼接**（规格 7）。
 *
 * 【定位调整】泊松融合（seamlessClone）不再是全图融合方案，降级为"局部边缘缝合工具"：
 *  - **核心区**（掩膜腐蚀 [CORE_RADIUS_PX] 后的内部）：**绝不经泊松**，写回奶蛙原像素，
 *    保留高频细节与二次元线稿 —— 全前景泊松重建会把线稿抹糊，这是失真的主要来源之一；
 *  - **环形缝合区**（核心区外沿到掩膜边界）：使用泊松解，负责把奶蛙边缘"缝"进背景；
 *  - **core↔ring 过渡**：对 0/1 核心区权重做 [SOFTEN_PX] 盒式模糊，得到线性 alpha 过渡带
 *    （规格要求的"简单 Alpha 过渡"）；ring↔背景的过渡由既有的 EdgeHaloFix 三段式 feather 承担。
 *
 * 【为什么腐蚀用 DistanceField 而不是形态学核】"距掩膜边界 ≥ N px"用 Chamfer 距离场
 * 一步得到（`dist >= N`），纯 Kotlin、可 JVM 单测，且与 RimLight 的法向计算共用同一实现
 * —— 全项目只有一份"到边界距离"的定义。
 *
 * 【细线条兜底】掩膜最大厚度 < 2×[CORE_RADIUS_PX] 时（细耳朵、细枝节），距离场处处 < N，
 * 核心区为空 —— 此时整条细线都是缝合区（全泊松），这是正确行为：细线条没有"内部"可言。
 *
 * 全部为纯 IntArray/FloatArray 运算，不依赖 OpenCV / Android Bitmap。
 */
object RingSeam {

    /** 核心区半径：掩膜腐蚀等效像素数（规格 7：4px）。 */
    const val CORE_RADIUS_PX = 4

    /** core↔ring 过渡带的软化半径（规格 7：简单 Alpha 过渡，2px 盒式单趟）。 */
    const val SOFTEN_PX = 2

    /**
     * 把泊松结果与奶蛙原像素按"核心区保真"规则拼接。
     *
     * @param sceneMask 场景空间前景掩膜（0~255），与图像同尺寸
     * @param poissonPixels 泊松融合输出（场景空间全图 RGB，mask 外 ≈ 背景原样）
     * @param frogPixels 已摆放进场景空间的奶蛙像素（带 alpha，背景透明）
     * @return 拼接后的像素数组；输入非法时返回 poisson 的拷贝（不抛异常）
     */
    fun apply(
        sceneMask: IntArray,
        poissonPixels: IntArray,
        frogPixels: IntArray,
        width: Int,
        height: Int,
        coreRadiusPx: Int = CORE_RADIUS_PX,
        softenPx: Int = SOFTEN_PX,
    ): IntArray {
        val total = width * height
        if (width <= 0 || height <= 0 ||
            sceneMask.size < total || poissonPixels.size < total || frogPixels.size < total
        ) {
            return poissonPixels.copyOf()
        }

        // 1) 二值化 + 距离场：dist = 到最近背景的近似欧氏距离
        val bin = IntArray(total) { i -> if (sceneMask[i] >= 128) 1 else 0 }
        val dist = DistanceField.compute(bin, width, height)

        // 2) 核心区权重（0/1）：掩膜内且距边界 ≥ N
        var anyCore = false
        val core01 = FloatArray(total)
        for (i in 0 until total) {
            if (bin[i] == 1 && dist[i] >= coreRadiusPx) {
                core01[i] = 1f
                anyCore = true
            }
        }
        // 细线条（无内部）→ 整条都是缝合区，直接用泊松结果
        if (!anyCore) return poissonPixels.copyOf()

        // 3) 软化：core↔ring 的线性 alpha 过渡带
        val softCore = BoxBlur.blur(core01, width, height, softenPx.coerceAtLeast(1), passes = 1)

        // 4) 拼接：核心区=奶蛙原像素，环形=泊松解，过渡带线性插值；mask 外保持泊松输出
        val out = poissonPixels.copyOf()
        for (i in 0 until total) {
            if (bin[i] == 0) continue
            val f = frogPixels[i]
            val fa = (f ushr 24) and 0xFF
            if (fa == 0) continue // 奶蛙透明处（理论上 mask 内不会出现）不写
            val t = softCore[i].coerceIn(0f, 1f)
            if (t <= 0.002f) continue // 纯缝合区，泊松值原样
            val p = poissonPixels[i]
            val nr = lerp((p ushr 16) and 0xFF, (f ushr 16) and 0xFF, t)
            val ng = lerp((p ushr 8) and 0xFF, (f ushr 8) and 0xFF, t)
            val nb = lerp(p and 0xFF, f and 0xFF, t)
            out[i] = (fa shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)
}
