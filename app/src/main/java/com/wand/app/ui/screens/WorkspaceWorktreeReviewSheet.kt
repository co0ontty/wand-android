package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceWorktreeOverview
import com.wand.app.data.WorkspaceWorktreeReview
import com.wand.app.data.buildWorkspaceMergeAgentPrompt
import com.wand.app.ui.components.GitBranchIcon
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.wandSelectedSurface
import kotlinx.coroutines.launch

/**
 * 项目级 Worktree 审查与 Agent 合并入口，对齐 iOS WorkspaceWorktreeReviewView /
 * Web WorkspaceWorktreeDialog。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceWorktreeReviewSheet(
    workspace: Workspace,
    api: WorkspacePort,
    onDismiss: () -> Unit,
    onMergeAgentStarted: (SessionSnapshot) -> Unit,
) {
    var overview by remember { mutableStateOf<WorkspaceWorktreeOverview?>(null) }
    var selectedTaskIds by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val actionable = overview?.actionableWorktrees.orEmpty()
    val selectedCount = actionable.count { it.taskId in selectedTaskIds }

    LaunchedEffect(workspace.id) {
        loading = true
        errorMessage = null
        try {
            val result = api.workspaceWorktreeOverview(workspace.id)
            overview = result
            selectedTaskIds = result.worktrees.filter { it.actionable }.map { it.taskId }.toSet()
        } catch (error: Exception) {
            errorMessage = error.message ?: "无法读取目录 Worktree。"
        } finally {
            loading = false
        }
    }

    WandBottomSheet(
        onDismissRequest = { if (!submitting) onDismiss() },
        gesturesEnabled = !submitting,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 620.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "目录 Worktrees",
                style = MaterialTheme.typography.titleLarge,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            TargetLens(workspace = workspace, overview = overview)
            Spacer(Modifier.height(12.dp))
            when {
                loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = WandColors.brand,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "正在检查所有 Worktree…",
                            style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textSecondary,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                overview != null && overview!!.worktrees.isEmpty() -> {
                    Text(
                        "这个目录还没有独立 Worktree。请先新建任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textSecondary,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
                overview != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "选择要交给 Agent 合并的 Worktree",
                            style = MaterialTheme.typography.labelMedium,
                            color = WandColors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (actionable.size > 1) {
                            Text(
                                if (selectedCount == actionable.size) "取消全选" else "全选可合并项",
                                style = MaterialTheme.typography.labelMedium,
                                color = WandColors.brand,
                                modifier = Modifier.clickable(enabled = !submitting) {
                                    selectedTaskIds = if (selectedCount == actionable.size) {
                                        emptySet()
                                    } else {
                                        actionable.map { it.taskId }.toSet()
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(overview!!.worktrees, key = { it.taskId }) { worktree ->
                            WorktreeRow(
                                worktree = worktree,
                                selected = worktree.taskId in selectedTaskIds,
                                enabled = worktree.actionable && !submitting,
                                onToggle = {
                                    selectedTaskIds = if (worktree.taskId in selectedTaskIds) {
                                        selectedTaskIds - worktree.taskId
                                    } else {
                                        selectedTaskIds + worktree.taskId
                                    }
                                },
                            )
                        }
                    }
                }
            }
            errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.danger,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WandButton(
                    label = "取消",
                    onClick = { if (!submitting) onDismiss() },
                    variant = WandButtonVariant.Text,
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                )
                WandButton(
                    label = if (submitting) "启动中…" else "交给 Agent 合并",
                    onClick = {
                        val current = overview ?: return@WandButton
                        if (submitting) return@WandButton
                        scope.launch {
                            submitting = true
                            errorMessage = null
                            try {
                                val prompt = buildWorkspaceMergeAgentPrompt(
                                    workspace,
                                    current,
                                    selectedTaskIds,
                                )
                                val provider = workspace.defaultProvider?.takeIf { it.isNotEmpty() }
                                    ?: "claude"
                                val started = api.startWorktreeMergeAgent(workspace, provider, prompt)
                                onMergeAgentStarted(started)
                            } catch (error: Exception) {
                                errorMessage = error.message ?: "无法启动 Worktree 合并 Agent。"
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    enabled = !submitting && !loading && selectedCount > 0,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

@Composable
private fun TargetLens(workspace: Workspace, overview: WorkspaceWorktreeOverview?) {
    val target = overview?.targetBranch?.takeIf { it.isNotEmpty() } ?: "仓库默认分支"
    val repoRoot = overview?.repoRoot?.takeIf { it.isNotEmpty() } ?: workspace.cwd
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WandColors.surface)
            .border(1.dp, WandColors.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GitBranchIcon(tint = WandColors.brand, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("合并目标", style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary)
            Text(target, style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
        }
        Text(
            repoRoot,
            style = MaterialTheme.typography.labelSmall,
            color = WandColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WorktreeRow(
    worktree: WorkspaceWorktreeReview,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandSelectedSurface(
                selected = selected,
                shape = RoundedCornerShape(12.dp),
                unselectedFill = WandColors.surface,
            )
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (selected) WandIcons.check else WandIcons.add,
            contentDescription = null,
            tint = if (selected) WandColors.brand else WandColors.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                worktree.summary,
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${worktree.stateLabel} · ${worktree.details}",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
