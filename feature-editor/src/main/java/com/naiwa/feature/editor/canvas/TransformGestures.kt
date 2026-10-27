package com.naiwa.feature.editor.canvas

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 编辑页的手势修饰符：单指拖动 / 双指缩放旋转。
 *
 * 设计取舍：**这里只上报手势增量，不在这里做节流或"松手"判断。**
 * 原因：`detectTransformGestures` 没有"手势结束"回调，硬要检测抬手需要自己写
 * `awaitEachGesture` 状态机，容易在多点触控下漏事件。
 * 改由 [com.naiwa.feature.editor.EditorViewModel] 用「最后一次事件后静默 120ms」来判定松手，
 * 逻辑集中在一处，且天然对"用户中途换手指"这类情况健壮。
 *
 * @param enabled 渲染中或没有素材时置 false，避免手势与渲染竞争同一个 Placement
 * @param onGesture 手势增量回调：panX/panY 为像素位移，zoom 为缩放倍率（1.0 = 不变），rotation 为角度增量
 */
fun Modifier.editorTransformGestures(
    enabled: Boolean,
    onGesture: (panX: Float, panY: Float, zoom: Float, rotation: Float) -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    detectTransformGestures { _, pan, zoom, rotation ->
        onGesture(pan.x, pan.y, zoom, rotation)
    }
}
