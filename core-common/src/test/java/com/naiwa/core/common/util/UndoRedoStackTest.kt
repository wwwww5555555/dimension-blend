package com.naiwa.core.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销/重做栈的行为测试。
 *
 * 这块逻辑容易被"测试用例写少了"坑到：栈满的丢弃策略、redo 栈在何时清空、
 * 空栈的返回值，这三处只要有一处错，用户就会遇到"撤销撤不回去"或"撤回了一个不该有的状态"。
 */
class UndoRedoStackTest {

    @Test
    fun `record 后可以撤销到上一个状态`() {
        val stack = UndoRedoStack<String>(capacity = 5)
        var current = "A"

        stack.record(current)          // 记录变更前状态 A
        current = "B"

        val previous = stack.undo(current)
        assertEquals("A", previous)
        assertFalse(stack.canUndo)     // 只有一条历史，撤完就没了
        assertTrue(stack.canRedo)
    }

    @Test
    fun `撤销后可以重做`() {
        val stack = UndoRedoStack<String>(capacity = 5)
        var current = "A"
        stack.record(current)
        current = "B"

        val previous = stack.undo(current)!!
        current = previous             // 回到 A
        val next = stack.redo(current)!!
        assertEquals("B", next)
    }

    @Test
    fun `超出容量时丢弃最旧的快照`() {
        val stack = UndoRedoStack<Int>(capacity = 3)
        for (i in 1..5) {
            stack.record(i)            // 记录 1..5，容量 3 应只保留 3、4、5
        }
        assertEquals(3, stack.undoDepth)

        var current = 99
        repeat(3) { current = stack.undo(current)!! }
        // 三次撤销应依次得到 5、4、3（最旧的 1、2 已被丢弃）
        assertEquals(3, current)
        assertFalse(stack.canUndo)
    }

    @Test
    fun `new 操作会清空重做栈`() {
        val stack = UndoRedoStack<String>(capacity = 5)
        stack.record("A")
        stack.undo("B")
        assertTrue(stack.canRedo)

        // 撤销后又做了新操作：旧的"重做路径"必须失效
        stack.record("C")
        assertFalse(stack.canRedo)
    }

    @Test
    fun `空栈的 undo 与 redo 返回 null 而不抛异常`() {
        val stack = UndoRedoStack<String>(capacity = 5)
        assertNull(stack.undo("A"))
        assertNull(stack.redo("A"))
    }

    @Test
    fun `capacity 非法时抛 IllegalArgumentException`() {
        try {
            UndoRedoStack<String>(capacity = 0)
            throw AssertionError("应当抛出异常")
        } catch (expected: IllegalArgumentException) {
            // 预期行为
        }
    }
}
