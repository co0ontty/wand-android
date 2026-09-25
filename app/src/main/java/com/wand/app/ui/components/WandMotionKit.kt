package com.wand.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled

/**
 * 动效工具层。规范见 `docs/motion-design.md`，这里是规范的唯一实现入口。
 *
 * 每条规则对应一个组件，页面不得再自己拼动画：
 * - 图标变形 → [WandMorphingIcon] / [WandMorphIconButton]
 * - 标签指示条 → [WandSlideIndicator]
 * - 搜索框就地展开 → [WandInlineSearchField]
 * - 加号就地展开面板 → [WandInlinePanel]
 * - 状态就地留在原地 → [WandInPlaceSwap]
 *
 * 所有组件都必须：1) 在 reduceMotion 下退化为瞬时；2) 不改变触控目标位置与尺寸。
 */

// MARK: - 图标变形

/**
 * 两个图标之间的「连贯变形」：交叉淡入 + 反向旋转 + 轻微缩放。
 *
 * Compose 没有路径级 morph，硬做形状插值需要 AnimatedVectorDrawable 且要求两个
 * 图标的 path 数量与命令序列一致，实际不可维护。这里用「快速交叉 + 反向旋转」：
 * 中间态几乎没有两个图标同时全亮的重影，观感上就是同一个图标在转过去。
 */
@Composable
fun WandMorphingIcon(
    progress: Float,
    from: ImageVector,
    to: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    rotationDegrees: Float = 90f,
    contentDescription: String? = null,
) {
    val p = progress.coerceIn(0f, 1f)
    // 快速交叉：0→0.35 之间就把旧图标收干净，避免长时间半透明重影。
    val fromAlpha = (1f - p * 2.2f).coerceIn(0f, 1f)
    val toAlpha = ((p - 0.45f) * 2.2f).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(iconSize)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            from,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = fromAlpha
                    rotationZ = rotationDegrees * p
                    scaleX = 1f - 0.22f * p
                    scaleY = 1f - 0.22f * p
                },
        )
        Icon(
            to,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = toAlpha
                    rotationZ = -rotationDegrees * (1f - p)
                    val s = 0.78f + 0.22f * p
                    scaleX = s
                    scaleY = s
                },
        )
    }
}

/**
 * 会变形图标的图标按钮。`expanded` 为真时图标变形成 [expandedIcon]（如 ＋ → ✕）。
 * 触控盒尺寸固定，变形过程中不移动、不变大，避免周边元素跟着抖。
 */
@Composable
fun WandMorphIconButton(
    expanded: Boolean,
    collapsedIcon: ImageVector,
    expandedIcon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WandColors.textSecondary,
    expandedTint: Color = WandColors.brand,
    iconSize: Dp = 20.dp,
    touchSize: Dp = 44.dp,
    enabled: Boolean = true,
    rotationDegrees: Float = 90f,
) {
    val motionEnabled = !reduceMotionEnabled()
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.morph()),
        label = "morphIconProgress",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "morphIconPress",
    )
    val color by animateColorAsState(
        targetValue = if (expanded) expandedTint else tint,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "morphIconTint",
    )
    Box(
        modifier = modifier
            .size(touchSize)
            .clip(WandShapes.sm)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        contentAlignment = Alignment.Center,
    ) {
        WandMorphingIcon(
            progress = progress,
            from = collapsedIcon,
            to = expandedIcon,
            tint = if (enabled) color else WandColors.textMuted.copy(alpha = 0.48f),
            iconSize = iconSize,
            rotationDegrees = rotationDegrees,
        )
    }
}

// MARK: - 状态就地切换

/**
 * 同一位置的状态切换容器：尺寸由调用方固定（如 32dp 圆），内容按 [contentKey] 交叉淡入淡出，
 * 不产生任何布局位移。用于「加载 → 完成 → 结果」这类提交反馈。
 */
