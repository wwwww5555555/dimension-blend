package com.naiwa.core.cv.blend

import com.naiwa.core.cv.util.BoxBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘三段式的测试。
 *
 * 重点是 **feather 必须带量纲**：架构文档 §6.6 规定
 * `feather = clamp(0.02 × min(w,h), 6, 24)`（1080p 工作域），其它分辨率按比例缩放并保留 2px 下限。
 * 没有量纲的"外扩 N 像素"在不同分辨率与不同设备上完全不可复现。
 */
class EdgeHaloFixTest {

    @Test
    fun `1080p 工作域的羽化落在 4 到 20 之间`() {
        val feather = EdgeHaloFix.featherPx(1080)
        assertTrue("实测 $feather", feather in 4..20)
        // 规格 6.5：0.015 × 1080 = 16.2 → 16
        assertEquals(16, feather)
    }

    @Test
    fun `其它工作域按比例缩放`() {
        val f1080 = EdgeHaloFix.featherPx(1080)
        val f720 = EdgeHaloFix.featherPx(720)
        val f540 = EdgeHaloFix.featherPx(540)

        assertTrue(f720 < f1080)
        assertTrue(f540 < f720)
        // 0.015 × 720 = 10.8 → 10；0.015 × 540 = 8.1 → 8
        assertEquals(10, f720)
        assertEquals(8, f540)
    }

    @Test
    fun `极小分辨率下仍有 2px 下限`() {
        assertTrue(EdgeHaloFix.featherPx(64) >= 2)
        assertTrue(EdgeHaloFix.featherPx(1) >= 2)
    }

    @Test
    fun `手动倍率会影响羽化半径`() {
        val base = EdgeHaloFix.featherPx(1080, 1f)
        val doubled = EdgeHaloFix.featherPx(1080, 2f)
        val halved = EdgeHaloFix.featherPx(1080, 0.5f)
        assertTrue("倍率 2 应更大：$doubled vs $base", doubled > base)
        assertTrue("倍率 0.5 应更小：$halved vs $base", halved < base)
    }

