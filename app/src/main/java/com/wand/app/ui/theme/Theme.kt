package com.wand.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
 * 手机灰白蓝体系以 APP 参考图为准；旧字段（brand/textSecondary/textHint/border/danger/
 * running/permission）保留兼容，指向新 token。
 */

// —— 亮色 Token ——
private object LightTokens {
    // 对齐 APP 参考图的灰白层级；蓝色只强调动作和选中项。
    val bgPrimary = Color(0xFFF2F3F7)
    val bgElevated = Color(0xFFFFFFFF)
    val surface = Color(0xFFFFFFFF)
    val surfaceSoft = Color(0xFFEDEEF2)
    val textPrimary = Color(0xFF1C1D21)
    val textSecondary = Color(0xFF60636B)
    // 弱文本与占位也保持正文级可读性，浅深主题由同一组对比度测试覆盖。
    val textMuted = Color(0xFF6A6D75)
    val brand = Color(0xFF0076D6)
    val brandSoft = Color(0xFF0076D6).copy(alpha = 0.08f)
    // 选中背景保持轻薄，让会话正文保持最高视觉优先级。
    val selectedFill = Color(0xFF0076D6).copy(alpha = 0.09f)
    // 列表与分组使用细浅分隔线，焦点和表单轮廓使用独立强边界。
    val border = Color(0xFFE3E5EB)
    val borderStrong = Color(0xFF707581).copy(alpha = 0.28f)
    val focusRing = Color(0xFF0076D6).copy(alpha = 0.50f)

    // 语义色
    val success = Color(0xFF4F7A58)
    val successSoft = Color(0xFF4F7A58).copy(alpha = 0.14f)
    val warning = Color(0xFFA96A2F)
    val warningSoft = Color(0xFFA96A2F).copy(alpha = 0.14f)
    val danger = Color(0xFFB24F45)
    val dangerSoft = Color(0xFFB24F45).copy(alpha = 0.14f)
    val permission = Color(0xFFA87317)
    val info = Color(0xFF4A6FA5)
    val infoSoft = Color(0xFF4A6FA5).copy(alpha = 0.14f)
    val thinking = Color(0xFF6F6DA3)
    val thinkingSoft = Color(0xFF6F6DA3).copy(alpha = 0.10f)

    // —— 实心容器上的前景色 ——
    // 实心品牌/语义容器从两套墨色派生至少 4.5:1 的前景，不能假设所有强调色都配白字。
    val onBrand = wandSolidInkFor(brand, surface, textPrimary)
    val onDanger = wandSolidInkFor(danger, surface, textPrimary)
    val onSuccess = wandSolidInkFor(success, surface, textPrimary)
    val onSecondary = wandSolidInkFor(textSecondary, surface, textPrimary)

    /**
     * 文字可能落到的全部底色：页面、浮层、卡片、次级卡片、品牌软底胶囊、选中行。
     * 派生墨色按其中最差的一种算，不按最容易的一种邀功。
     */
    val textSurfaces = listOf(
        bgPrimary,
        bgElevated,
        surface,
        surfaceSoft,
        wandComposite(brandSoft, surface),
        wandComposite(selectedFill, bgPrimary),
    )

    /**
     * 强调色用作小号文字（按钮正文、状态标签、链接式操作）时的专用墨色。
     * 原色作背景 / 圆点 / 描边仍然用上面那套 accent，只有文字这条通道压深：
     * 浅色主题里 #C5653D / #C28A20 直接当 11–14sp 小字写在米色底上只有 2.4–3.3:1。
     */
    val brandText = wandTextColorFor(brand, textSurfaces, dark = false)
    val successText = wandTextColorFor(success, textSurfaces, dark = false)
    val warningText = wandTextColorFor(warning, textSurfaces, dark = false)
    val dangerText = wandTextColorFor(danger, textSurfaces, dark = false)
    val permissionText = wandTextColorFor(permission, textSurfaces, dark = false)
    val infoText = wandTextColorFor(info, textSurfaces, dark = false)
    val thinkingText = wandTextColorFor(thinking, textSurfaces, dark = false)
}

