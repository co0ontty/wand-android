package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.SessionWatcher
import kotlinx.coroutines.flow.collect
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamRun
import com.wand.app.data.ExecutionSubject
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.BoardTask
import com.wand.app.data.DirectoryListing
import com.wand.app.data.ModelsResponse
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.Workspace
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.ensureBoardTaskParent
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.boardParentTaskOptions
import com.wand.app.data.findBoardTaskByWorkspaceTaskId
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.raw
import com.wand.app.ui.withLiveTitle
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.rememberWandDragReorderState
import com.wand.app.ui.components.wandLongPressDrag
import com.wand.app.ui.components.itemLiftModifier
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.WandPullToRefresh
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.launch

/** Stable route information carried from the task tree into a session detail screen. */
data class TaskSessionRoute(
    val sessionId: String,
    val structured: Boolean,
    val workspaceId: String? = null,
    val taskId: String? = null,
    val workspaceName: String? = null,
    val taskName: String? = null,
    /**
     * 该会话是团队群聊会话时带上运行 id：点行应进 IM 群聊页而不是普通聊天页。
     * 老服务端摘要里没有 `teamChat`，这里是 null，按普通会话打开。
     */
    val teamChatRunId: String? = null,
)

/** 首页草稿在完整表单中继续编辑；只有同一次打开且内容未被后续输入改过才可清理。 */
internal class HomeComposerDraftState {
    var text by mutableStateOf("")
        private set
    private var revision by mutableLongStateOf(0L)
    private var handoffOpeningId: Long? = null

    fun edit(value: String) {
        if (text == value) return
        text = value
        revision += 1
    }

    fun beginHandoff(openingId: Long) {
        handoffOpeningId = openingId
    }

    fun editInDialog(openingId: Long, value: String) {
        if (handoffOpeningId == openingId) edit(value)
    }

    fun revisionFor(openingId: Long): Long? = revision.takeIf { handoffOpeningId == openingId }

