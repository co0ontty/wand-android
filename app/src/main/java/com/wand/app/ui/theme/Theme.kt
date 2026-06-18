package com.wand.app.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计 Token 层（重设计规范 v1 第 1 节）：
 * - WandColors：亮/暗两套完整色板 + 语义色（屏幕代码统一从这里取色，禁止硬编码 Color(0x...)）
 * - WandMotion：统一动效时长 / 缓动 / 弹簧 / 呼吸动画规格
 * - WandShapes：统一圆角
 * 暖米色体系与 Web 端品牌对齐；旧字段（brand/textSecondary/textHint/border/danger/card/
 * running/permission）保留兼容，指向新 token。
 */

// —— 亮色 Token ——
private object LightTokens {
    val bgPrimary = Color(0xFFF8F6F2)
    val bgElevated = Color(0xFFFFFFFF)
    val surface = Color(0xFFFFFFFF)
    val surfaceSoft = Color(0xFFF0ECE6)
    val textPrimary = Color(0xFF201A16)
    val textSecondary = Color(0xFF655A50)
    val textMuted = Color(0xFF958A7F)
    val brand = Color(0xFFBF6A43)
    val brandSoft = Color(0xFFBF6A43).copy(alpha = 0.10f)
    val border = Color(0xFF1F1A16).copy(alpha = 0.10f)
    val borderStrong = Color(0xFF1F1A16).copy(alpha = 0.22f)
    val focusRing = Color(0xFFBF6A43).copy(alpha = 0.36f)

    // 语义色
    val success = Color(0xFF3F7A55)
    val successSoft = Color(0xFF3F7A55).copy(alpha = 0.10f)
    val warning = Color(0xFFAC7335)
    val warningSoft = Color(0xFFAC7335).copy(alpha = 0.12f)
    val danger = Color(0xFFB25247)
    val dangerSoft = Color(0xFFB25247).copy(alpha = 0.11f)
    val permission = Color(0xFFC28A20)
    val permissionSoft = Color(0xFFC28A20).copy(alpha = 0.11f)
    val info = Color(0xFF4E73A8)
    val infoSoft = Color(0xFF4E73A8).copy(alpha = 0.10f)
    val thinking = Color(0xFF7776A9)
    val thinkingSoft = Color(0xFF7776A9).copy(alpha = 0.08f)
}

// —— 暗色 Token ——
private object DarkTokens {
    val bgPrimary = Color(0xFF121416)
    val bgElevated = Color(0xFF1D2024)
    val surface = Color(0xFF22262B)
    val surfaceSoft = Color(0xFF2B3036)
    val textPrimary = Color(0xFFEDE7DE)
    val textSecondary = Color(0xFFC0B8AD)
    val textMuted = Color(0xFF867D73)
    val brand = Color(0xFFD47A52)
    val brandSoft = Color(0xFFD47A52).copy(alpha = 0.16f)
    val border = Color(0xFFEDE7DE).copy(alpha = 0.11f)
    val borderStrong = Color(0xFFEDE7DE).copy(alpha = 0.22f)
    val focusRing = Color(0xFFD47A52).copy(alpha = 0.42f)