// —— 暗色 Token ——
private object DarkTokens {
    val bgPrimary = Color(0xFF101114)
    val bgElevated = Color(0xFF17181C)
    val surface = Color(0xFF1C1D21)
    val surfaceSoft = Color(0xFF27282D)
    val textPrimary = Color(0xFFF4F5F7)
    val textSecondary = Color(0xFFC4C7CF)
    val textMuted = Color(0xFF989BA5)
    val brand = Color(0xFF63B4FF)
    val brandSoft = Color(0xFF63B4FF).copy(alpha = 0.18f)
    val selectedFill = Color(0xFF63B4FF).copy(alpha = 0.24f)
    val border = Color(0xFF34363D)
    val borderStrong = Color(0xFF50535C)
    val focusRing = Color(0xFF63B4FF).copy(alpha = 0.50f)

    // 语义色
    val success = Color(0xFF8BBA94)
    val successSoft = Color(0xFF8BBA94).copy(alpha = 0.14f)
    val warning = Color(0xFFD9A15C)
    val warningSoft = Color(0xFFD9A15C).copy(alpha = 0.14f)
    val danger = Color(0xFFE4887E)
    val dangerSoft = Color(0xFFE4887E).copy(alpha = 0.14f)
    val permission = Color(0xFFE6B75A)
    val info = Color(0xFF8FB0DC)
    val infoSoft = Color(0xFF8FB0DC).copy(alpha = 0.14f)
    val thinking = Color(0xFFA8A5D4)
    val thinkingSoft = Color(0xFFA8A5D4).copy(alpha = 0.12f)

    // —— 实心容器上的前景色 ——
    // 暗色主题的强调色本身是提亮版本（brand #D47550、danger #E4887E），白字压在上面只有
    // 2.2–3.3:1，必须反过来用深色字；这里按实测对比度逐色派生，而不是「暗色模式一律白字」。
    val onBrand = wandSolidInkFor(brand, textPrimary, bgPrimary)
    val onDanger = wandSolidInkFor(danger, textPrimary, bgPrimary)
    val onSuccess = wandSolidInkFor(success, textPrimary, bgPrimary)
    val onSecondary = wandSolidInkFor(textSecondary, textPrimary, bgPrimary)

    /** 与浅色主题同一组「文字可能落到的底」，按最差的那种派生。 */
    val textSurfaces = listOf(
        bgPrimary,
        bgElevated,
        surface,
        surfaceSoft,
        wandComposite(brandSoft, surface),
        wandComposite(selectedFill, bgPrimary),
    )

    /**
     * 小号文字专用的强调色。暗色里除了品牌橙叠在自己的软底胶囊上（3.95:1）之外，
     * 其余六个 accent 本来就在 4.5:1 之上，[wandTextColorFor] 会原样返回、不改动外观。
     */
    val brandText = wandTextColorFor(brand, textSurfaces, dark = true)
    val successText = wandTextColorFor(success, textSurfaces, dark = true)
    val warningText = wandTextColorFor(warning, textSurfaces, dark = true)
    val dangerText = wandTextColorFor(danger, textSurfaces, dark = true)
    val permissionText = wandTextColorFor(permission, textSurfaces, dark = true)
    val infoText = wandTextColorFor(info, textSurfaces, dark = true)
    val thinkingText = wandTextColorFor(thinking, textSurfaces, dark = true)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightTokens.brand,
    onPrimary = LightTokens.onBrand,
    primaryContainer = LightTokens.brandSoft,
    onPrimaryContainer = LightTokens.brandText,
    secondary = LightTokens.textSecondary,
    onSecondary = LightTokens.onSecondary,
    background = LightTokens.bgPrimary,
    onBackground = LightTokens.textPrimary,
    surface = LightTokens.surface,
    onSurface = LightTokens.textPrimary,
    surfaceVariant = LightTokens.surfaceSoft,
    onSurfaceVariant = LightTokens.textSecondary,
    outline = LightTokens.border,
    outlineVariant = LightTokens.border,
    error = LightTokens.danger,
    onError = LightTokens.onDanger,
    surfaceContainerHighest = LightTokens.bgElevated,
    surfaceContainerHigh = LightTokens.bgElevated,
    surfaceContainer = LightTokens.bgElevated,
    surfaceContainerLow = LightTokens.bgPrimary,
    surfaceContainerLowest = LightTokens.surface,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkTokens.brand,
    onPrimary = DarkTokens.onBrand,
    primaryContainer = DarkTokens.brandSoft,
    onPrimaryContainer = DarkTokens.brandText,
    secondary = DarkTokens.textSecondary,
    onSecondary = DarkTokens.onSecondary,
    background = DarkTokens.bgPrimary,
    onBackground = DarkTokens.textPrimary,
    surface = DarkTokens.surface,
    onSurface = DarkTokens.textPrimary,
    surfaceVariant = DarkTokens.surfaceSoft,
    onSurfaceVariant = DarkTokens.textSecondary,
    outline = DarkTokens.border,
    outlineVariant = DarkTokens.border,
    error = DarkTokens.danger,
    onError = DarkTokens.onDanger,
    surfaceContainerHighest = DarkTokens.bgElevated,
    surfaceContainerHigh = DarkTokens.bgElevated,
    surfaceContainer = DarkTokens.bgElevated,
    surfaceContainerLow = DarkTokens.bgPrimary,
    surfaceContainerLowest = DarkTokens.surface,
)

