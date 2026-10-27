package com.naiwa.core.cv.blend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块融合中**可在 JVM 覆盖**的部分。
 *
 * 边界说明（不夸大覆盖率）：完整的 [TiledBlender.fuse] 依赖 Android `Bitmap` 与 OpenCV 原生库，
 * 只能在真机/仪器测试里跑。这里钉住的是它内部两件容易悄悄写错、且错了会表现为
 * "某个 tile 整体偏移/漏融合"的事情：子掩膜裁剪、前景判定；外加一条端到端不变量
 * —— **合并后不允许出现没被任何 tile 覆盖的像素**（那会变成透明黑洞）。
 */
class TiledBlenderFuseTest {

    @Test
    fun `子掩膜裁剪与整图坐标一致`() {
        val width = 10
        val height = 8
        // 掩膜值 = y * 100 + x，任何行列错位都会立刻暴露
        val mask = IntArray(width * height) { i -> (i / width) * 100 + (i % width) }

        val tile = TiledBlender.cropMask(mask, width, left = 3, top = 2, width = 4, height = 3)

        assertEquals(12, tile.size)
        for (y in 0 until 3) {
            for (x in 0 until 4) {
                assertEquals(
                    "局部坐标 ($x,$y) 应等于整图坐标 (${x + 3},${y + 2})",
                    (y + 2) * 100 + (x + 3),
                    tile[y * 4 + x],
                )
            }
        }
    }

    @Test
    fun `相邻重叠块裁出的重叠区完全一致`() {
        // 25% 重叠的意义就是同一片像素被两块都看到；若裁剪有偏差，加权合并必然出现重影。
        val width = 64
        val height = 32
        val mask = IntArray(width * height) { i -> ((i / width) * 7 + (i % width) * 3) and 0xFF }

        val left = TiledBlender.cropMask(mask, width, left = 0, top = 0, width = 40, height = 32)
        val right = TiledBlender.cropMask(mask, width, left = 30, top = 0, width = 34, height = 32)

        // 整图 x ∈ [30, 40) 是两个 tile 的重叠区
        for (y in 0 until 32) {
            for (x in 30 until 40) {
                assertEquals(
                    "重叠像素 (x=$x,y=$y) 在两个 tile 里必须取到同一个值",
                    left[y * 40 + x],
                    right[y * 34 + (x - 30)],
                )
            }
        }
    }

    @Test
    fun `前景判定_全零为假_含非零为真`() {
        assertFalse(TiledBlender.hasForeground(IntArray(16)))
        assertTrue(TiledBlender.hasForeground(IntArray(16) { if (it == 15) 1 else 0 }))
        // 只有 0/1 也要判出来（阈值不能被写成 "> 8"）
        assertTrue(TiledBlender.hasForeground(IntArray(4) { 1 }))
    }

    @Test
    fun `大图合并后不允许存在未被覆盖的像素`() {
        // 用常量内容做合并：任何"没被任何 tile 覆盖"的像素都会保持 0（透明黑）。
        // 这比"算得准不准"更基础 —— 它保证分块方案本身是完备的。
        val width = 4096
        val height = 2600
        val plan = TiledBlender.planTiles(width, height)
        val tiles = plan.map { rect ->
            val w = rect[2] - rect[0]
            val h = rect[3] - rect[1]
            IntArray(w * h) { 0xFF808080.toInt() }
        }

        val merged = TiledBlender.mergeTiles(width, height, plan, tiles)

        var uncovered = 0
        var wrongValue = 0
        for (c in merged) {
            if (((c ushr 24) and 0xFF) == 0) uncovered++
            if (((c shr 16) and 0xFF) != 0x80) wrongValue++
        }
        println("[分块] 4096×2600 合并：${plan.size} 块，未覆盖像素 $uncovered，取值异常 $wrongValue")
        assertEquals("不应存在未被覆盖的像素", 0, uncovered)
        assertEquals("常量内容合并后应逐像素等于原值（权重已归一化）", 0, wrongValue)
    }
}
