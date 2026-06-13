package com.wand.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow as BackdropShadow

/**
 * 液态玻璃引擎抽象（视觉改造 v2）。
 *
 * 这是全 app **唯一**允许 import `com.kyant.backdrop.*` 的文件：
 * - API 33+（RuntimeShader）：真·液态玻璃 —— drawBackdrop 采样背后内容，
 *   vibrancy 提饱和 + blur 模糊 + lens 边缘折射 + 智能高光 + 软阴影。
 * - API 31-32：同管线但 lens 自动 no-op（库内部按 SDK 闸门），只有模糊。
 * - API < 31：完全不走 backdrop（捕获层零开销），降级为
 *   高 alpha 半透明底 + 对角 rim 渐变描边 + 软阴影 —— 配合 AmbientBackground
 *   依然读得出"玻璃"质感。
 *
 * 调用方永远不碰 Build.VERSION，也不碰 kyant 类型。
 * 换引擎（Haze / backdrop 升级）只改本文件。
 */

/** 真模糊管线（RenderEffect）可用性，API 31+。lens 折射在库内部另由 API 33 闸门控制。 */
private val glassBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

// —— 玻璃样式 Token ——

@Immutable
data class GlassStyle(
    /** 玻璃表面着色（模糊之上的色罩）。 */
    val tint: Color,
    /** 真玻璃路径下的色罩 alpha（低，让背后内容透出来）。 */
    val tintAlpha: Float,
    /** 降级路径下的底色 alpha（高，保证文字对比度）。 */
    val fallbackAlpha: Float,
    /** 背景模糊半径。 */
    val blurRadius: Dp,
    /** lens 折射边缘高度（0 = 不折射）。 */
    val refractionHeight: Dp,
    /** lens 折射强度。 */
    val refractionAmount: Dp,
    /** rim 渐变描边亮侧（左上，受光）。 */
    val rimLight: Color,
    /** rim 渐变描边暗侧（右下，背光）。 */
    val rimShade: Color,
    /** 软阴影规格（0 = 无阴影）。 */
    val shadowElevation: Dp,
    /** 阴影颜色。 */
    val shadowColor: Color = Color.Black.copy(alpha = 0.22f),
)

/** 把语义强调色（权限金 / 危险红 / 思考紫…）混入玻璃底色与 rim，生成同族变体。 */
fun GlassStyle.tinted(accent: Color, strength: Float = 0.16f): GlassStyle = copy(
    tint = lerp(tint, accent.copy(alpha = tint.alpha), strength),
    rimLight = lerp(rimLight, accent, 0.40f),
    rimShade = lerp(rimShade, accent, 0.40f),
)

// —— 亮色玻璃 ——
private val LightGlassRegular = GlassStyle(
    tint = Color(0xFFFFFAF2), tintAlpha = 0.55f, fallbackAlpha = 0.95f,
    blurRadius = 18.dp, refractionHeight = 10.dp, refractionAmount = 18.dp,
    rimLight = Color.White.copy(alpha = 0.60f),
    rimShade = Color(0xFF7D5B39).copy(alpha = 0.14f),
    shadowElevation = 5.dp, shadowColor = Color(0xFF5A3A22).copy(alpha = 0.20f),
)
private val LightGlassClear = GlassStyle(
    tint = Color.White, tintAlpha = 0.32f, fallbackAlpha = 0.88f,
    blurRadius = 12.dp, refractionHeight = 8.dp, refractionAmount = 14.dp,
    rimLight = Color.White.copy(alpha = 0.65f),
    rimShade = Color(0xFF7D5B39).copy(alpha = 0.12f),
    shadowElevation = 3.dp, shadowColor = Color(0xFF5A3A22).copy(alpha = 0.16f),
)
private val LightGlassAccent = GlassStyle(
    tint = Color(0xFFC5653D), tintAlpha = 0.78f, fallbackAlpha = 1f,
    blurRadius = 10.dp, refractionHeight = 8.dp, refractionAmount = 16.dp,
    rimLight = Color.White.copy(alpha = 0.50f),
    rimShade = Color(0xFF7D3A1C).copy(alpha = 0.45f),
    shadowElevation = 6.dp, shadowColor = Color(0xFFC5653D).copy(alpha = 0.38f),
)
private val LightGlassCard = GlassStyle(
    tint = Color.White, tintAlpha = 0.45f, fallbackAlpha = 0.62f,
    blurRadius = 0.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.72f),
    rimShade = Color(0xFF7D5B39).copy(alpha = 0.16f),
    shadowElevation = 1.5.dp, shadowColor = Color(0xFF5A3A22).copy(alpha = 0.12f),
)

