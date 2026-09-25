package com.wand.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.lerp as lerpDp
import com.wand.app.R
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.cardShadowColors
import com.wand.app.ui.theme.layeredShadow
import com.wand.app.ui.theme.glassCard
import com.wand.app.ui.theme.wandSelectedSurface

/**
 * 公共基础组件（重设计规范 v1 第 2.1 节）：
 * StatusDot / StatusBadge / LoadingState / ErrorState / EmptyState / SectionHeader / WandCard。
 * 状态字符串取值对齐服务端 SessionSnapshot.status（running/idle/exited/failed/stopped）
 * 与客户端派生态（thinking/permission/waiting-input/reconnecting/archived），未知值走灰色兜底。
 */

/** 用于卡片内部折叠标题行，避免默认 ripple 在圆角卡片里扩散成白色矩形。 */
@Composable
fun Modifier.clickableWithoutRipple(
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.72f else 1f,
        animationSpec = if (pressed) WandMotion.tweenPress() else WandMotion.tweenFast(),
        label = "pressAlpha",
    )
    // 静止态不要强制 graphicsLayer：圆角半透明行一旦离屏合成，部分 GPU 会透出第二块白矩形。
    return this
        .then(
            if (pressAlpha < 1f) Modifier.graphicsLayer { alpha = pressAlpha } else Modifier,
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
        )
}

/** 状态色调 → 品牌语义色。 */
@Composable
private fun WandStatusTone.statusColor(): Color = when (this) {
    WandStatusTone.Success -> WandColors.success
    WandStatusTone.Permission -> WandColors.permission
    WandStatusTone.Danger -> WandColors.danger
    WandStatusTone.Warning -> WandColors.warning
    WandStatusTone.Neutral -> WandColors.textMuted
}

/** 呼吸图层（running / thinking / 等待输入）；不需要呼吸的色调不挂 graphicsLayer。 */
@Composable
private fun Modifier.statusBreath(): Modifier {
    if (reduceMotionEnabled()) return this
    val transition = rememberInfiniteTransition(label = "statusDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = WandMotion.breathAlphaMin,
        animationSpec = WandMotion.breath(),
        label = "dotAlpha",
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = WandMotion.breathScaleMax,
        animationSpec = WandMotion.breath(),
        label = "dotScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/**
 * 8dp 状态圆点。
 * running/thinking → 绿 + 呼吸；waiting-input/permission → 金 + 呼吸；
 * failed → 红静止；stopped → 橙静止；idle/exited/archived/未知 → 灰静止。
 * 需要别的尺寸时在 modifier 里先传 size（外层约束优先）。
 */
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val presentation = wandStatusPresentation(status)
    Box(
        Modifier
            .size(8.dp)
            .then(modifier)
            .then(if (presentation.breathing) Modifier.statusBreath() else Modifier)
            .background(presentation.tone.statusColor(), CircleShape),
    )
}

/** 全屏居中占位骨架：LoadingState / ErrorState / EmptyState 共用。 */
@Composable
private fun CenteredPlaceholder(
    modifier: Modifier,
    spacing: Dp,
    horizontalPadding: Dp = 32.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** 全屏居中加载占位。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String = "加载中…") {
    CenteredPlaceholder(modifier = modifier, spacing = 12.dp, horizontalPadding = 0.dp) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = WandColors.brand,
            strokeWidth = 3.dp,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
    }
}

/** 全屏居中错误占位，可带重试按钮。 */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    CenteredPlaceholder(modifier = modifier, spacing = 12.dp) {
        Icon(
            WandIcons.error,
            contentDescription = null,
            tint = WandColors.danger,
            modifier = Modifier.size(40.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = WandColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            WandButton(label = "重试", onClick = onRetry)
        }
    }
}

/** 全屏居中空态占位：大图标 + 标题 + 副标题 + 可选主操作按钮。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CenteredPlaceholder(modifier = modifier, spacing = 8.dp) {
        Icon(
            icon,
            contentDescription = null,
            tint = WandColors.textMuted.copy(alpha = 0.7f),
            modifier = Modifier
                .size(48.dp)
                .padding(bottom = 4.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = WandColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            WandButton(
                label = actionText,
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 紧凑分段切换条；flat 模式用于卡片内，去掉“外壳套胶囊”的双层感。
 *
 * 动效（`docs/motion-design.md` 规则 5）：选中态由 [WandSegmentedTrack] 的滑动指示条承担，
 * 切换时指示条先被拉向新位置再收回，而不是每一段自己换底色后硬切。
 */
