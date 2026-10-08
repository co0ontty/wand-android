package com.wand.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 会话行从右往左划开后露出的动作。 */
internal enum class ConversationSwipeAction { Pin, Unpin, Dissolve, Restore, Delete }

/**
 * 划开可见的动作：置顶态只给反向动作，解散/恢复只给群聊，删除兜底。
 * 返回空集合时行退化成普通点击（多选态用这条）。
 */
internal fun conversationSwipeActions(kind: String, pinned: Boolean, dissolved: Boolean): List<ConversationSwipeAction> = buildList {
    add(if (pinned) ConversationSwipeAction.Unpin else ConversationSwipeAction.Pin)
    if (kind == "group") add(if (dissolved) ConversationSwipeAction.Restore else ConversationSwipeAction.Dissolve)
    add(ConversationSwipeAction.Delete)
}

internal fun conversationSwipeActionLabel(action: ConversationSwipeAction): String = when (action) {
    ConversationSwipeAction.Pin -> "置顶"
    ConversationSwipeAction.Unpin -> "取消置顶"
    ConversationSwipeAction.Dissolve -> "解散"
    ConversationSwipeAction.Restore -> "恢复"
    ConversationSwipeAction.Delete -> "删除"
}

internal fun conversationSwipeActionIcon(action: ConversationSwipeAction): ImageVector = when (action) {
    ConversationSwipeAction.Pin, ConversationSwipeAction.Unpin -> WandIcons.leader
    ConversationSwipeAction.Dissolve -> WandIcons.archive
    ConversationSwipeAction.Restore -> WandIcons.refresh
    ConversationSwipeAction.Delete -> WandIcons.delete
}

@Composable
internal fun conversationSwipeActionColor(action: ConversationSwipeAction): Color = when (action) {
    ConversationSwipeAction.Pin -> WandColors.brand
    ConversationSwipeAction.Unpin -> WandColors.info
    ConversationSwipeAction.Dissolve -> WandColors.warning
    ConversationSwipeAction.Restore -> WandColors.success
    ConversationSwipeAction.Delete -> WandColors.danger
}

/** 多选条上的批量置顶动作：选中项里有未置顶的就置顶，全已置顶则反向取消。 */
internal enum class ConversationBatchAction { Pin, Unpin }

internal fun conversationBatchAction(pinnedFlags: List<Boolean>): ConversationBatchAction? = when {
    pinnedFlags.isEmpty() -> null
    pinnedFlags.all { it } -> ConversationBatchAction.Unpin
    else -> ConversationBatchAction.Pin
}

/** 单个动作按钮的期望宽度；露出总宽再按行宽封顶，窄屏不至于把内容全推走。 */
private val ConversationSwipeActionWidth = 72.dp
private val ConversationSwipeActionGap = 6.dp
private val ConversationSwipeMaxRevealRatio = .58f

/**
 * 从右往左划开会话行，右侧露出动作抽屉。
 *
 * 手势、位移与收起判定与 [BoardTaskSwipeCard] 同源（复用 [boardTaskSwipeShouldReveal]），
 * 差别只在动作由调用方按会话状态给出，且卡片底色要跟列表表面一致而不是页面背景。
 * 开合状态由调用方持有，保证同一时刻只有一行是划开的。
 */
@Composable
internal fun ConversationSwipeRowCard(
    actions: List<ConversationSwipeAction>,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onAction: (ConversationSwipeAction) -> Unit,
    cardBackground: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val gapPx = with(density) { ConversationSwipeActionGap.toPx() }
        val desiredPx = with(density) { (ConversationSwipeActionWidth * actions.size + ConversationSwipeActionGap * (actions.size - 1)).toPx() }
        val revealWidthPx = minOf(desiredPx, with(density) { maxWidth.toPx() } * ConversationSwipeMaxRevealRatio)
        val actionWidth = with(density) { ((revealWidthPx - gapPx * (actions.size - 1)) / actions.size).toDp() }
        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val latestOnRevealedChange = rememberUpdatedState(onRevealedChange)
        val reduceMotion = reduceMotionEnabled()

        suspend fun settle(target: Float) {
            if (reduceMotion) offset.snapTo(target) else offset.animateTo(target, WandMotion.tweenFast())
        }

        // 别的行被划开、或动作已落地时，本行同步回位。
        LaunchedEffect(revealed, revealWidthPx) { settle(if (revealed) -revealWidthPx else 0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-revealWidthPx, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        val open = boardTaskSwipeShouldReveal(offset.value, revealWidthPx, velocity)
                        latestOnRevealedChange.value(open)
                        settle(if (open) -revealWidthPx else 0f)
                    },
                ),
        ) {
            Row(
                modifier = Modifier.matchParentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ConversationSwipeActionGap, Alignment.End),
            ) {
                actions.forEach { action ->
                    ConversationSwipeButton(
                        action = action,
                        width = actionWidth,
                        onClick = {
                            onAction(action)
                            latestOnRevealedChange.value(false)
                        },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .background(cardBackground),
            ) {
                content()
                if (revealed) {
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(interactionSource = interaction, indication = null) { latestOnRevealedChange.value(false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationSwipeButton(action: ConversationSwipeAction, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val label = conversationSwipeActionLabel(action)
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(WandShapes.md)
            .background(conversationSwipeActionColor(action))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(conversationSwipeActionIcon(action), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
