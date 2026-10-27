package com.naiwa.camera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * 奶蛙相机配色 —— 暖黄（琥珀 / 蜂蜜）主色。
 *
 * 设计约束（不是随便挑的颜色）：
 *  1. **编辑页大面积留暖色，但不能污染照片判断**：真正的画面预览区仍是照片本身，
 *     所有容器色都压在中低饱和度，避免用户误把 UI 的暖调当成照片被改暖了。
 *  2. **不依赖 dynamicColor**：动态取色会跟随壁纸变绿变蓝，与"固定偏黄"的要求冲突，
 *     所以这里全部写死色值，只按明暗两套切换。
 *  3. primary 是亮琥珀，所以 onPrimary 必须是深棕而不是白色 —— 白字压在 #F2B12B 上
 *     对比度只有 1.9:1，完全不可读。深棕 #3A2B00 上是 8.3:1，过 WCAG AA。
 */

// ---------- 亮色：奶油底 + 琥珀主色 ----------
private val AmberPrimary = Color(0xFFF2B12B)      // 主色：蜂蜜琥珀
private val AmberOnPrimary = Color(0xFF3A2B00)    // 压在琥珀上的文字（深棕，保证对比度）
private val AmberPrimaryContainer = Color(0xFFFFE9B8)
private val AmberOnPrimaryContainer = Color(0xFF2A1F00)

private val BronzeSecondary = Color(0xFF8D6E33)   // 次级：青铜
private val BronzeSecondaryContainer = Color(0xFFF6E4BE)

private val GoldTertiary = Color(0xFFB98314)

private val CreamBackground = Color(0xFFFFFBF2)   // 背景：暖奶油白
private val WarmInk = Color(0xFF231C0E)           // 正文：暖黑（不是纯黑，避免与暖底冲突）

private val LightColors = lightColorScheme(
    primary = AmberPrimary,
    onPrimary = AmberOnPrimary,
    primaryContainer = AmberPrimaryContainer,
    onPrimaryContainer = AmberOnPrimaryContainer,

    secondary = BronzeSecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = BronzeSecondaryContainer,
    onSecondaryContainer = Color(0xFF2A1F00),

    tertiary = GoldTertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF2A1F00),

    background = CreamBackground,
    onBackground = WarmInk,
    surface = Color(0xFFFFFCF5),
    onSurface = WarmInk,
    surfaceVariant = Color(0xFFF2E7CF),
    onSurfaceVariant = Color(0xFF514734),

    // surfaceContainer 系列决定 Scaffold / 底部素材条 / 卡片这类"带高度的容器"的颜色。
    // 不显式指定的话会退回 Material 默认的紫灰中性色，整体暖黄会被这些容器破坏。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF7E6),
    surfaceContainer = Color(0xFFFBF0D9),
    surfaceContainerHigh = Color(0xFFF5E8CC),
    surfaceContainerHighest = Color(0xFFEFE0BE),

    outline = Color(0xFF9A8C6E),
    outlineVariant = Color(0xFFDCCFB2),
    inverseSurface = Color(0xFF382F1E),
    inverseOnSurface = Color(0xFFFDEFD5),
    inversePrimary = Color(0xFFFFD479),
    scrim = Color(0xFF000000),
)

// ---------- 暗色：专业修图深灰底（#121212，对标 Lightroom） + 浅琥珀主色 ----------
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFD479),                  // 暗背景下用浅琥珀，保证足够亮度
    onPrimary = Color(0xFF3A2B00),
    primaryContainer = Color(0xFF5A4400),
    onPrimaryContainer = Color(0xFFFFE9B8),

    secondary = Color(0xFFDCC48C),
    onSecondary = Color(0xFF3B2F14),
    secondaryContainer = Color(0xFF54462A),
    onSecondaryContainer = Color(0xFFF6E4BE),

    tertiary = Color(0xFFE8C56A),
    onTertiary = Color(0xFF3B2F14),
    tertiaryContainer = Color(0xFF6B5210),
    onTertiaryContainer = Color(0xFFFFE08A),

    // 8.2：沉浸式深色 —— 画布背景 #121212，容器逐级提亮保持层次
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8E2D6),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE8E2D6),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB8B2A6),

    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),

    outline = Color(0xFF8A8478),
    outlineVariant = Color(0xFF3A3A3A),
    inverseSurface = Color(0xFFE8E2D6),
    inverseOnSurface = Color(0xFF2A2418),
    inversePrimary = Color(0xFFF2B12B),
    scrim = Color(0xFF000000),
)

/**
 * 应用主题。
 *
 * @param darkTheme 默认**强制深色**（规格 8.2：沉浸式 #121212）——
 *   修图类产品的画面判断依赖稳定环境色，跟随系统亮色会污染预览观感。
 */
@Composable
fun NaiwaCameraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