/** 两套 ColorScheme 的只读入口：主题内部与单测都从这里取，不再各留一份颜色表。 */
fun wandColorScheme(dark: Boolean): ColorScheme = if (dark) DarkScheme else LightScheme

/**
 * 语义强调色。同一个 accent 在界面上有两条通道：
 * 背景 / 状态点 / 图标 / 描边按图形 3:1 用 [wandAccentColor]，小号文字按正文 4.5:1 用
 * [wandAccentTextInk]。把两条通道分开，是因为把 accent 原值直接当 11–14sp 的字写在主题底上，
 * 浅色主题里会掉到 2.4–4.2:1（「等待授权」金色最糟），而压深 accent 又会改掉整套配色外观。
 */
enum class WandAccent {
    Brand,
    Success,
    Warning,
    Danger,
    Permission,
    Info,
    Thinking,
}

/** 原 accent：背景、圆点、图标着色、描边（图形 3:1 那档）。 */
fun wandAccentColor(accent: WandAccent, dark: Boolean): Color = when (accent) {
    WandAccent.Brand -> if (dark) DarkTokens.brand else LightTokens.brand
    WandAccent.Success -> if (dark) DarkTokens.success else LightTokens.success
    WandAccent.Warning -> if (dark) DarkTokens.warning else LightTokens.warning
    WandAccent.Danger -> if (dark) DarkTokens.danger else LightTokens.danger
    WandAccent.Permission -> if (dark) DarkTokens.permission else LightTokens.permission
    WandAccent.Info -> if (dark) DarkTokens.info else LightTokens.info
    WandAccent.Thinking -> if (dark) DarkTokens.thinking else LightTokens.thinking
}

/** 文字专用墨色：同色相、只朝本主题的墨极推进到正文 4.5:1。单测按它遍历七色 × 六种底。 */
fun wandAccentTextInk(accent: WandAccent, dark: Boolean): Color = when (accent) {
    WandAccent.Brand -> if (dark) DarkTokens.brandText else LightTokens.brandText
    WandAccent.Success -> if (dark) DarkTokens.successText else LightTokens.successText
    WandAccent.Warning -> if (dark) DarkTokens.warningText else LightTokens.warningText
    WandAccent.Danger -> if (dark) DarkTokens.dangerText else LightTokens.dangerText
    WandAccent.Permission -> if (dark) DarkTokens.permissionText else LightTokens.permissionText
    WandAccent.Info -> if (dark) DarkTokens.infoText else LightTokens.infoText
    WandAccent.Thinking -> if (dark) DarkTokens.thinkingText else LightTokens.thinkingText
}

/** 文字可能落到的全部底色（派生墨色与单测共用同一份，不各写一张表）。 */
fun wandTextSurfaces(dark: Boolean): List<Color> =
    if (dark) DarkTokens.textSurfaces else LightTokens.textSurfaces

/**
 * WandTheme 往 LocalContentColor 里给的默认前景色 = 本次生效 scheme 的 onBackground。
 * 抽成纯函数是因为裸 Text / Icon 的实际颜色就由这一个值决定，单测可以直接钉住它，
 * 不必等设备上跑组合。
 */
