package com.naiwa.core.cv.rim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘光（规格 7.1 / 7.1a）契约测试。
 *
 * 7.1a 的关键升级都在这里钉住：
 *  - 法向用距离变换梯度（非模糊 alpha 差分）；
 *  - 点积方向是 3D（azimuth + elevation），elevation 衰减水平分量；
 *  - 权重 = 点积^1.5（半影区更暗）。
 */
class RimLightTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `强度为零时逐像素不变`() {
        val pixels = intArrayOf(argb(255, 100, 100, 100), argb(0, 0, 0, 0))
        val out = RimLight.rimPixels(pixels, 2, 1, azimuthDeg = 90f, elevationDeg = 45f, strength = 0f, rimColor = 0xFFFFFFFF.toInt())
        for (i in pixels.indices) {
            assertEquals("强度 0 不变", pixels[i], out[i])
        }
    }

    @Test
    fun `alpha通道保持不变`() {
        val w = 32
        val h = 32
        val c = 15.5f
        val pixels = IntArray(w * h) { i ->
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val dx = x - c
            val dy = y - c
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            when {
                d < 10f -> argb(255, 120, 120, 120)
                d < 14f -> argb(128, 120, 120, 120)
                else -> argb(0, 0, 0, 0)
            }
        }
        val out = RimLight.rimPixels(pixels, w, h, azimuthDeg = 90f, elevationDeg = 45f, strength = 0.3f, rimColor = 0xFFFFFFFF.toInt())
        for (i in pixels.indices) {
            assertEquals("alpha 不变", pixels[i] ushr 24, out[i] ushr 24)
        }
    }

    @Test
    fun `实心内部不被加亮`() {
        val w = 32
        val h = 32
        val c = 15.5f
        val pixels = IntArray(w * h) { i ->
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val dx = x - c
            val dy = y - c
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < 6f) argb(255, 100, 100, 100) else argb(0, 0, 0, 0)
        }
        val out = RimLight.rimPixels(pixels, w, h, azimuthDeg = 90f, elevationDeg = 45f, strength = 0.4f, rimColor = 0xFFFFFFFF.toInt())
        val center = out[(h / 2) * w + w / 2]
        assertEquals("圆心不加亮", 100, (center shr 16) and 0xFF)
        assertEquals("圆心不加亮 G", 100, (center shr 8) and 0xFF)
    }

    @Test
    fun `朝向光源一侧被加亮`() {
        // 平滑过渡圆盘
        val w = 40
        val h = 40
        val c = 19.5f
        val pixels = IntArray(w * h) { i ->
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val dx = x - c
            val dy = y - c
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val a = when {
                d < 12f -> 255
                d > 20f -> 0
                else -> ((20f - d) / 8f * 255f).toInt().coerceIn(0, 255)
            }
            argb(a, 100, 100, 100)
        }
        // 光从下方来（azimuth=90°），中等高度角（elevation=45° 有足够水平分量）
        val out = RimLight.rimPixels(pixels, w, h, azimuthDeg = 90f, elevationDeg = 45f, strength = 0.4f, rimColor = 0xFFFFFFFF.toInt())

        var bottomLit = 0
        var topLit = 0
        for (i in pixels.indices) {
            val x = i % w
            val y = i / w
            val a = pixels[i] ushr 24
            if (a in 1..254) {
                val diff = ((out[i] shr 16) and 0xFF) - ((pixels[i] shr 16) and 0xFF)
                if (diff > 2) {
                    if (y > c) bottomLit++ else if (y < c) topLit++
                }
            }
        }
        assertTrue("朝光一侧被加亮（bottomLit=$bottomLit）", bottomLit > 0)
        assertTrue("背光一侧几乎不加亮（topLit=$topLit）", topLit < bottomLit)
    }

    @Test
    fun `尺寸非法时原样返回`() {
        val pixels = intArrayOf(argb(255, 100, 100, 100))
        val out = RimLight.rimPixels(pixels, 0, 0, azimuthDeg = 90f, elevationDeg = 45f, strength = 0.3f, rimColor = 0xFFFFFFFF.toInt())
        assertEquals("非法尺寸原样", pixels[0], out[0])
    }

    // ---------------------------------------------------------------- 7.1a 新增契约

    @Test
    fun `正顶光时边缘光方向性显著减弱`() {
        // 同一圆盘，分别用 elevation=45°（有明显水平分量）和 elevation=90°（正顶光，水平分量为 0）
        val w = 40
        val h = 40
        val c = 19.5f
        val pixels = IntArray(w * h) { i ->
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val dx = x - c
            val dy = y - c
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val a = when {
                d < 12f -> 255
                d > 20f -> 0
                else -> ((20f - d) / 8f * 255f).toInt().coerceIn(0, 255)
            }
            argb(a, 100, 100, 100)
        }
        val lowElev = RimLight.rimPixels(pixels, w, h, azimuthDeg = 90f, elevationDeg = 45f, strength = 0.4f, rimColor = 0xFFFFFFFF.toInt())
        val topLight = RimLight.rimPixels(pixels, w, h, azimuthDeg = 90f, elevationDeg = 90f, strength = 0.4f, rimColor = 0xFFFFFFFF.toInt())

        fun totalLit(out: IntArray): Int {
            var sum = 0
            for (i in out.indices) {
                val a = pixels[i] ushr 24
                if (a in 1..254) sum += ((out[i] shr 16) and 0xFF) - ((pixels[i] shr 16) and 0xFF)
            }
            return sum
        }

        // 正顶光（elevation=90°，cos=0）时水平分量为 0，边缘光应大幅减弱甚至消失
        val lit45 = totalLit(lowElev)
        val lit90 = totalLit(topLight)
        assertTrue("45° 时应有明显边缘光（lit=$lit45）", lit45 > 0)
        assertTrue("正顶光方向性应减弱（lit90=$lit90 < lit45=$lit45）", lit90 < lit45)
    }

    @Test
    fun `点积1点5次方压暗半影区`() {
        // 光从正下方（azimuth=90°）：底部边缘 dot≈1（最亮），左右两侧 dot≈0（半影区，被 ^1.5 压暗）
        val w = 40
        val h = 40
        val c = 19.5f
        val pixels = IntArray(w * h) { i ->
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val dx = x - c
            val dy = y - c
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val a = when {
                d < 12f -> 255
                d > 20f -> 0
                else -> ((20f - d) / 8f * 255f).toInt().coerceIn(0, 255)
            }
            argb(a, 100, 100, 100)
        }
        val out = RimLight.rimPixels(pixels, w, h, azimuthDeg = 90f, elevationDeg = 45f, strength = 0.4f, rimColor = 0xFFFFFFFF.toInt())

        // 底部边缘（正对光源，dot≈1）与侧边（法向接近水平，dot≈0）的平均提亮对比
        var bottomSum = 0
        var bottomN = 0
        var sideSum = 0
        var sideN = 0
        for (i in pixels.indices) {
            val x = i % w
            val y = i / w
            val a = pixels[i] ushr 24
            if (a !in 1..254) continue
            val diff = ((out[i] shr 16) and 0xFF) - ((pixels[i] shr 16) and 0xFF)
            val dx = kotlin.math.abs(x - c)
            val dy = y - c
            // 底部边缘：正下方（dy > 0 且 dy 明显大于 dx）
            if (dy > 0 && dy > dx) { bottomSum += diff; bottomN++ }
            // 侧边：左右两侧（dx 明显大于 |dy|）
            if (dx > 4 && dx > kotlin.math.abs(dy)) { sideSum += diff; sideN++ }
        }
        assertTrue("底部边缘应有样本（bottomN=$bottomN）", bottomN > 10)
        assertTrue("侧边应有样本（sideN=$sideN）", sideN > 10)
        val bottomAvg = bottomSum.toFloat() / bottomN
        val sideAvg = sideSum.toFloat() / sideN
        // 半影区（侧边 dot≈0）经 ^1.5 压暗后，平均提亮应显著低于正对光源的底部
        assertTrue("底部平均提亮应明显（bottomAvg=$bottomAvg）", bottomAvg > 8f)
        assertTrue("侧边平均提亮应显著低于底部（sideAvg=$sideAvg vs bottomAvg=$bottomAvg）", sideAvg < bottomAvg * 0.6f)
    }
}
