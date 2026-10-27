package com.naiwa.core.cv.color

import com.naiwa.core.common.model.LightParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 颜色与亮度协调的测试。
 *
 * 第一条断言（strength = 0 恒等）是**修正 Reinhard 力度施加方式之后的回归测试**：
 * 早期把力度乘在 σ_scene 上时，力度归零仍会产生均值平移，这条用例当场就会红。
 */
class ColorTransferTest {

    /** 造一张"偏暖偏暗"的奶蛙图（带 alpha，四角透明）。 */
    private fun makeFrog(size: Int): IntArray =
        IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val inside = x > size / 8 && x < size * 7 / 8 && y > size / 8 && y < size * 7 / 8
            if (inside) {
                (0xFF shl 24) or (90 shl 16) or (120 shl 8) or 70
            } else {
                0
            }
        }

    /** 造一张"偏冷偏亮"的场景图。 */
    private fun makeScene(size: Int): IntArray =
        IntArray(size * size) { (0xFF shl 24) or (170 shl 16) or (185 shl 8) or 210 }

    private val light = LightParams(colorTempK = 4200f, confidence = 0.9f)

    /**
     * **优化不得改变产品输出。**
     *
     * `LabColor` 的 cbrt / pow 现在走一维查找表（约 2.8× 加速）。这类优化的风险不在崩溃，
     * 而在"数值悄悄偏了"：单测必须证明**整条颜色协调链路**的输出与解析实现一致，
     * 而不只是 `LabColor` 本身误差小。
     *
     * 逐像素比对、允许 1 个 8bit 量化步长的差异（两条路径在取整边界上可能差 1）。
     */
    @Test
    fun `查找表版与解析版的产品输出一致`() {
        val size = 48
        val frog = makeFrog(size)
        val scene = makeScene(size)
        val rect = intArrayOf(0, 0, size, size)

        fun run(useLuts: Boolean): IntArray {
            LabColor.useLuts = useLuts
            return ColorTransfer.transferPixels(
                frogPixels = frog,
                width = size,
                height = size,
                scenePixels = scene,
                sceneWidth = size,
                sceneHeight = size,
                placementRect = rect,
                light = light,
                strength = 1f,
            )
        }

        val withLut = try {
            run(true)
        } finally {
            LabColor.useLuts = false
        }
        val analytic = try {
            run(false)
        } finally {
            LabColor.useLuts = true
        }

        var maxDiff = 0
        var changed = 0
        for (i in frog.indices) {
            val a = withLut[i]
            val b = analytic[i]
            // alpha 必须完全一致
            if ((a ushr 24) != (b ushr 24)) {
                throw AssertionError("alpha 被改动了（下标 $i）：${a ushr 24} vs ${b ushr 24}")
            }
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                if (d > maxDiff) maxDiff = d
                if (d > 0) changed++
            }
        }
        println("[ColorTransfer] 查找表 vs 解析：最大通道差 = $maxDiff，有差异的分量 $changed 个")

        assertTrue("产品输出必须与解析版一致（最大差 $maxDiff）", maxDiff <= 1)
    }

    @Test
    fun `力度为 0 时必须与原图逐像素相等`() {
        val size = 32
        val frog = makeFrog(size)
        val scene = makeScene(size)
        val out = ColorTransfer.transferPixels(
            frogPixels = frog,
            width = size,
            height = size,
            scenePixels = scene,
            sceneWidth = size,
            sceneHeight = size,
            placementRect = intArrayOf(0, 0, size, size),
            light = light,
            strength = 0f,
        )
        assertArrayEquals("strength = 0 必须是恒等变换", frog, out)
    }

    @Test
    fun `力度越大_与参考场景的亮度差越小`() {
        val size = 32
        val frog = makeFrog(size)
        val scene = makeScene(size)

        fun meanLuma(pixels: IntArray): Float {
            var sum = 0.0
            var n = 0
            for (c in pixels) {
                if ((c ushr 24) < 8) continue
                sum += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                n++
            }
            return if (n == 0) 0f else (sum / n).toFloat()
        }

        val sceneLuma = meanLuma(scene)
        val gap0 = abs(meanLuma(frog) - sceneLuma)
        val outHalf = ColorTransfer.transferPixels(
            frog, size, size, scene, size, size, intArrayOf(0, 0, size, size), light, 0.5f,
        )
        val gapHalf = abs(meanLuma(outHalf) - sceneLuma)
        val outFull = ColorTransfer.transferPixels(
            frog, size, size, scene, size, size, intArrayOf(0, 0, size, size), light, 1f,
        )
        val gapFull = abs(meanLuma(outFull) - sceneLuma)

        assertTrue("力度 0.5 应比 0 更接近场景：$gap0 -> $gapHalf", gapHalf < gap0)
        assertTrue("力度 1.0 应比 0.5 更接近场景：$gapHalf -> $gapFull", gapFull <= gapHalf + 1f)
    }

    @Test
    fun `alpha 通道在迁移后保持不变`() {
        val size = 32
        val frog = makeFrog(size)
        val scene = makeScene(size)
        val out = ColorTransfer.transferPixels(
            frog, size, size, scene, size, size, intArrayOf(0, 0, size, size), light, 1f,
        )
        for (i in frog.indices) {
            assertTrue(
                "第 $i 个像素的 alpha 被改动了",
                ((frog[i] ushr 24) and 0xFF) == ((out[i] ushr 24) and 0xFF),
            )
        }
    }

    @Test
    fun `完全透明的像素不应被写入颜色`() {
        val size = 16
        val frog = IntArray(size * size)   // 全透明
        val scene = makeScene(size)
        val out = ColorTransfer.transferPixels(
            frog, size, size, scene, size, size, intArrayOf(0, 0, size, size), light, 1f,
        )
        assertArrayEquals(frog, out)
    }
}