// —— 暗色玻璃 ——
private val DarkGlassRegular = GlassStyle(
    tint = Color(0xFF241C15), tintAlpha = 0.45f, fallbackAlpha = 0.93f,
    blurRadius = 18.dp, refractionHeight = 10.dp, refractionAmount = 18.dp,
    rimLight = Color.White.copy(alpha = 0.16f),
    rimShade = Color.Black.copy(alpha = 0.45f),
    shadowElevation = 6.dp, shadowColor = Color.Black.copy(alpha = 0.50f),
)
private val DarkGlassClear = GlassStyle(
    tint = Color(0xFF2A2018), tintAlpha = 0.30f, fallbackAlpha = 0.85f,
    blurRadius = 12.dp, refractionHeight = 8.dp, refractionAmount = 14.dp,
    rimLight = Color.White.copy(alpha = 0.14f),
    rimShade = Color.Black.copy(alpha = 0.40f),
    shadowElevation = 4.dp, shadowColor = Color.Black.copy(alpha = 0.40f),
)
private val DarkGlassAccent = GlassStyle(
    tint = Color(0xFFD97A4F), tintAlpha = 0.70f, fallbackAlpha = 1f,
    blurRadius = 10.dp, refractionHeight = 8.dp, refractionAmount = 16.dp,
    rimLight = Color.White.copy(alpha = 0.30f),
    rimShade = Color.Black.copy(alpha = 0.35f),
    shadowElevation = 6.dp, shadowColor = Color.Black.copy(alpha = 0.45f),
)
private val DarkGlassCard = GlassStyle(
    tint = Color(0xFF2A2018), tintAlpha = 0.40f, fallbackAlpha = 0.60f,
    blurRadius = 0.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.10f),
    rimShade = Color.Black.copy(alpha = 0.35f),
    shadowElevation = 2.dp, shadowColor = Color.Black.copy(alpha = 0.30f),
)

/** 四档玻璃样式，按系统亮/暗自动切换（用法对齐 WandColors）。 */
object WandGlass {
    /** 大面板：顶栏 / 输入栏 / 弹层 / 权限卡。 */
    val regular: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme()) DarkGlassRegular else LightGlassRegular

    /** 小控件：圆形按钮 / FAB / 徽章底。 */
    val clear: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme()) DarkGlassClear else LightGlassClear

    /** 品牌强调：发送按钮 / 主操作。降级时是实色品牌底（与旧视觉一致）。 */
    val accent: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme()) DarkGlassAccent else LightGlassAccent

    /** 列表/工具卡片：永远不走 backdrop（卡片不叠在滚动内容上），半透明 + rim。 */
    val card: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme()) DarkGlassCard else LightGlassCard
}

// —— 捕获层（backdrop 源） ——

/**
 * 玻璃背景捕获句柄。每个页面创建一个，由内容区 [glassBackdropSource] 填充，
 * 浮在内容之上的玻璃 chrome（顶栏 / 输入栏 / FAB…）通过 [glassSurface] 采样。
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: LayerBackdrop?)

private val NoopGlassBackdrop = GlassBackdrop(null)

@Composable
fun rememberGlassBackdrop(): GlassBackdrop =
    if (glassBlurSupported) {
        // SDK 版本是编译期之后不变的常量，分支在同一设备上恒定，条件调用 composable 安全。
        val layer = rememberLayerBackdrop()
        remember(layer) { GlassBackdrop(layer) }
    } else {
        NoopGlassBackdrop
    }

/** 标记玻璃 chrome 背后的内容区。API < 31 时是 no-op（零捕获开销）。 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier {
    val layer = backdrop.layer ?: return this
    return this.layerBackdrop(layer)
}

// —— 玻璃表面 ——

/**
 * 玻璃表面统一入口。
 *
 * @param backdrop 采样源；传 null 表示该表面不叠在滚动内容上（卡片 / 独立 window
 *   的弹层），永远走降级半透明路径。
 * @param shape 必须是圆角矩形族（lens 折射只支持 CornerBasedShape；
 *   矩形栏请用 RoundedCornerShape(0.dp) 而不是 RectangleShape）。
 */
