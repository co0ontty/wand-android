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
/** Shared center line for the back button, the three logos, and the companion. */
internal val FloatingControlCenter = FloatingControlRowInset + FloatingControlTouch / 2

/** Floating controls have 48dp targets; the reading viewport clears them without a toolbar. */
@Composable
internal fun FloatingChatControls(
    showBack: Boolean, onBack: () -> Unit,
    identity: String, cwd: String?, onTarget: () -> Unit,
    gitEnabled: Boolean, onGit: () -> Unit,
    onStop: (() -> Unit)? = null,
    taskSessions: List<WorkspaceSessionSummary> = emptyList(),
    onSelectTaskSession: (WorkspaceSessionSummary) -> Unit = {},
    modifier: Modifier = Modifier, files: @Composable () -> Unit,
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
            // 「文件」自己持有点击与形变动画，所以圆底画在它身后：可见 40dp、触控仍是 48dp，和另两枚同一套几何。
            Box(Modifier.size(FloatingControlTouch), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(WandColors.surface))
                files()
            }
            Box {
                FloatingChatIconButton(WandIcons.more, "更多：会话对象、工作目录与变更", { moreOpen = !moreOpen })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    if (taskSessions.size > 1) {
                        DropdownMenuItem(
                            text = { Text("切换任务会话") },
                            onClick = { moreOpen = false; taskSessionsOpen = true },
                        )
                    }
                    DropdownMenuItem(text = {
                        Column(Modifier.widthIn(max = 240.dp)) {
                            Text(identity, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cwd?.takeIf { it.isNotBlank() } ?: "未选择工作目录",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("查看或切换对象与目录", style = MaterialTheme.typography.labelSmall)
                        }
                    }, onClick = { moreOpen = false; onTarget() })
                    DropdownMenuItem(text = { Text("查看变更与提交") }, enabled = gitEnabled,
                        onClick = { moreOpen = false; onGit() })
                    if (onStop != null) DropdownMenuItem(text = { Text("停止当前任务") },
                        onClick = { moreOpen = false; onStop() })
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
