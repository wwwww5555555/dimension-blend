package com.naiwa.feature.editor.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naiwa.core.common.model.LightFxRules
import com.naiwa.core.common.model.LightOverrides

/**
 * 手动调节面板（P3）。
 *
 * 设计要点：
 *  1. **每个滑杆都有真实单位与区间**（色温 K、方位角 °、倍率），不做"0~100 的抽象值"
 *     —— 用户看不出 0~100 代表什么，也就无法建立"往哪边拖会怎样"的预期；
 *  2. **「自动」按钮把该项恢复为自动估计值**（[LightOverrides] 里置 null），
 *     而不是重置成某个默认数字。这是"可空覆盖值"这套设计的直接收益；
 *  3. 滑杆改动在 ViewModel 里防抖（300ms）后记录一次撤销点，
 *     否则拖一次滑杆会塞进几十条历史，把深度 20 的栈瞬间冲掉。
 */
@Composable
fun LightSlidersPanel(
    overrides: LightOverrides,
    autoColorTempK: Float,
    autoAzimuthDeg: Float,
    autoElevationDeg: Float,
    autoShadowStrength: Float,
    autoShadowSoftness: Float,
    autoRimStrength: Float,
    autoGlowStrength: Float,
    onOverridesChange: (LightOverrides) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("手动调节", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onResetAll, enabled = !overrides.isDefault) {
                Text("全部恢复自动")
            }
        }

        SliderRow(
            label = "色温",
            value = overrides.colorTempK ?: autoColorTempK,
            range = 2000f..12000f,
            displayText = "${(overrides.colorTempK ?: autoColorTempK).toInt()} K" +
                if (overrides.colorTempK == null) "（自动）" else "",
            isAuto = overrides.colorTempK == null,
            onValueChange = { onOverridesChange(overrides.copy(colorTempK = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(colorTempK = null)) },
            // 色温 6500K 是中性点，把它作为"参考刻度"更符合直觉
            neutralValue = 6500f,
        )

        SliderRow(
            label = "主光强度",
            value = overrides.lightIntensity ?: 1f,
            range = 0f..2f,
            displayText = "×" + String.format(java.util.Locale.US, "%.2f", overrides.lightIntensity ?: 1f),
            isAuto = overrides.lightIntensity == null,
            onValueChange = { onOverridesChange(overrides.copy(lightIntensity = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(lightIntensity = null)) },
            neutralValue = 1f,
        )

        SliderRow(
            label = "阴影方向",
            value = overrides.azimuthDeg ?: autoAzimuthDeg,
            range = 0f..360f,
            displayText = "${(overrides.azimuthDeg ?: autoAzimuthDeg).toInt()}°" +
                if (overrides.azimuthDeg == null) "（自动）" else "",
            isAuto = overrides.azimuthDeg == null,
            onValueChange = { onOverridesChange(overrides.copy(azimuthDeg = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(azimuthDeg = null)) },
            neutralValue = autoAzimuthDeg,
        )

        SliderRow(
            label = "光的高度角",
            value = overrides.elevationDeg ?: autoElevationDeg,
            range = 5f..90f,
            displayText = "${(overrides.elevationDeg ?: autoElevationDeg).toInt()}°" +
                if (overrides.elevationDeg == null) "（自动）" else "",
            isAuto = overrides.elevationDeg == null,
            onValueChange = { onOverridesChange(overrides.copy(elevationDeg = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(elevationDeg = null)) },
            neutralValue = autoElevationDeg,
        )

        SliderRow(
            label = "阴影浓度",
            value = overrides.shadowStrength ?: autoShadowStrength,
            range = 0f..1f,
            displayText = String.format(java.util.Locale.US, "%.2f", overrides.shadowStrength ?: autoShadowStrength),
            isAuto = overrides.shadowStrength == null,
            onValueChange = { onOverridesChange(overrides.copy(shadowStrength = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(shadowStrength = null)) },
            neutralValue = autoShadowStrength,
        )

        SliderRow(
            label = "阴影软硬",
            value = overrides.shadowSoftness ?: autoShadowSoftness,
            range = 0f..1f,
            displayText = String.format(java.util.Locale.US, "%.2f", overrides.shadowSoftness ?: autoShadowSoftness),
            isAuto = overrides.shadowSoftness == null,
            onValueChange = { onOverridesChange(overrides.copy(shadowSoftness = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(shadowSoftness = null)) },
            neutralValue = autoShadowSoftness,
        )

        SliderRow(
            label = "融合强度",
            value = overrides.fusionStrength ?: LightOverrides.DEFAULT_FUSION_STRENGTH,
            range = 0f..1f,
            displayText = String.format(
                java.util.Locale.US, "%.2f",
                overrides.fusionStrength ?: LightOverrides.DEFAULT_FUSION_STRENGTH,
            ),
            isAuto = overrides.fusionStrength == null,
            onValueChange = { onOverridesChange(overrides.copy(fusionStrength = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(fusionStrength = null)) },
            neutralValue = LightOverrides.DEFAULT_FUSION_STRENGTH,
        )

        SliderRow(
            label = "边缘羽化",
            value = overrides.featherScale ?: 1f,
            range = 0.5f..2f,
            displayText = "×" + String.format(java.util.Locale.US, "%.2f", overrides.featherScale ?: 1f),
            isAuto = overrides.featherScale == null,
            onValueChange = { onOverridesChange(overrides.copy(featherScale = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(featherScale = null)) },
            neutralValue = 1f,
        )

        // ---- 高级光效（规格 7.1 / 7.2 / 7.4）----
        // 三个滑杆拖到 0 就等于**关掉该步骤**（管线会整段跳过，不只是强度为 0），
        // 所以它们是真正意义上的"开关 + 强度"，不需要额外的开关控件。
        SliderRow(
            label = "边缘光",
            value = overrides.rimStrength ?: autoRimStrength,
            range = 0f..LightFxRules.RIM_MAX,
            displayText = String.format(
                java.util.Locale.US, "%.2f%s",
                overrides.rimStrength ?: autoRimStrength,
                if (overrides.rimStrength == null) "（自动）" else if (overrides.rimStrength == 0f) "（关）" else "",
            ),
            isAuto = overrides.rimStrength == null,
            onValueChange = { onOverridesChange(overrides.copy(rimStrength = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(rimStrength = null)) },
            neutralValue = autoRimStrength,
        )

        SliderRow(
            label = "辉光",
            value = overrides.glowStrength ?: autoGlowStrength,
            range = 0f..LightFxRules.GLOW_MAX,
            displayText = String.format(
                java.util.Locale.US, "%.2f%s",
                overrides.glowStrength ?: autoGlowStrength,
                if (overrides.glowStrength == null) "（自动）" else if (overrides.glowStrength == 0f) "（关）" else "",
            ),
            isAuto = overrides.glowStrength == null,
            onValueChange = { onOverridesChange(overrides.copy(glowStrength = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(glowStrength = null)) },
            neutralValue = autoGlowStrength,
        )

        SliderRow(
            label = "环境色",
            value = overrides.lightWrapStrength ?: LightFxRules.WRAP_DEFAULT,
            range = 0f..LightFxRules.WRAP_MAX,
            displayText = String.format(
                java.util.Locale.US, "%.2f%s",
                overrides.lightWrapStrength ?: LightFxRules.WRAP_DEFAULT,
                if (overrides.lightWrapStrength == null) "（自动）" else if (overrides.lightWrapStrength == 0f) "（关）" else "",
            ),
            isAuto = overrides.lightWrapStrength == null,
            onValueChange = { onOverridesChange(overrides.copy(lightWrapStrength = it)) },
            onResetToAuto = { onOverridesChange(overrides.copy(lightWrapStrength = null)) },
            neutralValue = LightFxRules.WRAP_DEFAULT,
        )

        Text(
            text = "拖动滑杆后松开即生效；「恢复自动」会把该项交回给光照估计结果。\n" +
                "辉光与环境色**只在导出时执行**（预览只做边缘光，保证拖动跟手）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayText: String,
    isAuto: Boolean,
    onValueChange: (Float) -> Unit,
    onResetToAuto: () -> Unit,
    neutralValue: Float,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label  $displayText",
                style = MaterialTheme.typography.labelSmall,
                color = if (isAuto) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            if (!isAuto) {
                TextButton(onClick = onResetToAuto) {
                    Text("自动", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            // 中性刻度：让用户知道"回到这里就是没有偏移"
            steps = 0,
        )
    }
}
