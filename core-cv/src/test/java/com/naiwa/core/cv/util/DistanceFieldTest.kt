package com.naiwa.core.cv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 距离变换（Chamfer 两遍扫描）契约测试，供 RimLight 的法向使用。
 */
class DistanceFieldTest {

    @Test
    fun `全前景时距离场在中心大边缘小`() {
        // 带背景的实心方块：左半前景、右半背景，验证"到最近背景的距离"语义
        val w = 16
        val h = 16
        val mask = IntArray(w * h) { i -> if (i % w < w / 2) 1 else 0 }
        val field = DistanceField.compute(mask, w, h)

        // 边界像素（x=7，紧邻背景）距离应是最小值（≈1，Chamfer 的 1 步）
        val edge = field[7]
        // 前景深处（x=0，离背景最远）距离应明显更大
        val deep = field[0]
        val center = field[5 * w + 4]
        assertTrue("边界距离应接近最小（edge=$edge）", edge < 2f)
        assertTrue("前景深处距离应大于边界（deep=$deep edge=$edge）", deep > edge)
        assertTrue("中心距离应大于边界（center=$center edge=$edge）", center > edge)
    }

    @Test
    fun `背景像素距离为零`() {
        val w = 8
        val h = 8
        // 左半前景右半背景
        val mask = IntArray(w * h) { i -> if (i % w < w / 2) 1 else 0 }
        val field = DistanceField.compute(mask, w, h)
        for (i in mask.indices) {
            if (mask[i] == 0) {
                assertEquals("背景距离为 0", 0f, field[i], 0f)
            }
        }
    }

    @Test
    fun `外法向指向物体外侧`() {
        // 左半实心方块（前景在左、背景在右），边界在 x=w/2
        val w = 20
        val h = 20
        val mask = IntArray(w * h) { i -> if (i % w < w / 2) 1 else 0 }
        val field = DistanceField.compute(mask, w, h)

        // 取前景内部靠近右边界的一点（x=8，边界在 x=10），其外法向应指向 +x（右侧）
        val n = DistanceField.outwardNormal(field, w, h, 8, 10)
        assertTrue("外法向应指向 +x（nx=${n[0]}）", n[0] > 0.5f)
        // y 方向应接近 0（垂直边界）
        assertTrue("垂直边界法向 y 分量应接近 0（ny=${n[1]}）", kotlin.math.abs(n[1]) < 0.5f)
    }

    @Test
    fun `尺寸非法时返回空场不抛异常`() {
        val field = DistanceField.compute(IntArray(0), 0, 0)
        assertEquals(0, field.size)
    }
}