    // 语义色
    val success = Color(0xFF78B184)
    val successSoft = Color(0xFF78B184).copy(alpha = 0.13f)
    val warning = Color(0xFFD39A56)
    val warningSoft = Color(0xFFD39A56).copy(alpha = 0.13f)
    val danger = Color(0xFFE07C72)
    val dangerSoft = Color(0xFFE07C72).copy(alpha = 0.13f)
    val permission = Color(0xFFE3AF4A)
    val permissionSoft = Color(0xFFE3AF4A).copy(alpha = 0.13f)
    val info = Color(0xFF84A7D5)
    val infoSoft = Color(0xFF84A7D5).copy(alpha = 0.13f)
    val thinking = Color(0xFFA4A1D2)
    val thinkingSoft = Color(0xFFA4A1D2).copy(alpha = 0.09f)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightTokens.brand,
    onPrimary = Color.White,
    primaryContainer = LightTokens.brandSoft,
    onPrimaryContainer = LightTokens.brand,
    secondary = LightTokens.textSecondary,
    onSecondary = Color.White,
    background = LightTokens.bgPrimary,
    onBackground = LightTokens.textPrimary,
    surface = LightTokens.surface,
    onSurface = LightTokens.textPrimary,
    surfaceVariant = LightTokens.surfaceSoft,
    onSurfaceVariant = LightTokens.textSecondary,
    outline = LightTokens.border,
    outlineVariant = LightTokens.border,
    error = LightTokens.danger,
    onError = Color.White,
    surfaceContainerHighest = LightTokens.bgElevated,
    surfaceContainerHigh = LightTokens.bgElevated,
    surfaceContainer = LightTokens.bgElevated,
    surfaceContainerLow = LightTokens.bgPrimary,
    surfaceContainerLowest = LightTokens.surface,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkTokens.brand,
    onPrimary = Color.White,
    primaryContainer = DarkTokens.brandSoft,
    onPrimaryContainer = DarkTokens.brand,
    secondary = DarkTokens.textSecondary,
    onSecondary = DarkTokens.bgPrimary,
    background = DarkTokens.bgPrimary,
    onBackground = DarkTokens.textPrimary,
    surface = DarkTokens.surface,
    onSurface = DarkTokens.textPrimary,
    surfaceVariant = DarkTokens.surfaceSoft,
    onSurfaceVariant = DarkTokens.textSecondary,
    outline = DarkTokens.border,
    outlineVariant = DarkTokens.border,
    error = DarkTokens.danger,
    onError = Color.White,
    surfaceContainerHighest = DarkTokens.bgElevated,
    surfaceContainerHigh = DarkTokens.bgElevated,
    surfaceContainer = DarkTokens.bgElevated,
    surfaceContainerLow = DarkTokens.bgPrimary,
    surfaceContainerLowest = DarkTokens.surface,
)

enum class WandAppearanceMode(val storageValue: String) {
    Light("light"),
    Dark("dark"),
    System("system");

    companion object {
        fun fromStorageValue(value: String?): WandAppearanceMode =
            entries.firstOrNull { it.storageValue == value } ?: System
    }
}

private val LocalWandDark = compositionLocalOf { false }

@Composable
@ReadOnlyComposable
private fun pick(light: Color, dark: Color): Color =
    if (LocalWandDark.current) dark else light

@Composable
@ReadOnlyComposable
fun isWandDarkTheme(): Boolean = LocalWandDark.current

/**
 * 屏幕代码里直接取用的完整色板（对称 iOS Theme.swift 的便捷访问）。
 * 所有字段按系统亮/暗模式自动切换。
 */
object WandColors {
    // —— 背景层级 ——
    /** 页面背景。 */
    val bgPrimary: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.bgPrimary, DarkTokens.bgPrimary)

    /** 浮层 / 弹窗背景。 */
    val bgElevated: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.bgElevated, DarkTokens.bgElevated)

    /** 卡片 / 输入框底。 */
    val surface: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.surface, DarkTokens.surface)

    /** 次级卡片底（最近路径、工具结果区等）。 */
    val surfaceSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.surfaceSoft, DarkTokens.surfaceSoft)

    // —— 文本 ——
    val textPrimary: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.textPrimary, DarkTokens.textPrimary)

    val textSecondary: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.textSecondary, DarkTokens.textSecondary)

    /** 弱文本 / 占位。 */
    val textMuted: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.textMuted, DarkTokens.textMuted)

    /** 兼容旧字段：占位文本，等同 textMuted。 */
    val textHint: Color
        @Composable @ReadOnlyComposable get() = textMuted

    // —— 品牌色 ——
    val brand: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.brand, DarkTokens.brand)

    /** 主色弱底（选中态背景）。 */
    val brandSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.brandSoft, DarkTokens.brandSoft)

    // —— 边框 / 聚焦 ——
    val border: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.border, DarkTokens.border)

    val borderStrong: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.borderStrong, DarkTokens.borderStrong)

    val focusRing: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.focusRing, DarkTokens.focusRing)

    // —— 语义色 ——
    /** 成功 / 运行中（绿）。 */
    val success: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.success, DarkTokens.success)

    val successSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.successSoft, DarkTokens.successSoft)

    /** 警告 / 已停止（橙）。 */
    val warning: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.warning, DarkTokens.warning)

    val warningSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.warningSoft, DarkTokens.warningSoft)

    /** 危险 / 已失败（红）。 */
    val danger: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.danger, DarkTokens.danger)

    val dangerSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.dangerSoft, DarkTokens.dangerSoft)

    /** 等待授权专用（金）。 */
    val permission: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.permission, DarkTokens.permission)

    val permissionSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.permissionSoft, DarkTokens.permissionSoft)

    /** 信息（蓝，Subagent 标签用）。 */
    val info: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.info, DarkTokens.info)

    val infoSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.infoSoft, DarkTokens.infoSoft)

    /** 思考块专用（紫灰）。 */
    val thinking: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.thinking, DarkTokens.thinking)

    val thinkingSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.thinkingSoft, DarkTokens.thinkingSoft)

    // —— 兼容旧字段 ——
    /** 兼容旧字段：次级卡片底，等同 surfaceSoft。 */
    val card: Color
        @Composable @ReadOnlyComposable get() = surfaceSoft

    /** 兼容旧字段：运行中（绿），等同 success。 */
    val running: Color
        @Composable @ReadOnlyComposable get() = success
}

