package com.wand.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 对比度计算（WCAG 2.1 相对亮度）：主题层唯一实现。
 * 亮/暗两套 token 的容器前景色、以及控件在实心容器上自动选字，都从这里派生，
 * 不在页面里写魔数，也不假设「暗色模式 = 白字」。
 * 门槛按角色分（[WandContrastRole]）：文字算正文档，圆点/图标/描边才算图形那档。
 * 纯函数，不读 CompositionLocal，可直接在 JVM 单测里断言。
 */

/** sRGB 单通道线性化。 */
private fun linearChannel(value: Float): Double {
    val v = value.toDouble()
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/**
 * 半透明前景实际叠到不透明底色上得到的颜色（alpha 归 1）。
 * 截图采样量到的是合成后的颜色，所以色对必须先用这一步对齐，再算对比度。
 */
fun wandComposite(foreground: Color, background: Color): Color {
    if (foreground.alpha >= 1f) return foreground
    val a = foreground.alpha
    return Color(
        red = foreground.red * a + background.red * (1f - a),
        green = foreground.green * a + background.green * (1f - a),
        blue = foreground.blue * a + background.blue * (1f - a),
        alpha = 1f,
    )
}

/** 相对亮度；半透明色按叠到纯黑后的结果计算，调用方应传合成好的实际底色。 */
fun wandRelativeLuminance(color: Color): Double =
    0.2126 * linearChannel(color.red) + 0.7152 * linearChannel(color.green) + 0.0722 * linearChannel(color.blue)

/**
 * 前景实际落到底色上之后两者的对比度（1.0 – 21.0）。
 * [background] 是容器实际显示的不透明颜色；容器色本身带 alpha 时先叠到页面底色再传进来。
 */
fun wandContrast(foreground: Color, background: Color): Double {
    val actual = wandRelativeLuminance(wandComposite(foreground, background))
    val base = wandRelativeLuminance(background)
    val high = maxOf(actual, base)
    val low = minOf(actual, base)
    return (high + 0.05) / (low + 0.05)
}

/**
 * 对比度门槛按「这个颜色在界面上承担什么」判定，不按样式名、也不按屏幕像素密度换算：
 * - [Body]：正文与小号标签字，4.5:1。Material 的 `labelLarge` 实际是 14sp 中字重，仍算 Body。
 * - [LargeText]：显式字号已达 WCAG 大字号（约 18pt / 24sp，或 14pt / 18.7sp 且粗体）的 3:1。
 * - [Graphic]：状态点、图标、描边等有意义图形（WCAG 1.4.11）的 3:1。
 * - [Disabled]：禁用控件在 WCAG 1.4.3 里豁免，这里只保留「读得出字」的下限，
 *   并且另有一条顺序约束：禁用字不得比可用字更醒目（见 [wandDisabledInk]）。
 */
enum class WandContrastRole {
    Body,
    LargeText,
    Graphic,
    Disabled,
}

const val WAND_BODY_CONTRAST = 4.5
const val WAND_LARGE_OR_GRAPHIC_CONTRAST = 3.0

/** 某个角色要求达到的最低对比度。 */
fun wandContrastMinimum(role: WandContrastRole): Double = when (role) {
    WandContrastRole.Body -> WAND_BODY_CONTRAST
    WandContrastRole.LargeText, WandContrastRole.Graphic -> WAND_LARGE_OR_GRAPHIC_CONTRAST
    WandContrastRole.Disabled -> WAND_LARGE_OR_GRAPHIC_CONTRAST
}

/**
 * 这段文字算不算 WCAG 的「大号文字」。
 * 只看 Compose 里显式声明的 sp 字号：sp 与 CSS px 对齐，18pt≈24sp、14pt 粗体≈18.7sp。
 * 设备像素密度（14sp 在 3× 屏上是 42 物理像素）与 Material 样式名都不是依据。
 */
fun wandIsLargeText(fontSizeSp: Float, fontWeight: Int = 400): Boolean =
    fontSizeSp >= 24f || (fontSizeSp >= 18.7f && fontWeight >= 700)

/** 按字号/字重决定这段文字该按 4.5 还是 3.0 验。 */
fun wandTextRole(fontSizeSp: Float, fontWeight: Int = 400): WandContrastRole =
    if (wandIsLargeText(fontSizeSp, fontWeight)) WandContrastRole.LargeText else WandContrastRole.Body

/** 是否达标；默认按正文档，只有真的是图形或已判过大字号才传别的角色。 */
fun wandMeetsContrast(
    foreground: Color,
    background: Color,
    role: WandContrastRole = WandContrastRole.Body,
): Boolean = wandContrast(foreground, background) >= wandContrastMinimum(role)

private fun wandBlend(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)

/** 推进步数：1/48 ≈ 每步 5 个 8bit 级，够细又不会在 token 初始化时空转。 */
private const val WAND_INK_STEPS = 48

/**
 * 把 [ink] 沿 [toward] 方向推进，直到在 [containers] 的**每一个**底色上都达到 [minimum]。
 * 沿单一极（纯黑 / 纯白）推进只改明度，色相与饱和关系基本保持，因此派生值仍是同一个颜色家族。
 * 没有任何一步能达标时返回该方向能到的最远值，由调用方（和单测）如实记录它没达标。
 */
fun wandInkMeetingContrast(
    ink: Color,
    containers: List<Color>,
    toward: Color,
    minimum: Double,
): Color {
    if (containers.all { wandContrast(ink, it) >= minimum }) return ink
    for (step in 1..WAND_INK_STEPS) {
        val candidate = wandBlend(ink, toward, step.toFloat() / WAND_INK_STEPS)
        if (containers.all { wandContrast(candidate, it) >= minimum }) return candidate
    }
    return wandBlend(ink, toward, 1f)
}

/**
 * 强调色**用作文字**时的前景：在原色不达正文档门槛时，朝本主题的墨极推进
 * （浅色主题压深、暗色主题提亮）。背景 / 圆点 / 描边继续用原 accent，不跟着变暗。
 * [containers] 传文字可能落到的全部底色，按最难的那一种派生，而不是挑好看的。
 */
fun wandTextColorFor(accent: Color, containers: List<Color>, dark: Boolean): Color =
    wandInkMeetingContrast(
        ink = accent,
        containers = containers,
        toward = if (dark) Color.White else Color.Black,
        minimum = WAND_BODY_CONTRAST,
    )

/**
 * 实心容器上的正文前景：两种墨各朝自己的极推进（浅色墨 → 纯白、深墨 → 纯黑），
 * 取能真正达到 4.5:1 的那一支。只比初始对比度会选错：浅色主题的品牌橙上
 * 白字 3.96 / 深字 3.93 都不够，深墨还能继续压，纯白已经没有去处。
 */
fun wandSolidInkFor(container: Color, lightInk: Color, darkInk: Color): Color {
    val towardWhite = wandInkMeetingContrast(lightInk, listOf(container), Color.White, WAND_BODY_CONTRAST)
    val towardBlack = wandInkMeetingContrast(darkInk, listOf(container), Color.Black, WAND_BODY_CONTRAST)
    return if (wandContrast(towardWhite, container) >= wandContrast(towardBlack, container)) {
        towardWhite
    } else {
        towardBlack
    }
}

/**
 * 禁用态前景：从 [mutedInk]（次级墨）出发，不追最高对比。
 * 只有当它在 [disabledContainer] 上仍比可用态更醒目时，才朝底色的方向淡出到不高于
 * [enabledContrast]；淡出不能越过「读得出字」的下限，否则宁可保留 [mutedInk]。
 */
fun wandDisabledInk(mutedInk: Color, disabledContainer: Color, enabledContrast: Double): Color {
    if (wandContrast(mutedInk, disabledContainer) <= enabledContrast) return mutedInk
    var lastReadable = mutedInk
    for (step in 1..WAND_INK_STEPS) {
        val faded = wandBlend(mutedInk, disabledContainer, step.toFloat() / WAND_INK_STEPS)
        val ratio = wandContrast(faded, disabledContainer)
        if (ratio < WAND_LARGE_OR_GRAPHIC_CONTRAST) return lastReadable
        lastReadable = faded
        if (ratio <= enabledContrast) return faded
    }
    return lastReadable
}