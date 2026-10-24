package com.wand.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

/** 松手时按速度判定「划开 / 收起」的最小速度（px/s）。 */
internal const val BOARD_TASK_SWIPE_OPEN_VELOCITY = 480f

/** 任务卡右划可以触发的动作：待办→开始、进行中→完成、已完成→归档。 */
internal enum class BoardTaskSwipeAction { Start, Complete, Archive }

internal fun boardTaskSwipeActions(status: String): List<BoardTaskSwipeAction> = when (status) {
    "todo" -> listOf(BoardTaskSwipeAction.Start)
    "doing" -> listOf(BoardTaskSwipeAction.Complete)
    "done" -> listOf(BoardTaskSwipeAction.Archive)
    // 归档任务已经在归档区里，不再提供滑动动作。
    else -> emptyList()
}

internal fun boardTaskSwipeActionLabel(action: BoardTaskSwipeAction): String = when (action) {
    BoardTaskSwipeAction.Start -> "开始"
    BoardTaskSwipeAction.Complete -> "完成"
    BoardTaskSwipeAction.Archive -> "归档"
}

/**
 * 滑动动作要落到的任务状态。
 * 归档走 DELETE `/api/wand-tasks/:id`（服务端 archiveBoardTask，与详情页「归档」一致），
 * 不通过状态补丁，所以这里返回 null。
 */
internal fun boardTaskSwipeTargetStatus(action: BoardTaskSwipeAction): String? = when (action) {
    BoardTaskSwipeAction.Start -> "doing"
    BoardTaskSwipeAction.Complete -> "done"
    BoardTaskSwipeAction.Archive -> null
}

/** 二次确认弹窗标题。 */
internal fun boardTaskSwipeActionTitle(action: BoardTaskSwipeAction): String = when (action) {
    BoardTaskSwipeAction.Start -> "开始任务？"
    BoardTaskSwipeAction.Complete -> "确认完成？"
    BoardTaskSwipeAction.Archive -> "归档任务？"
}

/** 二次确认弹窗正文：说清这一下会把任务改成什么。 */
internal fun boardTaskSwipeConfirmMessage(action: BoardTaskSwipeAction): String = when (action) {
    BoardTaskSwipeAction.Start -> "任务将标记为进行中。"
    BoardTaskSwipeAction.Complete -> "任务将标记为已完成。"
    BoardTaskSwipeAction.Archive -> "任务将移入归档，之后仍可在「归档」中找回。"
}

/**
 * 松手后是否停在「已划开」状态：速度优先，速度过小才看位移过半。
 *
 * 按钮露在右侧，所以划开方向是从右往左：位移为负、速度为负。
 */
internal fun boardTaskSwipeShouldReveal(
    offsetPx: Float,
    revealWidthPx: Float,
    velocity: Float,
): Boolean {
    if (revealWidthPx <= 0f) return false
    if (velocity <= -BOARD_TASK_SWIPE_OPEN_VELOCITY) return true
    if (velocity >= BOARD_TASK_SWIPE_OPEN_VELOCITY) return false
    return offsetPx <= -revealWidthPx / 2f
}

/**
 * 从右往左划任务卡，右侧露出动作按钮。
 *
 * - 卡片向左平移，动作按钮固定在右侧被卡片盖住，平移量就是露出的宽度；
 * - 卡片半透明玻璃底会透出按钮颜色，所以在卡片下面垫一层 [WandColors.bgPrimary] 保持原观感；
 * - 划开状态下点卡片任意位置只收起，不打开详情；动作本身由调用方做二次确认。
 *
 * 开合状态由调用方持有（同一时刻只允许一张卡划开），本组件只负责手势与动画。
 */
@Composable
internal fun BoardTaskSwipeCard(
    status: String,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onAction: (BoardTaskSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    extraActions: List<HomeRowAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    val actions = boardTaskSwipeActions(status).map { action ->
        HomeRowAction(action.name, boardTaskSwipeActionLabel(action), boardTaskSwipeActionIcon(action),
            boardTaskSwipeActionColor(action)) { onAction(action) }
    }
    HomeActionSwipeCard(actions + extraActions, revealed, onRevealedChange, WandColors.bgPrimary, modifier, content)
}

/** 动作图标：按钮与二次确认弹窗共用，所以对同包可见。 */
internal fun boardTaskSwipeActionIcon(action: BoardTaskSwipeAction): ImageVector = when (action) {
    BoardTaskSwipeAction.Start -> WandIcons.play
    BoardTaskSwipeAction.Complete -> WandIcons.check
    BoardTaskSwipeAction.Archive -> WandIcons.archive
}

@Composable
private fun boardTaskSwipeActionColor(action: BoardTaskSwipeAction): Color = when (action) {
    BoardTaskSwipeAction.Start -> WandColors.success
    BoardTaskSwipeAction.Complete -> WandColors.info
    BoardTaskSwipeAction.Archive -> WandColors.danger
}
