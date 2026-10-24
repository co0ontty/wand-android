package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.orderWorkspaceSessions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.theme.WandColors

internal val FloatingControlTouch = 48.dp
internal val FloatingControlRowInset = 4.dp
internal val ChatFloatingControlsClearance = FloatingControlTouch + FloatingControlRowInset * 2
/** Shared center line for the back button, the more button, and the companion. */
internal val FloatingControlCenter = FloatingControlRowInset + FloatingControlTouch / 2
/** 顶栏内容区左右各自让开的宽度：返回与更多各一个按钮，文件收在更多内。 */
internal val FloatingControlsBackInset = 12.dp + FloatingControlTouch
internal val FloatingControlsActionsInset = 12.dp + FloatingControlTouch

/** Floating controls have 48dp targets; the reading viewport clears them without a toolbar. */
@Composable
internal fun FloatingChatControls(
    showBack: Boolean, onBack: () -> Unit,
    identity: String, cwd: String?, onTarget: () -> Unit,
    gitEnabled: Boolean, onGit: () -> Unit,
    onStop: (() -> Unit)? = null,
    taskSessions: List<WorkspaceSessionSummary> = emptyList(),
    onSelectTaskSession: (WorkspaceSessionSummary) -> Unit = {},
    filesEnabled: Boolean, onFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreOpen by remember { mutableStateOf(false) }
    var taskSessionsOpen by remember { mutableStateOf(false) }
    if (taskSessionsOpen) {
        WandDialog(
            title = "切换任务会话",
            onDismissRequest = { taskSessionsOpen = false },
            confirm = WandDialogAction("关闭", { taskSessionsOpen = false }),
        ) {
            orderWorkspaceSessions(taskSessions).forEach { session ->
                TextButton(
                    onClick = {
                        taskSessionsOpen = false
                        onSelectTaskSession(session)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        session.title?.takeIf { it.isNotBlank() } ?: "终端会话",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = FloatingControlRowInset),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (showBack) FloatingChatIconButton(WandIcons.back, "返回", onBack)
        else Spacer(Modifier.size(FloatingControlTouch))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                FloatingChatIconButton(WandIcons.more, "更多：$identity，${cwd?.takeIf { it.isNotBlank() } ?: "未选择工作目录"}，会话对象与变更", { moreOpen = !moreOpen })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    SessionIconActions(buildList {
                        add(SessionIconAction("会话文件", WandIcons.folder, enabled = filesEnabled, onClick = onFiles))
                        add(SessionIconAction("对象与目录", WandIcons.agent, onClick = onTarget))
                        add(SessionIconAction("变更与提交", WandIcons.commit, enabled = gitEnabled, onClick = onGit))
                        if (taskSessions.size > 1) add(SessionIconAction("切换任务会话", WandIcons.workspace) { taskSessionsOpen = true })
                        if (onStop != null) add(SessionIconAction("停止当前任务", WandIcons.stop, destructive = true, onClick = onStop))
                    }, onDismiss = { moreOpen = false })
                }
            }
        }
    }
}

@Composable
internal fun FloatingChatIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(FloatingControlTouch)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(WandColors.surface), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = WandColors.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}
