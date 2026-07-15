package com.wand.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
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

// —— 立体感基元：统一控制卡片/面板的轻阴影、微光与描边。——

/** elevation ≤ 0 时直接跳过。非 @Composable，颜色由调用方按主题传入。 */
fun Modifier.layeredShadow(
    shape: Shape,
    elevation: Dp,
    keyColor: Color,
    ambientColor: Color,
): Modifier {
    if (elevation <= 0.dp) return this
    val shadowColor = lerp(keyColor, ambientColor, 0.62f)
    return this.shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
}

/** 卡片层叠投影的暖色调（亮：暖棕；暗：黑），返回 (接触硬影色, 环境柔影色)。 */
@Composable
@ReadOnlyComposable
fun cardShadowColors(): Pair<Color, Color> =
    if (isWandDarkTheme()) {
        Color.Black.copy(alpha = 0.28f) to Color.Black.copy(alpha = 0.16f)
    } else {
        Color(0xFF593A20).copy(alpha = 0.07f) to Color(0xFF593A20).copy(alpha = 0.035f)
    }

/**
 * 表面微光：竖向渐变叠在底色之上 —— 顶端一抹高光、底端一抹阴影，
 * 让平涂的卡面读出"受光的微曲面"。叠在 background(底色) 之后、border 之前。
 */
@Composable
@ReadOnlyComposable
fun surfaceSheenBrush(highlightScale: Float = 1f): Brush {
    val dark = isWandDarkTheme()
    // 高光只保留上沿的一点受光感；半透明着色卡按实心度衰减，避免白印。
    val topWhite = (if (dark) 0.010f else 0.016f) * highlightScale.coerceIn(0f, 1f)
    return Brush.verticalGradient(
        0f to Color.White.copy(alpha = topWhite),
        0.24f to Color.Transparent,
        1f to (if (dark) Color.Black.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.006f)),
    )
}

/**
 * 倒角描边：竖向渐变（顶高光 → 底背光），比四向均匀描边更有斜面立体感。
 * rimLight/rimShade 取自 GlassStyle。
 */
fun bevelRimBrush(rimLight: Color, rimShade: Color): Brush =
    Brush.verticalGradient(listOf(rimLight, rimShade))

// —— 亮色玻璃 ——
private val LightGlassRegular = GlassStyle(
    tint = Color(0xFFFFFBF5), tintAlpha = 0.72f, fallbackAlpha = 0.97f,
    blurRadius = 10.dp, refractionHeight = 1.dp, refractionAmount = 3.dp,
    rimLight = Color.White.copy(alpha = 0.20f),
    rimShade = Color(0xFF7D5B39).copy(alpha = 0.055f),
    shadowElevation = 0.6.dp, shadowColor = Color(0xFF593A20).copy(alpha = 0.045f),
)
private val LightGlassClear = GlassStyle(
    tint = Color(0xFFFFFCF7), tintAlpha = 0.62f, fallbackAlpha = 0.93f,
    blurRadius = 7.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.18f),
    rimShade = Color(0xFF7D5B39).copy(alpha = 0.045f),
    shadowElevation = 0.35.dp, shadowColor = Color(0xFF593A20).copy(alpha = 0.04f),
)
private val LightGlassAccent = GlassStyle(
    tint = Color(0xFFC5653D), tintAlpha = 0.92f, fallbackAlpha = 1f,
    blurRadius = 7.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.18f),
    rimShade = Color.Black.copy(alpha = 0.12f),
    shadowElevation = 1.2.dp, shadowColor = Color(0xFFC5653D).copy(alpha = 0.12f),
)
private val LightGlassCard = GlassStyle(
    tint = Color(0xFFFFFCF7), tintAlpha = 0.80f, fallbackAlpha = 0.94f,
    blurRadius = 0.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.18f),
    rimShade = Color(0xFF7D5B39).copy(alpha = 0.055f),
    shadowElevation = 0.6.dp, shadowColor = Color(0xFF593A20).copy(alpha = 0.04f),
)

// —— 暗色玻璃 ——
private val DarkGlassRegular = GlassStyle(
    tint = Color(0xFF241E19), tintAlpha = 0.66f, fallbackAlpha = 0.95f,
    blurRadius = 10.dp, refractionHeight = 1.dp, refractionAmount = 3.dp,
    rimLight = Color.White.copy(alpha = 0.065f),
    rimShade = Color.Black.copy(alpha = 0.16f),
    shadowElevation = 0.6.dp, shadowColor = Color.Black.copy(alpha = 0.20f),
)
private val DarkGlassClear = GlassStyle(
    tint = Color(0xFF2B241D), tintAlpha = 0.52f, fallbackAlpha = 0.90f,
    blurRadius = 7.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.055f),
    rimShade = Color.Black.copy(alpha = 0.14f),
    shadowElevation = 0.35.dp, shadowColor = Color.Black.copy(alpha = 0.16f),
)
private val DarkGlassAccent = GlassStyle(
    tint = Color(0xFFD47550), tintAlpha = 0.84f, fallbackAlpha = 1f,
    blurRadius = 7.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.13f),
    rimShade = Color.Black.copy(alpha = 0.16f),
    shadowElevation = 1.2.dp, shadowColor = Color.Black.copy(alpha = 0.22f),
)
private val DarkGlassCard = GlassStyle(
    tint = Color(0xFF211B17), tintAlpha = 0.58f, fallbackAlpha = 0.90f,
    blurRadius = 0.dp, refractionHeight = 0.dp, refractionAmount = 0.dp,
    rimLight = Color.White.copy(alpha = 0.052f),
    rimShade = Color.Black.copy(alpha = 0.16f),
    shadowElevation = 0.6.dp, shadowColor = Color.Black.copy(alpha = 0.18f),
)

