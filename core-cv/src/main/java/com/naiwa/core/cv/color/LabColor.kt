package com.naiwa.core.cv.color

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * sRGB ↔ CIE Lab 转换与 Lab 统计量。
 *
 * 为什么用 Lab 而不是直接对 RGB 做均值-方差迁移：
 *  Lab 的 L 通道与色度（a/b）分离，亮度匹配与色调迁移可以各自独立施加，
 *  不会出现"为了让奶蛙变亮结果整只变黄"这种耦合问题 —— 而耦合正是 RGB 域迁移最典型的翻车方式。
 *
 * 约定：L ∈ [0, 100]，a/b ∈ 约 [-128, 127]（实际图像里通常落在 [-60, 60]）。
 *
 * ## 性能：两个超越函数走一维查找表
 * 每次 `rgbToLab` 里有 **3 次 cbrt**，每次 `labToRgb` 里有 **3 次 pow(1/2.4)**。
 * 一张 1024² 的奶蛙、两个方向各走一遍，就是约 600 万次超越函数调用 ——
 * 这是颜色协调那条循环的最大单笔开销。
 *
 * 现在这两个函数用 [Lut1d]（8192 / 4096 段）替代，误差在 1e-6 量级，
 * 而每次调用从"一次超越函数"降到"两次比较 + 一次乘加 + 两次读数组"。
 *
 * 表内容**由本文件的解析公式生成**（[exactF] / [exactGamma]），不另写第二套数学 ——
 * 与 `KelvinRgb` 那次踩坑同一条原则：宁可让表依赖公式，也不要两份实现各自漂移。
 *
 * 完整的三维点变换 LUT 曾经试过，**实测更慢且精度不够**（往返误差 32.8/255），
 * 结论与数据记在 [Lut1d] 的 KDoc 里。
 */
object LabColor {

    /** 参考白：D65（sRGB 的标准白点）。 */
    private const val WHITE_X = 95.047f
    private const val WHITE_Y = 100.0f
    private const val WHITE_Z = 108.883f

    private const val EPSILON = 216f / 24389f
    private const val KAPPA = 24389f / 27f

    /** `f()` 的定义域上界。sRGB 色域内 `X/WHITE_X`、`Y/WHITE_Y`、`Z/WHITE_Z` 均 ≤ 1，留 5% 余量。 */
    private const val F_MAX = 1.05f

    /**
     * 是否启用查找表。默认开。
     * 存在的意义只有一个：让 `LabColorLutTest` 能逐点比对"查表版"与"解析版"。
     */
    @Volatile
    var useLuts: Boolean = true

    /** 建表耗时（毫秒），进程内一次。供诊断与文档引用。 */
    @Volatile
    var lutBuildTimeMs: Long = 0L
        private set

    private val cbrtLut: Lut1d
    private val gammaLut: Lut1d

    init {
        val t0 = System.nanoTime()
        // f(t) 在 t = EPSILON 处两段拼接且连续（两段都等于 0.20690），
        // 所以可以直接对整段做一维插值，不需要分段建表
        cbrtLut = Lut1d.of(0f, F_MAX, CBRT_STEPS) { t -> exactF(t) }
        gammaLut = Lut1d.of(0f, 1f, GAMMA_STEPS) { c -> exactGamma(c) }
        lutBuildTimeMs = (System.nanoTime() - t0) / 1_000_000
    }

    /** 预热（放到后台线程，避免第一帧才付建表成本）。 */
    fun warmUp() {
        // 建表在 init 里完成，这里只是确保对象已被初始化
        @Suppress("UNUSED_EXPRESSION")
        lutBuildTimeMs
    }

