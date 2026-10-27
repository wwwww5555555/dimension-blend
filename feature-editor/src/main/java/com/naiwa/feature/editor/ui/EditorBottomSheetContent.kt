package com.naiwa.feature.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naiwa.core.common.model.LightFxRules
import com.naiwa.core.common.model.LightOverrides

/** 底部抽屉的三个模块（对标 Lightroom 的 Light / Color / Effects 分组）。 */
private val TABS = listOf("光影", "调色", "高级")

/**
 * 编辑页底部抽屉内容：TabRow 三分类 + FineTuneSlider 参数组。
 *
 * 交互契约（与 ViewModel 的分工，见 8.4）：
 *  - `onOverridesChange` / `onColorAdjustChange`：**只在拖动中更新 state**
 *    （画面由 Compose ColorMatrix 实时反映，不跑任何渲染）；
 *  - `onCommit`：**松手时**才触发低清快渲染。
 */
@Composable
fun EditorBottomSheetContent(
    overrides: LightOverrides,
    colorAdjust: ColorAdjust,
    harmonizeStrength: Float,
    autoRimStrength: Float,
    onOverridesChange: (LightOverrides) -> Unit,
    onColorAdjustChange: (ColorAdjust) -> Unit,
    onCommit: () -> Unit,
    onHarmonizeStrengthChange: (Float) -> Unit,
    onResetAll: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    wrapDebugMode: Int,
    onCycleWrapDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            TABS.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedTab == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (selectedTab) {
                0 -> LightTab(
                    overrides = overrides,
                    harmonizeStrength = harmonizeStrength,
                    autoRimStrength = autoRimStrength,
                    onOverridesChange = onOverridesChange,
                    onCommit = onCommit,
                    onHarmonizeStrengthChange = onHarmonizeStrengthChange,
                )
                1 -> ColorTab(
                    adjust = colorAdjust,
                    onAdjustChange = onColorAdjustChange,
                    onCommit = onCommit,
                )
                else -> AdvancedTab(
                    overrides = overrides,
                    onOverridesChange = onOverridesChange,
                    onCommit = onCommit,
                    onResetAll = onResetAll,
                    onSaveProject = onSaveProject,
                    onLoadProject = onLoadProject,
                    wrapDebugMode = wrapDebugMode,
                    onCycleWrapDebug = onCycleWrapDebug,
                )
            }
        }
    }
}

/** 【光影】：融合强度 / 边缘光强 / 阴影浓度 / 画风保护度。 */
@Composable
private fun LightTab(
    overrides: LightOverrides,
    harmonizeStrength: Float,
    autoRimStrength: Float,
    onOverridesChange: (LightOverrides) -> Unit,
    onCommit: () -> Unit,
    onHarmonizeStrengthChange: (Float) -> Unit,
) {
    FineTuneSlider(
        label = "融合强度",
        value = overrides.fusionStrength ?: LightOverrides.DEFAULT_FUSION_STRENGTH,
        valueRange = 0f..1f,
        defaultValue = LightOverrides.DEFAULT_FUSION_STRENGTH,
        onValueChange = { onOverridesChange(overrides.copy(fusionStrength = it)) },
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2f", it) },
    )
    FineTuneSlider(
        label = "边缘光强",
        value = overrides.rimStrength ?: autoRimStrength,
        valueRange = 0f..LightFxRules.RIM_MAX,
        defaultValue = autoRimStrength,
        onValueChange = { onOverridesChange(overrides.copy(rimStrength = it)) },
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2f", it) },
    )
    FineTuneSlider(
        label = "阴影浓度",
        value = overrides.shadowStrength ?: 0.5f,
        valueRange = 0f..1f,
        defaultValue = 0.5f,
        onValueChange = { onOverridesChange(overrides.copy(shadowStrength = it)) },
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2f", it) },
    )
    FineTuneSlider(
        label = "画风保护度",
        value = harmonizeStrength,
        valueRange = 0f..1f,
        defaultValue = 0.8f,
        onValueChange = onHarmonizeStrengthChange,
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2f", it) },
    )
}

