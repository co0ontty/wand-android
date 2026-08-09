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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.wand.app.ui.components.GitBranchIcon
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

/**
 * 项目 / 任务浏览页。项目折叠，点击任务进入任务详情；支持重命名 / 删除任务（第二批 CRUD）。
 * 任务标识统一使用 Git 分支三节点图标（GitBranchIcon）。
 */
@Composable
fun WorkspaceListScreen(
    api: WorkspacePort,
    onBack: () -> Unit,
    onOpenTask: (workspaceId: String, taskId: String, workspaceName: String, taskName: String) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    refreshRequest: Int = 0,
    selectedTaskId: String? = null,
    onTaskRenamed: (WorkspaceTask) -> Unit = {},
    onTaskDeleted: (String) -> Unit = {},
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<WorkspaceTask?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WorkspaceTask?>(null) }
    var mutationBusy by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    // Compose 可观察缓存：异步任务返回后立即刷新展开区，不依赖其他状态碰巧重组。
    val taskCache = remember { mutableStateMapOf<String, List<WorkspaceTask>>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val loadingTasks = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    fun loadWorkspaceTasks(workspace: Workspace, force: Boolean = false) {
        if (loadingTasks[workspace.id] == true) return
        if (!force && taskCache[workspace.id] != null) return
        loadingTasks[workspace.id] = true
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

    suspend fun refresh() {
        loading = true
        try {
            val loaded = api.listWorkspaces()
            workspaces = loaded
            error = null
            // 项目入口默认展开项目，并主动拉取各自任务。
            loaded.forEach { workspace ->
                expanded.putIfAbsent(workspace.id, true)
                loadWorkspaceTasks(workspace, force = true)
            }
        } catch (e: Exception) {
            error = e.message ?: "无法加载项目列表"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api, refreshRequest) { refresh() }

    fun toggleWorkspace(workspace: Workspace) {
        val nextExpanded = expanded[workspace.id] != true
        expanded[workspace.id] = nextExpanded
        if (nextExpanded) loadWorkspaceTasks(workspace)
    }

    renameTarget?.let { task ->
        val trimmed = renameDraft.trim()
        val invalid = trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > 80 ||
            trimmed.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }
        WandDialog(
            title = "重命名任务",
            onDismissRequest = { if (!mutationBusy) renameTarget = null },
            icon = WandIcons.edit,
            confirm = WandDialogAction(
                label = if (mutationBusy) "保存中…" else "保存",
                enabled = !mutationBusy && !invalid,
                onClick = {
                    if (mutationBusy || invalid) return@WandDialogAction
                    scope.launch {
                        mutationBusy = true
                        mutationError = null
                        try {
                            val updated = api.renameWorkspaceTask(task.id, trimmed)
                            taskCache[task.workspaceId] = taskCache[task.workspaceId]
                                .orEmpty()
                                .map { if (it.id == updated.id) updated else it }
                            renameTarget = null
                            if (selectedTaskId == updated.id) onTaskRenamed(updated)
                        } catch (error: Exception) {
                            mutationError = error.message ?: "重命名任务失败"
                        } finally {
                            mutationBusy = false
                        }
                    }
                },
            ),
            dismiss = WandDialogAction(
                label = "取消",
                enabled = !mutationBusy,
                onClick = { renameTarget = null },
            ),
        ) {
            WandTextField(
                value = renameDraft,
                onValueChange = {
                    renameDraft = it
                    mutationError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = "任务名称",
                enabled = !mutationBusy,
                singleLine = true,
                isError = invalid || mutationError != null,
            )
            mutationError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    deleteTarget?.let { task ->
        WandDialog(
            title = "删除任务？",
            onDismissRequest = { if (!mutationBusy) deleteTarget = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (mutationBusy) "删除中…" else "删除",
                destructive = true,
                enabled = !mutationBusy,
                onClick = {
                    if (mutationBusy) return@WandDialogAction
                    scope.launch {
                        mutationBusy = true
                        mutationError = null
                        try {
                            api.deleteWorkspaceTask(task.id)
                            taskCache[task.workspaceId] = taskCache[task.workspaceId]
                                .orEmpty()
                                .filterNot { it.id == task.id }
                            deleteTarget = null
                            onTaskDeleted(task.id)
                        } catch (error: Exception) {
                            mutationError = error.message ?: "删除任务失败"
                        } finally {
                            mutationBusy = false
                        }
                    }
                },
            ),
            dismiss = WandDialogAction(
                label = "取消",
                enabled = !mutationBusy,
                onClick = { deleteTarget = null },
            ),
        ) {
            Text(
                "任务「${task.name}」及其会话和独立 worktree 将被删除，此操作无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            mutationError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary),
    ) {
        if (!embedded) {
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
                            isExpanded = expanded[workspace.id] == true,
                            tasks = taskCache[workspace.id],
                            isLoadingTasks = loadingTasks[workspace.id] == true,
                            onToggle = { toggleWorkspace(workspace) },
                            onOpenTask = { task ->
                                onOpenTask(
                                    workspace.id,
                                    task.id,
                                    workspace.name,
                                    task.name,
                                )
                            },
                            onRenameTask = { task ->
                                mutationError = null
                                renameDraft = task.name
                                renameTarget = task
                            },
                            onDeleteTask = { task ->
                                mutationError = null
                                deleteTarget = task
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
    onRenameTask: (WorkspaceTask) -> Unit,
    onDeleteTask: (WorkspaceTask) -> Unit,
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
                    TaskRow(
                        task = task,
                        onClick = { onOpenTask(task) },
                        onRename = { onRenameTask(task) },
                        onDelete = { onDeleteTask(task) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: WorkspaceTask,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(start = 38.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (task.status.raw() == "done") {
            Icon(
                WandIcons.statusDone,
                contentDescription = null,
                tint = WandColors.success,
                modifier = Modifier.size(16.dp),
            )
        } else {
            GitBranchIcon(
                tint = WandColors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            task.name.ifEmpty { "未命名任务" },
            style = MaterialTheme.typography.bodyMedium,
            color = WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    WandIcons.more,
                    contentDescription = "任务操作",
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(WandIcons.edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = WandColors.danger) },
                    leadingIcon = {
                        Icon(
                            WandIcons.delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = WandColors.danger,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
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
