package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.DirectoryListing
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.Workspace
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.raw
import com.wand.app.ui.withLiveTitle
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandChoiceStrip
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.rememberWandDragReorderState
import com.wand.app.ui.components.wandLongPressDrag
import com.wand.app.ui.components.itemLiftModifier
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.launch

/** One visual unit per decision in the new-task form. */
@Composable
private fun NewTaskFormCard(
    number: String,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(WandColors.surface)
            .border(1.dp, WandColors.border.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(number, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = WandColors.brand)
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = WandColors.textPrimary)
        }
        content()
    }
}

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
 * Android task-first root. Directory is grouping metadata, named tasks are optional containers,
 * and ungrouped sessions render under the directory's standalone section.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TaskListScreen(
    state: TaskListState,
    api: WorkspacePort,
    boardApi: TaskBoardPort,
    serverDisplayName: String,
    modifier: Modifier = Modifier,
    homeListMode: HomeListMode = HomeListMode.Sessions,
    onHomeListModeChange: (HomeListMode) -> Unit = {},
    selectedTaskId: String? = null,
    selectedSessionId: String? = null,
    interactionEnabled: Boolean = true,
    onOpenTask: (workspaceId: String, taskId: String, workspaceName: String, taskName: String) -> Unit,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenBoardSession: (sessionId: String, isStructured: Boolean) -> Unit = { _, _ -> },
    /**
     * 看板卡片点选：由上层在右侧主区（宽屏）/ 新页（窄屏）打开详情，
     * 侧栏不自己压一层详情顶栏。
     */
    onOpenBoardTaskDetail: (taskId: String) -> Unit,
    onOpenRestoredSession: (SessionSnapshot) -> Unit,
    onTaskRenamed: (taskId: String, taskName: String) -> Unit = { _, _ -> },
    onTaskClosed: (taskId: String) -> Unit = {},
    onSessionClosed: (sessionId: String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
    showComposer: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var newTaskOpen by remember { mutableStateOf(false) }
    var taskCwdDraft by remember { mutableStateOf("") }
    var newTaskWorkspaceId by remember { mutableStateOf<String?>(null) }
    var startFirstSession by remember { mutableStateOf(true) }
    var newTaskPrompt by remember { mutableStateOf("") }
    var newTaskWorktree by remember { mutableStateOf(false) }
    var newTaskName by remember { mutableStateOf("") }
    var newTaskTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
    var newTaskKind by remember { mutableStateOf(WorkspaceSessionKind.Structured) }
    var composerDraft by remember { mutableStateOf("") }
    var newTaskDraftRevision by remember { mutableLongStateOf(0L) }
    var targetDraftRevision by remember { mutableLongStateOf(0L) }
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
    var renameDirectoryTarget by remember { mutableStateOf<TaskDirectoryGroup?>(null) }
    var renameDirectoryDraft by remember { mutableStateOf("") }
    var clearTarget by remember { mutableStateOf<WorkspaceTaskSummary?>(null) }
    var archiveTarget by remember { mutableStateOf<WorkspaceTaskSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceTaskSummary?>(null) }
    var deleteSessionTarget by remember { mutableStateOf<WorkspaceSessionSummary?>(null) }
    var moveSessionTarget by remember { mutableStateOf<WorkspaceSessionSummary?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf(setOf<String>()) }
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var confirmManagedAction by remember { mutableStateOf(false) }
    var reviewTarget by remember { mutableStateOf<TaskDirectoryGroup?>(null) }
    var deleteDirectoryTarget by remember { mutableStateOf<TaskDirectoryGroup?>(null) }
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val dragState = rememberWandDragReorderState()
    val targetSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val reduceMotion = reduceMotionEnabled()
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val allGroups = directoryTreeGroups(state.groups)
    var attentionOnly by remember { mutableStateOf(false) }
    // 过滤顺序：先按查询词收窄，再按「只看等你」收窄。
    val searchedGroups = homeSearchGroups(allGroups, searchQuery)
    val visibleGroups = if (attentionOnly) attentionOnlyGroups(searchedGroups) else searchedGroups
    val searching = searchQuery.isNotBlank()
    val overview = homeOverview(allGroups)
    val showingBoard = homeListMode == HomeListMode.Tasks
    // 首页的时间是「几分钟前」这种相对说法，30 秒推一次就够，不必每秒重排整页。
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val managedSelection = SidebarManageSelection(selectedTaskIds, selectedSessionIds)
    val resolvedManagedAction = resolveManagedAction(managedSelection, visibleGroups)
    val hasVisibleContent = visibleGroups.isNotEmpty()
    val hasAnyContent = allGroups.isNotEmpty()
    var boardRefreshNonce by remember { mutableStateOf(0) }

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
        newTaskWorkspaceId = workspaceForPath(value)
        state.clearMutationError()
    }

    fun beginNewTask(
        initialCwd: String? = null,
        workspaceId: String? = null,
        initialPrompt: String = "",
    ) {
        if (!interactionEnabled) return
        state.clearMutationError()
        taskCwdDraft = initialCwd.orEmpty()
        newTaskWorkspaceId = workspaceId
        startFirstSession = true
        newTaskPrompt = initialPrompt
        newTaskWorktree = false
        newTaskName = ""
        newTaskTarget = WorkspaceSessionTarget.Claude
        newTaskKind = WorkspaceSessionKind.Structured
        newTaskDraftRevision += 1
        val defaultsRevision = newTaskDraftRevision
        newTaskOpen = true
        scope.launch {
            state.loadCreationDefaults()
            if (!newTaskOpen || defaultsRevision != newTaskDraftRevision) return@launch
            if (taskCwdDraft.isBlank()) {
                taskCwdDraft = state.defaultCwd.orEmpty()
                newTaskWorkspaceId = workspaceForPath(taskCwdDraft)
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

    // 展开组件必须有自己的关闭路径：搜索展开时按返回键先收搜索，而不是退出页面。
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        searchQuery = ""
    }
    LaunchedEffect(state.newTaskRequest) {
        if (state.consumeNewTaskRequest()) beginNewTask()
    }
    LaunchedEffect(selectedTaskId, selectedSessionId, visibleGroups) {
        state.expandPathToSelection(selectedTaskId, selectedSessionId)
    }

    if (newTaskOpen) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val cwd = taskCwdDraft.trim()
        val groupedName = newTaskName.trim()
        // 任务身份不再依赖首个会话提示词：名称留空时由提示词自动命名。
        val taskPrompt = if (!startFirstSession || newTaskTarget.isShell) {
            null
        } else {
            newTaskPrompt.trim().ifEmpty { null }
        }
        val canCreateTask = cwd.isNotEmpty() && TaskListState.isValidOptionalTaskName(groupedName) &&
            (groupedName.isNotEmpty() || taskPrompt != null)
        WandDialog(
            title = "新建任务",
            icon = WandIcons.add,
            onDismissRequest = { if (!state.mutationBusy) newTaskOpen = false },
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "创建中…" else if (startFirstSession) "创建并启动会话" else "创建任务",
                enabled = !state.mutationBusy && canCreateTask,
                onClick = {
                    if (state.mutationBusy || cwd.isEmpty()) return@WandDialogAction
                    if (!canCreateTask) return@WandDialogAction
                    // Freeze the submitted choices before any network suspension.
                    val submittedTarget = newTaskTarget
                    val submittedKind = newTaskKind
                    newTaskDraftRevision += 1
                    scope.launch {
                        state.rememberCreationChoice(
                            defaultProvider = submittedTarget.raw.takeUnless { submittedTarget.isShell },
                            defaultSessionKind = submittedKind,
                        )
                        val result = state.createTask(
                            name = groupedName,
                            cwd = cwd,
                            worktree = newTaskWorktree,
                            workspaceId = newTaskWorkspaceId,
                            description = taskPrompt,
                        )
                        if (result != null) {
                            newTaskOpen = false
                            val snapshot = if (startFirstSession) state.createTaskWindow(
                                result.task.id, submittedTarget, submittedKind, taskPrompt,
                            ) else null
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
                                if (startFirstSession && state.mutationError != null) {
                                    android.widget.Toast.makeText(context,
                                        "任务已创建，但启动会话失败：${state.mutationError}", android.widget.Toast.LENGTH_LONG).show()
                                }
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
                "任务与看板同步；选择工作目录，再决定是否立即启动会话。",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textMuted,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            NewTaskFormCard("01", if (startFirstSession && !newTaskTarget.isShell) "任务名称 · 可选" else "任务名称 · 必填") {
                WandTextField(
                    value = newTaskName,
                    onValueChange = { newTaskName = it; state.clearMutationError() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (startFirstSession && !newTaskTarget.isShell) "留空按提示词自动命名" else "输入任务名称",
                    singleLine = true,
                    enabled = !state.mutationBusy,
                )
            }
            NewTaskFormCard("02", "工作目录", modifier = Modifier.padding(top = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(WandColors.surfaceSoft.copy(alpha = 0.58f))
                    .border(1.dp, WandColors.border.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !state.mutationBusy) { openDirectoryPicker() }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.recentPaths.take(3).forEach { recent ->
                        Text(
                            recent.displayName,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (cwd == recent.path) WandColors.brandSoft else WandColors.surfaceSoft)
                                .clickable(enabled = !state.mutationBusy) { updateTaskCwd(recent.path) }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cwd == recent.path) WandColors.brand else WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (cwd.isEmpty()) {
                Text(
                    "必须选择目录才能创建",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            }
            NewTaskFormCard("03", "创建后", modifier = Modifier.padding(top = 12.dp)) {
                WandChoiceStrip(options = listOf(true to "启动会话", false to "仅建分组"), selected = startFirstSession,
                    onSelect = { if (!state.mutationBusy) startFirstSession = it }, minHeight = 44.dp, flat = true)
                Text(
                    if (startFirstSession) "创建任务后立即打开第一个会话" else "仅创建分组，之后再添加会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                )
            }
            if (startFirstSession) {
                NewTaskFormCard("04", "首次会话", modifier = Modifier.padding(top = 12.dp)) {
                if (!newTaskTarget.isShell) {
                    WandTextField(value = newTaskPrompt, onValueChange = { newTaskPrompt = it },
                        label = "首个会话的提示词（可选）", placeholder = "希望 CLI 帮你完成什么？",
                        minLines = 2, enabled = !state.mutationBusy,
                        modifier = Modifier.fillMaxWidth())
                    Text("任务名称留空时按此提示词自动命名，仍可随时改名。", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                }
                Text(
                    "首次打开的工具",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    WorkspaceSessionTarget.OPTIONS.forEach { option ->
                        val selected = newTaskTarget == option
                        val logoProvider = option.raw.takeUnless { option.isShell }
                        // 新建任务面板里的 provider 胶囊：选中底 / 描边 / 文字跟其他选择控件一样过渡。
                        val choiceFill by animateColorAsState(
                            targetValue = if (selected) WandColors.brandSoft else WandColors.surfaceSoft.copy(alpha = 0.62f),
                            animationSpec = WandMotion.respectMotion(
                                !reduceMotionEnabled(),
                                WandMotion.tweenFast(),
                            ),
                            label = "newTaskTargetFill",
                        )
                        val choiceStroke by animateColorAsState(
                            targetValue = if (selected) WandColors.brand.copy(alpha = 0.7f) else WandColors.border.copy(alpha = 0.5f),
                            animationSpec = WandMotion.respectMotion(
                                !reduceMotionEnabled(),
                                WandMotion.tweenFast(),
                            ),
                            label = "newTaskTargetStroke",
                        )
                        val choiceText by animateColorAsState(
                            targetValue = if (selected) WandColors.brand else WandColors.textPrimary,
                            animationSpec = WandMotion.respectMotion(
                                !reduceMotionEnabled(),
                                WandMotion.tweenFast(),
                            ),
                            label = "newTaskTargetText",
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(choiceFill)
                                .border(
                                    1.dp,
                                    choiceStroke,
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable(enabled = !state.mutationBusy) {
                                    newTaskDraftRevision += 1
                                    newTaskTarget = option
                                    if (!option.isShell) state.rememberCreationChoice(defaultProvider = option.raw)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = BrandLogos.painterForProvider(logoProvider),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    BrandLogos.tintForProvider(logoProvider, WandColors.textPrimary),
                                ),
                            )
                            Text(
                                option.label,
                                modifier = Modifier.padding(start = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = choiceText,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!newTaskTarget.isShell) {
                    Text(
                        "会话类型",
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    WandChoiceStrip(
                        options = WorkspaceSessionKind.entries.map { it to it.label },
                        selected = newTaskKind,
                        onSelect = {
                            if (state.mutationBusy) return@WandChoiceStrip
                            newTaskDraftRevision += 1
                            newTaskKind = it
                            state.rememberCreationChoice(defaultSessionKind = it)
                        },
                        minHeight = 36.dp,
                        flat = true,
                    )
                    Text(
                        newTaskKind.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                }
            }
            var advanced by remember { mutableStateOf(false) }
            BackHandler(enabled = advanced && !state.mutationBusy) { advanced = false }
            TextButton(onClick = { advanced = !advanced }, enabled = !state.mutationBusy,
                modifier = Modifier.padding(top = 6.dp)) {
                Text(if (advanced) "收起高级选项" else "高级选项 · 独立工作树")
            }
            WandInlinePanel(visible = advanced, growFrom = Alignment.Top) {
                NewTaskFormCard("05", "工作树隔离") {
                    WandChoiceStrip(options = listOf(false to "共用工作区", true to "隔离 worktree"),
                        selected = newTaskWorktree, onSelect = { if (!state.mutationBusy) newTaskWorktree = it },
                        minHeight = 44.dp, flat = true)
                    Text(if (newTaskWorktree) "需要 Git 仓库，会创建独立工作树。" else "仅做界面分组，不创建磁盘子目录。",
                        style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                }
            }
            MutationErrorText(state.mutationError, modifier = Modifier.padding(top = 8.dp))
        }
    }

    moveSessionTarget?.let { session ->
        SessionMoveSheet(api, session.id, session.title ?: "CLI 会话",
            onDismiss = { moveSessionTarget = null },
            onMoved = { scope.launch { state.refreshAfterMutation() } })
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
            MutationErrorText(state.mutationError)
        }
    }

    renameDirectoryTarget?.let { group ->
        val name = renameDirectoryDraft.trim()
        WandDialog(
            title = "重命名目录",
            onDismissRequest = { if (!state.mutationBusy) renameDirectoryTarget = null },
            icon = WandIcons.rename,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "保存中…" else "保存",
                enabled = !state.mutationBusy && name.isNotEmpty(),
                onClick = {
                    scope.launch {
                        if (state.renameDirectory(group, name)) renameDirectoryTarget = null
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { renameDirectoryTarget = null }),
        ) {
            WandTextField(
                value = renameDirectoryDraft,
                onValueChange = { renameDirectoryDraft = it; state.clearMutationError() },
                modifier = Modifier.fillMaxWidth(),
                label = "工作区名称",
                singleLine = true,
            )
            Text(
                "只改变 Wand 里的显示名，不会重命名磁盘上的文件夹。",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textMuted,
            )
            MutationErrorText(state.mutationError)
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
                "任务分组及工作目录会保留；其中的会话及 provider 历史将被删除。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            MutationErrorText(state.mutationError)
        }
    }

    archiveTarget?.let { summary ->
        WandDialog(
            title = "归档任务？",
            onDismissRequest = { if (!state.mutationBusy) archiveTarget = null },
            icon = WandIcons.archive,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "归档中…" else "归档",
                enabled = !state.mutationBusy,
                onClick = {
                    scope.launch {
                        if (state.archiveTask(summary.id)) {
                            onTaskClosed(summary.id)
                            archiveTarget = null
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { archiveTarget = null }),
        ) {
            Text(
                "任务「${summary.name}」会从侧栏隐藏并移入任务看板的归档任务，" +
                    "终端继续运行、Worktree 保留。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            MutationErrorText(state.mutationError)
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
                if (summary.isolated) "任务「${summary.name}」、其中的会话及未被其他会话使用的独立 worktree 将被删除，此操作无法撤销。"
                else "任务「${summary.name}」及其中的会话将被删除，工作目录不受影响。此操作无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            MutationErrorText(state.mutationError)
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
            MutationErrorText(state.mutationError)
        }
    }

    if (confirmManagedAction) {
        val managedAction = describeManagedAction(resolvedManagedAction)
        val destructive = managedSelectionIsDestructive(resolvedManagedAction)
        WandDialog(
            title = "$managedAction？",
            onDismissRequest = { if (!state.mutationBusy) confirmManagedAction = false },
            icon = if (destructive) WandIcons.delete else WandIcons.archive,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "处理中…" else managedAction,
                destructive = destructive,
                enabled = !state.mutationBusy && resolvedManagedAction.count > 0,
                onClick = {
                    scope.launch {
                        // 批量里任务是归档（终端继续跑、worktree 保留），只有显式选中的终端才真删。
                        resolvedManagedAction.taskIds.forEach { taskId ->
                            if (state.archiveTask(taskId)) onTaskClosed(taskId)
                        }
                        if (resolvedManagedAction.sessionIds.isNotEmpty()) {
                            val deleted = state.deleteSessions(resolvedManagedAction.sessionIds.toList())
                            if (deleted != null) {
                                resolvedManagedAction.sessionIds.forEach(onSessionClosed)
                            }
                        }
                        selecting = false
                        selectedTaskIds = emptySet()
                        selectedSessionIds = emptySet()
                        confirmManagedAction = false
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { confirmManagedAction = false }),
        ) {
            Text(
                describeManagedConfirmMessage(resolvedManagedAction),
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            MutationErrorText(state.mutationError)
        }
    }

    deleteDirectoryTarget?.let { group ->
        WandDialog(
            title = "删除工作区「${group.workspaceName}」？",
            onDismissRequest = { if (!state.mutationBusy) deleteDirectoryTarget = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (state.mutationBusy) "删除中…" else "删除工作区及其中会话",
                destructive = true,
                enabled = !state.mutationBusy,
                onClick = {
                    scope.launch {
                        if (state.deleteDirectory(group, cascade = true)) deleteDirectoryTarget = null
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { deleteDirectoryTarget = null }),
        ) {
            Text(
                "「${group.workspaceName}」下的任务、会话和独立 worktree 会一起删除，此操作无法撤销。" +
                    if (group.tasks.any { it.worktree != null }) {
                        "选择仅移除时会保留普通会话，但独立 Worktree 中的会话也会结束并删除。"
                    } else {
                        "只想移除工作区、保留会话，请选择下面的「仅移除工作区」。"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        if (state.deleteDirectory(group, cascade = false)) deleteDirectoryTarget = null
                    }
                },
                enabled = !state.mutationBusy,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(if (group.tasks.any { it.worktree != null }) "仅移除（保留普通会话）" else "仅移除工作区（保留会话）")
            }
            MutationErrorText(state.mutationError)
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
                    targetDraftRevision += 1
                    selectedTarget = it
                    if (!it.isShell) state.rememberCreationChoice(defaultProvider = it.raw)
                },
                onSelectKind = {
                    targetDraftRevision += 1
                    selectedKind = it
                    state.rememberCreationChoice(defaultSessionKind = it)
                },
                onConfirm = {
                    if (targetCreating) return@WorkspaceTargetSheet
                    targetCreating = true
                    targetError = null
                    val submittedTarget = selectedTarget
                    val submittedKind = selectedKind
                    targetDraftRevision += 1
                    scope.launch {
                        try {
                            val snapshot = state.createTaskWindow(task.id, submittedTarget, submittedKind)
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AmbientBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(
                    serverDisplayName = serverDisplayName,
                    interactionEnabled = interactionEnabled,
                    // 窄屏主操作的入口在底部启动条；这里只在没有启动条时（宽屏侧栏）兜底。
                    showNewTask = !showingBoard && !showComposer,
                    searchOpen = searchOpen,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onSearchToggle = {
                        // 就地展开 / 收起：收起时顺手清掉查询词，避免留下一个看不见的过滤条件。
                        if (searchOpen) {
                            searchOpen = false
                            searchQuery = ""
                        } else {
                            searchOpen = true
                        }
                        selecting = false
                        selectedTaskIds = emptySet()
                        selectedSessionIds = emptySet()
                    },
                    onNewTask = { beginNewTask() },
                    onRefresh = {
                        if (showingBoard) {
                            boardRefreshNonce += 1
                        } else {
                            scope.launch {
                                state.load(silent = true)
                            }
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onSwitchServer = onSwitchServer,
                    onOpenTaskBoard = {
                        onHomeListModeChange(HomeListMode.Tasks)
                        attentionOnly = false
                    },
                    onCollapseSidebar = onCollapseSidebar,
                    onStartSelection = if (!showingBoard && hasAnyContent) {
                        {
                            selecting = true
                            selectedTaskIds = emptySet()
                            selectedSessionIds = emptySet()
                        }
                    } else {
                        null
                    },
                )
                HomeModeTabs(
                    mode = homeListMode,
                    enabled = interactionEnabled,
                    onChange = { mode ->
                        selecting = false
                        selectedTaskIds = emptySet()
                        selectedSessionIds = emptySet()
                        if (mode == HomeListMode.Sessions) attentionOnly = false
                        onHomeListModeChange(mode)
                    },
                )
                if (!showingBoard && (selecting || !attentionOnly)) {
                    HomeActivityStrip(
                        overview = overview,
                        attentionOnly = attentionOnly,
                        enabled = interactionEnabled,
                        onToggleAttention = {
                            attentionOnly = !attentionOnly
                            selecting = false
                            selectedTaskIds = emptySet()
                            selectedSessionIds = emptySet()
                        },
                    )
                }
                // 会话 ↔ 任务 是同一张列表的两种视图，切换时交叉淡入淡出，
                // 不做整屏硬切、也不重新入场（对齐「列表切换不闪跳」）。
                AnimatedContent(
                    targetState = showingBoard,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        if (!reduceMotion) {
                            // 退场比进场快：两层内容不会同时全亮。
                            fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "homeListSwitch",
                ) { boardVisible ->
                if (boardVisible) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TaskBoardScreen(
                            api = boardApi,
                            onOpenBoundSession = onOpenSession,
                            onBack = {},
                            onOpenSession = onOpenBoardSession,
                            onOpenTaskDetail = onOpenBoardTaskDetail,
                            embedded = true,
                            refreshNonce = boardRefreshNonce,
                            // 查询词由顶部搜索接管：同一个输入框在两个列表之间通用。
                            externalQuery = searchQuery,
                            onExternalQueryChange = { searchQuery = it },
                            showSearchField = false,
                        )
                    }
                } else when {
                    state.loading && !hasAnyContent -> LoadingState(
                        modifier = Modifier.fillMaxSize(),
                        text = "正在加载任务…",
                    )
                    state.loadError != null && !hasAnyContent -> ErrorState(
                        modifier = Modifier.fillMaxSize(),
                        message = state.loadError ?: "无法加载任务列表",
                        onRetry = { scope.launch { state.load() } },
                    )
                    !hasAnyContent -> EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        icon = WandIcons.sparkle,
                        title = if (searching) "没有匹配的工作区" else "开始第一个任务",
                        subtitle = if (searching) {
                            "换个词试试，或者关掉搜索看全部。"
                        } else {
                            "在底部写一句想做的事，或者用 ＋ 打开完整的新建面板。"
                        },
                    )
                    !hasVisibleContent -> EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        icon = WandIcons.check,
                        title = if (searching) "没有匹配的会话" else "没有需要处理的会话",
                        subtitle = if (searching) {
                            "换个词试试，或者关掉搜索看全部。"
                        } else {
                            "所有 Agent 都在自己跑，不用你插手。"
                        },
                    )
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        if (selecting) {
                            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            SidebarManageBar(
                                count = managedSelection.count,
                                allSelected = managedSelection.count > 0 &&
                                    managedSelection.taskIds.size == collectManagedIds(visibleGroups).taskIds.size &&
                                    managedSelection.sessionIds.size == collectManagedIds(visibleGroups).sessionIds.size,
                                busy = state.mutationBusy,
                                onSelectAll = {
                                    val all = collectManagedIds(visibleGroups)
                                    val allOn = managedSelection.count > 0 &&
                                        managedSelection.taskIds.size == all.taskIds.size &&
                                        managedSelection.sessionIds.size == all.sessionIds.size
                                    if (allOn) {
                                        selectedTaskIds = emptySet()
                                        selectedSessionIds = emptySet()
                                    } else {
                                        selectedTaskIds = all.taskIds
                                        selectedSessionIds = all.sessionIds
                                    }
                                },
                                actionLabel = describeManagedAction(resolvedManagedAction),
                                actionDestructive = managedSelectionIsDestructive(resolvedManagedAction),
                                onAction = { if (managedSelection.count > 0) confirmManagedAction = true },
                                onDone = {
                                    selecting = false
                                    selectedTaskIds = emptySet()
                                    selectedSessionIds = emptySet()
                                    confirmManagedAction = false
                                },
                            )
                            }
                        }
                        (state.loadError ?: state.orderSaveError)?.let { message ->
                            InlineError(
                                message = message,
                                onRetry = {
                                    if (state.loadError != null) scope.launch { state.load() }
                                    else state.retrySaveGroupOrder()
                                },
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            state = homeListState,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 14.dp,
                                end = 14.dp,
                                top = 4.dp,
                                bottom = 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                    val canReorder = !selecting && !searching && !attentionOnly && visibleGroups.size > 1
                    itemsIndexed(visibleGroups, key = { _, group -> group.id }) { _, group ->
                        HomeWorkspaceCard(
                            group = group,
                            modifier = dragState.itemLiftModifier(group.id)
                                .then(if (reduceMotion) Modifier else Modifier.animateItem()),
                            headerDragModifier = Modifier.wandLongPressDrag(
                                state = dragState,
                                itemKey = group.id,
                                listState = homeListState,
                                enabled = canReorder,
                                onDragStarted = state::startDirectoryReorder,
                                onDragFinished = state::finishDirectoryReorder,
                                onDragCancelled = state::cancelDirectoryReorder,
                                onMove = { fromKey, toKey ->
                                    state.moveDirectory(
                                        fromKey as? String ?: "",
                                        toKey as? String ?: "",
                                    )
                                },
                            ),
                            // 搜索中一律展开：命中的会话如果藏在折叠节点里，看起来就像搜不到。
                            expanded = searching || isDirectoryExpanded(
                                userCollapsed = state.isDirectoryCollapsed(group.id),
                                directoryCount = visibleGroups.size,
                            ),
                            forceExpandTasks = searching,
                            standaloneCollapsed = !searching && state.isStandaloneCollapsed(group.id),
                            taskCollapsed = state::isTaskCollapsed,
                            nowMillis = nowMillis,
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                            selecting = selecting,
                            selectedTaskIds = selectedTaskIds,
                            selectedSessionIds = selectedSessionIds,
                            onToggleManagedTask = { id ->
                                selectedTaskIds = if (id in selectedTaskIds) selectedTaskIds - id else selectedTaskIds + id
                            },
                            onToggleManagedSession = { id ->
                                selectedSessionIds = if (id in selectedSessionIds) selectedSessionIds - id else selectedSessionIds + id
                            },
                            onEnterSelection = { taskId, sessionId ->
                                selecting = true
                                selectedTaskIds = setOfNotNull(taskId)
                                selectedSessionIds = setOfNotNull(sessionId)
                            },
                            onToggleGroup = { state.toggleDirectory(group.id) },
                            onToggleTask = state::toggleTask,
                            onToggleStandalone = { state.toggleStandalone(group.id) },
                            onOpenTask = { task ->
                                onOpenTask(task.task.workspaceId, task.id, group.workspaceName, task.name)
                            },
                            onOpenSession = { session, task ->
                                onOpenSession(taskSessionRoute(session, group, task))
                            },
                            onMoveSession = { moveSessionTarget = it },
                            onNewTask = { beginNewTask(group.workspaceCwd, group.workspaceId.takeUnless { group.synthetic }) },
                            onRenameDirectory = {
                                renameDirectoryDraft = group.workspaceName
                                renameDirectoryTarget = group
                                state.clearMutationError()
                            },
                            onNewWindow = { task ->
                                selectedTarget = WorkspaceSessionTarget.fromRaw(state.defaultProvider)
                                    ?: WorkspaceSessionTarget.Claude
                                selectedKind = state.defaultSessionKind
                                targetError = null
                                pendingTarget = group to task
                                targetDraftRevision += 1
                                val defaultsRevision = targetDraftRevision
                                scope.launch {
                                    state.loadCreationDefaults()
                                    if (pendingTarget?.second?.id != task.id || defaultsRevision != targetDraftRevision) return@launch
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
                            onArchive = { task ->
                                archiveTarget = task
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
                            onDeleteDirectory = {
                                deleteDirectoryTarget = group
                                state.clearMutationError()
                            },
                            onReview = { reviewTarget = group },
                        )
                    }
                        }
                    }
                }
                }
            }
        }
        // 多选是管理态，底部启动条先让位，避免和批量操作抢注意力。
        if (!showingBoard && showComposer && !selecting) {
            HomeComposerBar(
                value = composerDraft,
                onValueChange = { composerDraft = it },
                enabled = interactionEnabled,
                onSubmit = { prompt ->
                    // 提示词带进现有的新建任务面板：目录 / provider 这些真实选择仍在面板里确认。
                    composerDraft = ""
                    beginNewTask(initialPrompt = prompt)
                },
                onOpenFullDialog = { beginNewTask() },
            )
        }
    }
}

@Composable
private fun SidebarManageBar(
    count: Int,
    allSelected: Boolean,
    busy: Boolean,
    actionLabel: String,
    actionDestructive: Boolean,
    onSelectAll: () -> Unit,
    onAction: () -> Unit,
    onDone: () -> Unit,
) {
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        containerColor = WandColors.surface.copy(alpha = 0.92f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count > 0) "已选择 $count 项" else "点选任务或终端",
                style = MaterialTheme.typography.labelLarge,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll, enabled = !busy) {
                Text(if (allSelected) "取消全选" else "全选")
            }
            TextButton(onClick = onAction, enabled = !busy && count > 0) {
                // 纯归档不是破坏性操作，不渲染成红色（对齐 web 端 managedSelectionIsDestructive）。
                Text(
                    actionLabel,
                    color = when {
                        count == 0 -> WandColors.textMuted
                        actionDestructive -> WandColors.danger
                        else -> WandColors.brand
                    },
                )
            }
            TextButton(onClick = onDone, enabled = !busy) {
                Text("完成")
            }
        }
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

/**
 * 任务增删改共用的错误提示槽。原来在 5 个对话框里各抄一遍同样的 Text 样式，
 * 改一次配色就要改五处。
 */
@Composable
private fun MutationErrorText(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = WandColors.danger,
        modifier = modifier,
    )
}
