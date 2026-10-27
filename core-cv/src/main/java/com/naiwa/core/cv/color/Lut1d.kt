package com.naiwa.core.cv.color

/**
 * 一维查找表 —— 把逐像素的**超越函数**（cbrt / pow）换成"查表 + 线性插值"。
 *
 * 这是从 UHD-MFF（ECCV 2026）学到的**思路**：在高像素量级上，真正的瓶颈是"全分辨率重算子"，
 * 用 LUT + 插值替代能几乎不掉质量地把它降下来。区别在于落地位置 ——
 * 那篇论文用 LUT 替掉全分辨率卷积，这里用 LUT 替掉逐像素 cbrt / pow。
 *
 * ## 为什么不是三维 LUT（先试过，实测反而更慢）
 * 最初的实现用 33³ 三维表直接替掉 `sRGB ↔ Lab` 的整条点变换。实测（`LabColorLutTest` 记录）：
 *  - **在 JVM 上比解析实现慢 0.8×**：三维三线性要 8 个角点 × 3 通道 = 24 次读 + 24 次乘加 + 索引运算，
 *    而解析实现只有 3 次 cbrt（x86 上 cbrt 是软件实现但很短）。省下的没赚回来。
 *  - **精度不够**：网格步长 8/255，且 Lab→sRGB 在色域边界会被 `gamma()` 钳制，
 *    插值跨越"钳制面"会产生结构性误差 —— 实测往返最大通道误差 **32.8/255**，不可接受。
 * 结论：**点变换不该用三维 LUT，超越函数才该用一维 LUT**（本次改为后者）。
 *
 * ## 精度
 * 一维表的误差 ≈ `h² · |f''| / 8`。取 4096~8192 段时，本项目用到的两个函数
 * （`f(t)=t^(1/3)` 与 `c^(1/2.4)`）实测误差在 1e-6 量级，比 float 本身的精度还小。
 *
 * @param min 定义域下界（超出范围按边界值返回，不外推 —— 外推会让暗部出现负值）
 */
internal class Lut1d private constructor(
    private val values: FloatArray,
    private val min: Float,
    private val max: Float,
) {

    /** values.size - 1 = 段数。索引 i 对应的自变量为 `min + i * (max-min)/steps`。 */
    private val steps: Int = values.size - 1

    private val scale: Float = steps / (max - min)

    /** 查询。`t < min` 或 `t > max` 时返回端点值（钳制而非外推）。 */
    fun at(t: Float): Float {
        if (t <= min) return values[0]
        if (t >= max) return values[steps]
        val pos = (t - min) * scale
        val i = pos.toInt()
        if (i >= steps) return values[steps]
        val a = values[i]
        return a + (values[i + 1] - a) * (pos - i)
    }

    companion object {
        /**
         * 用给定函数在均匀网格上采样建表。
         *
         * @param steps 段数（越大越准，内存线性增长；本项目 4096~8192 已远超需求）
         */
        fun of(min: Float, max: Float, steps: Int, fn: (Float) -> Float): Lut1d {
            require(steps > 0 && max > min)
            val values = FloatArray(steps + 1)
            val step = (max - min) / steps
            for (i in 0..steps) values[i] = fn(min + i * step)
            return Lut1d(values, min, max)
        }
    }
}
