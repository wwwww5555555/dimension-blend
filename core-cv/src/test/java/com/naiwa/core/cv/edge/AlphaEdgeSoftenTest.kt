package com.naiwa.core.cv.edge

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.3 / 6.4 Alpha 边缘柔化的测试。
 *
 * 两条契约：
 *  1. **只改 alpha，RGB 逐像素不变**（与 [EdgeDefringe] 互补）；
 *  2. **实心区与完全透明区不变** —— 否则主体会变瘦、透明区会出现雾气。
 *
 * 6.4 的方向性还有一条很干净的判据：**对完全笔直的边（alpha 只随 x 变化，纵向恒定），
 * 沿切线（纵向）采样得到的值与原值相同，因此方向性这一步应当几乎不产生变化**。
 * 反过来对阶梯状轮廓才应该有效 —— 用这两条一起把方向锁死。
 */
class AlphaEdgeSoftenTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** 竖直直边：alpha 只随 x 变化（左实心 右透明），RGB 固定。 */
    private fun straightEdge(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val a = when {
            x < w / 2 - 2 -> 255
            x > w / 2 + 2 -> 0
            else -> 255 - ((x - (w / 2 - 2)) * 255 / 5)
        }
        argb(a.coerceIn(0, 255), 100, 150, 90)
    }

    /** 阶梯状轮廓：竖直直边加上每 4 行错开 1px 的台阶。 */
    private fun staircaseEdge(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val shift = (y / 4) % 2 // 0 / 1 交替 → 台阶
        val cx = w / 2 + shift
        val a = when {
            x < cx - 2 -> 255
            x > cx + 2 -> 0
            else -> 255 - ((x - (cx - 2)) * 255 / 5)
        }
        argb(a.coerceIn(0, 255), 100, 150, 90)
    }

    @Test
    fun `RGB 通道逐像素不变`() {
        val w = 32
        val h = 32
        val src = staircaseEdge(w, h)
        val out = AlphaEdgeSoften.soften(src, w, h, softness = 0.5f).pixels
        for (i in src.indices) {
            assertEquals(
                "边缘柔化只改 alpha，RGB 必须不变（下标 $i）",
                src[i] and 0x00FFFFFF,
                out[i] and 0x00FFFFFF,
            )
        }
    }

    @Test
    fun `实心区与完全透明区不变`() {
        val w = 32
        val h = 32
        val src = staircaseEdge(w, h)
        val out = AlphaEdgeSoften.soften(src, w, h, softness = 0.5f).pixels
        var solid = 0
        var clear = 0
        for (i in src.indices) {
            val a = (src[i] ushr 24) and 0xFF
            when (a) {
                255 -> {
                    assertEquals("实心区 alpha 不应被改动（下标 $i）", 255, (out[i] ushr 24) and 0xFF)
                    solid++
                }
                0 -> {
                    assertEquals("透明区 alpha 不应被改动（下标 $i）", 0, (out[i] ushr 24) and 0xFF)
                    clear++
                }
            }
        }
        assertTrue("上下两半各有实心与透明区", solid > 100 && clear > 100)
    }

    @Test
    fun `直边几乎不被方向性模糊改动`() {
        // 竖直直边：切向是纵向，纵向 alpha 恒定 → 沿切向采样等于原值 → 变化应极小
        val w = 32
        val h = 32
        val src = straightEdge(w, h)
        val result = AlphaEdgeSoften.soften(src, w, h, softness = 0.5f)
        var maxDelta = 0
        for (i in src.indices) {
            maxDelta = maxOf(maxDelta, abs(((src[i] ushr 24) and 0xFF) - ((result.pixels[i] ushr 24) and 0xFF)))
        }
        println("[6.4] 直边上的最大 alpha 变化 = $maxDelta，方向性改动 ${result.directionalPixels} px")
        // 6.3 的小半径各向同性仍会有一点点变化，所以不要求 0；但必须很小
        assertTrue("直边不应被明显改动，实测 $maxDelta", maxDelta <= 12)
    }

    @Test
    fun `阶梯轮廓会被方向性平滑`() {
        val w = 32
        val h = 32
        val src = staircaseEdge(w, h)
        val result = AlphaEdgeSoften.soften(src, w, h, softness = 0.5f)
        println("[6.3] 柔化 ${result.softenedPixels} px；[6.4] 方向性 ${result.directionalPixels} px")
        assertTrue("阶梯轮廓应被方向性平滑到", result.directionalPixels > 0)
        assertTrue("6.3 应作用于边缘带", result.softenedPixels > 0)
    }

    @Test
    fun `柔度越小半径越大`() {
        // 规格公式：radius = clamp(0.5 × (1 - softness), 0.3, 1.5)
        // softness = 0 → 0.5px；softness = 1 → 0.3px。所以 softness 越小改动越大。
        val w = 32
        val h = 32
        val src = staircaseEdge(w, h)
        fun totalChange(softness: Float): Long {
            val out = AlphaEdgeSoften.soften(src, w, h, softness).pixels
            var sum = 0L
            for (i in src.indices) {
                sum += abs(((src[i] ushr 24) and 0xFF) - ((out[i] ushr 24) and 0xFF)).toLong()
            }
            return sum
        }
        val hard = totalChange(0f)
        val soft = totalChange(1f)
        println("[6.3] 总改动量：softness=0 → $hard，softness=1 → $soft")
        assertTrue("softness 越小半径越大、改动越多", hard >= soft)
        assertTrue("两者都应有改动", hard > 0 && soft > 0)
    }

    @Test
    fun `6_3 不能退化成空操作`() {
        // 回归守卫。早期实现把规格给的数值当"核半径"、再取 σ = 值/2 = 0.15，
        // 于是高斯核 exp(-1/(2·0.0225)) ≈ 2e-10 在 float 归一化后中心项恰好等于 1.0f
        // —— 核退化成**单位脉冲**，6.3 完全没生效（实测 softenedPixels 恒为 0）。
        //
        // 另外记一笔：规格公式 `clamp(0.5 × (1 - softness), 0.3, 1.5)` 的取值区间只有 [0, 0.5]，
        // **上界 1.5 永远到不了**，所以 6.3 实际只能产出 σ ∈ [0.3, 0.5]。
        // 也就是说 6.3 被设计成一档"极轻"的柔化，可见的柔化主要来自 6.4。
        val w = 32
        val h = 32
        for (softness in listOf(0f, 0.5f, 1f)) {
            val r = AlphaEdgeSoften.soften(staircaseEdge(w, h), w, h, softness)
            assertTrue("softness=$softness 时 6.3 必须真的产生改动", r.softenedPixels > 0)
        }
    }

    @Test
    fun `尺寸不合法时原样返回且不抛异常`() {
        val r = AlphaEdgeSoften.soften(IntArray(4), 0, 0, 0.5f)
        assertEquals(0, r.softenedPixels)
        assertEquals(0, r.directionalPixels)
        assertEquals(4, r.pixels.size)
    }
}