fun wandDefaultContentColor(dark: Boolean): Color = wandColorScheme(dark).onBackground

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

    /** 主色弱底（图标芯片 / 徽章，不是选中态主信号）。 */
    val brandSoft: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.brandSoft, DarkTokens.brandSoft)

    /** 选中态填充。必须配合品牌描边或左侧条，单独使用仍然偏弱。 */
    val selectedFill: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.selectedFill, DarkTokens.selectedFill)

    // —— 实心容器上的前景色（按实际底色对比度派生，不是「暗色=白字」）——
    /** 品牌实心按钮上的文字 / 图标色。 */
    val onBrand: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.onBrand, DarkTokens.onBrand)

    /** 危险实心按钮上的文字 / 图标色。 */
    val onDanger: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.onDanger, DarkTokens.onDanger)

    /** 成功实心按钮上的文字 / 图标色。 */
    val onSuccess: Color
        @Composable @ReadOnlyComposable get() = pick(LightTokens.onSuccess, DarkTokens.onSuccess)

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

    // —— 强调色用作小号文字时的专用墨色 ——
    // 上面那组 accent 是给背景、圆点、描边、图标用的（图形门槛 3:1）；把它们直接当 11–14sp
    // 的文字写在米色/深色底上会掉到 2.4–4.2:1。文字一律用下面这组同色相派生墨色（正文 4.5:1）。
    /** 品牌橙用作按钮正文 / 链接式操作文字。 */
    val brandText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Brand, LocalWandDark.current)

    val successText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Success, LocalWandDark.current)

    val warningText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Warning, LocalWandDark.current)

    val dangerText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Danger, LocalWandDark.current)

    /** 「等待授权」这类小号状态标签文字（对应 [permission] 圆点的文字版）。 */
    val permissionText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Permission, LocalWandDark.current)

    val infoText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Info, LocalWandDark.current)

    val thinkingText: Color
        @Composable @ReadOnlyComposable get() = wandAccentTextInk(WandAccent.Thinking, LocalWandDark.current)

    // —— 兼容旧字段 ——
    /** 兼容旧字段：运行中（绿），等同 success。 */
    val running: Color
        @Composable @ReadOnlyComposable get() = success
}

/**
 * 终端 / diff / 代码输出的固定暖深色表面。
 * 不跟随亮暗主题，PTY 黑窗、工具终端卡、审查 diff 共用这一套。
 */
object WandTerminal {
    val background = Color(0xFF17120F)
    val text = Color(0xFFD9D9D4)
    val muted = Color(0xFFC9D1D9)
    val error = Color(0xFFF28C82)
    val added = Color(0xFF91D39D)
    val removed = Color(0xFFF29B94)
    val backgroundArgb: Int = 0xFF17120F.toInt()
}

val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * 减少动效判定（非 Compose 入口用，例如 Java Activity 决定是否播开屏）。
 * 与 [rememberReduceMotion] 同一份规则：动画时长倍率为 0 或窗口/过渡动画被关掉。
 */
fun reduceMotionEnabled(context: Context): Boolean {
    return try {
        val resolver = context.contentResolver
        val animator = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        val transition = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        )
        animator == 0f || transition == 0f
    } catch (_: Exception) {
        false
    }
}

@Composable
@ReadOnlyComposable
fun reduceMotionEnabled(): Boolean = LocalReduceMotion.current

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { reduceMotionEnabled(context) }
}

fun WandAppearanceMode.toNightMode(): Int = when (this) {
    WandAppearanceMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
    WandAppearanceMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
    WandAppearanceMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}

object WandAppearance {
    fun apply(mode: WandAppearanceMode) {
        val nightMode = mode.toNightMode()
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }
}

/**
 * 外观设置 + 系统当前深浅 → 本次要用的暗色标记。
 * 强制 Light / Dark 只看自己的设置，系统开关不能把它翻过去；只有 System 才跟随 [systemDark]。
 * 抽成纯函数是为了让三种模式 × 系统两种状态能在 JVM 单测里逐个断言。
 */
