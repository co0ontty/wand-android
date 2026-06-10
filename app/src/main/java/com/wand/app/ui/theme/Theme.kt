package com.wand.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 原生界面主题：与现有 res/values/colors.xml（奶油/棕暖色系）逐色对齐，
 * 保证原生屏与 ConnectActivity / 对话框视觉一致。
 */

// —— 浅色（values/colors.xml）——
private val LightPrimary = Color(0xFFC5653D)
private val LightBackground = Color(0xFFF6F1E8)
private val LightSurface = Color(0xFFFFFFFF)
private val LightCard = Color(0xFFFDFAF5)
private val LightTextPrimary = Color(0xFF3D2E1E)
private val LightTextSecondary = Color(0xFF7A6A5A)
private val LightTextHint = Color(0xFFB0A090)
private val LightDivider = Color(0xFFE0D8CC)
private val LightError = Color(0xFFD32F2F)

// —— 深色（values-night/colors.xml）——
private val DarkPrimary = Color(0xFFD97A4F)
private val DarkBackground = Color(0xFF1A1410)
private val DarkSurface = Color(0xFF241C15)
private val DarkCard = Color(0xFF2A2018)
private val DarkTextPrimary = Color(0xFFF0E6D8)
private val DarkTextSecondary = Color(0xFFB5A593)
private val DarkTextHint = Color(0xFF7A6A5A)
private val DarkDivider = Color(0xFF3A2E22)
private val DarkError = Color(0xFFEF5350)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimary.copy(alpha = 0.12f),
    onPrimaryContainer = LightPrimary,
    secondary = LightTextSecondary,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightTextSecondary,
    outline = LightDivider,
    outlineVariant = LightDivider,
    error = LightError,
    onError = Color.White,
    surfaceContainerHighest = LightCard,
    surfaceContainerHigh = LightCard,
    surfaceContainer = LightCard,
    surfaceContainerLow = LightBackground,
    surfaceContainerLowest = LightSurface,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.White,
    primaryContainer = DarkPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = DarkPrimary,
    secondary = DarkTextSecondary,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
    error = DarkError,
    onError = Color.White,
    surfaceContainerHighest = DarkCard,
    surfaceContainerHigh = DarkCard,
    surfaceContainer = DarkCard,
    surfaceContainerLow = DarkBackground,
    surfaceContainerLowest = DarkSurface,
)

/** 屏幕代码里直接取用的品牌色组（对称 iOS Theme.swift 的便捷访问）。 */
object WandColors {
    val brand: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

    val textSecondary: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    val textHint: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) DarkTextHint else LightTextHint

    val border: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline

    val danger: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

    val card: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) DarkCard else LightCard

    /** 状态色：运行中（绿）/ 待授权（橙）。 */
    val running: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF66BB6A) else Color(0xFF43A047)

    val permission: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFF57C00)
}

@Composable
fun WandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
