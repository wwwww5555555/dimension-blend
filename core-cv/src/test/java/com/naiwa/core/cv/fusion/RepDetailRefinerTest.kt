package com.naiwa.core.cv.fusion

import kotlin.math.abs
import kotlin.math.sqrt
import com.naiwa.core.cv.util.BoxBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 细节保真修正的性质测试。
 *
 * 这四条是"能不能安全开在用户设备上"的依据，缺一条都不该启用：
 *  1. **直流增益为 0** —— 不改变整体亮度（否则就是偷偷调了曝光）；
 *  2. **参数不合法时返回 null、不抛异常** —— 它是增强步骤，失败不能让预览挂掉；
 *  3. **权重为 0 的像素一个都不许动** —— 中心区与掩膜外的行为不能被破坏；
 *  4. **确实找回了过渡带丢掉的高频** —— 这是它存在的唯一理由，必须可量化验证。
 */
class RepDetailRefinerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int) = argb(255, v, v, v)

    @Test
    fun `折叠核的直流增益严格为零`() {
        val f = RepDetailRefiner.folded
        val k = f.kernel
        var sum = 0f
        for (y in 0 until k) {
            for (x in 0 until k) {
                sum += f.weightAt(0, 0, y, x)
            }
        }
        println("[Refiner] 折叠核 ${k}×${k} 权重和（直流增益）= %.3e，参数 ${RepDetailRefiner.parameterCount} 个".format(sum))
        assertTrue("直流增益必须为 0，否则会在过渡带引入整体亮度偏移：$sum", abs(sum) < 1e-5f)
    }

    @Test
    fun `强度为零时不做任何事`() {
        val w = 24
        val h = 18
        val scene = IntArray(w * h) { gray(if ((it % 5) < 2) 40 else 200) }
        val blended = IntArray(w * h) { argb(255, 120, 130, 140) }
        val band = FloatArray(w * h) { 1f }

        val out = RepDetailRefiner.refine(
            scenePixels = scene,
            blendedPixels = blended,
            bandWeight = band,
            width = w,
            height = h,
            region = intArrayOf(0, 0, w, h),
            strength = 0f,
        )
        assertNull("strength=0 应返回 null 表示未执行（调用方据此退回原结果）", out)
    }

    @Test
    fun `参数不合法时返回null且不抛异常`() {
        val w = 8
        val h = 8
        val scene = IntArray(w * h) { gray(100) }
        val blended = IntArray(w * h) { gray(120) }
        val band = FloatArray(w * h)

        // 1) 像素数组尺寸不足
        assertNull(
            "scene 尺寸不足应返回 null",
            RepDetailRefiner.refine(scene.copyOf(4), blended, band, w, h, intArrayOf(0, 0, w, h), 0.5f),
        )
        // 2) region 元素不足
        assertNull(
            "region 不完整应返回 null",
            RepDetailRefiner.refine(scene, blended, band, w, h, intArrayOf(0, 0), 0.5f),
        )
        // 3) 空区域
        assertNull(
            "空区域应返回 null",
            RepDetailRefiner.refine(scene, blended, band, w, h, intArrayOf(3, 3, 3, 3), 0.5f),
        )
        // 4) 区域越界（应被裁剪后仍可用，而不是崩溃）
        assertNotNull(
            "越界区域应被裁剪而不是抛异常",
            RepDetailRefiner.refine(scene, blended, band, w, h, intArrayOf(-5, -5, 99, 99), 0.5f),
        )
        // 5) band 数组太小
        assertNull(
            "band 尺寸不足应返回 null",
            RepDetailRefiner.refine(scene, blended, FloatArray(3), w, h, intArrayOf(0, 0, w, h), 0.5f),
        )
        // 6) 尺寸为 0
        assertNull(
            "尺寸为 0 应返回 null",
            RepDetailRefiner.refine(IntArray(0), IntArray(0), FloatArray(0), 0, 0, intArrayOf(0, 0, 0, 0), 0.5f),
        )
    }

    @Test
    fun `过渡带的高频细节被找回`() {
        val w = 80
        val h = 80
        val cx = 40f
        val cy = 40f

        // 背景：高频棋盘（模拟草地/木纹这类"高频背景"）
        val period = 4
        val scene = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val on = (((x / period) + (y / period)) and 1) == 0
            gray(if (on) 190 else 65)
        }

        // 过渡带：掩膜外沿的一圈（半径 22 ~ 34）
        val band = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d in 22f..34f) 1f else 0f
        }

        // 模拟"泊松结果上采样后过渡带发糊"：对亮度做一次强模糊
        val luma = FloatArray(w * h) { i ->
            val c = scene[i]
            (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF))
        }
        val blurred = BoxBlur.blur(luma, w, h, radius = 6, passes = 2)
        val blended = IntArray(w * h) { i ->
            if (band[i] > 0f) {
                val v = blurred[i].toInt().coerceIn(0, 255)
                argb(255, v, v, v)
            } else {
                scene[i]
            }
        }

        val refined = RepDetailRefiner.refine(
            scenePixels = scene,
            blendedPixels = blended,
            bandWeight = band,
            width = w,
            height = h,
            region = intArrayOf(0, 0, w, h),
            strength = RepDetailRefiner.DEFAULT_STRENGTH,
        )
        assertNotNull("本次应当执行成功", refined)
        refined!!

        // 度量：过渡带内与原背景的平均绝对误差（越小 = 细节还原得越准）
        var errBefore = 0.0
        var errAfter = 0.0
        var n = 0
        for (i in scene.indices) {
            if (band[i] <= 0f) continue
            val truth = (0.2126f * ((scene[i] shr 16) and 0xFF) +
                0.7152f * ((scene[i] shr 8) and 0xFF) + 0.0722f * (scene[i] and 0xFF))
            val vBefore = (0.2126f * ((blended[i] shr 16) and 0xFF) +
                0.7152f * ((blended[i] shr 8) and 0xFF) + 0.0722f * (blended[i] and 0xFF))
            val vAfter = (0.2126f * ((refined[i] shr 16) and 0xFF) +
                0.7152f * ((refined[i] shr 8) and 0xFF) + 0.0722f * (refined[i] and 0xFF))
            errBefore += abs(vBefore - truth)
            errAfter += abs(vAfter - truth)
            n++
        }
        errBefore /= n
        errAfter /= n

        println("[Refiner] 过渡带平均绝对误差：修正前 %.2f → 修正后 %.2f（%d 像素）".format(errBefore, errAfter, n))
        assertTrue("过渡带应有像素参与统计", n > 200)
        assertTrue("细节保真应降低与原背景的误差：before=$errBefore after=$errAfter", errAfter < errBefore)
    }

    @Test
    fun `简单场景也用区域局部坐标_与整图区域结果一致`() {
        val w = 40
        val h = 40
        val scene = IntArray(w * h) { gray(if ((it % 7) < 3) 60 else 200) }
        val blended = IntArray(w * h) { gray(128) }

        // 整图区域 + 整图 band
        val fullBand = FloatArray(w * h) { 1f }
        val full = RepDetailRefiner.refine(
            scene, blended, fullBand, w, h, intArrayOf(0, 0, w, h), 0.5f,
        )

        // 只算中间 10×10 的区域，band 按区域局部坐标给出
        val rx0 = 15; val ry0 = 15; val rw = 10; val rh = 10
        val localBand = FloatArray(rw * rh) { 1f }
        val localBlended = blended.copyOf()
        val local = RepDetailRefiner.refine(
            scene, localBlended, localBand, w, h, intArrayOf(rx0, ry0, rx0 + rw, ry0 + rh), 0.5f,
        )
        assertNotNull(full)
        assertNotNull(local)
        full!!
        local!!

        // 区域内的结果必须与"整图跑一遍"完全相同 —— band 的坐标语义必须一致
        for (y in ry0 until ry0 + rh) {
            for (x in rx0 until rx0 + rw) {
                val i = y * w + x
                assertEquals("区域局部与整图结果不一致（$x,$y）", full[i], local[i])
            }
        }
    }
}