@Composable
fun WandInPlaceSwap(
    contentKey: Any?,
    modifier: Modifier = Modifier,
    durationMillis: Int = WandMotion.fast,
    content: @Composable (Any?) -> Unit,
) {
    val motionEnabled = !reduceMotionEnabled()
    AnimatedContent(
        targetState = contentKey,
        modifier = modifier,
        transitionSpec = {
            val enter = if (motionEnabled) {
                fadeIn(WandMotion.tweenFast()) + scaleIn(
                    initialScale = 0.72f,
                    animationSpec = WandMotion.tweenFast(),
                )
            } else {
                EnterTransition.None
            }
            val exit = if (motionEnabled) {
                fadeOut(tween(durationMillis = WandMotion.quickExit)) + scaleOut(
                    targetScale = 1.12f,
                    animationSpec = WandMotion.tweenExit(),
                )
            } else {
                ExitTransition.None
            }
            // togetherWith 的顺序是「进场内容 → 退场内容」，写反了会看到两层内容对调错位。
            enter togetherWith exit
        },
        label = "inPlaceSwap",
    ) { key ->
        content(key)
    }
}

// MARK: - 标签指示条

/**
 * 均匀分段的下标 → 指示条 [左边缘, 右边缘] 的比例（0..1）。
 * [gapFraction] 是段间距占轨道宽度的比例。抽出来是为了能单测几何，不靠目测。
 */
internal fun slideEdgeFractions(index: Int, count: Int, gapFraction: Float): Pair<Float, Float> {
    if (count <= 0) return 0f to 0f
    val clamped = index.coerceIn(0, count - 1)
    val gap = gapFraction.coerceIn(0f, 0.4f)
    val segment = (1f - gap * (count - 1)) / count
    val left = clamped * (segment + gap)
    return left to (left + segment)
}

/**
 * 分段控件 + 滑动指示条（对齐 iOS 分段控件的手感）：
 * 指示条先被「拉向」新位置、再自然收回——移动方向上的前缘先走，后缘晚一拍跟上，
 * 中间态指示条比一段更宽，落位后再收回成一段。
 */
