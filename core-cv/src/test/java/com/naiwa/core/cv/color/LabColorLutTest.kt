package com.naiwa.core.cv.color

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LabColor` 一维查找表的回归测试。
 *
 * 优化最容易出的问题不是崩溃，而是**数值悄悄偏了**。所以这里不测"看起来差不多"，
 * 而是把"查表版"与"解析版"逐点比对，把最大误差与实测加速比都打出来。
 *
 * 顺带记录一条**被实测否定**的方案：最初用 33³ 三维 LUT 直接替掉整条 `sRGB ↔ Lab` 点变换，
 * 结果在 JVM 上比解析实现**慢 0.8×**，且 Lab→sRGB 方向往返误差达 **32.8/255**
 * （均匀网格跨不过色域钳制面）。因此改为只对超越函数用一维 LUT。
 */
class LabColorLutTest {

    @After
    fun tearDown() {
        // 无论用例怎么结束，都要把开关还原，避免污染其它用例
        LabColor.useLuts = true
    }

    @Test
    fun `查表版与解析版在全色域内等价`() {
        val lut = FloatArray(3)
        val ref = FloatArray(3)
        var maxDE = 0f
        var maxDL = 0f
        var maxDAB = 0f
        var sumDE = 0.0
        var n = 0

        LabColor.useLuts = true
        val lutValues = ArrayList<FloatArray>(8 * 8 * 8)
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            LabColor.rgbToLab(r, g, b, lut)
            lutValues += floatArrayOf(lut[0], lut[1], lut[2])
        }

        LabColor.useLuts = false
        var k = 0
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            LabColor.rgbToLab(r, g, b, ref)
            val v = lutValues[k++]
            val dl = abs(v[0] - ref[0])
            val da = abs(v[1] - ref[1])
            val db = abs(v[2] - ref[2])
            val de = sqrt(dl * dl + da * da + db * db)
            if (de > maxDE) maxDE = de
            if (dl > maxDL) maxDL = dl
            val dab = sqrt(da * da + db * db)
            if (dab > maxDAB) maxDAB = dab
            sumDE += de
            n++
        }

        println(
            "[LUT-1D] sRGB→Lab 采样 $n 点：最大 ΔE76 = %.6f（ΔL %.6f / ΔChroma %.6f），平均 = %.7f"
                .format(maxDE, maxDL, maxDAB, sumDE / n),
        )
        println("[LUT-1D] 建表耗时 = ${LabColor.lutBuildTimeMs} ms（进程内一次）")

        // 1e-3 个 Lab 单位远低于任何显示设备的可分辨阈值，也低于 8bit 量化步长
        assertTrue("最大 ΔE76 过大：$maxDE", maxDE < 1e-3f)
    }

    @Test
    fun `Lab反变换的查表版与解析版等价`() {
        val lab = FloatArray(3)
        val lutRgb = FloatArray(3)
        val refRgb = FloatArray(3)
        var maxErr = 0f
        var n = 0

        // 采样点取"真实 sRGB 颜色转换出来的 Lab"，即管线里实际会遇到的取值范围
        val samples = ArrayList<FloatArray>()
        LabColor.useLuts = false
        for (r in 0..255 step 9) for (g in 0..255 step 11) for (b in 0..255 step 13) {
            LabColor.rgbToLab(r, g, b, lab)
            samples += floatArrayOf(lab[0], lab[1], lab[2])
        }

        LabColor.useLuts = true
        for (s in samples) {
            LabColor.labToRgb(s[0], s[1], s[2], lutRgb)
            LabColor.useLuts = false
            LabColor.labToRgb(s[0], s[1], s[2], refRgb)
            LabColor.useLuts = true
            maxErr = maxOf(maxErr, abs(lutRgb[0] - refRgb[0]), abs(lutRgb[1] - refRgb[1]), abs(lutRgb[2] - refRgb[2]))
            n++
        }
        println("[LUT-1D] Lab→sRGB 采样 $n 点：最大通道误差 = %.5f/255".format(maxErr))
        assertTrue("Lab→sRGB 误差过大：$maxErr", maxErr < 0.01f)
    }

    @Test
    fun `查表确实比解析实现快`() {
        val out = FloatArray(3)
        val iterations = 400_000

        fun run(useLuts: Boolean): Long {
            LabColor.useLuts = useLuts
            // 预热：让 JIT 编译好，否则测到的是解释器
            repeat(30_000) { i ->
                val v = i * 37 and 0xFF
                LabColor.rgbToLab(v, (v * 3) and 0xFF, (v * 7) and 0xFF, out)
            }
            val t0 = System.nanoTime()
            var acc = 0f
            for (i in 0 until iterations) {
                val v = i * 37 and 0xFF
                LabColor.rgbToLab(v, (v * 3) and 0xFF, (v * 7) and 0xFF, out)
                acc += out[0]
            }
            val dt = System.nanoTime() - t0
            // 让 acc 参与外部可见的副作用，避免 JIT 把整个循环优化掉
            if (acc == Float.MIN_VALUE) println("不可能命中")
            return dt
        }

        // 各跑两轮取更快的一次，减少 JIT/GC 抖动带来的假阴性
        val lutNanos = minOf(run(true), run(true))
        val refNanos = minOf(run(false), run(false))
        LabColor.useLuts = true

        val speedup = refNanos.toDouble() / lutNanos.toDouble()
        println(
            "[LUT-1D] rgbToLab × $iterations：解析 %.1f ms / 查表 %.1f ms → 加速 %.2f×"
                .format(refNanos / 1e6, lutNanos / 1e6, speedup),
        )

        // 只钉"不更慢"这个保守下界：具体倍率随 JVM/CPU 波动，写死会变成 flaky 测试
        assertTrue("一维查找表不应比解析实现慢（speedup=$speedup）", speedup > 1.0)
    }
}
