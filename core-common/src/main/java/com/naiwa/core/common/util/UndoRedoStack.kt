package com.naiwa.core.common.util

import kotlin.collections.ArrayDeque

/**
 * 定深度的撤销/重做栈（内存快照版，不落盘）。
 *
 * 用法约定：**记录的是「变更之前」的状态**。
 * ```
 * // 用户开始一次新操作前
 * history.record(current)      // 把变更前状态压入撤销栈，同时清空重做栈
 * current = newState           // 应用变更
 *
 * // 撤销
 * val prev = history.undo(current)   // 返回变更前状态，并把 current 压入重做栈
 *
 * // 重做
 * val next = history.redo(current)
 * ```
 *
 * 边界行为（均有单测覆盖）：
 *  - 超出 [capacity] 时丢弃**最旧**的快照（FIFO），保证内存占用有上限；
 *  - `record()` 会清空重做栈（新的操作分支使旧的重做路径失效）；
 *  - 栈空时 `undo()`/`redo()` 返回 `null`，不抛异常。
 *
 * @param capacity 最大撤销深度，默认 20。快照为轻量值对象，20 份的内存占用可忽略。
 */
class UndoRedoStack<T>(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "capacity 必须 >= 1，当前为 $capacity" }
    }

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    /** 记录变更前的状态。会清空重做栈。 */
    fun record(before: T) {
        undoStack.addLast(before)
        while (undoStack.size > capacity) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    /**
     * 撤销一步。
     * @param current 当前状态（会被压入重做栈）
     * @return 变更前状态；若已无可撤销内容返回 null
     */
    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return previous
    }

    /**
     * 重做一步。
     * @param current 当前状态（会被压回撤销栈）
     * @return 重做后的状态；若已无可重做内容返回 null
     */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    companion object {
        /** 默认撤销深度：足够覆盖「摆位置 → 调光照 → 换素材 → 调融合」一轮反复。 */
        const val DEFAULT_CAPACITY = 20
    }
}