/** 【调色】：色温（居中对称）/ 亮度 / 对比度 / 饱和度 —— 全部走 Compose ColorMatrix 实时预览。 */
@Composable
private fun ColorTab(
    adjust: ColorAdjust,
    onAdjustChange: (ColorAdjust) -> Unit,
    onCommit: () -> Unit,
) {
    FineTuneSlider(
        label = "色温",
        value = adjust.temperature,
        valueRange = -1f..1f,
        defaultValue = 0f,
        bipolar = true,
        onValueChange = { onAdjustChange(adjust.copy(temperature = it)) },
        onValueChangeFinished = onCommit,
        format = signedFormat(scale = 100f),
    )
    FineTuneSlider(
        label = "亮度",
        value = adjust.brightness,
        valueRange = -1f..1f,
        defaultValue = 0f,
        bipolar = true,
        onValueChange = { onAdjustChange(adjust.copy(brightness = it)) },
        onValueChangeFinished = onCommit,
        format = signedFormat(scale = 100f),
    )
    FineTuneSlider(
        label = "对比度",
        value = adjust.contrast,
        valueRange = -1f..1f,
        defaultValue = 0f,
        bipolar = true,
        onValueChange = { onAdjustChange(adjust.copy(contrast = it)) },
        onValueChangeFinished = onCommit,
        format = signedFormat(scale = 100f),
    )
    FineTuneSlider(
        label = "饱和度",
        value = adjust.saturation,
        valueRange = -1f..1f,
        defaultValue = 0f,
        bipolar = true,
        onValueChange = { onAdjustChange(adjust.copy(saturation = it)) },
        onValueChangeFinished = onCommit,
        format = signedFormat(scale = 100f),
    )
}

/** 【高级】：羽化范围 / 光包裹强度（"半径"本轮未开放，见交付说明）。 */
@Composable
private fun AdvancedTab(
    overrides: LightOverrides,
    onOverridesChange: (LightOverrides) -> Unit,
    onCommit: () -> Unit,
    onResetAll: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    wrapDebugMode: Int,
    onCycleWrapDebug: () -> Unit,
) {
    // 调试通道：切换后立刻全量重渲染，屏幕直接显示中间量（灰度/通道色）
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        androidx.compose.material3.TextButton(onClick = onCycleWrapDebug) {
            Text("调试通道：${com.naiwa.core.common.debug.DebugReport.wrapModeLabel(wrapDebugMode)}")
        }
    }
    // 工程（非破坏性编辑）：只序列化 DAG 参数，与分辨率无关
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        androidx.compose.material3.TextButton(onClick = onSaveProject) { Text("保存工程") }
        androidx.compose.material3.TextButton(onClick = onLoadProject) { Text("加载工程") }
    }
    FineTuneSlider(
        label = "羽化范围",
        value = overrides.featherScale ?: 1f,
        valueRange = 0.5f..2f,
        defaultValue = 1f,
        onValueChange = { onOverridesChange(overrides.copy(featherScale = it)) },
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2fx", it) },
    )
    FineTuneSlider(
        label = "光包裹强度",
        value = overrides.lightWrapStrength ?: LightFxRules.WRAP_DEFAULT,
        valueRange = 0f..LightFxRules.WRAP_MAX,
        defaultValue = LightFxRules.WRAP_DEFAULT,
        onValueChange = { onOverridesChange(overrides.copy(lightWrapStrength = it)) },
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2f", it) },
    )
    FineTuneSlider(
        label = "辉光强度",
        value = overrides.glowStrength ?: LightFxRules.GLOW_BACKLIT,
        valueRange = 0f..LightFxRules.GLOW_MAX,
        defaultValue = LightFxRules.GLOW_BACKLIT,
        onValueChange = { onOverridesChange(overrides.copy(glowStrength = it)) },
        onValueChangeFinished = onCommit,
        format = { String.format(java.util.Locale.US, "%.2f", it) },
    )
    androidx.compose.material3.TextButton(
        onClick = onResetAll,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("全部恢复默认")
    }
}
