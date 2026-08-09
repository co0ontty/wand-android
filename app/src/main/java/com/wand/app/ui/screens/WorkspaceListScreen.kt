package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspacePort
import com.wand.app.data.parseWorkspaceTaskStatus
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.data.raw
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

/**
 * 项目 / 任务只读浏览页。项目折叠，点击任务进入任务详情；支持下拉刷新。
 * 第一批不含新建/编辑/删除（第二批再扩展 CRUD）。
 */
@Composable
fun WorkspaceListScreen(
    api: WorkspacePort,
    onBack: () -> Unit,
    onOpenTask: (workspaceId: String, taskId: String, workspaceName: String, taskName: String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    // 每个项目下展开的任务列表（按 workspaceId 缓存）。
    val taskCache = remember { mutableMapOf<String, List<WorkspaceTask>>() }
    val expanded = remember { mutableSetOf<String>() }
    val loadingTasks = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        try {
            workspaces = api.listWorkspaces()
            error = null
            // 首个项目默认展开，便于直接看到任务。
            if (expanded.isEmpty() && workspaces.isNotEmpty()) {
                expanded.add(workspaces.first().id)
            }
        } catch (e: Exception) {
            error = e.message ?: "无法加载项目列表"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) { refresh() }

    fun toggleWorkspace(workspace: Workspace) {
        if (expanded.contains(workspace.id)) {
            expanded.remove(workspace.id)
            return
        }
        expanded.add(workspace.id)
        if (taskCache[workspace.id] == null && !loadingTasks.contains(workspace.id)) {
            loadingTasks.add(workspace.id)
            scope.launch {
                try {
                    taskCache[workspace.id] = api.listWorkspaceTasks(workspace.id)
                } catch (_: Exception) {
                    taskCache[workspace.id] = emptyList()
                } finally {
                    loadingTasks.remove(workspace.id)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                WandIcons.chevronRight,
                contentDescription = "返回",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayerRotate180()
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
            Text(
                "项目 / 任务",
                style = MaterialTheme.typography.titleLarge,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Icon(
                WandIcons.refresh,
                contentDescription = "刷新",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { scope.launch { refresh() } }
                    .padding(8.dp),
            )
        }

        when {
            loading && workspaces.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = WandColors.brand,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            error != null && workspaces.isEmpty() -> {
                ErrorState(
                    message = error!!,
                    onRetry = { scope.launch { refresh() } },
                )
            }
            workspaces.isEmpty() -> {
                EmptyState(
                    title = "还没有项目",
                    message = "在网页版或桌面端创建项目与任务，这里会同步显示。",
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = workspaces, key = { it.id }) { workspace ->
                        WorkspaceCard(
                            workspace = workspace,
                            isExpanded = expanded.contains(workspace.id),
                            tasks = taskCache[workspace.id],
                            isLoadingTasks = loadingTasks.contains(workspace.id),
                            onToggle = { toggleWorkspace(workspace) },
                            onOpenTask = { task ->
                                onOpenTask(
                                    workspace.id,
                                    task.id,
                                    workspace.name,
                                    task.name,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    isExpanded: Boolean,
    tasks: List<WorkspaceTask>?,
    isLoadingTasks: Boolean,
    onToggle: () -> Unit,
    onOpenTask: (WorkspaceTask) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WandColors.bgElevated.copy(alpha = 0.6f))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                WandIcons.folder,
                contentDescription = null,
                tint = WandColors.brand,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    workspace.name.ifEmpty { "未命名项目" },
                    style = MaterialTheme.typography.titleSmall,
                    color = WandColors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    workspace.cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                WandIcons.expand,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .graphicsLayerRotate(if (isExpanded) 180f else 0f)
                    .size(20.dp),
            )
        }
        if (isExpanded) {
            if (isLoadingTasks && tasks == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = WandColors.brand,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (tasks != null && tasks.isEmpty()) {
                Text(
                    "还没有任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else if (tasks != null) {
                tasks.forEach { task ->
                    TaskRow(task = task, onClick = { onOpenTask(task) })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: WorkspaceTask, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 38.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (task.status.raw() == "done") WandIcons.check else WandIcons.commit,
            contentDescription = null,
            tint = if (task.status.raw() == "done") WandColors.success else WandColors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            task.name.ifEmpty { "未命名任务" },
            style = MaterialTheme.typography.bodyMedium,
            color = WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            WandIcons.chevronRight,
            contentDescription = "打开任务",
            tint = WandColors.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                WandIcons.sparkle,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = WandColors.textPrimary,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(WandIcons.error, contentDescription = null, tint = WandColors.danger, modifier = Modifier.size(28.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
            WandButton(label = "重试", onClick = onRetry)
        }
    }
}

// region 图标旋转辅助

private fun Modifier.graphicsLayerRotate(degrees: Float): Modifier =
    this.graphicsLayer { rotationZ = degrees }

private fun Modifier.graphicsLayerRotate180(): Modifier = graphicsLayerRotate(180f)

// endregion