@Composable
fun WandSegmentedTrack(
    itemCount: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    containerShape: Shape = WandShapes.md,
    indicatorShape: Shape = WandShapes.sm,
    containerColor: Color = WandColors.surfaceSoft.copy(alpha = 0.6f),
    indicatorColor: Color = WandColors.surface,
    indicatorBorder: Color? = WandColors.border.copy(alpha = 0.55f),
    padding: Dp = 3.dp,
    minHeight: Dp = 44.dp,
    items: @Composable RowScope.() -> Unit,
) {
    val motionEnabled = !reduceMotionEnabled()
    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    val movingForward = selectedIndex >= previousIndex
    LaunchedEffect(selectedIndex) { previousIndex = selectedIndex }
    val (targetLeft, targetRight) = slideEdgeFractions(selectedIndex, itemCount, gapFraction = 0f)
    // 前缘先动、后缘延后一拍：指示条中间态被拉长，落位后再收回。
    val left by animateFloatAsState(
        targetValue = targetLeft,
        animationSpec = WandMotion.respectMotion(
            motionEnabled,
            WandMotion.indicator(
                delayMillis = if (movingForward) WandMotion.indicatorTrailDelayMillis else 0,
            ),
        ),
        label = "indicatorLeft",
    )
    val right by animateFloatAsState(
        targetValue = targetRight,
        animationSpec = WandMotion.respectMotion(
            motionEnabled,
            WandMotion.indicator(
                delayMillis = if (movingForward) 0 else WandMotion.indicatorTrailDelayMillis,
            ),
        ),
        label = "indicatorRight",
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cornerPx = with(density) { 10.dp.toPx() }
    val strokePx = with(density) { 0.8.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(containerShape)
            .background(containerColor)
            .padding(padding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight - padding * 2)
                // 指示条用 drawBehind 画在内容背后：它的高度天然等于这一行的高度，
                // 不需要再靠 fillMaxHeight 之类的约束去猜，也不会把容器撑高。
                .drawBehind {
                    val indicatorLeft = size.width * left
                    val indicatorWidth = (size.width * (right - left)).coerceAtLeast(0f)
                    if (indicatorWidth <= 0f) return@drawBehind
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(indicatorLeft, 0f),
                        size = Size(indicatorWidth, size.height),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    )
                    val border = indicatorBorder
                    if (border != null) {
                        drawRoundRect(
                            color = border,
                            topLeft = Offset(indicatorLeft, 0f),
                            size = Size(indicatorWidth, size.height),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                            style = Stroke(width = strokePx),
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            content = items,
        )
    }
}

// MARK: - 搜索框就地展开

/**
 * 就地展开的搜索框：展开即拿焦点、直接弹键盘，不跳页、不换屏。
 *
 * 收起规则：查询词非空时点 ✕ 先清空，再点一次才收起——避免「点一下把输入和结果一起弄丢」。
 */
@Composable
fun WandInlineSearchField(
    expanded: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCollapse: (() -> Unit)?,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val motionEnabled = !reduceMotionEnabled()
    LaunchedEffect(expanded) {
        if (!expanded) {
            keyboard?.hide()
            return@LaunchedEffect
        }
        if (!autoFocus) return@LaunchedEffect
        // 展开动画起步后再要焦点：立刻 requestFocus 会在测量前抢焦点，键盘有时不弹。
        kotlinx.coroutines.delay(if (motionEnabled) 60L else 0L)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(WandShapes.full)
            .background(WandColors.surface.copy(alpha = 0.92f))
            .border(0.8.dp, WandColors.border.copy(alpha = 0.7f), WandShapes.full)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            WandIcons.search,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier.size(14.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = WandColors.textPrimary),
            cursorBrush = SolidColor(WandColors.brand),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { focusManager.clearFocus() },
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        // 收起按钮只有「调用方自己管收起」时才画在这里；
        // 顶栏那枚变形的放大镜已经承担了清空 + 收起，再画一个 ✕ 会出现两个并排的关闭。
        if (onCollapse != null) {
            WandMorphIconButton(
                expanded = false,
                collapsedIcon = WandIcons.close,
                expandedIcon = WandIcons.close,
                contentDescription = if (query.isNotEmpty()) "清空搜索" else "关闭搜索",
                iconSize = 16.dp,
                touchSize = 36.dp,
                tint = WandColors.textMuted,
                onClick = {
                    if (query.isNotEmpty()) {
                        onQueryChange("")
                    } else {
                        focusManager.clearFocus()
                        onCollapse.invoke()
                    }
                },
            )
        }
    }
}

// MARK: - 加号就地展开面板

/**
 * 从触发按钮原地展开的面板：不弹底部弹层、不换页，内容从触发点方向「长出来」。
 *
 * [growFrom] 决定展开方向（底部输入栏里的 ＋ 向上展开，顶部工具栏里的放下去）。
 * 收起是同一段动画倒放，视觉上回到触发按钮。
 */
@Composable
fun WandInlinePanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    growFrom: Alignment.Vertical = Alignment.Bottom,
    content: @Composable () -> Unit,
) {
    val motionEnabled = !reduceMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motionEnabled) {
            expandVertically(
                animationSpec = WandMotion.tweenEnter(),
                expandFrom = growFrom,
            ) + fadeIn(WandMotion.tweenFast())
        } else {
            androidx.compose.animation.EnterTransition.None
        },
        exit = if (motionEnabled) {
            shrinkVertically(
                animationSpec = WandMotion.tweenExit(),
                shrinkTowards = growFrom,
            ) + fadeOut(WandMotion.tweenFast())
        } else {
            androidx.compose.animation.ExitTransition.None
        },
    ) {
        content()
    }
}

/** 就地展开面板里的一枚动作胶囊（相册 / 文件 / 新建任务…）。 */
@Composable
fun WandInlinePanelAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motionEnabled = !reduceMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "panelActionScale",
    )
    Row(
        modifier = modifier
            .clip(WandShapes.full)
            .background(WandColors.surface.copy(alpha = 0.94f))
            .border(0.8.dp, WandColors.border.copy(alpha = 0.7f), WandShapes.full)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) WandColors.brand else WandColors.textMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) WandColors.textPrimary else WandColors.textMuted.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

