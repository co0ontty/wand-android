package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.orderWorkspaceSessions
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.theme.WandColors

/**
 * 任务内「其他终端」快捷切换条（对齐 iOS WorkspaceTaskView 的 sessionStrip）：
 * 横向滚动的 Tab，展示当前任务下的全部工作窗口 —— provider 图标 + 短标签 +
 * 运行状态点；点击直接切到对应会话页，当前会话高亮。
 *
 * 数据来自 GET /api/workspace-tasks/:taskId；加载失败或任务下没有会话时整条
 * 隐藏，不影响原布局。切换由外层导航完成（replaceTop），返回键仍回到任务详情。
 */
@Composable
fun TaskSessionTabStrip(
    api: WorkspacePort,
    taskId: String,
    currentSessionId: String?,
    onSelect: (WorkspaceSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sessions by remember(taskId) { mutableStateOf<List<WorkspaceSessionSummary>?>(null) }

    // 进入页面和每次切换会话都刷新一次：新会话可能刚在任务里创建。
    LaunchedEffect(taskId, currentSessionId) {
        runCatching { api.workspaceTask(taskId) }
            .onSuccess { sessions = orderWorkspaceSessions(it.sessions) }
    }

    val tabs = sessions
    if (tabs.isNullOrEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, session ->
            TaskSessionTab(
                session = session,
                index = index,
                isSelected = session.id == currentSessionId,
                onClick = { onSelect(session) },
            )
        }
    }
}

@Composable
private fun TaskSessionTab(
    session: WorkspaceSessionSummary,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val label = listSessionLabel(session, index)
    val accent = if (session.provider == "codex") WandColors.info else WandColors.brand
    val shape = RoundedCornerShape(8.dp)
    val isRunning = session.status in RUNNING_STATUSES

    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (isSelected) WandColors.selectedFill else WandColors.bgElevated.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = if (isSelected) WandColors.brand.copy(alpha = 0.55f) else WandColors.border,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp)
            .semantics { contentDescription = "切换到 $label" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(session.provider),
            contentDescription = null,
            tint = BrandLogos.tintForProvider(session.provider, if (isSelected) accent else WandColors.textSecondary),
            modifier = Modifier.size(14.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isSelected) WandColors.textPrimary else WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isRunning) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(WandColors.success, CircleShape),
            )
        }
    }
}

private val RUNNING_STATUSES = setOf("initializing", "running", "thinking")
