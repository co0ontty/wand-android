package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDispatchRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.BOARD_TASK_PRIORITIES
import com.wand.app.data.BOARD_TASK_DETAIL_STATUSES
import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ExecutionSubject
import com.wand.app.data.ModelsResponse
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TeamRunAction
import com.wand.app.data.Workspace
import com.wand.app.data.dispatchStartBlockedReason
import com.wand.app.data.boardAgentModelName
import com.wand.app.data.boardParentTaskOptions
import com.wand.app.data.groupBoardSessionsByAgent
import com.wand.app.data.boardTaskKindLabel
import com.wand.app.data.boardTaskModeLabel
import com.wand.app.data.boardTaskPriorityLabel
import com.wand.app.data.boardTaskAgentLabel
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.data.patchBoardTaskBody
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.WandAgentFields
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandChoice
import com.wand.app.ui.components.WandChoiceStrip
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.WandTeamRunPanel
import com.wand.app.ui.components.WandPullToRefresh
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun TaskBoardScreen(
    api: TaskBoardPort,
    workspaceApi: com.wand.app.data.WorkspacePort,
    onOpenBoundSession: ((TaskSessionRoute) -> Unit)? = null,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, isStructured: Boolean) -> Unit,
    /**
     * 卡片点选 / 新建成功后进入详情。看板只负责列表，详情固定由
     * [TaskBoardTaskScreen] 占一屏：侧栏（或全屏列表）不会再多堆一层详情顶栏。
     */
    onOpenTaskDetail: (taskId: String) -> Unit,
    linkedWorkspaceId: String? = null,
    embedded: Boolean = false,
    /** 首页嵌入态隐藏看板搜索，独立看板保留自己的搜索框。 */
    showSearchField: Boolean = true,
    /** 页面底部还浮着东西（首页的菜单胶囊）时，给列表尾部留出的避让高度。 */
    bottomClearance: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshingBoard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var internalQuery by remember { mutableStateOf("") }
    val query = internalQuery
    val onQueryChange: (String) -> Unit = { internalQuery = it }
    var filterWorkspaceId by remember { mutableStateOf(linkedWorkspaceId.orEmpty()) }
    var statusFilter by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    // 从哪一列点开的「新建」决定初始状态：待办 = 只创建，进行中 = 创建并指派。
    var createStatus by remember { mutableStateOf("todo") }
    var busy by remember { mutableStateOf(false) }
    var lastAgent by remember { mutableStateOf(BoardTaskAgent.default()) }
    // 新建对话框的「指派对象」团队列表：拉取失败回落空，不影响看板本身。
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var employees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    // 「建卡成功但交给团队失败」的卡 id：重发只走 startTeamRun，绝不重复建卡。
    // 生命周期收敛在「进入新建流程」（openCreateDialog）与「重试消费后」（成功/失败赋值），
    // onDismiss 只是额外保险：任何再开新建对话框的路径都从干净初始态开始。
    var teamRetryTaskId by remember { mutableStateOf<String?>(null) }

    fun openCreateDialog(initialStatus: String) {
        teamRetryTaskId = null
        error = null
        busy = false
        createStatus = initialStatus
        showCreate = true
    }
    val refreshMutex = remember(api) { kotlinx.coroutines.sync.Mutex() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    fun openSession(id: String, structured: Boolean) {
        val task = tasks.firstOrNull { card -> card.sessions.any { it.id == id } }
        val bound = onOpenBoundSession
        if (bound != null && task?.workspaceTaskId != null) {
            bound(TaskSessionRoute(id, structured, task.workspaceId ?: com.wand.app.data.GLOBAL_WORKSPACE_ID,
                task.workspaceTaskId, task.workspace?.name, task.title))
        } else onOpenSession(id, structured)
    }

    suspend fun refresh(showProgress: Boolean = false) = refreshMutex.withLock {
        if (showProgress) loading = true
        try {
            tasks = api.listBoardTasks()
            error = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "无法加载任务。"
        } finally {
            loading = false
        }
    }

    suspend fun refreshBoardData(showProgress: Boolean = false) {
        refresh(showProgress)
        workspaces = runCatching { api.listBoardWorkspaces() }.getOrDefault(emptyList())
        models = runCatching { api.boardModels() }.getOrNull()
        lastAgent = runCatching { api.boardTaskAgentDefaults() }.getOrDefault(BoardTaskAgent.default())
        teams = runCatching { api.listAiTeams() }.getOrDefault(emptyList())
        employees = runCatching { api.listSiliconEmployees() }.getOrDefault(emptyList())
    }

    /**
     * 标题留空时标题由服务端后台生成。创建响应里只有描述首行占位，
     * 这里短轮询几次，拿到真标题就刷新列表和详情；一直没变就保留占位。
     */
    suspend fun awaitGeneratedBoardTaskTitle(taskId: String, placeholder: String) {
        for (delayMs in listOf(1_200L, 2_000L, 3_000L, 5_000L, 8_000L)) {
            delay(delayMs)
            val task = runCatching { api.getBoardTask(taskId) }.getOrNull() ?: continue
            if (task.title.isNotBlank() && task.title != placeholder) {
                refresh()
                return
            }
        }
    }

    fun patchTask(id: String, body: JSONObject, after: (suspend () -> Unit)? = null) {
        scope.launch {
            busy = true
            try {
                api.updateBoardTask(id, body)
                after?.invoke()
                refresh()
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(api, linkedWorkspaceId) {
        if (!linkedWorkspaceId.isNullOrBlank() && filterWorkspaceId.isEmpty()) {
            filterWorkspaceId = linkedWorkspaceId
        }
        refreshBoardData(showProgress = tasks.isEmpty())
    }

    LaunchedEffect(api, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch { api.taskChanges.collect { refresh() } }
            launch { while (true) { delay(6_000); refresh() } }
        }
    }

    val scoped = filterBoardTasks(
        tasks,
        query,
        filterWorkspaceId,
    )
    val visible = sortBoardTasks(
        if (statusFilter.isBlank()) scoped else scoped.filter { it.status == statusFilter },
    )
    val stats = boardTaskStats(scoped)
    val projectName = workspaces.firstOrNull { it.id == filterWorkspaceId }?.name ?: "全部任务"

    Scaffold(
        containerColor = WandColors.bgPrimary,
        topBar = {
            if (!embedded) {
                WandDetailTopBar(
                    title = "任务",
                    subtitle = "任务管理 · ${stats.remaining} 项未完成",
                    leading = { WandDetailBackButton(onClick = onBack) },
                )
            }
        },
    ) { padding ->
        WandPullToRefresh(
            isRefreshing = refreshingBoard,
            onRefresh = {
                if (!loading && !refreshingBoard) {
                    refreshingBoard = true
                    scope.launch {
                        try {
                            refreshBoardData()
                        } finally {
                            refreshingBoard = false
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && tasks.isEmpty() -> CircularProgressIndicator(
                    color = WandColors.success,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                else -> TaskBoardList(
                    tasks = visible,
                    stats = stats,
                    projectName = projectName,
                    workspaces = workspaces,
                    query = query,
                    showSearch = showSearchField,
                    bottomClearance = bottomClearance,
                    filterWorkspaceId = filterWorkspaceId,
                    statusFilter = statusFilter,
                    onQueryChange = onQueryChange,
                    onFilterWorkspace = { filterWorkspaceId = it },
                    onStatusFilter = { statusFilter = it },
                    onOpen = { task -> onOpenTaskDetail(task.id) },
                    onToggleComplete = { task ->
                        patchTask(task.id, patchBoardTaskBody(status = boardTaskToggledStatus(task.status)))
                    },
                    onSwipeAction = { task, action ->
                        val status = boardTaskSwipeTargetStatus(action)
                        if (status != null) {
                            patchTask(task.id, patchBoardTaskBody(status = status))
                        } else {
                            // 归档 = DELETE（服务端 archiveBoardTask），与详情页「归档」一致。
                            scope.launch {
                                busy = true
                                try {
                                    api.deleteBoardTask(task.id)
                                    refresh()
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    onOpenSession = ::openSession,
                    onCreateForStatus = { status -> openCreateDialog(status) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { message ->
                Surface(
                    color = WandColors.dangerSoft,
                    shape = WandShapes.sm,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) { Text(message, color = WandColors.danger, modifier = Modifier.padding(12.dp)) }
            }
        }
    }

    if (showCreate) {
        CreateBoardTaskDialog(
            workspaces = workspaces,
            tasks = tasks,
            teams = teams,
            employees = employees,
            models = models,
            lastAgent = lastAgent,
            defaultWorkspaceId = filterWorkspaceId,
            initialStatus = createStatus,
            busy = busy,
            error = error,
            onDismiss = {
                if (!busy) {
                    showCreate = false
                    teamRetryTaskId = null
                }
            },
            teamRunRetry = teamRetryTaskId != null,
            api = api,
            workspaceApi = workspaceApi,
            onDispatchStarted = { started ->
                // 派工由服务端建卡 + 起 run：关窗、刷新看板，直接落到那张卡的详情。
                showCreate = false
                teamRetryTaskId = null
                scope.launch { refresh() }
                onOpenTaskDetail(started.taskId)
            },
            onCreate = create@{ title, description, status, priority, directory, agent, parentTaskId, teamId, employeeId ->
                if (busy) return@create
                busy = true
                error = null
                scope.launch {
                    try {
                        val workspace = resolveCreationWorkspace(workspaceApi, directory)
                        val workspaceId = workspace?.id
                        if (workspace != null && workspaces.none { it.id == workspace.id }) workspaces = workspaces + workspace
                        // 上一步「交给团队」失败的卡还在手里：只在本次仍会走团队链路时才复用旧卡，
                        // 条件与下面的重发、按钮文案同一份（boardDispatchesToTeam）——
                        // 改回「待办」再点「创建任务」就真的是建新卡。
                        val teamDispatch = boardDispatchesToTeam(
                            teamSelected = teamId != null,
                            dispatches = boardCreateDispatches(status),
                            hasDescription = description.isNotBlank(),
                        )
                        val retriedTaskId = if (teamDispatch) teamRetryTaskId else null
                        val creationSubject = teamId?.let(ExecutionSubject::team)
                            ?: employeeId?.let(ExecutionSubject::employee)
                            ?: ExecutionSubject.cli(agent.provider)
                        val createdTask: BoardTask? = if (retriedTaskId != null) null else api.createBoardSubjectTask(
                            title, description, status, priority, workspaceId,
                            agent.takeIf { creationSubject.type == "cli" }, parentTaskId, creationSubject,
                        )
                        val createdId = retriedTaskId ?: createdTask!!.id
                        if (createdTask != null) {
                            lastAgent = agent
                            runCatching { api.saveBoardTaskAgentDefaults(agent) }
                        }
                        // 交给团队：建卡 + startTeamRun（note 传空，目标由卡片标题+描述在服务端拼装）
                        // 整条链都成功才算成；失败保留对话框并原位显示错误原文，卡已建好则照常刷新列表。
                        if (teamDispatch) {
                            try {
                                api.startTeamRun(createdId, teamId!!, "")
                                teamRetryTaskId = null
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                teamRetryTaskId = createdId
                                refresh()
                                error = "任务已创建，但交给团队失败：${e.message ?: "可稍后在详情里再交给团队"}"
                                return@launch
                            }
                        }
                        showCreate = false
                        onOpenTaskDetail(createdId)
                        // 只有「进行中」列的新建才顺带第一次指派；「待办」列只创建任务；团队分支不再派 CLI。
                        var dispatchError: String? = null
                        var dispatchedSessionId: String? = null
                        var dispatchedStructured = true
                        if (teamId == null && retriedTaskId == null &&
                            boardCreateDispatches(status) && description.isNotBlank()
                        ) {
                            val subject = employeeId?.let(ExecutionSubject::employee)
                                ?: ExecutionSubject.cli(agent.provider)
                            runCatching { api.dispatchBoardSubject(createdId, subject, agent, description, workspaceId) }
                                .onSuccess {
                                    dispatchedSessionId = boardDispatchSessionId(it.sessionId)
                                    dispatchedStructured = it.isStructured
                                }
                                .onFailure { dispatchError = it.message ?: "任务已创建，但第一次指派失败。" }
                        }
                        refresh()
                        if (dispatchError != null) error = dispatchError
                        // 创建并指派成功后直接落到新 Agent 的会话，不让用户再自己找一遍。
                        dispatchedSessionId?.let { openSession(it, dispatchedStructured) }
                        createdTask?.let { fresh ->
                            if (title.isBlank() && fresh.titleSource == "auto") {
                                scope.launch { awaitGeneratedBoardTaskTitle(fresh.id, fresh.title) }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        error = e.message
                    } finally { busy = false }
                }
            },
        )
    }

    if (embedded) Unit
}

@Composable
private fun TaskBoardList(
    tasks: List<BoardTask>,
    stats: BoardTaskStats,
    projectName: String,
    workspaces: List<Workspace>,
    query: String,
    showSearch: Boolean = true,
    filterWorkspaceId: String,
    statusFilter: String,
    onQueryChange: (String) -> Unit,
    onFilterWorkspace: (String) -> Unit,
    onStatusFilter: (String) -> Unit,
    onOpen: (BoardTask) -> Unit,
    onToggleComplete: (BoardTask) -> Unit,
    onSwipeAction: (BoardTask, BoardTaskSwipeAction) -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    onCreateForStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
) {
    val grouped = groupedBoardTasks(tasks)
    val archived = boardArchivedTasks(tasks)
    var archiveCollapsed by remember { mutableStateOf(true) }
    var doneCollapsed by remember { mutableStateOf(true) }
    // 同一时刻只允许一张卡划开：新划开的卡接管，旧卡在自身 LaunchedEffect 里收起。
    // 这份状态必须留在列表内部：手势每帧都会改它，上提到屏幕层会让整屏逐帧重组，卡片就直接拖不动了。
    var swipedTaskId by remember { mutableStateOf<String?>(null) }
    // 就地展开的任务：点卡片在当前页展开详情，其他卡片顺势下移，不跳详情页（规则 7）。
    var expandedTaskId by remember { mutableStateOf<String?>(null) }
    fun setSwipedTaskId(id: String?) {
        swipedTaskId = id
    }
    // 划出的动作不直接改任务，先经过二次确认，避免误触直接改状态。
    var pendingSwipe by remember { mutableStateOf<Pair<BoardTask, BoardTaskSwipeAction>?>(null) }
    val listState = rememberLazyListState()
    // 列表一滚动就收掉已划开的卡，不给「停在待点状态」的机会。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) setSwipedTaskId(null)
    }
    // 筛选条件变了就收起已划开的卡，避免隐藏卡片仍占用交互状态。
    LaunchedEffect(statusFilter, filterWorkspaceId, query) {
        setSwipedTaskId(null)
        expandedTaskId = null
    }
    // 任务详情的展开只由卡片点击控制，不拦截页面返回。
    val archiveOpen = !archiveCollapsed || query.isNotBlank() || statusFilter == "archived"
    val doneOpen = boardDoneSectionOpen(doneCollapsed, query)
    val motionEnabled = !reduceMotionEnabled()
    val showWorkspace = filterWorkspaceId.isBlank()
    // 分组行、归档行与筛选结果三个分支渲染的是同一条任务卡，只保留这一处构造。
    val taskRow: @Composable LazyItemScope.(BoardTask) -> Unit = { task ->
        BoardTaskItem(
            task = task,
            showWorkspace = showWorkspace,
            revealed = swipedTaskId == task.id,
            onRevealedChange = { open -> setSwipedTaskId(if (open) task.id else null) },
            onOpen = { onOpen(task) },
            expanded = expandedTaskId == task.id,
            onToggleExpanded = {
                expandedTaskId = if (expandedTaskId == task.id) null else task.id
            },
            onToggleComplete = { onToggleComplete(task) },
            onOpenSession = onOpenSession,
            onSwipeAction = { action ->
                setSwipedTaskId(null)
                pendingSwipe = task to action
            },
            modifier = if (motionEnabled) Modifier.animateItem() else Modifier,
        )
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "hero") {
            TaskBoardHero(
                projectName = projectName,
                stats = stats,
                query = query,
                onQueryChange = onQueryChange,
                showSearch = showSearch,
            )
        }
        item(key = "filters") {
            Column(modifier = Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WandChoice(
                    label = workspaces.firstOrNull { it.id == filterWorkspaceId }?.name ?: "所有项目",
                    options = listOf("" to "所有项目") + workspaces.map { it.id to it.name },
                    onSelect = onFilterWorkspace,
                    chip = false,
                )
                WandChoiceStrip(
                    options = listOf(
                        "" to "全部",
                        "todo" to "待办",
                        "doing" to "进行中",
                        "done" to "已完成",
                        "archived" to "归档",
                    ),
                    selected = statusFilter,
                    onSelect = onStatusFilter,
                    minHeight = 48.dp,
                    labelFontSize = 12.sp,
                    activeTextColor = WandColors.brand,
                )
            }
        }
        if (tasks.isNotEmpty()) {
            item(key = "create-task") {
                WandButton(
                    label = "新建任务",
                    onClick = { onCreateForStatus("todo") },
                    icon = WandIcons.add,
                    variant = WandButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = WandIcons.todo,
                    title = if (query.isNotBlank() || statusFilter.isNotBlank()) "没有匹配的任务" else "还没有任务",
                    subtitle = if (query.isNotBlank() || statusFilter.isNotBlank()) {
                        "换个筛选条件，或新建一条任务。"
                    } else {
                        "写下要做的事，再选择由谁执行。"
                    },
                    actionText = "新建任务",
                    onAction = { onCreateForStatus("todo") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
            }
        } else if (statusFilter.isBlank()) {
            grouped.forEach { (status, items) ->
                val sectionArchived = if (status == "done") archived else emptyList()
                if (items.isEmpty() && sectionArchived.isEmpty()) return@forEach
                item(key = "header-$status") {
                    BoardSectionHeader(
                        status = status,
                        count = items.size,
                        onAdd = onCreateForStatus,
                        expanded = status != "done" || doneOpen,
                        onToggle = if (status == "done") ({ doneCollapsed = !doneCollapsed }) else null,
                    )
                }
                if (status != "done" || doneOpen) {
                    items(items, key = { it.id }) { task -> taskRow(task) }
                }
                if (status == "done" && sectionArchived.isNotEmpty()) {
                    item(key = "archive-header") {
                        BoardArchiveHeader(
                            count = sectionArchived.size,
                            expanded = archiveOpen,
                            onToggle = { archiveCollapsed = !archiveCollapsed },
                        )
                    }
                    if (archiveOpen) {
                        items(sectionArchived, key = { it.id }) { task -> taskRow(task) }
                    }
                }
            }
        } else {
            items(tasks, key = { it.id }) { task -> taskRow(task) }
        }
    }
    pendingSwipe?.let { (task, action) ->
        BoardTaskSwipeConfirmDialog(
            task = task,
            action = action,
            onDismiss = { pendingSwipe = null },
            onConfirm = {
                pendingSwipe = null
                onSwipeAction(task, action)
            },
        )
    }
}

/** 划出动作后的二次确认；确认才真正改状态或归档。 */
@Composable
private fun BoardTaskSwipeConfirmDialog(
    task: BoardTask,
    action: BoardTaskSwipeAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WandDialog(
        title = boardTaskSwipeActionTitle(action),
        onDismissRequest = onDismiss,
        icon = boardTaskSwipeActionIcon(action),
        confirm = WandDialogAction(
            label = boardTaskSwipeActionLabel(action),
            destructive = action == BoardTaskSwipeAction.Archive,
            onClick = onConfirm,
        ),
        dismiss = WandDialogAction(label = "取消", onClick = onDismiss),
    ) {
        Text(
            "「${boardTaskCardTitle(task)}」",
            color = WandColors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            boardTaskSwipeConfirmMessage(action),
            color = WandColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TaskBoardHero(
    projectName: String,
    stats: BoardTaskStats,
    query: String,
    onQueryChange: (String) -> Unit,
    showSearch: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(projectName, color = WandColors.textPrimary,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${stats.remaining} 项未完成", color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BoardMetric("待办", stats.todo, boardStatusColor("todo"), Modifier.weight(1f))
            BoardMetric("进行中", stats.doing, boardStatusColor("doing"), Modifier.weight(1f))
            BoardMetric("已完成", stats.done, boardStatusColor("done"), Modifier.weight(1f))
        }
        if (showSearch) BoardSearchCapsule(value = query, onValueChange = onQueryChange)
    }
}

@Composable
private fun BoardSearchCapsule(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(WandShapes.sm)
            .background(WandColors.surfaceSoft)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            WandIcons.search,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier.size(13.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(
                color = WandColors.textPrimary,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(WandColors.brand),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            "搜索任务",
                            color = WandColors.textMuted,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(48.dp)) {
                Icon(WandIcons.close, contentDescription = "清除搜索",
                    tint = WandColors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BoardMetric(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, color = WandColors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(
            value.toString(),
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun BoardArchiveHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth().heightIn(min = 48.dp)
            .clip(WandShapes.sm)
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, top = 8.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            WandIcons.archive,
            contentDescription = if (expanded) "收起归档任务" else "展开归档任务",
            tint = WandColors.textMuted,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "归档任务",
            color = WandColors.textMuted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            count.toString(),
            color = WandColors.textMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BoardSectionHeader(
    status: String,
    count: Int,
    onAdd: (String) -> Unit,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    val color = boardStatusColor(status)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenNormal()),
        label = "doneSectionChevron",
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            boardTaskStatusLabel(status),
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            count.toString(),
            color = WandColors.textMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.weight(1f))
        if (onToggle != null) {
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起已完成任务" else "展开已完成任务",
                tint = WandColors.textMuted,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        WandIconButton(
            icon = WandIcons.add,
            contentDescription = boardGroupAddTaskDescription(status),
            onClick = { onAdd(status) },
            variant = WandIconButtonVariant.Compact,
        )
    }
}

@Composable
private fun BoardTaskItem(
    task: BoardTask,
    showWorkspace: Boolean,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleComplete: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    onSwipeAction: (BoardTaskSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoardTaskSwipeCard(
        status = task.status,
        revealed = revealed,
        onRevealedChange = onRevealedChange,
        onAction = onSwipeAction,
        modifier = modifier,
    ) {
        BoardTaskCard(
            task = task,
            showWorkspace = showWorkspace,
            onOpen = onOpen,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            onToggleComplete = onToggleComplete,
            onOpenSession = onOpenSession,
        )
    }
}

private val BoardCardChipShape = RoundedCornerShape(8.dp)
private val BoardCardBlockShape = RoundedCornerShape(8.dp)

/** 任务卡上元信息芯片的统一几何：22dp 高、8dp 圆角、11sp 文本。 */
private val BoardCardChipHeight = 22.dp

/** 连续任务行：先读标题与摘要；会话按需展开，风险保留语义色，普通元数据不重复加容器。 */
@Composable
private fun BoardTaskCard(
    task: BoardTask,
    showWorkspace: Boolean,
    onOpen: () -> Unit,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
    onToggleComplete: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
) {
    val done = task.status == "done" || task.status == "archived"
    val model = boardTaskCardModel(task, showWorkspace)
    // 展开态给全量内容：描述不再截两行、会话不再只留前三条、标签不再折成 +N。
    val visibleSessions = if (expanded) {
        boardTaskCardSessions(task.sessions, limit = Int.MAX_VALUE)
    } else {
        model.sessions
    }
    val extraSessionCount = (task.sessions.size - visibleSessions.size).coerceAtLeast(0)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggleExpanded)
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardStatusCheck(
                status = task.status,
                onClick = onToggleComplete,
                modifier = Modifier,
            )
            Text(
                model.title,
                color = if (done) WandColors.textMuted else WandColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
            model.identifier?.let { identifier ->
                Text(
                    identifier,
                    color = WandColors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 15.dp),
                )
            }
        }
        model.body?.let { body ->
            Text(
                body,
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                // 未展开时标题化的摘要只给两行；展开后完整读出来。
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (model.hasChips) {
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                model.workspaceName?.let { name ->
                    BoardMetaChip(label = name)
                }
                model.milestoneName?.let { name ->
                    BoardMetaChip(label = name, icon = WandIcons.milestone)
                }
                model.priority?.let { priority ->
                    val color = boardPriorityColor(priority)
                    BoardMetaChip(
                        label = boardTaskPriorityLabel(priority),
                        icon = WandIcons.priority,
                        color = color,
                        containerColor = color.copy(alpha = 0.14f),
                    )
                }
                model.labels.forEach { label ->
                    BoardMetaChip(label = label)
                }
                if (model.extraLabelCount > 0) {
                    Text(
                        "+${model.extraLabelCount}",
                        color = WandColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .height(BoardCardChipHeight)
                            .padding(horizontal = 2.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
                model.due?.let { due ->
                    val color = if (due.overdue) WandColors.danger else WandColors.textSecondary
                    BoardMetaChip(
                        label = due.label,
                        icon = WandIcons.due,
                        color = color,
                        containerColor = if (due.overdue) WandColors.dangerSoft else null,
                    )
                }
                model.agentLabel?.let { label ->
                    BoardMetaChip(label = label)
                }
            }
        }
        if (!expanded && task.sessions.isNotEmpty()) {
            Text("${task.sessions.size} 个会话 · 点按展开", color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (expanded && (visibleSessions.isNotEmpty() || extraSessionCount > 0)) {
            BoardTaskSessions(
                sessions = visibleSessions,
                extraCount = extraSessionCount,
                onOpenSession = onOpenSession,
                // 「+N 个会话」不再跳页：点一下原地把剩下的会话摊开（规则 7）。
                onOpenTask = onToggleExpanded,
            )
        }
        model.processingLabel?.let { label ->
            BoardProcessingRow(
                label = label,
                running = model.running,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        // 展开出来的收尾行：详情页仍是完整入口，但不再拦着「只想多看一眼」的人。
        WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "打开完整任务页",
                    color = WandColors.brand,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .heightIn(min = 48.dp).clip(BoardCardBlockShape)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "收起",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .heightIn(min = 48.dp).clip(BoardCardBlockShape)
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
        HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f), modifier = Modifier.padding(top = 12.dp))
    }
}

/**
 * 会话区：左侧一根分组细线，行内是「工具徽标 + 会话标题 + 运行跳动点」。
 * 同一任务里多个同名工具的会话靠标题区分，不再是一排认不出来的同名胶囊。
 */
@Composable
private fun BoardTaskSessions(
    sessions: List<BoardTaskCardSession>,
    extraCount: Int,
    onOpenSession: (String, Boolean) -> Unit,
    onOpenTask: () -> Unit,
) {
    val railColor = WandColors.borderStrong
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .drawBehind {
                drawRoundRect(
                    color = railColor,
                    topLeft = Offset.Zero,
                    size = Size(2.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sessions.forEach { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(BoardCardBlockShape)
                        .clickable { onOpenSession(session.id, session.isStructured) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = BrandLogos.painterForProvider(session.provider),
                        contentDescription = null,
                        tint = BrandLogos.tintForProvider(session.provider, WandColors.textPrimary),
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        session.label,
                        color = if (session.running) WandColors.textPrimary else WandColors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (session.running) BoardAgentDots()
                }
            }
            if (extraCount > 0) {
                Text(
                    "+$extraCount 个会话",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth().heightIn(min = 48.dp)
                        .clip(BoardCardBlockShape)
                        .clickable(onClick = onOpenTask)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun BoardStatusCheck(
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = status == "done" || status == "archived"
    val doing = status == "doing"
    val green = WandColors.success
    // 勾选圈是看板上最高频的一次点按：底、描边和勾同步过渡，状态变化不再硬闪。
    val motionEnabled = !reduceMotionEnabled()
    val fill by animateColorAsState(
        targetValue = if (done) green else Color.Transparent,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "boardStatusCheckFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (done) green else green.copy(alpha = 0.55f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "boardStatusCheckStroke",
    )
    Box(
        modifier = modifier
            .size(48.dp).clip(CircleShape)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { contentDescription = if (done) "重新打开任务" else "完成任务" }
            .padding(15.dp)
            .border(1.5.dp, stroke, CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        when {
            done -> Icon(
                WandIcons.check,
                contentDescription = "标为未完成",
                tint = Color.White,
                modifier = Modifier.size(11.dp),
            )
            doing -> Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(green),
            )
        }
    }
}

/**
 * 普通元信息平铺；仅优先级和逾期用弱语义底色，避免每个属性都成为独立卡片。
 */
@Composable
private fun BoardMetaChip(
    label: String,
    icon: ImageVector? = null,
    color: Color = WandColors.textSecondary,
    containerColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .height(BoardCardChipHeight)
            .clip(BoardCardChipShape)
            .background(containerColor ?: Color.Transparent)
            .padding(horizontal = if (containerColor != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        }
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val BOARD_AGENT_DOT_CYCLE_MS = 1_200
private const val BOARD_AGENT_DOT_STAGGER_MS = 150

/**
 * 具体 Agent 会话胶囊尾部的三点跳动：该会话在跑时出现，节奏对齐 Web 任务卡与聊天流式占位。
 * 三个点错峰循环，跳动幅度 3dp，只动 translationY 和 alpha，不引起父容器重布局。
 */
@Composable
private fun BoardAgentDots(modifier: Modifier = Modifier) {
    val animate = !reduceMotionEnabled()
    // 关闭动画时不要建无限循环：三点静止成实心，也不再每帧唤醒合成器。
    val transition = if (animate) rememberInfiniteTransition(label = "boardAgentDots") else null
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (index in 0 until 3) {
            val raised = if (transition != null) {
                val lift by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = BOARD_AGENT_DOT_CYCLE_MS
                            0f at 0
                            1f at 120
                            0f at 240
                            0f at BOARD_AGENT_DOT_CYCLE_MS
                        },
                        initialStartOffset = StartOffset(index * BOARD_AGENT_DOT_STAGGER_MS),
                    ),
                    label = "boardAgentDotLift$index",
                )
                lift
            } else {
                0f
            }
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = -3.dp.toPx() * raised }
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(
                        WandColors.success.copy(alpha = if (animate) 0.35f + 0.65f * raised else 1f),
                    ),
            )
        }
    }
}

@Composable
private fun BoardProcessingRow(
    label: String,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val animate = running && !reduceMotionEnabled()
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "boardProcessing")
        val value by transition.animateFloat(
            initialValue = WandMotion.breathAlphaMin,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "boardProcessingAlpha",
        )
        value
    } else {
        1f
    }
    Row(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(WandColors.success),
        )
        Text(label, color = WandColors.success, style = MaterialTheme.typography.labelSmall)
    }
}

/** 看板任务详情正文。内嵌列表（手机）与宽屏右侧详情页共用同一份实现。 */
@Composable
internal fun TaskBoardDetailPane(
    task: BoardTask,
    workspaces: List<Workspace>,
    models: ModelsResponse?,
    lastAgent: BoardTaskAgent,
    teams: List<AiTeam>,
    employees: List<SiliconEmployee>,
    teamRun: AiTeamRunDetail?,
    busy: Boolean,
    /** 派发 / 团队操作的失败文案；成功刷新后由调用方清空。 */
    actionError: String? = null,
    onPatch: (JSONObject) -> Unit,
    onRemember: (BoardTaskAgent) -> Unit,
    onDispatch: suspend (BoardTaskAgent, String, ExecutionSubject) -> Boolean,
    onTeamRunAction: (TeamRunAction) -> Unit,
    onDelete: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    onMoveSession: (com.wand.app.data.BoardTaskSession) -> Unit,
    baseUrl: String = "",
    /** 「打开群聊」：参数是团队运行 id，由上层落到 IM 群聊页。 */
    onOpenTeamChat: (runId: String, taskIdentifier: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var title by remember(task.id, task.title) { mutableStateOf(task.title) }
    var agent by remember(task.id, task.agent) { mutableStateOf(task.agent ?: lastAgent) }
    var dispatchSubjectKey by remember(task.id, task.executionSubject?.key) {
        mutableStateOf(task.executionSubject?.key ?: "cli")
    }
    val hasAgents = task.sessions.isNotEmpty()
    var composeOpen by remember(task.id) { mutableStateOf(!hasAgents) }
    var executionSettingsOpen by remember(task.id) { mutableStateOf(false) }
    var composePrompt by remember(task.id) { mutableStateOf(if (hasAgents) "" else task.description) }
    LaunchedEffect(teams, employees, dispatchSubjectKey) {
        val subject = ExecutionSubject.fromKey(dispatchSubjectKey, agent.provider)
        if ((subject.type == "team" && teams.none { it.id == subject.id }) ||
            (subject.type == "employee" && employees.none { it.id == subject.id })) {
            dispatchSubjectKey = "cli"
        }
    }
    val done = task.status == "done" || task.status == "archived"
    val workspaceChoices = buildList {
        add("" to "未归属工作区（使用临时目录）")
        val seen = workspaces.map { it.id }.toSet()
        workspaces.forEach { add(it.id to it.name) }
        val current = task.workspace
        if (current != null && current.id !in seen) add(current.id to current.name)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WandTextField(
            value = title,
            onValueChange = { title = it.replace("\n", "") },
            label = "任务标题（可选）",
            placeholder = "留空则保留自动生成的标题",
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WandButton(
                label = "保存",
                onClick = {
                    onPatch(patchBoardTaskBody(title = title.trim().ifBlank { task.title }))
                },
                enabled = !busy && title.trim().isNotEmpty() && title.trim() != task.title,
                variant = WandButtonVariant.Secondary,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            WandButton(
                label = if (done) "重新打开" else "完成任务",
                onClick = { onPatch(patchBoardTaskBody(status = boardTaskToggledStatus(task.status))) },
                enabled = !busy,
                variant = WandButtonVariant.Secondary,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (task.description.isNotBlank()) {
            Text(task.description, style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
        }
        HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WandChoice(
                label = "状态 · ${boardTaskStatusLabel(task.status)}",
                options = BOARD_TASK_DETAIL_STATUSES.map { it to boardTaskStatusLabel(it) },
                onSelect = { if (!busy && it != task.status) onPatch(patchBoardTaskBody(status = it)) },
                enabled = !busy,
            )
            WandChoice(
                label = "优先级 · ${boardTaskPriorityLabel(task.priority)}",
                options = BOARD_TASK_PRIORITIES.map { it to boardTaskPriorityLabel(it) },
                onSelect = { onPatch(patchBoardTaskBody(priority = it)) },
                enabled = !busy,
            )
            WandChoice(
                label = "工作区 · ${task.workspace?.name ?: "未归属工作区"}",
                options = workspaceChoices,
                onSelect = { onPatch(patchBoardTaskBody(workspaceId = it.ifBlank { null })) },
                enabled = !busy,
            )
        }
        Text("任务内的会话", color = WandColors.textSecondary, style = MaterialTheme.typography.labelLarge)
        val agentGroups = groupBoardSessionsByAgent(task.sessions, task.agent)
        if (agentGroups.isEmpty()) {
            Text(
                "还没有指派 Agent。",
                color = WandColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            agentGroups.forEach { group ->
                Text(
                    group.label + group.agent?.let {
                        val model = boardAgentModelName(models, it.provider, it.model)
                        (if (model.isBlank()) "" else " · $model") + " · ${boardTaskModeLabel(it.mode)}"
                    }.orEmpty(),
                    color = WandColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (group.sessions.isEmpty()) {
                    Text(
                        "默认执行参数 · 尚无关联会话",
                        color = WandColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    group.sessions.forEach { session ->
                        val running = boardSessionRunning(session.status)
                        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .clickable { onOpenSession(session.id, session.isStructured) }
                            .padding(vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    painter = BrandLogos.painterForProvider(session.provider),
                                    contentDescription = null,
                                    tint = BrandLogos.tintForProvider(session.provider, WandColors.textPrimary),
                                    modifier = Modifier.size(16.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            session.title.ifBlank { session.toolLabel },
                                            color = WandColors.textPrimary,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (running) BoardAgentDots()
                                    }
                                    Text(
                                        listOfNotNull(
                                            boardAgentModelName(models, group.provider, session.model).takeIf { it.isNotBlank() },
                                            boardSessionStatusLabel(session.status),
                                        ).joinToString(" · "),
                                        color = WandColors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                WandIconButton(icon = WandIcons.folder,
                                    contentDescription = "移动会话到任务 ${session.title}",
                                    onClick = { if (!busy) onMoveSession(session) },
                                    variant = WandIconButtonVariant.Quiet)
                                Icon(WandIcons.chevronRight, contentDescription = null,
                                    tint = WandColors.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        if (composeOpen) {
            val subject = ExecutionSubject.fromKey(dispatchSubjectKey, agent.provider)
            val teamTarget = teams.firstOrNull { subject.type == "team" && it.id == subject.id }
            val employeeTarget = employees.firstOrNull { subject.type == "employee" && it.id == subject.id }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        teamTarget != null -> if (task.sessions.isEmpty()) "交给团队" else "再交给团队处理"
                        employeeTarget != null -> if (task.sessions.isEmpty()) "交给员工" else "再交给员工处理"
                        else -> if (task.sessions.isEmpty()) "指派 Agent" else "再指派一个 Agent"
                    },
                    color = WandColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (teamTarget != null) "输入这次交给团队的提示词，直接开工"
                    else if (employeeTarget != null) "输入这次交给员工的提示词，直接开工"
                    else "先输入提示词，再选参数直接派发",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                WandTextField(
                    value = composePrompt,
                    onValueChange = { composePrompt = it },
                    label = "提示词",
                    placeholder = if (teamTarget != null)
                        "输入这次交给团队的提示词。任务卡里的旧描述不会自动带上。留空则按任务内容开工。"
                    else if (employeeTarget != null)
                        "输入这次交给员工的提示词。留空则按任务内容开工。"
                    else "输入这次派给 Agent 的提示词。任务卡里的旧描述不会自动带上。",
                    minLines = 4,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (teams.isNotEmpty() || employees.isNotEmpty()) {
                    WandChoice(
                        label = "指派对象 · ${employeeTarget?.name ?: teamTarget?.name ?: "CLI 工具"}",
                        options = buildList {
                            add("cli" to "CLI 工具")
                            employees.forEach { add("employee:${it.id}" to "员工 · ${it.name}") }
                            teams.forEach { add("team:${it.id}" to "团队 · ${it.name}（${it.members.size} 人）") }
                        },
                        onSelect = {
                            dispatchSubjectKey = it
                            val selected = ExecutionSubject.fromKey(it, agent.provider)
                            onPatch(JSONObject().put("executionSubject", selected.toJson()))
                        },
                        enabled = !busy,
                    )
                }
                if (teamTarget == null && employeeTarget == null) {
                    WandButton(
                        label = "执行设置 · ${boardTaskAgentLabel(agent.provider, agent.engine)} · ${boardTaskModeLabel(agent.mode)}",
                        onClick = { executionSettingsOpen = !executionSettingsOpen }, enabled = !busy,
                        variant = WandButtonVariant.Text, modifier = Modifier.fillMaxWidth().semantics {
                            stateDescription = if (executionSettingsOpen) "已展开" else "已收起"
                        },
                    )
                    WandInlinePanel(visible = executionSettingsOpen) {
                    WandAgentFields(
                        models = models,
                        agent = agent,
                        providerLabel = "CLI 工具",
                        enabled = !busy,
                        onChange = { next ->
                            agent = next
                            onRemember(next)
                        },
                    )
                    }
                }
                Spacer(Modifier.height(4.dp))
                WandButton(
                    label = when {
                        busy -> "正在派发…"
                        teamTarget != null -> "交给团队"
                        employeeTarget != null -> "交给员工"
                        else -> "派发 Agent"
                    },
                    onClick = {
                        val selected = ExecutionSubject.fromKey(dispatchSubjectKey, agent.provider)
                        scope.launch {
                            if (onDispatch(agent, composePrompt.trim(), selected)) {
                                // 成功后收起表单，run 摘要在同一位置出现（对齐「提交后原位显示结果」）。
                                composeOpen = false
                                composePrompt = ""
                            }
                        }
                    },
                    enabled = !busy && (subject.type != "cli" || composePrompt.trim().isNotEmpty()),
                    loading = busy,
                    variant = WandButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            // 有会话时默认收起派发表单，但入口必须一眼可见：整行主按钮，不再是一个 36dp 的 +。
            WandButton(
                label = "再指派一个 Agent",
                onClick = {
                    composePrompt = ""
                    composeOpen = true
                },
                enabled = !busy,
                icon = WandIcons.add,
                variant = WandButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        actionError?.let {
            Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall)
        }
        WandInlinePanel(visible = teamRun != null) {
            teamRun?.let { detail ->
                WandTeamRunPanel(
                    detail = detail,
                    baseUrl = baseUrl,
                    busy = busy,
                    onAction = onTeamRunAction,
                    onOpenGroupChat = { runId -> onOpenTeamChat(runId, task.identifier) },
                )
            }
        }
        WandButton(
            label = "归档",
            onClick = onDelete,
            enabled = !busy,
            variant = WandButtonVariant.Danger,
            compact = true,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 新建任务是否顺带完成第一次指派。
 * 「待办」列只创建任务；「进行中」列代表已经决定要跑，所以创建后立刻派给所选 Agent。
 */
internal fun boardCreateDispatches(status: String): Boolean = status == "doing"


private fun boardSessionStatusLabel(status: String): String = when (status) {
    "running" -> "进行中"
    "idle" -> "空闲"
    "exited" -> "已结束"
    "failed" -> "失败"
    else -> status.ifBlank { "会话" }
}

@Composable
private fun boardStatusColor(status: String): Color = when (status) {
    "todo" -> WandColors.thinking
    "doing" -> WandColors.success
    "done" -> WandColors.info
    else -> WandColors.textMuted
}

@Composable
private fun boardPriorityColor(priority: String): Color = when (priority) {
    "urgent" -> WandColors.danger
    "high" -> WandColors.warning
    "medium" -> WandColors.permission
    else -> WandColors.textMuted
}