// MARK: - 长按拖动排序

/**
 * 拖动排序状态。只存「谁在被拖」和「相对手指的偏移」；
 * 目标索引从 `LazyListState` 的实时布局里读，避免自己维护一份可能与真实布局不一致的下标。
 */
@Stable
class WandDragReorderState {
    internal var draggingKey: Any? by mutableStateOf(null)
    internal var offsetY by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = draggingKey != null

    fun isDragging(key: Any?): Boolean = key != null && draggingKey == key
}

@Composable
fun rememberWandDragReorderState(): WandDragReorderState = remember { WandDragReorderState() }

/**
 * 被拖动的条目抬起：置顶 + 轻微放大 + 投影。
 * 位移写在 graphicsLayer 的 lambda 里，拖动时只失效图层、不重组整列。
 */
@Composable
fun WandDragReorderState.itemLiftModifier(itemKey: Any): Modifier {
    val dragging = isDragging(itemKey)
    val motionEnabled = !reduceMotionEnabled()
    return Modifier
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer {
            translationY = if (dragging) offsetY else 0f
            val scale = if (dragging && motionEnabled) 1.02f else 1f
            scaleX = scale
            scaleY = scale
            shadowElevation = if (dragging) 12.dp.toPx() else 0f
        }
}

/**
 * 长按拖动排序手势。挂在「条目里那块可以拖的区域」上（例如工作区卡片的标题行），
 * 而不是整张卡片：卡片内部的会话行有自己的长按多选，两套手势不能抢同一个区域。
 *
 * 落点判定用相邻条目的真实高度：被拖条目越过邻居的 60% 就交换，
 * 交换后把偏移扣掉「邻居高度 + 间距」，卡片才会稳稳跟着手指而不是跳一格。
 */
@Composable
fun Modifier.wandLongPressDrag(
    state: WandDragReorderState,
    itemKey: Any,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    enabled: Boolean = true,
    onDragStarted: () -> Unit = {},
    onDragFinished: () -> Unit = {},
    onDragCancelled: () -> Unit = {},
): Modifier {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return this.pointerInput(itemKey, enabled) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
            onDragStart = {
                onDragStarted()
                state.draggingKey = itemKey
                state.offsetY = 0f
                // 抓起时给一次触感：长按在这里的语义是「拿起」，不是进入多选。
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            },
            onDrag = { change, dragAmount ->
                change.consume()
                state.offsetY += dragAmount.y
                val visible = listState.layoutInfo.visibleItemsInfo
                val current = visible.firstOrNull { it.key == itemKey }
                if (current != null) {
                    val below = visible.firstOrNull { it.index == current.index + 1 }
                    val above = visible.firstOrNull { it.index == current.index - 1 }
                    when {
                        below != null && state.offsetY > below.size * DRAG_SWAP_RATIO -> {
                            val gap = (below.offset - (current.offset + current.size)).coerceAtLeast(0)
                            onMove(itemKey, below.key)
                            state.offsetY -= (below.size + gap)
                        }
                        above != null && state.offsetY < -above.size * DRAG_SWAP_RATIO -> {
                            val gap = (current.offset - (above.offset + above.size)).coerceAtLeast(0)
                            onMove(itemKey, above.key)
                            state.offsetY += (above.size + gap)
                        }
                    }
                }
            },
            onDragEnd = {
                state.draggingKey = null
                state.offsetY = 0f
                onDragFinished()
            },
            onDragCancel = {
                state.draggingKey = null
                state.offsetY = 0f
                onDragCancelled()
            },
        )
    }
}

/** 越过邻居 60% 才交换：太灵敏会在一次拖动里连跳好几格。 */
private const val DRAG_SWAP_RATIO = 0.6f

/** 面板/卡片左侧的状态引导条：颜色即健康度（等你 > 在跑 > 普通）。 */
@Composable
fun WandStatusRail(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .clip(CircleShape)
            .background(color),
    )
}