/** 四档玻璃样式，按系统亮/暗自动切换（用法对齐 WandColors）。 */
object WandGlass {
    /** 大面板：顶栏 / 输入栏 / 弹层 / 权限卡。 */
    val regular: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isWandDarkTheme()) DarkGlassRegular else LightGlassRegular

    /** 小控件：圆形按钮 / FAB / 徽章底。 */
    val clear: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isWandDarkTheme()) DarkGlassClear else LightGlassClear

    /** 品牌强调：发送按钮 / 主操作。降级时是实色品牌底（与旧视觉一致）。 */
    val accent: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isWandDarkTheme()) DarkGlassAccent else LightGlassAccent

    /** 列表/工具卡片：永远不走 backdrop（卡片不叠在滚动内容上），半透明 + rim。 */
    val card: GlassStyle
        @Composable @ReadOnlyComposable get() =
            if (isWandDarkTheme()) DarkGlassCard else LightGlassCard
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
 * @param edgeToEdge 全幅贴边栏（顶栏 / 底栏，shape 为 RoundedCornerShape(0.dp)）置 true：
 *   去掉四周的玻璃高光 / rim 描边 —— 这道亮边在贴着屏幕边沿的栏上会描出一条难看的白边。
 *   栏与内容的分隔交给软阴影或发丝分隔线，不靠 rim。
 * @param drawRim 是否绘制玻璃引擎自带的动态高光 / 降级渐变 rim。组件若已有独立的
 *   均匀描边，可关闭它来避免两层边缘叠成不规则光晕。
 */
fun Modifier.glassSurface(
    backdrop: GlassBackdrop?,
    shape: Shape,
    style: GlassStyle,
    edgeToEdge: Boolean = false,
    drawRim: Boolean = !edgeToEdge,
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
            // 贴边栏或已有独立描边的组件不再叠加动态高光。
            highlight = if (drawRim) ({ Highlight.Default }) else null,
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
            // 降级路径同理：避免渐变 rim 与组件自身描边叠加。
            .then(
                if (!drawRim) Modifier
                else Modifier.border(
                    1.dp,
                    bevelRimBrush(style.rimLight, style.rimShade),
                    shape,
                )
            )
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
    // 半透明语义色直接作为容器底时，部分 Android GPU 会让子 compositing layer 的白色
    // 缓冲区透出来，形成卡片内部的矩形白块。先与页面底合成稳定的不透明语义底。
    val bg = tint?.compositeOver(WandColors.bgPrimary)
        ?: style.tint.copy(alpha = style.fallbackAlpha)
    val (keyShadow, ambientShadow) = cardShadowColors()
    // 近实心卡保留一点受光感；半透明语义卡改用语义色描边，避免白印。
    val solidity = if (tint != null) 0f else ((bg.alpha - 0.2f) / 0.6f).coerceIn(0f, 1f)
    val sheen = surfaceSheenBrush(highlightScale = solidity)
    val rimLight = if (rimTint != null)
        lerp(rimTint.copy(alpha = 0.55f), style.rimLight, solidity)
    else
        style.rimLight.copy(alpha = style.rimLight.alpha * solidity)
    return this
        .layeredShadow(shape, style.shadowElevation, keyShadow, ambientShadow)
        .clip(shape)
        .background(bg)
        .background(sheen, shape)
        .border(0.55.dp, bevelRimBrush(rimLight, style.rimShade), shape)
}

// —— 环境渐变背景 ——

/**
 * 页面环境背景：品牌色调的柔和层次洗色（静态绘制，零每帧开销）。
 * 玻璃 chrome 需要背后有「东西」才能体现模糊与折射；
 * API < 31 时它也让半透明降级面板读出层次。
 * 放在 [glassBackdropSource] 的 Box 内部最底层，四个页面共用。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    Box(modifier.ambientBackground())
}

/** [AmbientBackground] 的 Modifier 形态：次级页面直接挂在 Scaffold modifier 上。 */
@Composable
fun Modifier.ambientBackground(): Modifier {
    val dark = isWandDarkTheme()
    val base = WandColors.bgPrimary
    val topWash = if (dark) Color(0xFFD47550).copy(alpha = 0.022f) else Color(0xFFC5653D).copy(alpha = 0.018f)
    val bottomWash = if (dark) Color.Black.copy(alpha = 0.10f) else Color(0xFF7D5B39).copy(alpha = 0.018f)
    return this.then(
        Modifier.drawBehind {
            drawRect(base)
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            drawRect(
                Brush.verticalGradient(
                    0f to topWash,
                    0.42f to Color.Transparent,
                    1f to bottomWash,
                )
            )
        }
    )
}

/** 次级页面顶栏的降级玻璃（不叠在滚动内容上，无 backdrop 采样）。 */
val secondaryBarGlass: GlassStyle
    @Composable @ReadOnlyComposable get() =
        (if (isWandDarkTheme()) DarkGlassRegular else LightGlassRegular)
            .copy(refractionHeight = 0.dp, shadowElevation = 0.dp)