    /**
     * 单像素 sRGB → Lab。
     *
     * @param r g b 0..255
     * @param out 长度 ≥ 3：[0]=L(0..100)、[1]=a、[2]=b
     */
    fun rgbToLab(r: Int, g: Int, b: Int, out: FloatArray) {
        val rl = linearLut[r]
        val gl = linearLut[g]
        val bl = linearLut[b]

        // sRGB → XYZ（D65）
        val x = (0.4124564f * rl + 0.3575761f * gl + 0.1804375f * bl) * 100f
        val y = (0.2126729f * rl + 0.7151522f * gl + 0.0721750f * bl) * 100f
        val z = (0.0193339f * rl + 0.1191920f * gl + 0.9503041f * bl) * 100f

        val fx = f(x / WHITE_X)
        val fy = f(y / WHITE_Y)
        val fz = f(z / WHITE_Z)

        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    /** 单像素 Lab → sRGB（分量写入 out 的 [0..2]，0~255）。 */
    fun labToRgb(l: Float, a: Float, b: Float, out: FloatArray) {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f

        val xr = if (fx.pow(3) > EPSILON) fx.pow(3) else (116f * fx - 16f) / KAPPA
        val yr = if (l > KAPPA * EPSILON) ((l + 16f) / 116f).pow(3) else l / KAPPA
        val zr = if (fz.pow(3) > EPSILON) fz.pow(3) else (116f * fz - 16f) / KAPPA

        val x = xr * WHITE_X / 100f
        val y = yr * WHITE_Y / 100f
        val z = zr * WHITE_Z / 100f

        // XYZ → 线性 sRGB
        val rl = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        val gl = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        val bl = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z

        out[0] = gamma(rl) * 255f
        out[1] = gamma(gl) * 255f
        out[2] = gamma(bl) * 255f
    }

    /** 线性化查找表：sRGB 8bit → 线性光（256 项，覆盖全部输入，精确）。 */
    private val linearLut = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** 查表版 `f()`（热路径）。 */
    private fun f(t: Float): Float = if (useLuts) cbrtLut.at(t) else exactF(t)

    /** 查表版 `gamma()`（热路径）。 */
    private fun gamma(c: Float): Float =
        if (useLuts) gammaLut.at(c.coerceIn(0f, 1f)) else exactGamma(c)

    /** 解析版 `f()` —— 建表用的唯一真源。 */
    private fun exactF(t: Float): Float =
        if (t > EPSILON) cbrt(t) else (KAPPA * t + 16f) / 116f

    /** 解析版 `gamma()` —— 建表用的唯一真源。 */
    private fun exactGamma(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    /** 建表段数：误差按 h² 下降，8192 段已把误差压到 1e-6 量级。 */
    private const val CBRT_STEPS = 8192
    private const val GAMMA_STEPS = 4096

    /**
     * Lab 统计量（均值 + 标准差）。
     * 只在奶蛙的 alpha 掩膜内统计 —— 透明区经 premultiply 后接近黑，
     * 一旦被算进来会把均值/方差整体拉偏，这是颜色迁移最常见的"看起来没生效"的原因。
     */
    data class LabStats(val meanL: Float, val meanA: Float, val meanB: Float,
                        val stdL: Float, val stdA: Float, val stdB: Float) {

        companion object {
            /**
             * 从 ARGB 像素统计 Lab 均值/标准差。
             * @param alphaThreshold 只统计 alpha 高于此值的像素
             * @param region 只统计该矩形区域（用于场景侧：只关心奶蛙所在位置的背景光照）
             */
            fun compute(
                pixels: IntArray,
                width: Int,
                height: Int,
                alphaThreshold: Int = 8,
                region: IntArray? = null,
            ): LabStats {
                val lab = FloatArray(3)
                var n = 0
                var sumL = 0.0
                var sumA = 0.0
                var sumB = 0.0
                var sumL2 = 0.0
                var sumA2 = 0.0
                var sumB2 = 0.0

                val x0 = region?.get(0) ?: 0
                val y0 = region?.get(1) ?: 0
                val x1 = region?.get(2) ?: width
                val y1 = region?.get(3) ?: height

                for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) {
                    val row = y * width
                    for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                        val c = pixels[row + x]
                        if (((c ushr 24) and 0xFF) < alphaThreshold) continue
                        rgbToLab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, lab)
                        sumL += lab[0]; sumL2 += lab[0] * lab[0]
                        sumA += lab[1]; sumA2 += lab[1] * lab[1]
                        sumB += lab[2]; sumB2 += lab[2] * lab[2]
                        n++
                    }
                }
                if (n == 0) return NEUTRAL
                val meanL = (sumL / n).toFloat()
                val meanA = (sumA / n).toFloat()
                val meanB = (sumB / n).toFloat()
                return LabStats(
                    meanL = meanL,
                    meanA = meanA,
                    meanB = meanB,
                    stdL = std(sumL2 / n - (sumL / n) * (sumL / n)).coerceAtLeast(0.5f),
                    stdA = std(sumA2 / n - (sumA / n) * (sumA / n)).coerceAtLeast(0.5f),
                    stdB = std(sumB2 / n - (sumB / n) * (sumB / n)).coerceAtLeast(0.5f),
                )
            }

            private fun std(variance: Double): Float = abs(variance).let { kotlin.math.sqrt(it).toFloat() }

            /** 空统计量：调用方据此判定"没有有效像素"，避免除零。 */
            val NEUTRAL = LabStats(50f, 0f, 0f, 1f, 1f, 1f)
        }
    }

    /**
     * 亮度分位点（p5/p50/p95）。
     * 用途：颜色迁移里如果只做"均值-方差"匹配，奶蛙的高光很容易溢出成一片死白
     * —— 因为方差匹配管的是"整体对比度"，管不了"最亮的那 5%"。
     */
    fun lumaPercentiles(
        pixels: IntArray,
        width: Int,
        height: Int,
        alphaThreshold: Int = 8,
        region: IntArray? = null,
    ): FloatArray {
        val lab = FloatArray(3)
        val values = ArrayList<Float>(2048)

        val x0 = region?.get(0) ?: 0
        val y0 = region?.get(1) ?: 0
        val x1 = region?.get(2) ?: width
        val y1 = region?.get(3) ?: height

        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) {
            val row = y * width
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                val c = pixels[row + x]
                if (((c ushr 24) and 0xFF) < alphaThreshold) continue
                rgbToLab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, lab)
                values += lab[0]
            }
        }
        if (values.isEmpty()) return floatArrayOf(0f, 50f, 100f)
        values.sort()
        return floatArrayOf(
            pick(values, 0.05f),
            pick(values, 0.50f),
            pick(values, 0.95f),
        )
    }

    private fun pick(sorted: List<Float>, q: Float): Float {
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
