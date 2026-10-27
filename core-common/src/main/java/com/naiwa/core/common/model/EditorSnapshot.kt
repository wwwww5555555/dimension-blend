package com.naiwa.core.common.model

/**
 * 可撤销的编辑状态快照。
 *
 * 撤销/重做的粒度是「一次用户操作」：
 *  - 拖动/缩放/旋转在**手指抬起时**记录一次（拖动过程中不记录，否则 20 次容量会被一帧帧的移动吃光）；
 *  - 滑杆在**值稳定 300ms 后**记录一次（用 debounce），同理。
 *
 * 快照只含轻量值对象（两个 data class），单份约几十字节，
 * 因此深度 20 的栈总占用可忽略（见 [com.naiwa.core.common.util.UndoRedoStack]）。
 */
data class EditorSnapshot(
    val placement: Placement = Placement.DEFAULT,
    val overrides: LightOverrides = LightOverrides.DEFAULT,
) {
    companion object {
        val DEFAULT = EditorSnapshot()
    }
}
