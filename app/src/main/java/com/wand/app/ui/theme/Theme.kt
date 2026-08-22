package com.wand.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val bgPrimary = Color(0xFFF5F3EE)
    val bgElevated = Color(0xFFFCFAF6)
    val surface = Color(0xFFFFFDF9)
    val surfaceSoft = Color(0xFFECE8E1)
    val textPrimary = Color(0xFF28231F)
    val textSecondary = Color(0xFF5C544D)
    val textMuted = Color(0xFF7A7168)
    val brand = Color(0xFFC5653D)
    val brandSoft = Color(0xFFC5653D).copy(alpha = 0.14f)
    // 对齐 Web --border-default / --border-strong：旧 7% 描边在米色底上几乎看不见。
    val border = Color(0xFFD9D2C9)
    val borderStrong = Color(0xFF6D5848).copy(alpha = 0.28f)
    val focusRing = Color(0xFFC5653D).copy(alpha = 0.50f)

    // 语义色
    val success = Color(0xFF4F7A58)
    val successSoft = Color(0xFF4F7A58).copy(alpha = 0.14f)
    val warning = Color(0xFFA96A2F)
    val warningSoft = Color(0xFFA96A2F).copy(alpha = 0.14f)
    val danger = Color(0xFFB24F45)
    val dangerSoft = Color(0xFFB24F45).copy(alpha = 0.14f)
    val permission = Color(0xFFC28A20)
    val permissionSoft = Color(0xFFC28A20).copy(alpha = 0.12f)
    val info = Color(0xFF4A6FA5)
    val infoSoft = Color(0xFF4A6FA5).copy(alpha = 0.14f)
    val thinking = Color(0xFF6F6DA3)
    val thinkingSoft = Color(0xFF6F6DA3).copy(alpha = 0.10f)
}

// —— 暗色 Token ——
private object DarkTokens {
    val bgPrimary = Color(0xFF12100E)
    val bgElevated = Color(0xFF1C1916)
    val surface = Color(0xFF242017)
    val surfaceSoft = Color(0xFF2F2A24)
    val textPrimary = Color(0xFFF4EFE8)
    val textSecondary = Color(0xFFB4AAA0)
    val textMuted = Color(0xFF8C8278)
    val brand = Color(0xFFD47550)
    val brandSoft = Color(0xFFD47550).copy(alpha = 0.18f)
    val border = Color(0xFFEDE2D5).copy(alpha = 0.14f)
    val borderStrong = Color(0xFFEDE2D5).copy(alpha = 0.24f)
    val focusRing = Color(0xFFD47550).copy(alpha = 0.50f)

    // 语义色
    val success = Color(0xFF8BBA94)
    val successSoft = Color(0xFF8BBA94).copy(alpha = 0.14f)
    val warning = Color(0xFFD9A15C)
    val warningSoft = Color(0xFFD9A15C).copy(alpha = 0.14f)
    val danger = Color(0xFFE4887E)
    val dangerSoft = Color(0xFFE4887E).copy(alpha = 0.14f)
    val permission = Color(0xFFE6B75A)
    val permissionSoft = Color(0xFFE6B75A).copy(alpha = 0.14f)
    val info = Color(0xFF8FB0DC)
    val infoSoft = Color(0xFF8FB0DC).copy(alpha = 0.14f)
    val thinking = Color(0xFFA8A5D4)
    val thinkingSoft = Color(0xFFA8A5D4).copy(alpha = 0.12f)
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
    /** 按压 / 点按反馈。 */
    const val press = 110

    /** 快（小元素淡入淡出 / 颜色切换）。 */
    const val fast = 150

    /** 标准（出现 / 消失 / 折叠展开）。 */
    const val normal = 240

    /** 慢（大面积布局过渡）。 */
    const val slow = 360

    /** 呼吸动画单程时长。 */
    const val breathDuration = 1_600

    /** 呼吸动画 alpha 低点。过低会闪成空心点。 */
    const val breathAlphaMin = 0.55f

    /** 呼吸动画 scale 高点。过大看起来像在跳。 */
    const val breathScaleMax = 1.12f

    /** 标准缓动（兼容旧调用）。 */
    val easing: Easing = FastOutSlowInEasing

    /** 对齐 Web --ease-out-expo：进入和位置变化更干脆地落稳。 */
    val emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    val enterEasing: Easing = emphasized

    val exitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> tweenPress(): TweenSpec<T> = tween(press, easing = easing)

    fun <T> tweenFast(): TweenSpec<T> = tween(fast, easing = easing)

    fun <T> tweenNormal(): TweenSpec<T> = tween(normal, easing = emphasized)

    fun <T> tweenSlow(): TweenSpec<T> = tween(slow, easing = emphasized)

    fun <T> tweenEnter(): TweenSpec<T> = tween(normal, easing = enterEasing)

    fun <T> tweenExit(): TweenSpec<T> = tween(fast, easing = exitEasing)

    /** 弹性进入（轻过冲，适合面板出现）。 */
    fun <T> springSpec(): SpringSpec<T> =
        spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)

    /** 直接操作反馈：临界阻尼、无过冲，适合按压和非动量状态切换。 */
    fun <T> settleSpringSpec(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** 状态呼吸灯规格。配合 breathAlphaMin / breathScaleMax 使用。 */
    fun <T> breath(): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(tween(breathDuration, easing = FastOutSlowInEasing), RepeatMode.Reverse)
}

/** 统一圆角（规范 1.2）。 */
object WandShapes {
    /** 6dp —— 小标签 / 徽章。 */
    val xs: CornerBasedShape = RoundedCornerShape(6.dp)

    /** 10dp —— 输入框内嵌代码块。 */
    val sm: CornerBasedShape = RoundedCornerShape(10.dp)

    /** 14dp —— 卡片 / 工具卡 / 权限卡。 */
    val md: CornerBasedShape = RoundedCornerShape(14.dp)

    /** 20dp —— 输入栏 / 气泡 / 底部弹层。 */
    val lg: CornerBasedShape = RoundedCornerShape(20.dp)

    /** 圆形胶囊。 */
    val full: CornerBasedShape = RoundedCornerShape(999.dp)

    // 自定义每角圆角时用的原始半径（如聊天气泡"尾巴"）。
    val radiusXs: Dp = 6.dp
    val radiusSm: Dp = 10.dp
    val radiusMd: Dp = 14.dp
    val radiusLg: Dp = 20.dp
}

/**
 * Material 3 字体比例。页面通过 MaterialTheme.typography 取用，避免继续散落字号、行高和字重。
 * 只保留产品实际使用的视觉层级；未覆盖的槽位继承 Material 3 默认值。
 */
val WandTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

/** Material 3 官方组件读取的形状比例，与 WandShapes 保持一一对应。 */
val WandMaterialShapes = Shapes(
    WandShapes.xs,
    WandShapes.sm,
    WandShapes.md,
    WandShapes.lg,
    RoundedCornerShape(22.dp),
)

/** 非 Material 主题槽位统一从这里取值。 */
object WandSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object WandSizes {
    val minTouchTarget = 48.dp
    val toolbarIcon = 21.dp
    val controlHeight = 50.dp
    val divider = 0.5.dp
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
            typography = WandTypography,
            shapes = WandMaterialShapes,
            content = content,
        )
    }
}
