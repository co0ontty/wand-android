package com.wand.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

/** 会话行从右往左划开后露出的动作。 */
internal enum class ConversationSwipeAction { Pin, Unpin, Dissolve, Restore, Delete }

/**
 * 划开可见的动作：置顶态只给反向动作，解散/恢复只给群聊，删除兜底。
 * 返回空集合时行退化成普通点击（多选态用这条）。
 */
internal fun conversationSwipeActions(kind: String, pinned: Boolean, dissolved: Boolean, external: Boolean = false): List<ConversationSwipeAction> = buildList {
    add(if (pinned) ConversationSwipeAction.Unpin else ConversationSwipeAction.Pin)
    if (kind == "group" && !external) add(if (dissolved) ConversationSwipeAction.Restore else ConversationSwipeAction.Dissolve)
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

internal enum class ConversationManagementAction { Dissolve, Restore, Delete }

internal fun conversationManagementActions(kind: String, dissolved: Boolean, external: Boolean, deleting: Boolean): List<ConversationManagementAction> {
    if (kind != "group" || deleting) return emptyList()
    return buildList {
        if (!external) add(if (dissolved) ConversationManagementAction.Restore else ConversationManagementAction.Dissolve)
        add(ConversationManagementAction.Delete)
    }
}

internal fun ConversationManagementAction.title(external: Boolean = false): String = when (this) {
    ConversationManagementAction.Dissolve -> "解散群聊"
    ConversationManagementAction.Restore -> "恢复群聊"
    ConversationManagementAction.Delete -> if (external) "删除本地群镜像" else "删除群聊"
}

internal fun ConversationManagementAction.explanation(external: Boolean): String = when (this) {
    ConversationManagementAction.Delete -> if (external)
        "仅删除 Wand 中的本地群镜像、消息和关联执行；不退出或解散钉钉群，也不删除其他群共用的机器人。此操作无法撤销。"
    else "将删除这个群聊、所有关联任务，以及它们的消息、执行记录和资源文件。不会删除其他对话或工作区。此操作无法撤销。"
    else -> "解散后保留历史记录和关联任务，恢复后可继续聊天。已在执行的任务不会因此停止。"
}

/** 多选条上的批量置顶动作：选中项里有未置顶的就置顶，全已置顶则反向取消。 */
internal enum class ConversationBatchAction { Pin, Unpin }

internal fun conversationBatchAction(pinnedFlags: List<Boolean>): ConversationBatchAction? = when {
    pinnedFlags.isEmpty() -> null
    pinnedFlags.all { it } -> ConversationBatchAction.Unpin
    else -> ConversationBatchAction.Pin
}

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
    HomeActionSwipeCard(actions.map { action ->
        HomeRowAction(action.name, conversationSwipeActionLabel(action), conversationSwipeActionIcon(action),
            conversationSwipeActionColor(action)) { onAction(action) }
    }, revealed, onRevealedChange, cardBackground, modifier, content)
}
