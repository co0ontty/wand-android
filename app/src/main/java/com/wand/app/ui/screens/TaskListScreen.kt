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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListEntry
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.raw
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.wandSelectedRow
import com.wand.app.ui.theme.wandSelectedSurface
import kotlinx.coroutines.launch

/** Stable route information carried from the task tree into a session detail screen. */
data class TaskSessionRoute(
    val sessionId: String,
    val structured: Boolean,
    val workspaceId: String? = null,
    val taskId: String? = null,
    val workspaceName: String? = null,
    val taskName: String? = null,
)

/**
 * Android task-first root. Directory is grouping metadata, task is the user-visible container,
 * and managed sessions are only rendered under a task or the legacy standalone section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    state: TaskListState,
    historyState: SessionListState,
    api: WorkspacePort,
    serverDisplayName: String,
    modifier: Modifier = Modifier,
    selectedTaskId: String? = null,
    selectedSessionId: String? = null,
    interactionEnabled: Boolean = true,
    onOpenTask: (workspaceId: String, taskId: String, workspaceName: String, taskName: String) -> Unit,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenRestoredSession: (SessionSnapshot) -> Unit,
    onTaskRenamed: (taskId: String, taskName: String) -> Unit = { _, _ -> },
    onTaskClosed: (taskId: String) -> Unit = {},
    onSessionClosed: (sessionId: String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val expandedTasks = remember { mutableStateMapOf<String, Boolean>() }
    val expandedStandalone = remember { mutableStateMapOf<String, Boolean>() }
    var historyExpanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var newTaskOpen by remember { mutableStateOf(false) }
    var taskNameDraft by remember { mutableStateOf("") }
    var taskCwdDraft by remember { mutableStateOf("") }
    var taskWorktreeEnabled by remember { mutableStateOf(true) }
    var pendingTarget by remember { mutableStateOf<Pair<TaskDirectoryGroup, WorkspaceTaskSummary>?>(null) }
    var selectedTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
    var selectedKind by remember { mutableStateOf(WorkspaceSessionKind.Structured) }
    var targetCreating by remember { mutableStateOf(false) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceTaskSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var clearTarget by remember { mutableStateOf<WorkspaceTaskSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceTaskSummary?>(null) }
    var deleteSessionTarget by remember { mutableStateOf<WorkspaceSessionSummary?>(null) }
    var reviewTarget by remember { mutableStateOf<TaskDirectoryGroup?>(null) }
    val targetSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val recoverableEntries = historyState.entries.mapNotNull { it as? SessionListEntry.Recoverable }
    val visibleGroups = state.groups.filter { it.tasks.isNotEmpty() || it.standaloneSessions.isNotEmpty() }
    val hasVisibleContent = visibleGroups.isNotEmpty() || recoverableEntries.isNotEmpty()

    fun beginNewTask(initialCwd: String? = null) {
        if (!interactionEnabled) return
        state.clearMutationError()
        taskNameDraft = ""
        taskCwdDraft = initialCwd.orEmpty()
        taskWorktreeEnabled = state.defaultTaskWorktree
        newTaskOpen = true
        scope.launch {
            state.loadCreationDefaults()
            taskWorktreeEnabled = state.defaultTaskWorktree
            if (taskCwdDraft.isBlank()) {
                taskCwdDraft = state.defaultCwd
                    ?: state.recentPaths.firstOrNull()?.path
                    ?: state.groups.firstOrNull { !it.synthetic }?.workspaceCwd
                    .orEmpty()
            }
        }
    }

    LaunchedEffect(state.newTaskRequest) {
        if (state.consumeNewTaskRequest()) beginNewTask()
    }

    if (newTaskOpen) {
        val name = taskNameDraft.trim()
        val cwd = taskCwdDraft.trim()
        WandDialog(
            title = "新任务",
            onDismissRequest = { if (!state.mutationBusy) newTaskOpen = false },
            icon = WandIcons.add,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "创建中…" else "创建",
                enabled = !state.mutationBusy && name.isNotEmpty() && cwd.isNotEmpty(),
                onClick = {
                    if (state.mutationBusy || name.isEmpty() || cwd.isEmpty()) return@WandDialogAction
                    scope.launch {
                        val result = state.createTask(name, cwd, taskWorktreeEnabled)
                        if (result != null) {
                            newTaskOpen = false
                            onOpenTask(
                                result.workspace.id,
                                result.task.id,
                                result.workspace.name,
                                result.task.name,
                            )
                        }
                    }
                },
            ),
            dismiss = WandDialogAction(
                label = "取消",
                enabled = !state.mutationBusy,
                onClick = { newTaskOpen = false },
            ),
        ) {
            WandTextField(
                value = taskNameDraft,
                onValueChange = { taskNameDraft = it; state.clearMutationError() },
                modifier = Modifier.fillMaxWidth(),
                label = "任务名称",
                placeholder = "例如：修复会话恢复流程",
                enabled = !state.mutationBusy,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            WandTextField(
                value = taskCwdDraft,
                onValueChange = { taskCwdDraft = it; state.clearMutationError() },
                modifier = Modifier.fillMaxWidth(),
                label = "任务目录",
                placeholder = state.defaultCwd ?: "/path/to/project",
                enabled = !state.mutationBusy,
                singleLine = true,
            )
            if (state.recentPaths.isNotEmpty()) {
                Text(
                    "最近目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                state.recentPaths.take(4).forEach { recent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !state.mutationBusy) { taskCwdDraft = recent.path }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            WandIcons.folder,
                            contentDescription = null,
                            tint = WandColors.textMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                recent.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = WandColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                recent.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = WandColors.textMuted,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "独立 worktree 隔离",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textPrimary,
                    )
                    Text(
                        if (taskWorktreeEnabled) {
                            "在独立分支与工作树中运行，完成后可审查合并。"
                        } else {
                            "直接使用任务目录，适合非 Git 目录或共享改动。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textMuted,
                    )
                }
                Switch(
                    checked = taskWorktreeEnabled,
                    onCheckedChange = {
                        taskWorktreeEnabled = it
                        state.rememberCreationChoice(defaultTaskWorktree = it)
                    },
                    enabled = !state.mutationBusy,
                )
            }
            state.mutationError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    renameTarget?.let { summary ->
        val name = renameDraft.trim()
        WandDialog(
            title = "重命名任务",
            onDismissRequest = { if (!state.mutationBusy) renameTarget = null },
            icon = WandIcons.rename,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "保存中…" else "保存",
                enabled = !state.mutationBusy && name.isNotEmpty(),
                onClick = {
                    scope.launch {
                        val updated = state.renameTask(summary.id, name)
                        if (updated != null) {
                            onTaskRenamed(updated.id, updated.name)
                            renameTarget = null
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { renameTarget = null }),
        ) {
            WandTextField(
                value = renameDraft,
                onValueChange = { renameDraft = it; state.clearMutationError() },
                modifier = Modifier.fillMaxWidth(),
                label = "任务名称",
                singleLine = true,
            )
            state.mutationError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = WandColors.danger)
            }
        }
    }

    clearTarget?.let { summary ->
        WandDialog(
            title = "清空任务会话？",
            onDismissRequest = { if (!state.mutationBusy) clearTarget = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "清空中…" else "清空 ${summary.totalSessions} 个会话",
                destructive = true,
                enabled = !state.mutationBusy,
                onClick = {
                    scope.launch {
                        if (state.clearTaskSessions(summary.id) != null) {
                            onTaskClosed(summary.id)
                            clearTarget = null
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { clearTarget = null }),
        ) {
            Text(
                "任务与 worktree 会保留；其中的会话及 provider 历史将被删除。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            state.mutationError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = WandColors.danger)
            }
        }
    }

    deleteTarget?.let { summary ->
        WandDialog(
            title = "删除任务？",
            onDismissRequest = { if (!state.mutationBusy) deleteTarget = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "删除中…" else "删除",
                destructive = true,
                enabled = !state.mutationBusy,
                onClick = {
                    scope.launch {
                        if (state.deleteTask(summary.id)) {
                            onTaskClosed(summary.id)
                            deleteTarget = null
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { deleteTarget = null }),
        ) {
            Text(
                "任务「${summary.name}」及其会话和独立 worktree 将被删除，此操作无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            state.mutationError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = WandColors.danger)
            }
        }
    }

    deleteSessionTarget?.let { session ->
        val label = listSessionLabel(session, 0)
        WandDialog(
            title = "删除终端？",
            onDismissRequest = { if (!state.mutationBusy) deleteSessionTarget = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "删除中…" else "删除",
                destructive = true,
                enabled = !state.mutationBusy,
                onClick = {
                    scope.launch {
                        if (state.deleteSessions(listOf(session.id)) != null) {
                            onSessionClosed(session.id)
                            deleteSessionTarget = null
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { deleteSessionTarget = null }),
        ) {
            Text(
                "终端「$label」会结束并被删除，此操作无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            state.mutationError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = WandColors.danger)
            }
        }
    }

    reviewTarget?.let { group ->
        WorkspaceWorktreeReviewSheet(
            workspace = group.asWorkspace(),
            api = api,
            onDismiss = { reviewTarget = null },
            onMergeAgentStarted = { snapshot ->
                reviewTarget = null
                onOpenRestoredSession(snapshot)
            },
        )
    }

    pendingTarget?.let { (group, task) ->
        WandBottomSheet(
            onDismissRequest = {
                if (!targetCreating) {
                    pendingTarget = null
                    targetError = null
                }
            },
            sheetState = targetSheetState,
            gesturesEnabled = !targetCreating,
        ) {
            WorkspaceTargetSheet(
                selected = selectedTarget,
                selectedKind = selectedKind,
                creating = targetCreating,
                error = targetError,
                onSelect = {
                    selectedTarget = it
                    if (!it.isShell) state.rememberCreationChoice(defaultProvider = it.raw)
                },
                onSelectKind = {
                    selectedKind = it
                    state.rememberCreationChoice(defaultSessionKind = it)
                },
                onConfirm = {
                    if (targetCreating) return@WorkspaceTargetSheet
                    targetCreating = true
                    targetError = null
                    scope.launch {
                        try {
                            val snapshot = state.createTaskWindow(task.id, selectedTarget, selectedKind)
                            if (snapshot == null) {
                                targetError = state.mutationError ?: "创建工作窗口失败"
                                return@launch
                            }
                            pendingTarget = null
                            onOpenSession(
                                TaskSessionRoute(
                                    sessionId = snapshot.id,
                                    structured = snapshot.isStructured,
                                    workspaceId = group.workspaceId,
                                    taskId = task.id,
                                    workspaceName = group.workspaceName,
                                    taskName = task.name,
                                ),
                            )
                        } catch (error: Exception) {
                            targetError = error.message ?: "创建工作窗口失败"
                        } finally {
                            targetCreating = false
                        }
                    }
                },
                onDismiss = { if (!targetCreating) pendingTarget = null },
            )
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(WandColors.bgPrimary),
    ) {
        Box {
            WandDetailTopBar(
                title = serverDisplayName,
                subtitle = "任务",
                actions = {
                    if (onCollapseSidebar != null) {
                        WandIconButton(
                            icon = WandIcons.panelCollapse,
                            contentDescription = "收起任务侧边栏",
                            onClick = onCollapseSidebar,
                            variant = WandIconButtonVariant.Toolbar,
                        )
                    }
                    WandIconButton(
                        icon = WandIcons.add,
                        contentDescription = "新建任务",
                        onClick = { beginNewTask() },
                        variant = WandIconButtonVariant.Accent,
                        enabled = interactionEnabled,
                    )
                    WandIconButton(
                        icon = WandIcons.more,
                        contentDescription = "更多选项",
                        onClick = { menuOpen = true },
                        variant = WandIconButtonVariant.Toolbar,
                    )
                },
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = WandColors.bgElevated,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                DropdownMenuItem(
                    text = { Text("刷新任务") },
                    leadingIcon = { Icon(WandIcons.refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            state.load(silent = true)
                            historyState.load(silent = true)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    leadingIcon = { Icon(WandIcons.settings, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
                DropdownMenuItem(
                    text = { Text("打开网页版") },
                    leadingIcon = { Icon(WandIcons.web, contentDescription = null) },
                    onClick = { menuOpen = false; onOpenWeb() },
                )
                DropdownMenuItem(
                    text = { Text("切换服务器") },
                    leadingIcon = { Icon(WandIcons.swapServer, contentDescription = null) },
                    onClick = { menuOpen = false; onSwitchServer() },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AmbientBackground(Modifier.fillMaxSize())
            when {
                state.loading && !hasVisibleContent -> LoadingState(text = "正在加载任务…")
                state.loadError != null && !hasVisibleContent -> ErrorState(
                    message = state.loadError ?: "无法加载任务列表",
                    onRetry = { scope.launch { state.load() } },
                )
                !hasVisibleContent && !historyState.canLoadMore -> EmptyState(
                    icon = WandIcons.todo,
                    title = "还没有任务",
                    subtitle = "新建任务并选择目录，之后的会话都会归属于该任务。",
                    actionText = "新建任务",
                    onAction = { beginNewTask() },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.loadError?.let { message ->
                        item(key = "task-load-error") {
                            InlineError(message = message, onRetry = { scope.launch { state.load() } })
                        }
                    }
                    items(visibleGroups, key = { "group-${it.id}" }) { group ->
                        TaskDirectorySection(
                            group = group,
                            directoryCount = visibleGroups.size,
                            groupCollapsed = expandedGroups[group.id] == false,
                            expandedTasks = expandedTasks,
                            standaloneCollapsed = expandedStandalone[group.id] == false,
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                            onToggleGroup = {
                                expandedGroups[group.id] = expandedGroups[group.id] == false
                            },
                            onToggleTask = { taskId ->
                                expandedTasks[taskId] = expandedTasks[taskId] == false
                            },
                            onToggleStandalone = {
                                expandedStandalone[group.id] = expandedStandalone[group.id] == false
                            },
                            onOpenTask = { task ->
                                onOpenTask(group.workspaceId, task.id, group.workspaceName, task.name)
                            },
                            onOpenSession = { session, task ->
                                onOpenSession(taskSessionRoute(session, group, task))
                            },
                            onNewTask = { beginNewTask(group.workspaceCwd) },
                            onNewWindow = { task ->
                                selectedTarget = WorkspaceSessionTarget.fromRaw(state.defaultProvider)
                                    ?: WorkspaceSessionTarget.Claude
                                selectedKind = state.defaultSessionKind
                                targetError = null
                                pendingTarget = group to task
                                scope.launch {
                                    state.loadCreationDefaults()
                                    selectedTarget = WorkspaceSessionTarget.fromRaw(state.defaultProvider)
                                        ?: selectedTarget
                                    selectedKind = state.defaultSessionKind
                                    targetSheetState.show()
                                }
                            },
                            onRename = { task ->
                                renameDraft = task.name
                                renameTarget = task
                                state.clearMutationError()
                            },
                            onClear = { task ->
                                clearTarget = task
                                state.clearMutationError()
                            },
                            onDelete = { task ->
                                deleteTarget = task
                                state.clearMutationError()
                            },
                            onDeleteSession = { session ->
                                deleteSessionTarget = session
                                state.clearMutationError()
                            },
                            onReview = { reviewTarget = group },
                        )
                    }
                    if (recoverableEntries.isNotEmpty() || historyState.canLoadMore) {
                        item(key = "recoverable-history") {
                            RecoverableHistorySection(
                                entries = recoverableEntries,
                                expanded = historyExpanded,
                                canLoadMore = historyState.canLoadMore,
                                loadingMore = historyState.loadingMore,
                                onToggle = { historyExpanded = !historyExpanded },
                                onOpen = { history ->
                                    scope.launch {
                                        historyState.restore(history)?.let(onOpenRestoredSession)
                                    }
                                },
                                onLoadMore = { scope.launch { historyState.loadMore() } },
                                isRestoring = historyState::isRestoring,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDirectorySection(
    group: TaskDirectoryGroup,
    directoryCount: Int,
    groupCollapsed: Boolean,
    expandedTasks: Map<String, Boolean>,
    standaloneCollapsed: Boolean,
    selectedTaskId: String?,
    selectedSessionId: String?,
    onToggleGroup: () -> Unit,
    onToggleTask: (String) -> Unit,
    onToggleStandalone: () -> Unit,
    onOpenTask: (WorkspaceTaskSummary) -> Unit,
    onOpenSession: (WorkspaceSessionSummary, WorkspaceTaskSummary?) -> Unit,
    onNewTask: () -> Unit,
    onNewWindow: (WorkspaceTaskSummary) -> Unit,
    onRename: (WorkspaceTaskSummary) -> Unit,
    onClear: (WorkspaceTaskSummary) -> Unit,
    onDelete: (WorkspaceTaskSummary) -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
    onReview: () -> Unit,
) {
    val pathCaption = directoryPathCaption(group.workspaceName, group.workspaceCwd)
    val canCollapseDirectory = showsDirectoryDisclosure(directoryCount)
    val groupExpanded = isDirectoryExpanded(groupCollapsed, directoryCount)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WandColors.bgElevated.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(WandColors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(WandIcons.folder, contentDescription = null, tint = WandColors.brand, modifier = Modifier.size(13.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (canCollapseDirectory) Modifier.clickable(onClick = onToggleGroup) else Modifier,
                    )
                    .padding(start = 8.dp, top = 7.dp, bottom = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.workspaceName.ifEmpty { "任务目录" },
                        style = MaterialTheme.typography.labelLarge,
                        color = WandColors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (group.synthetic) {
                        Text(
                            "未归档",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (pathCaption != null) {
                    Text(
                        pathCaption,
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canCollapseDirectory) {
                TreeDisclosureCaret(
                    expanded = groupExpanded,
                    contentDescription = if (groupExpanded) "收起目录" else "展开目录",
                    onClick = onToggleGroup,
                )
            }
            Text(
                "${group.tasks.size} 任务",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
            )
            if (group.tasks.any { it.worktree != null }) {
                WandIconButton(
                    icon = WandIcons.commit,
                    contentDescription = "审查 Worktree",
                    onClick = onReview,
                    variant = WandIconButtonVariant.Compact,
                )
            }
            WandIconButton(
                icon = WandIcons.add,
                contentDescription = "在 ${group.workspaceName} 新建任务",
                onClick = onNewTask,
                variant = WandIconButtonVariant.Compact,
            )
        }
        if (groupExpanded) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 6.dp, bottom = 8.dp)) {
                group.tasks.forEach { task ->
                    TaskAggregateRow(
                        task = task,
                        parentNames = listOf(group.workspaceName),
                        expanded = isTaskSessionsExpanded(
                            userCollapsed = expandedTasks[task.id] == false,
                            sessionCount = task.totalSessions,
                        ),
                        selected = task.id == selectedTaskId,
                        selectedSessionId = selectedSessionId,
                        onToggle = { onToggleTask(task.id) },
                        onOpen = { onOpenTask(task) },
                        onOpenSession = { onOpenSession(it, task) },
                        onNewWindow = { onNewWindow(task) },
                        onRename = { onRename(task) },
                        onClear = { onClear(task) },
                        onDelete = { onDelete(task) },
                        onDeleteSession = onDeleteSession,
                    )
                }
                if (group.tasks.isEmpty()) {
                    Text(
                        "这个目录还没有任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
                if (group.standaloneSessions.isNotEmpty()) {
                    StandaloneSessionSection(
                        sessions = group.standaloneSessions,
                        parentNames = listOf(group.workspaceName),
                        expanded = !standaloneCollapsed,
                        selectedSessionId = selectedSessionId,
                        onToggle = onToggleStandalone,
                        onOpen = { onOpenSession(it, null) },
                        onDelete = onDeleteSession,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskAggregateRow(
    task: WorkspaceTaskSummary,
    parentNames: Collection<String>,
    expanded: Boolean,
    selected: Boolean,
    selectedSessionId: String?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenSession: (WorkspaceSessionSummary) -> Unit,
    onNewWindow: () -> Unit,
    onRename: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .wandSelectedSurface(
                selected = selected,
                shape = RoundedCornerShape(9.dp),
                unselectedFill = WandColors.bgPrimary.copy(alpha = 0.28f),
                showUnselectedBorder = false,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                task.name.ifEmpty { "未命名任务" },
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(start = 2.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            )
            if (task.isIsolated) {
                Icon(
                    WandIcons.commit,
                    contentDescription = "隔离 worktree",
                    tint = WandColors.success,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (showsTaskSessionDisclosure(task.totalSessions)) {
                TreeDisclosureCaret(
                    expanded = expanded,
                    contentDescription = if (expanded) "收起终端" else "展开终端",
                    label = task.totalSessions.toString(),
                    onClick = onToggle,
                )
            }
            WandIconButton(
                icon = WandIcons.add,
                contentDescription = "在 ${task.name} 新建工作窗口",
                onClick = onNewWindow,
                variant = WandIconButtonVariant.Compact,
            )
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "任务操作",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Compact,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    DropdownMenuItem(
                        text = { Text("打开任务") },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    if (task.totalSessions > 0) {
                        DropdownMenuItem(
                            text = { Text("清空会话", color = WandColors.danger) },
                            onClick = { menuOpen = false; onClear() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除", color = WandColors.danger) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        if (expanded) {
            if (task.sessions.isEmpty()) {
                Text(
                    "还没有终端。点右侧「＋」新建。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .padding(start = 10.dp, end = 2.dp, bottom = 4.dp)
                        .fillMaxWidth(),
                ) {
                    task.sessions.forEachIndexed { index, session ->
                        AggregateSessionRow(
                            session = session,
                            label = listSessionLabel(session, index, parentNames + task.name),
                            selected = session.id == selectedSessionId,
                            onClick = { onOpenSession(session) },
                            onDelete = { onDeleteSession(session) },
                        )
                    }
                    if (task.totalSessions > task.sessions.size) {
                        Text(
                            "列表仅显示 ${task.sessions.size}/${task.totalSessions} 个会话，" +
                                "打开任务可查看全部。",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            modifier = Modifier.clickable(onClick = onOpen).padding(start = 8.dp, end = 12.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandaloneSessionSection(
    sessions: List<WorkspaceSessionSummary>,
    parentNames: Collection<String>,
    expanded: Boolean,
    selectedSessionId: String?,
    onToggle: () -> Unit,
    onOpen: (WorkspaceSessionSummary) -> Unit,
    onDelete: (WorkspaceSessionSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${sessions.size} 个未分组终端",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            TreeDisclosureCaret(
                expanded = expanded,
                contentDescription = if (expanded) "收起未分组终端" else "展开未分组终端",
                onClick = onToggle,
            )
        }
        if (expanded) {
            sessions.forEachIndexed { index, session ->
                AggregateSessionRow(
                    session = session,
                    label = listSessionLabel(session, index, parentNames),
                    selected = session.id == selectedSessionId,
                    onClick = { onOpen(session) },
                    onDelete = { onDelete(session) },
                )
            }
        }
    }
}

@Composable
private fun AggregateSessionRow(
    session: WorkspaceSessionSummary,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandSelectedRow(
                selected = selected,
                shape = RoundedCornerShape(8.dp),
                contentInset = true,
            )
            .padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(session.status ?: "idle", modifier = Modifier.size(7.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(workspaceProviderLabel(session.provider))
                        if (session.sessionKind == "pty") append(" · 终端")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    maxLines = 1,
                )
            }
        }
        WandIconButton(
            icon = WandIcons.delete,
            contentDescription = "删除终端 $label",
            onClick = onDelete,
            variant = WandIconButtonVariant.Quiet,
        )
    }
}

@Composable
private fun RecoverableHistorySection(
    entries: List<SessionListEntry.Recoverable>,
    expanded: Boolean,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onToggle: () -> Unit,
    onOpen: (HistorySession) -> Unit,
    onLoadMore: () -> Unit,
    isRestoring: (HistorySession) -> Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(WandColors.bgElevated.copy(alpha = 0.62f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                WandIcons.history,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "可恢复历史（${entries.size}）",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
                modifier = Modifier.weight(1f).padding(start = 9.dp),
            )
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起历史" else "展开历史",
                tint = WandColors.textMuted,
                modifier = Modifier.size(17.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }
        if (expanded) {
            entries.forEach { entry ->
                val history = entry.history
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(
                        enabled = !isRestoring(history),
                        onClick = { onOpen(history) },
                    ).padding(start = 42.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            history.firstUserMessage.ifBlank { "空会话" },
                            style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${workspaceProviderLabel(history.provider)} · ${history.cwd}",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isRestoring(history)) {
                        Text("恢复中…", style = MaterialTheme.typography.labelSmall, color = WandColors.brand)
                    } else {
                        Icon(
                            WandIcons.chevronRight,
                            contentDescription = "恢复会话",
                            tint = WandColors.textMuted,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            if (canLoadMore) {
                WandButton(
                    label = if (loadingMore) "加载中…" else "加载更多历史",
                    onClick = onLoadMore,
                    enabled = !loadingMore,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TreeDisclosureCaret(
    expanded: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
            )
        }
        Icon(
            WandIcons.expand,
            contentDescription = contentDescription,
            tint = WandColors.textMuted,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 0f else -90f },
        )
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(WandColors.dangerSoft).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = WandColors.danger,
            modifier = Modifier.weight(1f),
        )
        Text(
            "重试",
            style = MaterialTheme.typography.labelMedium,
            color = WandColors.danger,
            modifier = Modifier.clickable(onClick = onRetry).padding(6.dp),
        )
    }
}

internal fun taskSessionRoute(
    session: WorkspaceSessionSummary,
    group: TaskDirectoryGroup,
    task: WorkspaceTaskSummary?,
): TaskSessionRoute = TaskSessionRoute(
    sessionId = session.id,
    structured = session.isStructuredSession(),
    workspaceId = task?.let { group.workspaceId },
    taskId = task?.id,
    workspaceName = task?.let { group.workspaceName },
    taskName = task?.name,
)

private fun WorkspaceSessionSummary.isStructuredSession(): Boolean =
    sessionKind == "structured" || runner == "structured"

private fun TaskDirectoryGroup.asWorkspace(): Workspace = Workspace(
    id = workspaceId,
    name = workspaceName,
    cwd = workspaceCwd,
    defaultProvider = null,
    layout = null,
    createdAt = null,
    lastOpenedAt = null,
    worktreeCount = tasks.count { it.worktree != null },
)