    fun finishHandoff(openingId: Long, submittedRevision: Long? = null) {
        if (handoffOpeningId != openingId) return
        if (submittedRevision != null && revision == submittedRevision) edit("")
        handoffOpeningId = null
    }
}

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
    /** 首页顶栏「更多」→「AI 团队」：纯穿透回调。 */
    onOpenAiTeams: () -> Unit = {},
    onOpenSiliconEmployees: () -> Unit = {},
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
    showComposer: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val contactContext = androidx.compose.ui.platform.LocalContext.current
    var newTaskOpen by remember { mutableStateOf(false) }
    var taskCwdDraft by remember { mutableStateOf("") }
    var newTaskWorkspaceId by remember { mutableStateOf<String?>(null) }
    var startFirstSession by remember { mutableStateOf(true) }
    var newTaskPrompt by remember { mutableStateOf("") }
    var newTaskWorktree by remember { mutableStateOf(false) }
    var newTaskName by remember { mutableStateOf("") }
    var newTaskParentId by remember { mutableStateOf("") }
    // 补关联失败时保留已建任务；重试只重发关联请求，绝不能再创建一条。
    var pendingParentLink by remember { mutableStateOf<Pair<TaskCreationResult, String>?>(null) }
    var parentLinkBusy by remember { mutableStateOf(false) }
    var parentLinkError by remember { mutableStateOf<String?>(null) }
    var parentTasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var parentsLoading by remember { mutableStateOf(false) }
    var parentError by remember { mutableStateOf<String?>(null) }
    var newTaskTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
    // 新建任务「交给团队」：与 CLI 目标互斥；workspace task 已建但 team-run 失败时记住两种 id，
    // 重试只补「找卡 → 派团队」这两步，绝不重复建卡。
    var newTaskTeams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var newTaskEmployees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var contactsTeams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var contactsTeamRuns by remember { mutableStateOf<List<AiTeamRun>>(emptyList()) }
    var contactsEmployees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var newTaskTeamId by remember { mutableStateOf<String?>(null) }
    var newTaskEmployeeId by remember { mutableStateOf<String?>(null) }
    var teamRunRetry by remember { mutableStateOf<NewTaskTeamRetry?>(null) }
    var teamRunError by remember { mutableStateOf<String?>(null) }
    var newTaskKind by remember { mutableStateOf(WorkspaceSessionKind.Structured) }
    var newTaskModel by remember { mutableStateOf("default") }
    var newTaskThinkingEffort by remember { mutableStateOf("off") }
    var newTaskModels by remember { mutableStateOf<ModelsResponse?>(null) }
    val homeComposerDraft = remember { HomeComposerDraftState() }
    var newTaskDraftRevision by remember { mutableLongStateOf(0L) }
    var newTaskOpeningId by remember { mutableLongStateOf(0L) }
    var newTaskSubmitting by remember { mutableStateOf(false) }
    var targetDraftRevision by remember { mutableLongStateOf(0L) }
    var directoryPickerOpen by remember { mutableStateOf(false) }
    var directoryPickerPath by remember { mutableStateOf("") }
    var directoryListing by remember { mutableStateOf<DirectoryListing?>(null) }
    var directoryLoading by remember { mutableStateOf(false) }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var pendingTarget by remember { mutableStateOf<Pair<TaskDirectoryGroup, WorkspaceTaskSummary>?>(null) }
    var selectedTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
    var selectedEmployeeId by remember { mutableStateOf<String?>(null) }
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
    val allGroups = directoryTreeGroups(state.groups)
    var attentionOnly by remember { mutableStateOf(false) }
    val visibleGroups = if (attentionOnly) attentionOnlyGroups(allGroups) else allGroups
    val recentConversations = recentHomeConversations(visibleGroups)
    val contactConversations = recentHomeConversations(allGroups, limit = Int.MAX_VALUE)
    val overview = homeOverview(allGroups)
    // 状态行的数字与列表使用同一批可见会话，分母仍是全量数。
    val activityStats = homeActivityStats(
        globalOverview = overview,
        finalOverview = homeOverview(visibleGroups),
        attentionOnly = attentionOnly,
    )
    val sessionEmptyCopy = homeSessionEmptyCopy()
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
    var refreshingSessions by remember { mutableStateOf(false) }

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
        newTaskParentId = "" // 父任务必须与所选目录属于同一项目。
        // 团队选择绑定在旧目录的项目上：目录一变（不再命中已有非 global 项目）就清零，
        // 重试态也随之作废（老卡不属于这里）。
        if (!isTeamPickAllowed(newTaskWorkspaceId)) {
            newTaskTeamId = null
            teamRunRetry = null
            teamRunError = null
        }
        state.clearMutationError()
    }

    fun loadParentTasks(openingId: Long) {
        parentsLoading = true
        parentError = null
        scope.launch {
            try {
                val loaded = boardApi.listBoardTasks()
                if (newTaskOpen && openingId == newTaskOpeningId) parentTasks = loaded
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (newTaskOpen && openingId == newTaskOpeningId) {
                    parentError = error.message ?: "无法加载父任务"
                }
            } finally {
                if (newTaskOpen && openingId == newTaskOpeningId) parentsLoading = false
            }
        }
    }

    fun beginNewTask(
        initialCwd: String? = null,
        workspaceId: String? = null,
        initialPrompt: String = "",
        fromHomeComposer: Boolean = false,
        initialEmployeeId: String? = null,
        initialTeamId: String? = null,
        initialTarget: WorkspaceSessionTarget? = null,
    ) {
        if (!interactionEnabled || newTaskOpen || newTaskSubmitting) return
        state.clearMutationError()
        taskCwdDraft = initialCwd.orEmpty()
        newTaskWorkspaceId = workspaceId
        startFirstSession = true
        newTaskPrompt = initialPrompt
        newTaskWorktree = false
        newTaskName = ""
        newTaskParentId = ""
        pendingParentLink = null
        parentLinkError = null
        parentTasks = emptyList()
        newTaskTarget = initialTarget ?: WorkspaceSessionTarget.Claude
        newTaskTeamId = initialTeamId
        newTaskEmployeeId = initialEmployeeId
        newTaskTeams = contactsTeams
        newTaskEmployees = contactsEmployees
        teamRunRetry = null
        teamRunError = null
        newTaskKind = WorkspaceSessionKind.Structured
        newTaskModel = "default"
        newTaskThinkingEffort = "off"
        newTaskModels = null
        newTaskDraftRevision += 1
        newTaskOpeningId += 1
        val defaultsRevision = newTaskDraftRevision
        val openingId = newTaskOpeningId
        if (fromHomeComposer) homeComposerDraft.beginHandoff(openingId)
        newTaskOpen = true
        loadParentTasks(openingId)
        scope.launch {
            state.loadCreationDefaults()
            if (!newTaskOpen || openingId != newTaskOpeningId) return@launch
            if (taskCwdDraft.isBlank()) {
                taskCwdDraft = state.defaultCwd.orEmpty()
                newTaskWorkspaceId = workspaceForPath(taskCwdDraft)
                newTaskParentId = ""
            }
            if (defaultsRevision == newTaskDraftRevision && initialTarget == null &&
                initialEmployeeId == null && initialTeamId == null) {
                newTaskTarget = WorkspaceSessionTarget.fromRaw(state.defaultProvider) ?: newTaskTarget
                newTaskKind = state.defaultSessionKind
                newTaskThinkingEffort = state.defaultThinkingEffort
            }
            val loadedModels = runCatching { boardApi.boardModels() }.getOrNull()
            if (!newTaskOpen || openingId != newTaskOpeningId) return@launch
            newTaskModels = loadedModels
            val available = com.wand.app.ui.thinkingEffortOptions(
                newTaskTarget.raw, newTaskModel,
                loadedModels?.defaultModelFor(newTaskTarget.raw),
                loadedModels?.modelsFor(newTaskTarget.raw).orEmpty(),
            )
            if (available.none { it.id == newTaskThinkingEffort }) newTaskThinkingEffort = "off"
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
    // 每次打开对话框拉一次团队；失败回落空列表 = 不显示团队区，绝不挡新建任务本身。
    // 不进 30s/轮询环：团队定义只在 Web 端编辑，频率对不上。
    LaunchedEffect(newTaskOpen, newTaskOpeningId) {
        if (newTaskOpen) {
            newTaskTeams = runCatching { boardApi.listAiTeams() }.getOrDefault(emptyList())
            newTaskEmployees = runCatching { boardApi.listSiliconEmployees() }.getOrDefault(emptyList())
        }
    }
    val contactsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(boardApi, contactsLifecycleOwner) {
        contactsLifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            suspend fun refreshContacts() {
                runCatching { boardApi.listSiliconEmployees() }.onSuccess { contactsEmployees = it }
                runCatching { boardApi.listAiTeams() }.onSuccess { contactsTeams = it }
                runCatching { boardApi.listAiTeamRuns(limit = 200) }.onSuccess { contactsTeamRuns = it }
            }
            refreshContacts()
            SessionWatcher.employeeDefinitionChanges.collect { refreshContacts() }
        }
    }
    LaunchedEffect(selectedTaskId, selectedSessionId, visibleGroups) {
        state.expandPathToSelection(selectedTaskId, selectedSessionId)
    }

    val directoryPickerContent: @Composable () -> Unit = {
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
    }

    if (newTaskOpen) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val cwd = taskCwdDraft.trim()
        val parentOptions = if (newTaskWorkspaceId != null || cwd.isEmpty()) {
            boardParentTaskOptions(parentTasks, newTaskWorkspaceId?.takeUnless { it == GLOBAL_WORKSPACE_ID })
        } else listOf("" to "不关联父任务")
        val groupedName = newTaskName.trim()
        // 任务身份不再依赖首个会话提示词：名称留空时由提示词自动命名。
        val taskPrompt = newTaskPrompt.trim().ifEmpty { null }
        val teamSelected = newTaskTeamId != null && newTaskTeams.any { it.id == newTaskTeamId }
        val teamSubmitError =
            newTaskTeamSubmitError(newTaskPrompt, teamSelected, isTeamPickAllowed(newTaskWorkspaceId))
        val canCreateTask = (pendingParentLink != null && newTaskTeamId == null) ||
            (cwd.isNotEmpty() &&
                TaskListState.isValidOptionalTaskName(groupedName) &&
                (groupedName.isNotEmpty() || taskPrompt != null) &&
                teamSubmitError == null)
        val submitNewTask: () -> Unit = submit@{
            if (state.mutationBusy || parentLinkBusy || newTaskSubmitting || !canCreateTask) return@submit
            // 提交侧兜底：目录被改成未登记项目后团队选择即使还挂着，也不许走团队分支（§5.1 R2）。
            if (teamSubmitError != null) {
                teamRunError = teamSubmitError
                return@submit
            }
            teamRunError = null
            val submittedTarget = newTaskTarget
            val submittedKind = newTaskKind
            val submittedModel = newTaskModel.takeUnless { it == "default" }
            val submittedEffort = newTaskThinkingEffort
            val submittedStartSession = startFirstSession
            val submittedWorktree = newTaskWorktree
            val submittedWorkspaceId = newTaskWorkspaceId
            val submittedTeamId = newTaskTeamId.takeIf { id ->
                newTaskTeams.any { it.id == id } && isTeamPickAllowed(newTaskWorkspaceId)
            }
            val submittedEmployeeId = newTaskEmployeeId.takeIf { id ->
                submittedKind == WorkspaceSessionKind.Structured && newTaskEmployees.any { it.id == id }
            }
            // 重试态只认团队分支记下的 id 对；CLI 分支的老 retry id 不复用，跨分支不复用。
            val submittedTeamRunRetry =
                if (newTaskTeamRetryActive(submittedTeamId != null, teamRunRetry)) teamRunRetry else null
            val submittedParentId = pendingParentLink?.second
                ?: newTaskParentId.takeIf { id -> id.isNotEmpty() && parentOptions.any { it.first == id } }
            val submittedOpeningId = newTaskOpeningId
            val submittedComposerRevision = homeComposerDraft.revisionFor(submittedOpeningId)
            newTaskDraftRevision += 1
            newTaskSubmitting = true
            scope.launch {
                try {
                    if (submittedTeamId != null) {
                        // 团队分支：`needCreate` 是唯一决定建不建卡的闸（纯函数
                        // newTaskNeedsCardCreation），重试态恒 false → :createTask 不可达。
                        val needCreate = newTaskNeedsCardCreation(submittedTeamRunRetry)
                        val created = if (needCreate) {
                            state.createTask(
                                name = groupedName,
                                cwd = cwd,
                                worktree = submittedWorktree,
                                workspaceId = submittedWorkspaceId,
                                description = taskPrompt,
                                parentTaskId = submittedParentId,
                            ) ?: return@launch
                        } else {
                            null
                        }
                        // 两种 id 分开：`workspaceTaskId` 给导航（Screen.WorkspaceTask），
                        // `boardCardId` 给 POST /api/wand-tasks/{id}/team-runs。看板卡是另一张表，
                        // 传 workspace task id 服务端查不到，必然回「任务不存在。」。
                        val workspaceTaskId = created?.task?.id
                            ?: submittedTeamRunRetry?.workspaceTaskId.orEmpty()
                        val card = submittedTeamRunRetry?.boardCardId
                            ?.let { id -> runCatching { boardApi.getBoardTask(id) }.getOrNull() }
                            ?: try {
                                findBoardTaskByWorkspaceTaskId(boardApi.listBoardTasks(), workspaceTaskId)
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                teamRunRetry = NewTaskTeamRetry(workspaceTaskId)
                                teamRunError = newTaskTeamCardMissingMessage(error.message)
                                return@launch
                            }
                        if (card == null) {
                            teamRunRetry = NewTaskTeamRetry(workspaceTaskId, submittedTeamRunRetry?.boardCardId)
                            teamRunError = newTaskTeamCardMissingMessage(null)
                            return@launch
                        }
                        val cardId = card.id
                        // 派团队只发这一条：note 传空，目标 = 标题+描述在服务端拼装，与看板新建同口径；
                        // **不起 CLI 会话**。
                        try {
                            boardApi.startTeamRun(cardId, submittedTeamId, "")
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            // 失败保留对话框、记住两种 id，重试只重发这一步；错误原位显示服务端原文，不加 Toast。
                            teamRunRetry = NewTaskTeamRetry(workspaceTaskId, cardId)
                            teamRunError = "任务已创建，但交给团队失败：${error.message ?: "可稍后在详情里再交给团队"}"
                            return@launch
                        }
                        teamRunRetry = null
                        // 成功导航必须落在真正派了团队的那张卡上（重试态 = 旧卡）。
                        val navigationWorkspaceTaskId = created?.task?.id ?: card.workspaceTaskId.orEmpty()
                        val workspaceId = created?.workspace?.id ?: card.workspaceId ?: card.workspace?.id ?: ""
                        if (workspaceId.isNotBlank() && navigationWorkspaceTaskId.isNotBlank()) {
                            val workspaceName = created?.workspace?.name
                                ?: state.groups.firstOrNull { it.workspaceId == workspaceId }?.workspaceName.orEmpty()
                            newTaskOpen = false
                            homeComposerDraft.finishHandoff(submittedOpeningId, submittedComposerRevision)
                            onOpenTask(
                                workspaceId,
                                navigationWorkspaceTaskId,
                                workspaceName,
                                created?.task?.name ?: card.title.takeIf { it.isNotBlank() } ?: "任务",
                            )
                        } else {
                            // 兑不到这张卡的归属就不假装成功，也**不关窗**：这句话的唯一可见通道是
                            // 对话框的 error 位（teamRunError 优先），关窗即卸载等于凭空消失。
                            // 同时记住卡 id：用户再点一次只会对这张卡重发 team-runs，不会建二卡。
                            teamRunRetry = NewTaskTeamRetry(workspaceTaskId, cardId)
                            teamRunError = "已交给团队，但没取到这张任务卡的归属，请点「取消」关闭后在任务看板查看。"
                        }
                        return@launch
                    }
                    state.rememberCreationChoice(
                        defaultProvider = submittedTarget.raw.takeUnless { submittedTarget.isShell },
                        defaultSessionKind = submittedKind,
                    )
                    val result = pendingParentLink?.first ?: state.createTask(
                        name = groupedName,
                        cwd = cwd,
                        worktree = submittedWorktree,
                        workspaceId = submittedWorkspaceId,
                        description = taskPrompt,
                        parentTaskId = submittedParentId,
                    )
                    if (result != null) {
                        if (submittedParentId != null) {
                            pendingParentLink = result to submittedParentId
                            parentLinkBusy = true
                            parentLinkError = null
                            try {
                                ensureBoardTaskParent(boardApi, result.task.id, submittedParentId)
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                parentLinkError = "任务已创建，但关联父任务失败：${error.message ?: "请稍后重试"}。点击提交重试，不会重复创建任务。"
                                return@launch
                            } finally {
                                parentLinkBusy = false
                            }
                        }
                        pendingParentLink = null
                        newTaskOpen = false
                        homeComposerDraft.finishHandoff(submittedOpeningId, submittedComposerRevision)
                        val snapshot = if (submittedStartSession) state.createTaskWindow(
                            result.task.id, submittedTarget, submittedKind, taskPrompt,
                            submittedModel, submittedEffort, submittedEmployeeId,
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
                            if (submittedStartSession && state.mutationError != null) {
                                android.widget.Toast.makeText(context,
                                    "任务已创建，但启动会话失败：${state.mutationError}", android.widget.Toast.LENGTH_LONG).show()
                            }
                            onOpenTask(result.workspace.id, result.task.id, result.workspace.name, result.task.name)
                        }
                    }
                } finally {
                    newTaskSubmitting = false
                }
            }
        }
        NewTaskComposerDialog(
            name = newTaskName,
            onNameChange = { newTaskName = it; state.clearMutationError() },
            prompt = newTaskPrompt,
            onPromptChange = {
                newTaskPrompt = it
                homeComposerDraft.editInDialog(newTaskOpeningId, it)
                state.clearMutationError()
            },
            cwd = cwd,
            onChooseDirectory = ::openDirectoryPicker,
            parentOptions = parentOptions,
            parentTaskId = newTaskParentId,
            fieldsLocked = pendingParentLink != null,
            onParentTaskChange = { newTaskParentId = it; state.clearMutationError() },
            parentsLoading = parentsLoading,
            parentError = parentError,
            onReloadParents = { loadParentTasks(newTaskOpeningId) },
            target = newTaskTarget,
            teams = newTaskTeams,
            employees = newTaskEmployees,
            teamId = newTaskTeamId,
            employeeId = newTaskEmployeeId,
            teamAllowed = isTeamPickAllowed(newTaskWorkspaceId),
            onTeamChange = { id ->
                // 互斥：选团队即放弃 CLI 目标（CLI 与团队不能同时作为派发对象）。
                newTaskDraftRevision += 1
                newTaskTeamId = id
                newTaskEmployeeId = null
                teamRunRetry = null
                teamRunError = null
            },
            onEmployeeChange = { id ->
                newTaskDraftRevision += 1
                newTaskEmployeeId = id
                newTaskTeamId = null
                teamRunRetry = null
                teamRunError = null
                startFirstSession = true
                newTaskKind = WorkspaceSessionKind.Structured
            },
            onTargetChange = { option ->
                val resetsCliParams = newTaskTargetChangeResetsCliParams(newTaskTarget, option)
                newTaskDraftRevision += 1
                newTaskTarget = option
                newTaskTeamId = null
                newTaskEmployeeId = null
                teamRunRetry = null
                teamRunError = null
                // 只有真的换了工具才重置模型/思考深度；团队 ⇄ 同一目标往返保留手选值。
                if (resetsCliParams) {
                    newTaskModel = "default"
                    val available = com.wand.app.ui.thinkingEffortOptions(
                        option.raw, "default", newTaskModels?.defaultModelFor(option.raw),
                        newTaskModels?.modelsFor(option.raw).orEmpty(),
                    )
                    newTaskThinkingEffort = state.defaultThinkingEffort.takeIf { effort ->
                        available.any { it.id == effort }
                    } ?: "off"
                }
                if (!option.isShell) state.rememberCreationChoice(defaultProvider = option.raw)
            },
            kind = newTaskKind,
            onKindChange = {
                newTaskDraftRevision += 1
                newTaskKind = it
                if (it != WorkspaceSessionKind.Structured) {
                    newTaskEmployeeId = null
                    newTaskTeamId = null
                }
                state.rememberCreationChoice(defaultSessionKind = it)
            },
            model = newTaskModel,
            onModelChange = { chosen ->
                newTaskDraftRevision += 1
                newTaskModel = chosen
                val available = com.wand.app.ui.thinkingEffortOptions(
                    newTaskTarget.raw, chosen,
                    newTaskModels?.defaultModelFor(newTaskTarget.raw),
                    newTaskModels?.modelsFor(newTaskTarget.raw).orEmpty(),
                )
                if (available.none { it.id == newTaskThinkingEffort }) newTaskThinkingEffort = "off"
            },
            thinkingEffort = newTaskThinkingEffort,
            onThinkingEffortChange = { newTaskDraftRevision += 1; newTaskThinkingEffort = it },
            models = newTaskModels,
            startFirstSession = startFirstSession,
            onStartFirstSessionChange = { startFirstSession = it },
            worktree = newTaskWorktree,
            onWorktreeChange = { newTaskWorktree = it },
            busy = state.mutationBusy || parentLinkBusy || newTaskSubmitting,
            error = teamRunError ?: parentLinkError ?: state.mutationError,
            teamRunRetry = teamRunRetry != null,
            canCreate = canCreateTask,
            onSubmit = submitNewTask,
            directoryPickerContent = directoryPickerContent,
            onDismiss = {
                if (!state.mutationBusy && !newTaskSubmitting) {
                    newTaskOpen = false
                    homeComposerDraft.finishHandoff(newTaskOpeningId)
                    teamRunRetry = null
                    teamRunError = null
                }
            },
        )
    }

    moveSessionTarget?.let { session ->
        SessionMoveSheet(api, session.id, session.title ?: "CLI 会话",
            onDismiss = { moveSessionTarget = null },
            onMoved = { scope.launch { state.refreshAfterMutation() } })
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
                employees = contactsEmployees,
                selectedEmployeeId = selectedEmployeeId,
                creating = targetCreating,
                error = targetError,
                onSelect = {
                    selectedEmployeeId = null
                    targetDraftRevision += 1
                    selectedTarget = it
                    if (!it.isShell) state.rememberCreationChoice(defaultProvider = it.raw)
                },
                onSelectEmployee = {
                    selectedEmployeeId = it
                    selectedKind = WorkspaceSessionKind.Structured
                },
                onSelectKind = {
                    targetDraftRevision += 1
                    selectedKind = it
                    if (it != WorkspaceSessionKind.Structured) selectedEmployeeId = null
                    state.rememberCreationChoice(defaultSessionKind = it)
                },
                onConfirm = {
                    if (targetCreating) return@WorkspaceTargetSheet
                    targetCreating = true
                    targetError = null
                    val submittedTarget = selectedTarget
                    val submittedKind = selectedKind
                    val submittedEmployeeId = selectedEmployeeId
                    targetDraftRevision += 1
                    scope.launch {
                        try {
                            val snapshot = state.createTaskWindow(task.id, submittedTarget,
                                submittedKind, employeeId = submittedEmployeeId)
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
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AmbientBackground(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(
                    serverDisplayName = serverDisplayName,
                    homeListMode = homeListMode,
                    interactionEnabled = interactionEnabled,
                    onHomeListModeChange = { mode ->
                        selecting = false
                        selectedTaskIds = emptySet()
                        selectedSessionIds = emptySet()
                        if (mode == HomeListMode.Sessions) attentionOnly = false
                        onHomeListModeChange(mode)
                    },
                    onOpenSettings = onOpenSettings,
                    onSwitchServer = onSwitchServer,
                    onOpenAiTeams = onOpenAiTeams,
                    onOpenSiliconEmployees = onOpenSiliconEmployees,
                    onCollapseSidebar = onCollapseSidebar,
                )
                if (homeListMode == HomeListMode.Sessions) {
                    HomeContactsStrip(
                        employees = contactsEmployees,
                        teams = contactsTeams,
                        enabled = interactionEnabled,
                        onEmployee = { employee ->
                            val existing = contactConversation(contactConversations,
                                ExecutionSubject.employee(employee.id))
                            if (existing != null) onOpenSession(taskSessionRoute(existing.session,
                                existing.group, existing.task))
                            else beginNewTask(initialEmployeeId = employee.id)
                        },
                        onTeam = { team ->
                            val subject = ExecutionSubject.team(team.id)
                            val knownRuns = contactsTeamRuns.filter { it.teamId == team.id }
                            val existing = contactConversation(contactConversations, subject,
                                knownRuns.map { it.id }.toSet())
                            if (existing != null) {
                                onOpenSession(taskSessionRoute(existing.session,
                                    existing.group, existing.task))
                            } else {
                                scope.launch {
                                    val freshRuns = runCatching {
                                        boardApi.listAiTeamRuns(team.id, limit = 200)
                                    }.getOrNull()
                                    if (freshRuns == null) {
                                        android.widget.Toast.makeText(
                                            contactContext,
                                            "无法加载团队最近对话，请重试。",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                        return@launch
                                    }
                                    contactsTeamRuns = contactsTeamRuns.filterNot { it.teamId == team.id } + freshRuns
                                    val refreshed = contactConversation(contactConversations, subject,
                                        freshRuns.map { it.id }.toSet())
                                    if (refreshed != null) onOpenSession(taskSessionRoute(refreshed.session,
                                        refreshed.group, refreshed.task))
                                    else {
                                        val latestChat = freshRuns.firstOrNull { it.chatSessionId != null }
                                        if (latestChat != null) onOpenSession(TaskSessionRoute(
                                            sessionId = latestChat.chatSessionId!!,
                                            structured = true,
                                            teamChatRunId = latestChat.id,
                                        )) else beginNewTask(initialTeamId = team.id)
                                    }
                                }
                            }
                        },
                        onCli = { provider ->
                            val existing = contactConversation(contactConversations,
                                ExecutionSubject.cli(provider))
                            if (existing != null) onOpenSession(taskSessionRoute(existing.session,
                                existing.group, existing.task))
                            else WorkspaceSessionTarget.fromRaw(provider)?.let { beginNewTask(initialTarget = it) }
                        },
                        onManageEmployees = onOpenSiliconEmployees,
                    )
                }
                // 整行的显隐只有 [homeActivityStripVisible] 一个门，且就在调用处：
                // 「只看等你」的开关长在这一行里，组件内不得再有第二套早退（D13/S24）。
                if (homeActivityStripVisible(showingBoard, activityStats)) {
                    HomeActivityStrip(
                        stats = activityStats,
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
                            showSearchField = false,
                        )
                    }
                } else WandPullToRefresh(
                    isRefreshing = refreshingSessions,
                    onRefresh = {
                        if (interactionEnabled && !refreshingSessions) {
                            refreshingSessions = true
                            scope.launch {
                                try {
                                    state.load(silent = true)
                                } finally {
                                    refreshingSessions = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                    state.loading && !hasAnyContent -> LoadingState(
                        modifier = Modifier.fillMaxSize(),
                        text = "正在加载任务…",
                    )
                    state.loadError != null && !hasAnyContent -> ErrorState(
                        modifier = Modifier.fillMaxSize(),
                        message = state.loadError ?: "无法加载任务列表",
                        onRetry = { scope.launch { state.load() } },
                    )
                    // 筛选开着时即使一条数据都没有，也必须先给「已选等你」的关闭路径，
                    // 不能被下面的初始空态抢先显示「开始第一个任务」。
                    !hasAnyContent && attentionOnly -> EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        icon = WandIcons.check,
                        title = sessionEmptyCopy.title,
                        subtitle = sessionEmptyCopy.subtitle,
                    )
                    !hasAnyContent -> EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        icon = WandIcons.sparkle,
                        title = "开始第一个任务",
                        subtitle = "在底部写一句想做的事，或者用 ＋ 打开完整的新建面板。",
                    )
                    // 有数据但筛选结果为空时，保留「已选等你」关闭路径。
                    !hasVisibleContent -> EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        icon = WandIcons.check,
                        title = sessionEmptyCopy.title,
                        subtitle = sessionEmptyCopy.subtitle,
                    )
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        if (selecting) {
                            Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
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
                                start = 6.dp,
                                end = 6.dp,
                                top = 4.dp,
                                bottom = 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                    if (recentConversations.isNotEmpty()) {
                        item(key = "recent-conversation-header") {
                            Text("最近对话", color = WandColors.textPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp))
                        }
                        items(recentConversations, key = { "recent:${it.session.id}" }) { recent ->
                            val session = recent.session
                            val parentNames = listOfNotNull(recent.group.workspaceName,
                                recent.task?.name)
                            WandCard(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                HomeSessionRow(
                                    session = session,
                                    employee = contactsEmployees.firstOrNull { it.id == session.employeeId },
                                    label = listSessionLabel(session.withLiveTitle(), 0, parentNames),
                                    nowMillis = nowMillis,
                                    selected = session.id == selectedSessionId,
                                    selecting = selecting,
                                    managedSelected = session.id in selectedSessionIds,
                                    onToggleManaged = { selectedSessionIds =
                                        if (session.id in selectedSessionIds) selectedSessionIds - session.id
                                        else selectedSessionIds + session.id },
                                    onEnterSelection = {
                                        selecting = true
                                        selectedTaskIds = emptySet()
                                        selectedSessionIds = setOf(session.id)
                                    },
                                    onClick = { onOpenSession(taskSessionRoute(session, recent.group,
                                        recent.task)) },
                                    onDelete = {
                                        deleteSessionTarget = session
                                        state.clearMutationError()
                                    },
                                    onMove = { moveSessionTarget = session },
                                )
                            }
                        }
                        item(key = "workspace-tree-header") {
                            Text("任务与工作区", color = WandColors.textSecondary,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = 10.dp, end = 10.dp, top = 12.dp, bottom = 2.dp))
                        }
                    }
                    val canReorder = !selecting && !attentionOnly && visibleGroups.size > 1
                    itemsIndexed(visibleGroups, key = { _, group -> group.id }) { _, group ->
                        val dragging = dragState.isDragging(group.id)
                        HomeWorkspaceCard(
                            group = group,
                            employees = contactsEmployees,
                            modifier = dragState.itemLiftModifier(group.id)
                                .then(if (reduceMotion || dragging) Modifier else Modifier.animateItem()),
                            headerDragModifier = Modifier.wandLongPressDrag(
                                state = dragState,
                                itemKey = group.id,
                                listState = homeListState,
                                enabled = canReorder,
                                onDragStarted = { state.startDirectoryReorder(group.id) },
                                onDragFinished = state::finishDirectoryReorder,
                                onDragCancelled = state::cancelDirectoryReorder,
                                onMove = { fromKey, toKey ->
                                    state.moveDirectory(
                                        fromKey as? String ?: "",
                                        toKey as? String ?: "",
                                    )
                                },
                            ),
                            expanded = !dragging && isDirectoryExpanded(
                                userCollapsed = state.isDirectoryCollapsed(group.id),
                            ),
                            dragging = dragging,
                            standaloneCollapsed = state.isStandaloneCollapsed(group.id),
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
                                selectedEmployeeId = null
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
                                    selectedEmployeeId = null
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
        }
        // 多选是管理态，底部启动条先让位，避免和批量操作抢注意力。
        if (!showingBoard && showComposer && !selecting) {
            HomeComposerBar(
                value = homeComposerDraft.text,
                onValueChange = homeComposerDraft::edit,
                enabled = interactionEnabled && !newTaskSubmitting,
                onSubmit = { prompt ->
                    // 提示词带进现有的新建任务面板：目录 / provider 这些真实选择仍在面板里确认。
                    beginNewTask(initialPrompt = prompt, fromHomeComposer = true)
                },
                onOpenFullDialog = {
                    beginNewTask(initialPrompt = homeComposerDraft.text, fromHomeComposer = true)
                },
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
    teamChatRunId = groupChatRunId(session),
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
