package com.naiwa.core.cv.blend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块融合的测试，重点是架构文档 §7.3 的**硬性要求**：
 * 25% 重叠 + 汉宁窗加权，且合并结果不能因为分块而变暗或变亮。
 */
class TiledBlenderTest {

    @Test
    fun `小图不分块`() {
        val plan = TiledBlender.planTiles(1000, 800)
        assertEquals(1, plan.size)
        assertArrayEqualsInt(intArrayOf(0, 0, 1000, 800), plan[0])
    }

    @Test
    fun `大图分块覆盖完整且相邻块有 25% 重叠`() {
        val w = 5000
        val h = 3000
        val plan = TiledBlender.planTiles(w, h)

        // 覆盖完整：每个像素至少被一个 tile 覆盖
        val covered = BooleanArray(w * h)
        for (rect in plan) {
            for (y in rect[1] until rect[3]) {
                for (x in rect[0] until rect[2]) covered[y * w + x] = true
            }
        }
        assertTrue("存在未被任何 tile 覆盖的像素", covered.all { it })

        // 重叠：第一行相邻两个 tile 的重叠宽度应约为 tileSize * 25%
        val firstRow = plan.filter { it[1] == plan[0][1] }.sortedBy { it[0] }
        if (firstRow.size >= 2) {
            val overlap = firstRow[0][2] - firstRow[1][0]
            val expected = (TiledBlender.TILE_SIZE * TiledBlender.OVERLAP_RATIO).toInt()
            assertTrue("重叠宽度 $overlap 与期望 $expected 偏差过大", kotlin.math.abs(overlap - expected) <= 2)
        }
    }

    @Test
    fun `汉宁窗两端为 0 中间为 1`() {
        val window = TiledBlender.hannWindow(9)
        assertEquals(0f, window.first(), 1e-4f)
        assertEquals(0f, window.last(), 1e-4f)
        assertEquals(1f, window[4], 1e-4f)
        // 对称
        for (i in window.indices) {
            assertEquals(window[i], window[window.size - 1 - i], 1e-4f)
        }
    }

    @Test
    fun `相同内容的分块合并后保持原值`() {
        val w = 64
        val h = 64
        val plan = listOf(
            intArrayOf(0, 0, 40, 40),
            intArrayOf(24, 0, 64, 40),
            intArrayOf(0, 24, 40, 64),
            intArrayOf(24, 24, 64, 64),
        )
        val value = (0xFF shl 24) or (120 shl 16) or (60 shl 8) or 30
        val tiles = plan.map { rect ->
            IntArray((rect[2] - rect[0]) * (rect[3] - rect[1])) { value }
        }
        val merged = TiledBlender.mergeTiles(w, h, plan, tiles)

        // 合并只是加权平均：所有输入相同 ⇒ 输出必须完全相同，不能因权重归一化误差而漂移
        for (i in merged.indices) {
            val c = merged[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertTrue("第 $i 个像素在合并后发生漂移：($r,$g,$b)", r in 119..121 && g in 59..61 && b in 29..31)
        }
    }

    @Test
    fun `计划与数据数量不一致时抛出明确异常`() {
        try {
            TiledBlender.mergeTiles(10, 10, listOf(intArrayOf(0, 0, 10, 10)), emptyList())
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 预期行为
        }
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i])
    }
}
