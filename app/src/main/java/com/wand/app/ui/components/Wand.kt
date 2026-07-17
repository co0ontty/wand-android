package com.wand.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.lerp as lerpDp
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.bevelRimBrush
import com.wand.app.ui.theme.cardShadowColors
import com.wand.app.ui.theme.layeredShadow
import com.wand.app.ui.theme.surfaceSheenBrush

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
        animationSpec = tween(durationMillis = if (pressed) 90 else 150),
        label = "pressAlpha",
    )
    return graphicsLayer { alpha = pressAlpha }
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = onClick,
        )
}

/** 状态 → 语义色。 */
@Composable
private fun statusColor(status: String): Color = when (status.trim().lowercase()) {
    "running", "thinking" -> WandColors.success
    "waiting-input", "waiting_input", "permission" -> WandColors.permission
    "failed" -> WandColors.danger
    "stopped", "reconnecting" -> WandColors.warning
    else -> WandColors.textMuted // idle / exited / archived / 未知
}

/** 该状态是否需要呼吸动画。 */
private fun statusBreathing(status: String): Boolean = when (status.trim().lowercase()) {
    "running", "thinking", "waiting-input", "waiting_input", "permission",
    "reconnecting" -> true
    else -> false
}

/** 状态 → 中文标签，未知值原样返回。 */
private fun statusLabel(status: String): String = when (status.trim().lowercase()) {
    "running" -> "运行中"
    "thinking" -> "思考中"
    "waiting-input", "waiting_input" -> "等待输入"
    "permission" -> "等待授权"
    "reconnecting" -> "重连中"
    "idle" -> "空闲"
    "stopped" -> "已停止"
    "failed" -> "已失败"
    "exited" -> "已退出"
    "archived" -> "已归档"
    else -> status.ifBlank { "未知" }
}

/**
 * 8dp 状态圆点。
 * running/thinking → 绿 + 呼吸；waiting-input/permission → 金 + 呼吸；
 * failed → 红静止；stopped → 橙静止；idle/exited/archived/未知 → 灰静止。
 * 需要别的尺寸时在 modifier 里先传 size（外层约束优先）。
 */
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    if (statusBreathing(status)) {
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
        Box(
            modifier
                .size(8.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(color, CircleShape),
        )
    } else {
        Box(
            modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
    }
}

/**
 * 状态徽章：圆点 + 中文标签的弱底胶囊。
 * 语义色 12% alpha 底 + 语义色文字，FULL 圆角，水平 8dp / 垂直 3dp 内边距，11sp。
 */
@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier = modifier
            .clip(WandShapes.full)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusDot(status, modifier = Modifier.size(6.dp))
        Text(
            statusLabel(status),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** 全屏居中加载占位。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String = "加载中…") {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = WandColors.brand,
                strokeWidth = 3.dp,
            )
            Text(text, fontSize = 13.sp, color = WandColors.textMuted)
        }
    }
}