/**
 * 统一动效规格（规范 1.5）。
 * 用法：tween(WandMotion.normal, easing = WandMotion.easing)，或直接用 tweenNormal() 等快捷函数。
 */
object WandMotion {
    /** 快（小元素淡入淡出 / 颜色切换）。 */
    const val fast = 150

    /** 标准（出现 / 消失 / 折叠展开）。 */
    const val normal = 250

    /** 慢（大面积布局过渡）。 */
    const val slow = 400

    /** 呼吸动画单程时长。 */
    const val breathDuration = 1200

    /** 呼吸动画 alpha 低点（1f ↔ 0.35f）。 */
    const val breathAlphaMin = 0.35f

    /** 呼吸动画 scale 高点（1f ↔ 1.25f）。 */
    const val breathScaleMax = 1.25f

    /** 标准缓动。 */
    val easing: Easing = FastOutSlowInEasing

    fun <T> tweenFast(): TweenSpec<T> = tween(fast, easing = easing)

    fun <T> tweenNormal(): TweenSpec<T> = tween(normal, easing = easing)

    fun <T> tweenSlow(): TweenSpec<T> = tween(slow, easing = easing)

    /** 弹性进入（dampingRatio 0.8 + MediumLow 刚度）。 */
    fun <T> springSpec(): SpringSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    /** 状态呼吸灯规格：tween(1200) 往返。配合 breathAlphaMin / breathScaleMax 使用。 */
    fun <T> breath(): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(tween(breathDuration), RepeatMode.Reverse)
}

/** 统一圆角（规范 1.2）。 */
object WandShapes {
    /** 6dp —— 小标签 / 徽章。 */
    val xs: Shape = RoundedCornerShape(6.dp)

    /** 10dp —— 输入框内嵌代码块。 */
    val sm: Shape = RoundedCornerShape(10.dp)

    /** 14dp —— 卡片 / 工具卡 / 权限卡。 */
    val md: Shape = RoundedCornerShape(14.dp)

    /** 20dp —— 输入栏 / 气泡 / 底部弹层。 */
    val lg: Shape = RoundedCornerShape(20.dp)

    /** 圆形胶囊。 */
    val full: Shape = RoundedCornerShape(50)

    // 自定义每角圆角时用的原始半径（如聊天气泡"尾巴"）。
    val radiusXs: Dp = 6.dp
    val radiusSm: Dp = 10.dp
    val radiusMd: Dp = 14.dp
    val radiusLg: Dp = 20.dp
}

@Composable
fun WandTheme(
    appearanceMode: WandAppearanceMode = WandAppearanceMode.System,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearanceMode) {
        WandAppearanceMode.Light -> false
        WandAppearanceMode.Dark -> true
        WandAppearanceMode.System -> systemDark
    }
    CompositionLocalProvider(LocalWandDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            content = content,
        )
    }
}
