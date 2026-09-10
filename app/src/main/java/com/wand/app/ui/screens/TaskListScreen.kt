package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.DirectoryListing
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
import com.wand.app.data.activityStatus
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.withLiveTitle
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.wandSelectedRow
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
    var newTaskOpen by remember { mutableStateOf(false) }
    var taskCwdDraft by remember { mutableStateOf("") }
    var newTaskWorkspaceId by remember { mutableStateOf<String?>(null) }
    var newTaskTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
    var newTaskKind by remember { mutableStateOf(WorkspaceSessionKind.Structured) }
    var directoryPickerOpen by remember { mutableStateOf(false) }
    var directoryPickerPath by remember { mutableStateOf("") }
    var directoryListing by remember { mutableStateOf<DirectoryListing?>(null) }
    var directoryLoading by remember { mutableStateOf(false) }
    var directoryError by remember { mutableStateOf<String?>(null) }
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
    val visibleGroups = directoryTreeGroups(state.groups)
    val hasVisibleContent = visibleGroups.isNotEmpty() || recoverableEntries.isNotEmpty()
    val directoryGroupCount = visibleGroups.size
    val metrics = taskListMetrics(visibleGroups)

    fun normalizedPath(value: String): String = value.trim().replace(Regex("/+$"), "").ifEmpty { "/" }

    fun workspaceForPath(value: String): String? {
        val normalized = normalizedPath(value)
        return state.groups.asSequence()
            .filter { !it.synthetic }
            .firstOrNull { normalizedPath(it.workspaceCwd) == normalized }
            ?.workspaceId
    }

    fun updateTaskCwd(value: String) {
        taskCwdDraft = value
        // 手动改目录后，已选项目只有在目录仍匹配时才保留；不匹配就退回独立任务。
        if (newTaskWorkspaceId != null && workspaceForPath(value) != newTaskWorkspaceId) {
            newTaskWorkspaceId = null
        }
        state.clearMutationError()
    }

    fun beginNewTask(initialCwd: String? = null, workspaceId: String? = null) {
        if (!interactionEnabled) return
        state.clearMutationError()
        taskCwdDraft = initialCwd.orEmpty()
        newTaskWorkspaceId = workspaceId
        newTaskTarget = WorkspaceSessionTarget.Claude
        newTaskKind = WorkspaceSessionKind.Structured
        newTaskOpen = true
        scope.launch {
            state.loadCreationDefaults()
            if (taskCwdDraft.isBlank()) {
                taskCwdDraft = state.defaultCwd.orEmpty()
            }
            newTaskTarget = WorkspaceSessionTarget.fromRaw(state.defaultProvider) ?: newTaskTarget
            newTaskKind = state.defaultSessionKind
        }
    }

    fun openDirectoryPicker() {
        directoryPickerPath = taskCwdDraft.trim().ifEmpty {
            state.defaultCwd?.trim().takeUnless { it.isNullOrEmpty() }
                ?: state.recentPaths.firstOrNull()?.path.orEmpty()
        }.ifEmpty { "/" }
        directoryPickerOpen = true
        directoryLoading = true
        directoryError = null
        scope.launch {
            try {
                directoryListing = api.listDirectory(directoryPickerPath)
            } catch (error: Exception) {
                directoryError = error.message ?: "无法读取目录"
            } finally {
                directoryLoading = false
            }
        }
    }

    fun browseDirectory(path: String) {
        directoryPickerPath = path
        directoryLoading = true
        directoryError = null
        scope.launch {
            try {
                directoryListing = api.listDirectory(path)
            } catch (error: Exception) {
                directoryError = error.message ?: "无法读取目录"
            } finally {
                directoryLoading = false
            }
        }
    }

    fun parentDirectory(path: String): String {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/').ifEmpty { "/" }
    }

    LaunchedEffect(state.newTaskRequest) {
        if (state.consumeNewTaskRequest()) beginNewTask()
    }
    LaunchedEffect(selectedTaskId, selectedSessionId, visibleGroups) {
        state.expandPathToSelection(selectedTaskId, selectedSessionId)
    }

    if (newTaskOpen) {
        val cwd = taskCwdDraft.trim()
        val matchingProjects = state.groups
            .filter { !it.synthetic && normalizedPath(it.workspaceCwd) == normalizedPath(cwd) }
        val selectedProject = matchingProjects.firstOrNull { it.workspaceId == newTaskWorkspaceId }
        WandDialog(
            title = "新建任务",
            onDismissRequest = { if (!state.mutationBusy) newTaskOpen = false },
            icon = WandIcons.add,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "创建中…" else "创建任务",
                enabled = !state.mutationBusy && cwd.isNotEmpty(),
                onClick = {
                    if (state.mutationBusy || cwd.isEmpty()) return@WandDialogAction
                    scope.launch {
                        state.rememberCreationChoice(
                            defaultProvider = newTaskTarget.raw.takeUnless { newTaskTarget.isShell },
                            defaultSessionKind = newTaskKind,
                        )
                        val result = state.createTask(
                            name = "",
                            cwd = cwd,
                            worktree = newTaskWorkspaceId != null && state.defaultTaskWorktree,
                            workspaceId = newTaskWorkspaceId,
                        )
                        if (result != null) {
                            newTaskOpen = false
                            val snapshot = state.createTaskWindow(result.task.id, newTaskTarget, newTaskKind)
                            if (snapshot != null) {
                                onOpenSession(
                                    TaskSessionRoute(
                                        sessionId = snapshot.id,
                                        structured = snapshot.isStructured,
                                        workspaceId = result.workspace.id,
                                        taskId = result.task.id,
                                        workspaceName = result.workspace.name,
                                        taskName = result.task.name,
                                    ),
                                )
                            } else {
                                onOpenTask(
                                    result.workspace.id,
                                    result.task.id,
                                    result.workspace.name,
                                    result.task.name,
                                )
                            }
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
            Text(
                "先选工作目录，再决定是否归入已有项目。任务名称由系统自动生成。",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WandColors.surfaceSoft.copy(alpha = 0.58f))
                    .border(1.dp, WandColors.border.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
                    .clickable(enabled = !state.mutationBusy) { openDirectoryPicker() }
                    .padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(WandColors.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(WandIcons.folder, contentDescription = null, tint = WandColors.brand)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        if (cwd.isEmpty()) "选择工作目录" else "工作目录",
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.textMuted,
                    )
                    Text(
                        if (cwd.isEmpty()) "点击浏览服务器上的目录" else cwd,
                        style = if (cwd.isEmpty()) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (cwd.isEmpty()) WandColors.textPrimary else WandColors.brand,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(WandIcons.chevronRight, contentDescription = "选择目录", tint = WandColors.textMuted)
            }
            if (state.recentPaths.isNotEmpty()) {
                Text(
                    "最近使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.recentPaths.take(3).forEach { recent ->
                        Text(
                            recent.displayName,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(WandColors.surfaceSoft)
                                .clickable(enabled = !state.mutationBusy) { updateTaskCwd(recent.path) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                if (cwd.isEmpty()) "必须选择目录才能创建任务" else "任务会在此目录下显示在目录树中",
                style = MaterialTheme.typography.labelSmall,
                color = if (cwd.isEmpty()) WandColors.danger else WandColors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "项目归属（可选）",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            if (matchingProjects.isEmpty()) {
                Text(
                    "此目录没有已绑定项目，将创建独立任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textMuted,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val independentSelected = newTaskWorkspaceId == null
                    CreationChoiceCard(
                        title = "独立任务",
                        subtitle = "按目录归类",
                        selected = independentSelected,
                        enabled = !state.mutationBusy,
                        icon = WandIcons.folder,
                        onClick = { newTaskWorkspaceId = null },
                        modifier = Modifier.weight(1f),
                    )
                    matchingProjects.forEach { project ->
                        CreationChoiceCard(
                            title = project.workspaceName,
                            subtitle = "已有项目",
                            selected = selectedProject?.workspaceId == project.workspaceId,
                            enabled = !state.mutationBusy,
                            icon = WandIcons.folder,
                            onClick = { newTaskWorkspaceId = project.workspaceId },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Text(
                "首次打开的工具",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            WorkspaceSessionTarget.OPTIONS.chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    rowOptions.forEach { option ->
                        val selected = newTaskTarget == option
                        val logoProvider = option.raw.takeUnless { option.isShell }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) WandColors.brandSoft else WandColors.surfaceSoft.copy(alpha = 0.62f))
                                .border(
                                    1.dp,
                                    if (selected) WandColors.brand.copy(alpha = 0.7f) else WandColors.border.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable(enabled = !state.mutationBusy) {
                                    newTaskTarget = option
                                    if (!option.isShell) state.rememberCreationChoice(defaultProvider = option.raw)
                                }
                                .padding(horizontal = 9.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = BrandLogos.painterForProvider(logoProvider),
                                contentDescription = null,
                                modifier = Modifier.size(21.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    BrandLogos.tintForProvider(logoProvider, WandColors.textPrimary),
                                ),
                            )
                            Text(
                                option.label,
                                modifier = Modifier.padding(start = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) WandColors.brand else WandColors.textPrimary,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (!newTaskTarget.isShell) {
                Text(
                    "运行方式",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    WorkspaceSessionKind.entries.forEach { option ->
                        val selected = newTaskKind == option
                        CreationChoiceCard(
                            title = option.label,
                            subtitle = option.description,
                            selected = selected,
                            enabled = !state.mutationBusy,
                            icon = if (option == WorkspaceSessionKind.Structured) WandIcons.chat else WandIcons.terminal,
                            onClick = {
                                newTaskKind = option
                                state.rememberCreationChoice(defaultSessionKind = option)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
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

    if (directoryPickerOpen) {
        WandBottomSheet(
            onDismissRequest = { if (!state.mutationBusy) directoryPickerOpen = false },
            modifier = Modifier.navigationBarsPadding(),
            sheetState = targetSheetState,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择工作目录", style = MaterialTheme.typography.titleLarge, color = WandColors.textPrimary)
                    Text(
                        directoryPickerPath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { directoryPickerOpen = false }) { Text("关闭") }
            }
            directoryError?.let {
                Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp))
            }
            if (directoryLoading) {
                Text("读取中…", color = WandColors.textMuted, modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(horizontal = 12.dp)) {
                    if (directoryPickerPath != "/") {
                        item {
                            DirectoryPickerRow("返回上一级", parentDirectory(directoryPickerPath), WandIcons.chevronRight) {
                                browseDirectory(parentDirectory(directoryPickerPath))
                            }
                        }
                    }
                    items(directoryListing?.items.orEmpty().filter { it.isDirectory }, key = { it.path }) { item ->
                        DirectoryPickerRow(item.name, item.path, WandIcons.folder) { browseDirectory(item.path) }
                    }
                }
            }
            TextButton(
                onClick = {
                    updateTaskCwd(directoryPickerPath)
                    directoryPickerOpen = false
                },
                enabled = !directoryLoading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("选择此目录")
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
        val label = listSessionLabel(session.withLiveTitle(), 0)
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
                                    workspaceId = task.task.workspaceId,
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
        modifier = modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary)
            // HomeActivity uses transparent edge-to-edge system bars. Consume the top inset
            // here so the dashboard chrome never sits underneath the clock/camera cutout.
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AmbientBackground(Modifier.fillMaxSize())
            when {
                state.loading && !hasVisibleContent -> LoadingState(text = "正在加载任务…")
                state.loadError != null && !hasVisibleContent -> ErrorState(
                    message = state.loadError ?: "无法加载任务列表",
                    onRetry = { scope.launch { state.load() } },
                )
                !hasVisibleContent && !historyState.canLoadMore -> Column(Modifier.fillMaxSize()) {
                    HomeOverviewCard(
                        serverDisplayName = serverDisplayName,
                        directoryCount = 0,
                        taskCount = 0,
                        sessionCount = 0,
                        onNewTask = { beginNewTask() },
                        interactionEnabled = interactionEnabled,
                        onRefresh = {
                            scope.launch {
                                state.load(silent = true)
                                historyState.load(silent = true)
                            }
                        },
                        onOpenSettings = onOpenSettings,
                        onOpenWeb = onOpenWeb,
                        onSwitchServer = onSwitchServer,
                        onCollapseSidebar = onCollapseSidebar,
                    )
                    EmptyState(
                        modifier = Modifier.weight(1f),
                        icon = WandIcons.todo,
                        title = "还没有任务",
                        subtitle = "新建任务并选择目录，之后的会话都会归属于该任务。",
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "overview") {
                        HomeOverviewCard(
                            serverDisplayName = serverDisplayName,
                            directoryCount = metrics.directoryCount,
                            taskCount = metrics.taskCount,
                            sessionCount = metrics.sessionCount,
                            onNewTask = { beginNewTask() },
                            interactionEnabled = interactionEnabled,
                            onRefresh = {
                                scope.launch {
                                    state.load(silent = true)
                                    historyState.load(silent = true)
                                }
                            },
                            onOpenSettings = onOpenSettings,
                            onOpenWeb = onOpenWeb,
                            onSwitchServer = onSwitchServer,
                            onCollapseSidebar = onCollapseSidebar,
                        )
                    }
                    state.loadError?.let { message ->
                        item(key = "task-load-error") {
                            InlineError(message = message, onRetry = { scope.launch { state.load() } })
                        }
                    }
                    items(visibleGroups, key = { "group-${it.id}" }) { group ->
                        TaskDirectorySection(
                            group = group,
                            directoryCount = directoryGroupCount,
                            groupCollapsed = state.isDirectoryCollapsed(group.id),
                            taskCollapsed = state::isTaskCollapsed,
                            standaloneCollapsed = state.isStandaloneCollapsed(group.id),
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                            onToggleGroup = { state.toggleDirectory(group.id) },
                            onToggleTask = state::toggleTask,
                            onToggleStandalone = { state.toggleStandalone(group.id) },
                            onOpenTask = { task ->
                                onOpenTask(task.task.workspaceId, task.id, group.workspaceName, task.name)
                            },
                            onOpenSession = { session, task ->
                                onOpenSession(taskSessionRoute(session, group, task))
                            },
                            onNewTask = { beginNewTask(group.workspaceCwd, group.workspaceId.takeUnless { group.synthetic }) },
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
                                expanded = state.historyExpanded,
                                canLoadMore = historyState.canLoadMore,
                                loadingMore = historyState.loadingMore,
                                onToggle = state::toggleHistory,
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
private fun HomeOverviewCard(
    serverDisplayName: String,
    directoryCount: Int,
    taskCount: Int,
    sessionCount: Int,
    onNewTask: () -> Unit,
    interactionEnabled: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        containerColor = WandColors.surface.copy(alpha = 0.86f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WandBrandMark(size = 36)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        "工作台",
                        style = MaterialTheme.typography.titleMedium,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusDot("running", modifier = Modifier.size(7.dp))
                    Text(
                        "已连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.success,
                    )
                }
                Text(
                    serverDisplayName.ifBlank { "当前服务器" },
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (onCollapseSidebar != null) {
                WandIconButton(
                    icon = WandIcons.panelCollapse,
                    contentDescription = "收起任务侧边栏",
                    onClick = onCollapseSidebar,
                    variant = WandIconButtonVariant.Toolbar,
                )
            }
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "更多选项",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Toolbar,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    DropdownMenuItem(
                        text = { Text("刷新任务") },
                        leadingIcon = { Icon(WandIcons.refresh, contentDescription = null) },
                        onClick = { menuOpen = false; onRefresh() },
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
        }
        WandButton(
            label = "新建任务",
            onClick = onNewTask,
            enabled = interactionEnabled,
            icon = WandIcons.add,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeOverviewMetric("目录", directoryCount, Modifier.weight(1f))
            HomeOverviewMetric("任务", taskCount, Modifier.weight(1f))
            HomeOverviewMetric("窗口", sessionCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeOverviewMetric(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(WandColors.surfaceSoft.copy(alpha = 0.48f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = WandColors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = WandColors.textMuted,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun TaskDirectorySection(
    group: TaskDirectoryGroup,
    directoryCount: Int,
    groupCollapsed: Boolean,
    taskCollapsed: (String) -> Boolean,
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
    val canCollapseDirectory = showsDirectoryDisclosure(directoryCount)
    val groupExpanded = isDirectoryExpanded(groupCollapsed, directoryCount)
    val reduceMotion = reduceMotionEnabled()
    val sessionTotal = directoryGroupSessionTotal(group)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canCollapseDirectory) Modifier.clickable(onClick = onToggleGroup) else Modifier,
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(WandColors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    WandIcons.folder,
                    contentDescription = null,
                    tint = WandColors.brand,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.size(9.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.workspaceName.ifEmpty { "任务目录" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (groupHasLiveActivity(group)) {
                        StatusDot("running", modifier = Modifier.padding(start = 7.dp).size(7.dp))
                    }
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
                directoryGroupMetaLabel(group.tasks.size, sessionTotal),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
            if (group.tasks.any { it.worktree != null }) {
                WandIconButton(
                    icon = WandIcons.commit,
                    contentDescription = "审查 Worktree",
                    onClick = onReview,
                    variant = WandIconButtonVariant.Compact,
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNewTask),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(WandColors.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        WandIcons.add,
                        contentDescription = "在 ${group.workspaceName} 新建任务",
                        tint = WandColors.brand,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = groupExpanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(
                    animationSpec = WandMotion.tweenEnter(),
                    expandFrom = Alignment.Top,
                ) + fadeIn(WandMotion.tweenEnter())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(
                    animationSpec = WandMotion.tweenExit(),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(WandMotion.tweenExit())
            },
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 25.dp, end = 2.dp, top = 1.dp, bottom = 4.dp)
                    .fillMaxWidth(),
            ) {
                orderedTaskSummaries(group.tasks).forEach { task ->
                    TaskAggregateRow(
                        task = task,
                        parentNames = listOf(group.workspaceName),
                        expanded = isTaskSessionsExpanded(
                            userCollapsed = taskCollapsed(task.id),
                            sessionCount = task.totalSessions,
                        ),
                        selected = isTaskRowSelected(
                            taskId = task.id,
                            visibleSessionIds = task.sessions.map { it.id },
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                        ),
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
    val reduceMotion = reduceMotionEnabled()
    val done = task.status == com.wand.app.data.WorkspaceTaskStatus.Done
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (done) Modifier.graphicsLayer { alpha = 0.76f } else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .wandSelectedRow(
                    selected = selected,
                    shape = RoundedCornerShape(8.dp),
                )
                .clickableWithoutRipple(onClick = onOpen)
                .padding(end = 2.dp),
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
                    .padding(top = 8.dp, bottom = 8.dp, end = 6.dp),
            )
            Text(
                if (task.status == com.wand.app.data.WorkspaceTaskStatus.Done) "已完成" else "进行中",
                style = MaterialTheme.typography.labelSmall,
                color = if (task.status == com.wand.app.data.WorkspaceTaskStatus.Done) {
                    WandColors.textMuted
                } else {
                    WandColors.success
                },
                modifier = Modifier.padding(end = 4.dp),
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
                        text = { Text("新建工作窗口") },
                        leadingIcon = { Icon(WandIcons.add, contentDescription = null) },
                        onClick = { menuOpen = false; onNewWindow() },
                    )
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
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(
                    animationSpec = WandMotion.tweenEnter(),
                    expandFrom = Alignment.Top,
                ) + fadeIn(WandMotion.tweenEnter())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(
                    animationSpec = WandMotion.tweenExit(),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(WandMotion.tweenExit())
            },
        ) {
            if (task.sessions.isEmpty()) {
                Text(
                    "还没有终端。点右侧「＋」新建。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
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
                            label = listSessionLabel(session.withLiveTitle(), index, parentNames + task.name),
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
                .padding(vertical = 8.dp),
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
                    label = listSessionLabel(session.withLiveTitle(), index, parentNames),
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
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandSelectedRow(
                selected = selected,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickableWithoutRipple(onClick = onClick)
                .padding(top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(session.withLiveTitle().activityStatus(), modifier = Modifier.size(7.dp))
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
        Box {
            WandIconButton(
                icon = WandIcons.more,
                contentDescription = "终端操作 $label",
                onClick = { menuOpen = true },
                variant = WandIconButtonVariant.Quiet,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = WandColors.bgElevated,
            ) {
                DropdownMenuItem(
                    text = { Text("打开") },
                    onClick = { menuOpen = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text("删除终端", color = WandColors.danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
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
            Text(label, style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        Icon(
            WandIcons.expand,
            contentDescription = contentDescription,
            tint = WandColors.textMuted,
            modifier = Modifier.size(16.dp).graphicsLayer {
                rotationZ = if (expanded) 0f else -90f
            },
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
    structured = session.isStructured,
    workspaceId = task?.let { group.workspaceId },
    taskId = task?.id,
    workspaceName = task?.let { group.workspaceName },
    taskName = task?.name,
)

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

@Composable
private fun CreationChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) WandColors.brandSoft else WandColors.surfaceSoft.copy(alpha = 0.62f))
            .border(
                1.dp,
                if (selected) WandColors.brand.copy(alpha = 0.7f) else WandColors.border.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) WandColors.brand else WandColors.textMuted)
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) WandColors.brand else WandColors.textPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = WandColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun DirectoryPickerRow(
    title: String,
    path: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = WandColors.brand, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = WandColors.textPrimary)
            if (path != title) {
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(WandIcons.chevronRight, contentDescription = null, tint = WandColors.textMuted)
    }
}