@Composable
fun <T> WandChoiceStrip(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 42.dp,
    labelFontSize: TextUnit = 13.sp,
    activeTextColor: Color? = null,
    flat: Boolean = false,
) {
    val resolvedActiveTextColor = activeTextColor ?: WandColors.brand
    val shape = if (flat) WandShapes.sm else WandShapes.full
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    WandSegmentedTrack(
        itemCount = options.size,
        selectedIndex = selectedIndex,
        modifier = modifier,
        containerShape = shape,
        indicatorShape = shape,
        containerColor = WandColors.surfaceSoft.copy(alpha = if (flat) 0.34f else 0.48f),
        indicatorColor = WandColors.selectedFill,
        indicatorBorder = WandColors.brand.copy(alpha = 0.55f),
        padding = if (flat) 0.dp else 3.dp,
        minHeight = minHeight,
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val textColor by animateColorAsState(
                if (active) resolvedActiveTextColor else WandColors.textSecondary,
                WandMotion.tweenFast(),
                label = "choiceStripText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (flat) minHeight else minHeight - 6.dp)
                    .clip(shape)
                    .selectable(
                        selected = active,
                        role = if (flat) Role.RadioButton else Role.Tab,
                    ) { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = labelFontSize,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 统一卡片容器：平面色底 + 极轻软阴影。
 * - onClick 非空时整卡可点（带 ripple）。
 * - selected = true 时用品牌软底 + 品牌描边，避免只靠弱底分不清选中。
 * - containerColor 可覆盖底色（语义弱底卡片走纯色平面路径）。
 * - 内容是 ColumnScope，内边距用 contentPadding 控制（规范建议 12-14dp）。
 */
@Composable
fun WandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = WandShapes.md,
    containerColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // 按压反馈：轻微下沉（缩放 + 阴影回落），给卡片一个"被按下去"的实体触感。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressDepth by animateFloatAsState(
        if (pressed && onClick != null) 1f else 0f,
        WandMotion.tweenFast(),
        label = "cardPress",
    )
    val scale = 1f - 0.012f * pressDepth
    val (keyShadow, ambientShadow) = cardShadowColors()
    val cardClick = onClick
    val pressModifier = if (cardClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = ripple(), onClick = cardClick)
    } else {
        Modifier
    }

    if (containerColor != null) {
        // 语义色覆盖：保留纯色底配方（semantic soft 底依赖确定底色，不叠表面微光以免冲淡语义色），
        // 但补一层轻投影，让语义弱底卡也微微浮起、不再贴平。
        val bg by animateColorAsState(containerColor, WandMotion.tweenFast(), label = "cardBg")
        Column(
            modifier = modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .layeredShadow(shape, 1.dp * (1f - 0.5f * pressDepth), keyShadow, ambientShadow)
                .clip(shape)
                .background(bg)
                .then(if (onClick != null) pressModifier else Modifier)
                .padding(contentPadding),
            content = content,
        )
        return
    }
    val style = WandGlass.card
    val brand = WandColors.brand
    val t by animateFloatAsState(if (selected) 1f else 0f, WandMotion.tweenFast(), label = "cardSel")
    val elevation = lerpDp(style.shadowElevation, style.shadowElevation * 1.2f, t) * (1f - 0.5f * pressDepth)
    val bg = lerp(style.tint.copy(alpha = style.fallbackAlpha), WandColors.selectedFill, t)
    val stroke = lerp(Color.Transparent, brand, t)
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .layeredShadow(shape, elevation, keyShadow, ambientShadow)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(1.5.dp, stroke, shape) else Modifier)
            .then(if (onClick != null) pressModifier else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 非 Column 布局使用的标准卡片表面。页面不再直接依赖玻璃引擎，未来更换实现只改此处。
 */
@Composable
fun Modifier.wandCardSurface(
    shape: Shape = WandShapes.md,
    tint: Color? = null,
    rimTint: Color? = null,
): Modifier = glassCard(shape = shape, tint = tint, rimTint = rimTint)

/**
 * 顶栏 / 工具栏里的统一图标按钮（对齐 iOS toolbar button）。
 * 合并 ChatScreen.QuietTopIconButton 与 SessionListScreen.SessionToolbarIconButton：
 * 前者 48×48 / icon 22dp，后者 48×48 / icon 21dp —— 统一为可配参数。
 */
@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WandColors.textSecondary,
    enabled: Boolean = true,
    iconSize: Dp = 21.dp,
) {
    WandIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        variant = WandIconButtonVariant.Toolbar,
        tint = tint,
        enabled = enabled,
        iconSize = iconSize,
    )
}

/** 与桌面启动图标共用原始图层，保持像素猫的配色与安全区比例。 */
@Composable
fun WandBrandMark(size: Int = 64) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 24f / 108f).dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