fun Modifier.glassSurface(
    backdrop: GlassBackdrop?,
    shape: Shape,
    style: GlassStyle,
): Modifier {
    val layer = backdrop?.layer
    return if (layer != null && glassBlurSupported) {
        this.drawBackdrop(
            backdrop = layer,
            shape = { shape },
            effects = {
                vibrancy()
                if (style.blurRadius > 0.dp) blur(style.blurRadius.toPx())
                if (style.refractionHeight > 0.dp && shape is CornerBasedShape) {
                    lens(style.refractionHeight.toPx(), style.refractionAmount.toPx())
                }
            },
            highlight = { Highlight.Default },
            shadow = if (style.shadowElevation > 0.dp) {
                { BackdropShadow(radius = style.shadowElevation * 2.5f, color = style.shadowColor) }
            } else null,
            onDrawSurface = { drawRect(style.tint.copy(alpha = style.tintAlpha)) },
        )
    } else {
        this
            .then(
                if (style.shadowElevation > 0.dp) {
                    Modifier.shadow(
                        elevation = style.shadowElevation,
                        shape = shape,
                        ambientColor = style.shadowColor,
                        spotColor = style.shadowColor,
                    )
                } else Modifier
            )
            .clip(shape)
            .background(style.tint.copy(alpha = style.fallbackAlpha))
            .border(1.dp, Brush.linearGradient(listOf(style.rimLight, style.rimShade)), shape)
    }
}

/** 玻璃面板便捷容器（FAB / toast / 浮层）。 */
@Composable
fun GlassPanel(
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = WandShapes.lg,
    style: GlassStyle = WandGlass.regular,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glassSurface(backdrop, shape, style),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 卡片玻璃速记：半透明底 + 对角 rim 渐变描边 + 极轻阴影。
 * 卡片平铺在页面背景上、不与滚动内容重叠，所以永远不走 backdrop 采样。
 *
 * @param tint 覆盖底色（语义弱底卡：permissionSoft / thinkingSoft…）。
 * @param rimTint 语义强调色混入 rim（状态卡边缘提色）。
 */
@Composable
fun Modifier.glassCard(
    shape: Shape = WandShapes.md,
    tint: Color? = null,
    rimTint: Color? = null,
): Modifier {
    var style = WandGlass.card
    if (rimTint != null) style = style.tinted(rimTint)
    val bg = tint ?: style.tint.copy(alpha = style.fallbackAlpha)
    return this
        .shadow(
            elevation = style.shadowElevation,
            shape = shape,
            ambientColor = style.shadowColor,
            spotColor = style.shadowColor,
        )
        .clip(shape)
        .background(bg)
        .border(1.dp, Brush.linearGradient(listOf(style.rimLight, style.rimShade)), shape)
}

// —— 环境渐变背景 ——

/**
 * 页面环境背景：品牌色调的柔和径向光斑（静态绘制，零每帧开销）。
 * 玻璃 chrome 需要背后有「东西」才能体现模糊与折射；
 * API < 31 时它也让半透明降级面板读出层次。
 * 放在 [glassBackdropSource] 的 Box 内部最底层，四个页面共用。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val base = WandColors.bgPrimary
    val glowA = if (dark) Color(0xFFD97A4F).copy(alpha = 0.13f) else Color(0xFFC5653D).copy(alpha = 0.10f)
    val glowB = if (dark) Color(0xFF8C4A2F).copy(alpha = 0.09f) else Color(0xFFD18B00).copy(alpha = 0.07f)
    val glowC = if (dark) Color(0xFF9D9DCC).copy(alpha = 0.05f) else Color(0xFF4F7A58).copy(alpha = 0.04f)
    Box(
        modifier.drawBehind {
            drawRect(base)
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            drawRect(
                Brush.radialGradient(
                    listOf(glowA, Color.Transparent),
                    center = Offset(w * 0.12f, h * 0.08f),
                    radius = w * 0.95f,
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(glowB, Color.Transparent),
                    center = Offset(w * 0.95f, h * 0.90f),
                    radius = w * 0.85f,
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(glowC, Color.Transparent),
                    center = Offset(w * 0.02f, h * 0.55f),
                    radius = w * 0.55f,
                )
            )
        }
    )
}
