package com.naiwa.core.common.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回读方向的判定逻辑（纯 JVM 部分）。
 *
 * GL 那一半（探测、readPixels）只能真机验证，但"看到什么就推出该不该翻"这条判定
 * 必须在这里锁住 —— 它是本项目历史上唯一真正出过画面事故的一次：
 * 三个渲染器各写一份回读，其中两份多翻了一次，于是降级时画面上下颠倒。
 */
class GlReadbackTest {

    @Test
    fun `首行就是原图首行时不翻转`() {
        assertFalse(GlReadback.needsFlip(firstReadRowIsSourceTop = true))
    }

    @Test
    fun `首行是原图末行时必须翻转`() {
        assertTrue(GlReadback.needsFlip(firstReadRowIsSourceTop = false))
    }

    @Test
    fun `默认策略是不翻转`() {
        // 规范推导：GLUtils 上传 + uv.y=0 在帧缓冲底部 + glReadPixels 从底部开始
        // 这三步已经把方向抵掉，再翻一次就是上下颠倒。
        assertFalse(GlReadback.DEFAULT_FLIP)
    }

    @Test
    fun `探测前没有结论`() {
        GlReadback.resetProbe()
        assertNull(GlReadback.measuredFlip)
    }

    @Test
    fun `探测结论会被缓存并对外可读`() {
        GlReadback.overrideForTest(true)
        assertEquals(true, GlReadback.measuredFlip)
        GlReadback.overrideForTest(false)
        assertEquals(false, GlReadback.measuredFlip)
        // 收尾：不要把测试态留给同进程里的其它测试
        GlReadback.resetProbe()
    }

    @Test
    fun `非法尺寸直接放弃而不是抛异常`() {
        // fbo = 0 / 宽高 <= 0 都必须在碰 GL 之前返回，否则 JVM 单测里会
        // 撞上 "not mocked" 的 GLES30 桩，把一次正常降级变成崩溃。
        assertNull(GlReadback.read(0, 100, 100))
        assertNull(GlReadback.read(1, 0, 100))
        assertNull(GlReadback.read(1, 100, -1))
    }
}