/** 全屏居中错误占位，可带重试按钮。 */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                WandIcons.error,
                contentDescription = null,
                tint = WandColors.danger,
                modifier = Modifier.size(40.dp),
            )
            Text(
                message,
                fontSize = 14.sp,
                color = WandColors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text("重试", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = WandColors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                    Text(actionText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 紧凑分段切换条；flat 模式用于卡片内，去掉“外壳套胶囊”的双层感。 */
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
    val containerShape = if (flat) WandShapes.sm else WandShapes.full
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(containerShape)
            .background(WandColors.surfaceSoft.copy(alpha = if (flat) 0.34f else 0.48f))
            .border(
                0.55.dp,
                WandColors.borderStrong.copy(alpha = if (flat) 0.18f else 0.26f),
                containerShape,
            )
            .padding(if (flat) 0.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(if (flat) 0.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val textColor by animateColorAsState(
                if (active) resolvedActiveTextColor else WandColors.textSecondary,
                WandMotion.tweenFast(),
                label = "choiceStripText",
            )
            val itemBackground by animateColorAsState(
                if (active) {
                    if (flat) WandColors.brandSoft else WandColors.surface.copy(alpha = 0.96f)
                } else {
                    Color.Transparent
                },
                WandMotion.tweenFast(),
                label = "choiceStripBg",
            )
            val itemBorder by animateColorAsState(
                if (active) WandColors.borderStrong.copy(alpha = 0.24f) else Color.Transparent,
                WandMotion.tweenFast(),
                label = "choiceStripBorder",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (flat) minHeight else minHeight - 6.dp)
                    .then(
                        if (flat) {
                            Modifier.background(itemBackground)
                        } else {
                            Modifier
                                .clip(WandShapes.full)
                                .background(itemBackground)
                                .border(1.dp, itemBorder, WandShapes.full)
                        },
                    )
                    .selectable(
                        selected = active,
                        role = if (flat) Role.RadioButton else Role.Tab,
                    ) { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
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

/** 区块标题：13sp SemiBold textSecondary，letterSpacing 0.5sp，上 16dp 下 8dp。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = WandColors.textMuted,
        letterSpacing = 0.sp,
        modifier = modifier.padding(top = 18.dp, bottom = 7.dp),
    )
}

/**
 * 统一卡片容器（玻璃质感版）：半透明底 + 对角 rim 渐变描边 + 极轻软阴影。
 * - onClick 非空时整卡可点（带 ripple）。
 * - selected = true 时过渡到品牌弱底 + 品牌 rim（mode-card 选中态），带过渡动画。
 * - containerColor 可覆盖底色（语义弱底卡片走旧的纯色平面路径，保证语义色不被玻璃冲淡）。
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

    if (containerColor != null) {
        // 语义色覆盖：保留纯色底配方（semantic soft 底依赖确定底色，不叠表面微光以免冲淡语义色），
        // 但补一层轻投影，让语义弱底卡也微微浮起、不再贴平。
        val targetBorder = if (selected) WandColors.brand else WandColors.border
        val bg by animateColorAsState(containerColor, WandMotion.tweenFast(), label = "cardBg")
        val borderColor by animateColorAsState(targetBorder, WandMotion.tweenFast(), label = "cardBorder")
        // WandColors.border 自带主题 alpha；未选中态必须保留该 alpha，不能用 copy 覆盖成 54%。
        // 选中态的 brand 是不透明色，再单独降到清晰但克制的 72%。
        val renderedBorderColor = if (selected) {
            borderColor.copy(alpha = 0.72f)
        } else {
            borderColor
        }
        Column(
            modifier = modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .layeredShadow(shape, 1.dp * (1f - 0.5f * pressDepth), keyShadow, ambientShadow)
                .clip(shape)
                .background(bg)
                .border(if (selected) 0.8.dp else 0.55.dp, renderedBorderColor, shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
                    } else Modifier
                )
                .padding(contentPadding),
            content = content,
        )
        return
    }
    val style = WandGlass.card
    val brand = WandColors.brand
    val t by animateFloatAsState(if (selected) 1f else 0f, WandMotion.tweenFast(), label = "cardSel")
    val elevation = lerpDp(style.shadowElevation, style.shadowElevation * 1.2f, t) * (1f - 0.5f * pressDepth)
    val bg = lerp(style.tint.copy(alpha = style.fallbackAlpha), brand.copy(alpha = 0.10f), t)
    val rimLight = lerp(style.rimLight, brand.copy(alpha = 0.42f), t)
    val rimShade = lerp(style.rimShade, brand.copy(alpha = 0.28f), t)
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .layeredShadow(shape, elevation, keyShadow, ambientShadow)
            .clip(shape)
            .background(bg)
            .background(surfaceSheenBrush(highlightScale = 0.32f), shape)
            .border(lerpDp(0.45.dp, 0.8.dp, t), bevelRimBrush(rimLight, rimShade), shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
                } else Modifier
            )
            .padding(contentPadding),
        content = content,
    )
}

/** 顶栏 / 悬浮 chrome 里的统一圆形图标按钮。 */
@Composable
fun WandChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WandColors.textSecondary,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .clip(CircleShape)
            .background(WandColors.surface.copy(alpha = 0.62f))
            .border(0.55.dp, WandColors.border.copy(alpha = 0.54f), CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

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
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else WandColors.textMuted.copy(alpha = 0.48f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Wand 品牌标记：品牌渐变圆角方块 + 白色星芒图标（对称 iOS Theme.WandBrandMark）。 */
@Composable
fun WandBrandMark(size: Int = 64) {
    val corner = RoundedCornerShape((size * 0.26f).dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .shadow(
                elevation = (size * 0.10f).dp,
                shape = corner,
                ambientColor = WandColors.brand.copy(alpha = 0.18f),
                spotColor = WandColors.brand.copy(alpha = 0.18f),
            )
            .clip(corner)
            .background(
                Brush.linearGradient(
                    listOf(lerp(WandColors.brand, Color.White, 0.06f), lerp(WandColors.brand, Color.Black, 0.06f))
                )
            ),
    ) {
        Icon(
            WandIcons.sparkle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size * 0.46f).dp),
        )
    }
}