fun wandResolveDark(appearanceMode: WandAppearanceMode, systemDark: Boolean): Boolean =
    when (appearanceMode) {
        WandAppearanceMode.Light -> false
        WandAppearanceMode.Dark -> true
        WandAppearanceMode.System -> systemDark
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

    /** Launcher opening: a complete source → workspace → phone signal journey. */
    const val openingJourneyDuration = 960
    const val openingSettle = 120

    /** 呼吸动画单程时长。 */
    const val breathDuration = 1_600

    /** 呼吸动画 alpha 低点。过低会闪成空心点。 */
    const val breathAlphaMin = 0.55f

    /** 呼吸动画 scale 高点。过大看起来像在跳。 */
    const val breathScaleMax = 1.12f

    /** 分镜时间基：开屏把 0..1 切成多个时间窗（信号 → 工作区 → 手机），
     *  时间基保持线性，每个窗口自己的动感由各自的缓动负责。
     *  用强调/标准缓动当时间基会把整段故事挤进前 1/5（实测插画 170ms 内就长完）。 */
    val storyboard: Easing = LinearEasing

    /** 标准缓动（兼容旧调用）。 */
    val easing: Easing = FastOutSlowInEasing

    /** 对齐 Web --ease-out-expo：进入和位置变化更干脆地落稳。 */
    val emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    val enterEasing: Easing = emphasized

    val exitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> tweenPress(): TweenSpec<T> = tween(press, easing = easing)

    fun <T> tweenFast(): TweenSpec<T> = tween(fast, easing = easing)

    fun <T> tweenNormal(): TweenSpec<T> = tween(normal, easing = emphasized)

    fun <T> tweenEnter(): TweenSpec<T> = tween(normal, easing = enterEasing)

    fun <T> openingJourney(): TweenSpec<T> =
        // 分镜是 0..1 上的多个时间窗（信号 72..413ms、工作区 192..576ms、手机 557..922ms），
        // 时间基必须线性；每段自己的落稳交给窗口内的缓动（见 openingMarkTravel）。
        tween(openingJourneyDuration, easing = storyboard)

    fun <T> tweenExit(): TweenSpec<T> = tween(fast, easing = exitEasing)

    /** 直接操作反馈：临界阻尼、无过冲，适合按压和非动量状态切换。 */
    fun <T> settleSpringSpec(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /**
     * 图标变形（＋ → ✕、发送 → 停止）。
     * 比标准时长略长：形状要对得上，太快会看成闪一下；太慢会看出是两个图标在交叉。
     */
    fun <T> morph(): TweenSpec<T> = tween(morphDuration, easing = emphasized)

    const val morphDuration = 200

    /**
     * 标签指示条。前缘用 0 延迟、后缘用 [indicatorTrailDelayMillis]，
     * 两段用同一条曲线，中间态自然被拉长再收回（对齐 iOS 分段控件）。
     */
    fun <T> indicator(delayMillis: Int = 0): TweenSpec<T> =
        tween(indicatorDuration, delayMillis = delayMillis, easing = emphasized)

    const val indicatorDuration = 260
    const val indicatorTrailDelayMillis = 70

    /** 旧内容退场比新内容进场快（90ms 量级），换位时不会出现两层内容叠着看。 */
    const val quickExit = 90

    /** 状态呼吸灯规格。配合 breathAlphaMin / breathScaleMax 使用。 */
    fun <T> breath(): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(tween(breathDuration, easing = FastOutSlowInEasing), RepeatMode.Reverse)

    /**
     * 给 animateXxxAsState 用的有限规格：系统关闭动画时退化为瞬时。
     * animateXxxAsState 没有「不动画」的开关，颜色 / 尺寸这类过渡统一从这里取规格，
     * 保证「移除动画」的设备上只剩瞬时切换，和其他 AnimatedVisibility 的处理一致。
     */
    fun <T> respectMotion(enabled: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (enabled) spec else snap()
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
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
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
        fontSize = 12.sp,
        lineHeight = 16.sp,
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
    val controlHeight = 48.dp
}

@Composable
fun WandTheme(
    appearanceMode: WandAppearanceMode = WandAppearanceMode.System,
    content: @Composable () -> Unit,
) {
    val dark = wandResolveDark(appearanceMode, isSystemInDarkTheme())
    val reduceMotion = rememberReduceMotion()
    // 先算出本次真正生效的 scheme，再在 MaterialTheme 之内取色：
    // 在套入新主题之前从外层旧 MaterialTheme 读默认色会拿到上一套配色。
    val scheme = wandColorScheme(dark)
    CompositionLocalProvider(
        LocalWandDark provides dark,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = WandTypography,
            shapes = WandMaterialShapes,
        ) {
            // MaterialTheme 只提供 colors / typography / shapes。裸 Text / Icon 的默认前景来自
            // LocalContentColor，它的默认值是 Color.Black；页面根容器又普遍是 Modifier.background()
            // 的 Box / Column，不像 Surface 那样自带 contentColor。缺这一行时，暗色主题下未显式
            // 着色的标题、会话名、页签和空态主标题会留在纯黑（实测对比度 1.11–1.56:1）。
            // Material 容器（Surface / Button / ListItem / TopAppBar / Menu）在自己的子树里
            // 继续提供 onSurface / onPrimary / onError，不被这里的默认值覆盖。
            CompositionLocalProvider(
                LocalContentColor provides wandDefaultContentColor(dark),
                content = content,
            )
        }
    }
}