    @Test
    fun `过渡带之外保持场景原像素`() {
        val w = 32
        val h = 32
        val sceneColor = 0xFF203040.toInt()
        val scene = IntArray(w * h) { sceneColor }
        val feathered = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val fused = IntArray(w * h) { 0xFFFF0000.toInt() }
        // 掩膜只在中心 8×8 区域为前景
        val mask = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 12..19 && y in 12..19) 255 else 0
        }

        val out = EdgeHaloFix.blend(
            scenePixels = scene,
            featheredPixels = feathered,
            fusedPixels = fused,
            mask = mask,
            width = w,
            height = h,
            featherPx = 3,
        )

        // 距离掩膜足够远的角落必须原封不动
        assertEquals(sceneColor, out[0])
        assertEquals(sceneColor, out[w - 1])
    }

    @Test
    fun `掩膜中心采用融合结果`() {
        val w = 48
        val h = 48
        val scene = IntArray(w * h) { 0xFF203040.toInt() }
        val fusedColor = 0xFF00AA00.toInt()
        val feathered = IntArray(w * h) { 0xFFFF00FF.toInt() }
        val fused = IntArray(w * h) { fusedColor }
        val mask = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 8..39 && y in 8..39) 255 else 0
        }

        val out = EdgeHaloFix.blend(scene, feathered, fused, mask, w, h, featherPx = 2)

        // 中心区域应基本等于融合结果（允许 ±2 的插值误差）
        val center = out[24 * w + 24]
        val r = (center shr 16) and 0xFF
        val g = (center shr 8) and 0xFF
        val b = center and 0xFF
        assertTrue("中心像素应接近融合结果，实测 ($r,$g,$b)", r <= 2 && g in 168..172 && b <= 2)
    }

    // ------------------------------------------------------------------ 细节保真（可选第四段）

    /**
     * 造一张合成图用于细节保真相关用例。
     *
     * 刻意构造得**贴近真实失效场景**：
     *  - 背景是高频棋盘（模拟草地/木纹）；
     *  - `fused` 是**背景的模糊版**（模拟 540p 泊松结果上采样后丢高频）；
     *  - `feathered` 保留原始背景。
     * 这样 `out = (1-w)·feathered + w·fused` 里丢掉的高频恰好正比于 w，
     * 细节保真的目标就是把它按 w 补回来 —— 度量也因此是单调的。
     */
    private class Fixture(w: Int, h: Int) {
        // 注意：构造参数若不加 val，只在属性初始化器与 init 块里可见，成员函数里访问不到 —— 必须转成属性
        val width: Int = w
        val height: Int = h

        val mask = IntArray(w * h) { i ->
            val x = i % width
            val y = i / width
            if (x in 14..25 && y in 14..25) 255 else 0
        }

        private fun lumaAt(i: Int): Float {
            val x = i % width
            val y = i / width
            val on = (((x / 2) + (y / 2)) and 1) == 0
            return if (on) 187f else 68f
        }

        val scene = IntArray(w * h) { i ->
            val v = lumaAt(i).toInt()
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }

        /** 模糊版背景：用盒式模糊近似"上采样后的低频结果"。 */
        val fused: IntArray = run {
            val plane = FloatArray(w * h) { lumaAt(it) }
            val blurred = BoxBlur.blur(plane, w, h, radius = 3, passes = 2)
            IntArray(w * h) { i ->
                val v = blurred[i].toInt().coerceIn(0, 255)
                0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }

        val feathered = scene.copyOf()

        fun lumaOf(argb: Int): Float =
            0.2126f * ((argb shr 16) and 0xFF) +
                0.7152f * ((argb shr 8) and 0xFF) +
                0.0722f * (argb and 0xFF)
    }

    @Test
    fun `细节保真为 0 时与不传该参数逐像素相同`() {
        val f = Fixture(36, 36)
        val base = EdgeHaloFix.blend(f.scene, f.feathered, f.fused, f.mask, f.width, f.height, featherPx = 3)
        val zero = EdgeHaloFix.blend(
            f.scene, f.feathered, f.fused, f.mask, f.width, f.height,
            featherPx = 3, detailStrength = 0f,
        )
        for (i in base.indices) {
            assertEquals("detailStrength=0 必须与不传该参数完全一致（下标 $i）", base[i], zero[i])
        }
    }

    @Test
    fun `细节保真不改动奶蛙本体与掩膜外`() {
        val f = Fixture(36, 36)
        val base = EdgeHaloFix.blend(f.scene, f.feathered, f.fused, f.mask, f.width, f.height, featherPx = 3)

        val report = EdgeHaloFix.DetailReport()
        val refined = EdgeHaloFix.blend(
            f.scene, f.feathered, f.fused, f.mask, f.width, f.height,
            featherPx = 3, detailStrength = 0.5f, detailReport = report,
        )

        assertTrue("本次应真的应用了细节保真（原因=${report.fallbackReason}）", report.applied)

        // 奶蛙本体（alpha=1 → band = w × (1-1) = 0）必须完全不动
        assertEquals("掩膜中心属于奶蛙本体，不应被改动", base[20 * f.width + 20], refined[20 * f.width + 20])
        // 远离掩膜处（core=0 → w=0 → band=0）同样必须不动
        assertEquals("画面角落不应被改动", base[0], refined[0])
        assertEquals("右下角不应被改动", base[f.width * f.height - 1], refined[f.width * f.height - 1])

        // 但背景侧边缘必须有像素被修正（否则这个功能等于没生效）
        var changed = 0
        for (i in base.indices) if (base[i] != refined[i]) changed++
        assertTrue("边缘背景应有像素被修正，实测 $changed 个", changed > 30)
    }

    @Test
    fun `细节保真把过渡带的背景高频补回来`() {
        val f = Fixture(36, 36)
        val base = EdgeHaloFix.blend(f.scene, f.feathered, f.fused, f.mask, f.width, f.height, featherPx = 3)
        val refined = EdgeHaloFix.blend(
            f.scene, f.feathered, f.fused, f.mask, f.width, f.height,
            featherPx = 3, detailStrength = 0.5f,
        )

        // 统计范围：**奶蛙以外的背景像素**（细节保真的作用域就是这个），
        // 且必须是被混合动过的（base 与原始背景不同）
        var before = 0.0
        var after = 0.0
        var n = 0
        for (i in f.scene.indices) {
            if (f.mask[i] != 0) continue
            val truth = f.lumaOf(f.scene[i])
            val b = f.lumaOf(base[i])
            if (kotlin.math.abs(b - truth) < 0.5f) continue // 未被混合影响，不参与统计
            before += kotlin.math.abs(b - truth)
            after += kotlin.math.abs(f.lumaOf(refined[i]) - truth)
            n++
        }
        before /= n
        after /= n
        println("[EdgeHaloFix] 边缘背景与原图的平均亮度差：修正前 %.2f → 修正后 %.2f（%d 像素）".format(before, after, n))
        assertTrue("边缘背景应有足够像素参与统计", n > 30)
        assertTrue("细节保真应让边缘背景更接近原图：before=$before after=$after", after < before)
    }
}
