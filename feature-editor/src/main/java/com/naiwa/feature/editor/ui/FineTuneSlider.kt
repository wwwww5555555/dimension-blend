package com.naiwa.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs

/**
 * 专业级高精度滑杆（对标 Lightroom / 醒图的调节手感）。
 *
 * 四个关键设计（全部是"手感"的一部分，不是装饰）：
 *  1. **物理阻尼**：拖动像素换算值域时乘 [damping]（默认 0.5）——原生 Slider 是
 *     "拖到底"映射，一像素一变化，微调根本停不住；减半后同样的手部行程只能走到一半值域，
 *     精细度翻倍。想粗调就多拖一次，想要精度永远比想要速度更难补。
 *  2. **居中对称（bipolar）**：色温/亮度这类"零点在中间"的参数，轨道从中点向两侧展开，
 *     左侧冷色 / 右侧暖色，用户一眼看出"我偏离了原点多少、往哪边"。
 *  3. **双击重置**：双击轨道或标签 = 参数回默认。这是修图 App 的肌肉记忆，
 *     必须支持，且要能在拖过一圈后一键找回原点。
 *  4. **实时数值气泡**：拖动中在滑块上方浮出当前值（带 ± 号），松手 600ms 后淡出——
 *     数值常驻会挡画面，不显示又没法判断精度。
 *
 * 材质：轨道用 surfaceContainerHighest 打底、进度段用 primaryContainer / tertiaryContainer，
 * 手柄用 primary + 半透明外圈模拟 shadow（M3 无 elevation 的 Canvas 等价做法）。
 *
 * @param value 当前值
 * @param valueRange 值域
 * @param defaultValue 双击重置目标（通常是 0 或该参数的中性值）
 * @param onValueChange 拖动中回调（调用方只更新 state，**不要**在里面跑渲染）
 * @param onValueChangeFinished 松手回调（调用方在这里触发低清重渲染）
 * @param bipolar 是否居中对称（true = 零点在正中，左右分色）
 * @param damping 物理阻尼系数（0.5 = 手指行程换算值域时减半）
 * @param format 数值格式化（含正负号与单位）
 */
@Composable
fun FineTuneSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    bipolar: Boolean = false,
    damping: Float = 0.5f,
    format: (Float) -> String = { String.format(java.util.Locale.US, "%.2f", it) },
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val scope = rememberCoroutineScope()

    var widthPx by remember { mutableStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var bubbleVisible by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val span = (valueRange.endInclusive - valueRange.start)
    val isModified = abs(value - defaultValue) > span * 0.002f

    val trackBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val accentWarm = MaterialTheme.colorScheme.primary
    val accentCool = MaterialTheme.colorScheme.tertiary
    val accentContainer = MaterialTheme.colorScheme.primaryContainer
    val coolContainer = MaterialTheme.colorScheme.tertiaryContainer
    val handleColor = MaterialTheme.colorScheme.primary
    val handleRing = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val labelColor = if (isModified) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- 标签行：修改态高亮 + 双击重置提示 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                fontWeight = if (isModified) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.pointerInput(defaultValue, span) {
                    // 双击标签也能重置——用户不会每次都精准点到轨道
                    detectTapGestures(onDoubleTap = {
                        currentOnChange(defaultValue)
                        currentOnFinished()
                    })
                },
            )
            if (isModified) {
                Text(
                    text = "双击重置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(valueRange, damping, widthPx) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            bubbleVisible = true
                            hideJob?.cancel()
                        },
                        onDragEnd = {
                            isDragging = false
                            currentOnFinished()
                            hideJob = scope.launch {
                                delay(600)
                                if (!isDragging) bubbleVisible = false
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            currentOnFinished()
                            bubbleVisible = false
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        // 物理阻尼：像素位移 → 值域位移，再乘 damping 降低灵敏度
                        val deltaValue = dragAmount.x / widthPx * span * damping
                        val next = (currentValue + deltaValue)
                            .coerceIn(valueRange.start, valueRange.endInclusive)
                        if (next != currentValue) currentOnChange(next)
                    }
                }
                .pointerInput(valueRange.start, valueRange.endInclusive, defaultValue) {
                    detectTapGestures(onDoubleTap = {
                        currentOnChange(defaultValue)
                        currentOnFinished()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            // ---- 轨道 + 进度 + 手柄 ----
            Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                val trackH = 6.dp.toPx()
                val centerY = size.height / 2f
                val halfW = size.width / 2f
                val r = CornerRadius(trackH / 2f, trackH / 2f)

                // 底轨
                drawRoundRect(
                    color = trackBg,
                    topLeft = Offset(0f, centerY - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = r,
                )

                val norm = ((value - valueRange.start) / span).coerceIn(0f, 1f)
                val x = size.width * norm

                if (bipolar) {
                    // 居中对称：零点在正中，左右分色（左冷右暖）
                    drawRoundRect(
                        color = coolContainer,
                        topLeft = Offset(minOf(halfW, x), centerY - trackH / 2f),
                        size = Size(abs(halfW - x), trackH),
                        cornerRadius = r,
                    )
                    drawRoundRect(
                        color = accentContainer,
                        topLeft = Offset(halfW, centerY - trackH / 2f),
                        size = Size(x - halfW, trackH).let {
                            Size(it.width.coerceAtLeast(0f), it.height)
                        },
                        cornerRadius = r,
                    )
                    // 零点刻度线
                    drawRoundRect(
                        color = accentWarm.copy(alpha = 0.75f),
                        topLeft = Offset(halfW - 1.dp.toPx(), centerY - trackH / 2f - 3.dp.toPx()),
                        size = Size(2.dp.toPx(), trackH + 6.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                } else {
                    // 单向：从起点填充到当前值
                    drawRoundRect(
                        color = accentContainer,
                        topLeft = Offset(0f, centerY - trackH / 2f),
                        size = Size(x, trackH),
                        cornerRadius = r,
                    )
                }

                // 手柄：外圈半透明（模拟 shadow）+ 内圈实色
                val radius = if (isDragging) 11.dp.toPx() else 9.dp.toPx()
                drawCircle(color = handleRing, radius = radius + 6.dp.toPx(), center = Offset(x, centerY))
                drawCircle(color = handleColor, radius = radius, center = Offset(x, centerY))
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = radius * 0.38f,
                    center = Offset(x - radius * 0.25f, centerY - radius * 0.25f),
                )
            }

            // ---- 实时数值气泡（悬浮在轨道上方；alpha 淡入淡出，固定占位不推动布局） ----
            val bubbleAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (bubbleVisible) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(180),
                label = "bubbleAlpha",
            )
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier
                        .padding(bottom = 34.dp)
                        .alpha(bubbleAlpha),
                ) {
                    Text(
                        text = format(value),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 带符号的数值格式化（居中对称参数用）：+15 / -8 / 0。 */
fun signedFormat(unit: String = "", scale: Float = 1f): (Float) -> String = { v ->
    val scaled = v * scale
    val body = if (abs(scaled) < 0.05f) "0" else {
        (if (scaled > 0) "+" else "") + String.format(java.util.Locale.US, "%.0f", scaled)
    }
    "$body$unit"
}
