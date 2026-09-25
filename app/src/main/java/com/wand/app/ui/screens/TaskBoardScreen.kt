package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.BOARD_TASK_EFFORTS
import com.wand.app.data.BOARD_TASK_KINDS
import com.wand.app.data.BOARD_TASK_PRIORITIES
import com.wand.app.data.BOARD_TASK_PROVIDERS
import com.wand.app.data.BOARD_TASK_DETAIL_STATUSES
import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.Workspace
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.data.groupBoardSessionsByAgent
import com.wand.app.data.boardTaskEffortLabel
import com.wand.app.data.boardTaskKindLabel
import com.wand.app.data.boardTaskModeLabel
import com.wand.app.data.boardTaskPriorityLabel
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.data.normalizeBoardTaskAgentMode
import com.wand.app.data.patchBoardTaskBody
import com.wand.app.data.supportedBoardTaskModes
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import androidx.activity.compose.BackHandler
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
    refreshNonce: Int = 0,
    /**
     * 外部查询词：嵌在首页里时，搜索框由顶部搜索栏承担（[showSearchField] = false），
     * 这里只接收结果，避免同一个屏幕出现两个搜索入口。
     */
    externalQuery: String? = null,
    onExternalQueryChange: ((String) -> Unit)? = null,
    showSearchField: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var internalQuery by remember { mutableStateOf("") }
    val query = externalQuery ?: internalQuery
    val onQueryChange: (String) -> Unit = { value ->
        if (externalQuery != null) onExternalQueryChange?.invoke(value) else internalQuery = value
    }
    var filterWorkspaceId by remember { mutableStateOf(linkedWorkspaceId.orEmpty()) }
    var statusFilter by remember { mutableStateOf("") }
    var boardSwipeOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    // 从哪一列点开的「新建」决定初始状态：待办 = 只创建，进行中 = 创建并指派。
    var createStatus by remember { mutableStateOf("todo") }
    var busy by remember { mutableStateOf(false) }
    var lastAgent by remember { mutableStateOf(BoardTaskAgent.default()) }
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

    LaunchedEffect(api, linkedWorkspaceId, refreshNonce) {
        if (!linkedWorkspaceId.isNullOrBlank() && filterWorkspaceId.isEmpty()) {
            filterWorkspaceId = linkedWorkspaceId
        }
        refresh(showProgress = refreshNonce == 0 || tasks.isEmpty())
        workspaces = runCatching { api.listBoardWorkspaces() }.getOrDefault(emptyList())
        models = runCatching { api.boardModels() }.getOrNull()
        lastAgent = runCatching { api.boardTaskAgentDefaults() }.getOrDefault(BoardTaskAgent.default())
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
        containerColor = Color.Transparent,
        topBar = {
            if (!embedded) {
                WandDetailTopBar(
                    title = "工作台",
                    subtitle = "任务管理 · ${stats.remaining} 项未完成",
                    leading = { WandDetailBackButton(onClick = onBack) },
                    actions = {
                        WandIconButton(
                            icon = WandIcons.refresh,
                            contentDescription = "刷新任务",
                            onClick = { scope.launch { refresh() } },
                            variant = WandIconButtonVariant.Toolbar,
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            // 划开状态时不摆悬浮按钮：它正好压在右下的滑动动作按钮上，会吃掉那一下点击。
            if (!boardSwipeOpen) {
                FloatingActionButton(
                    onClick = {
                        createStatus = "todo"
                        showCreate = true
                    },
                    containerColor = WandColors.success,
                    contentColor = Color.White,
                    shape = CircleShape,
                ) {
                    Icon(WandIcons.add, contentDescription = "新建任务")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
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
                    filterWorkspaceId = filterWorkspaceId,
                    statusFilter = statusFilter,
                    onSwipeOpenChange = { boardSwipeOpen = it },
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
                    onCreateForStatus = { status ->
                        createStatus = status
                        showCreate = true
                    },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
            error?.let { message ->
                Surface(
                    color = WandColors.dangerSoft,
                    shape = WandShapes.sm,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                ) { Text(message, color = WandColors.danger, modifier = Modifier.padding(12.dp)) }
            }
        }
    }

    if (showCreate) {
        CreateBoardTaskDialog(
            workspaces = workspaces,
            models = models,
            lastAgent = lastAgent,
            defaultWorkspaceId = filterWorkspaceId,
            initialStatus = createStatus,
            busy = busy,
            error = error,
            onDismiss = { if (!busy) showCreate = false },
            onCreate = create@{ title, description, status, priority, workspaceId, agent ->
                if (busy) return@create
                busy = true
                error = null
                scope.launch {
                    try {
                        val created = api.createBoardTask(title, description, status, priority, workspaceId, agent)
                        lastAgent = agent
                        runCatching { api.saveBoardTaskAgentDefaults(agent) }
                        showCreate = false
                        onOpenTaskDetail(created.id)
                        // 只有「进行中」列的新建才顺带第一次指派；「待办」列只创建任务。
                        var dispatchError: String? = null
                        var dispatchedSessionId: String? = null
                        var dispatchedStructured = true
                        if (boardCreateDispatches(status) && description.isNotBlank()) {
                            runCatching { api.dispatchBoardTask(created.id, agent, description, workspaceId) }
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
                        if (title.isBlank() && created.titleSource == "auto") {
                            scope.launch { awaitGeneratedBoardTaskTitle(created.id, created.title) }
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
    onSwipeOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = groupedBoardTasks(tasks)
    val archived = boardArchivedTasks(tasks)
    var archiveCollapsed by remember { mutableStateOf(true) }
    // 同一时刻只允许一张卡划开：新划开的卡接管，旧卡在自身 LaunchedEffect 里收起。
    // 这份状态必须留在列表内部：手势每帧都会改它，上提到屏幕层会让整屏逐帧重组，卡片就直接拖不动了。
    // 只把「有没有张开」这个布尔量报上去，用来给右下角悬浮按钮让位。
    var swipedTaskId by remember { mutableStateOf<String?>(null) }
    // 就地展开的任务：点卡片在当前页展开详情，其他卡片顺势下移，不跳详情页（规则 7）。
    var expandedTaskId by remember { mutableStateOf<String?>(null) }
    fun setSwipedTaskId(id: String?) {
        swipedTaskId = id
        onSwipeOpenChange(id != null)
    }
    // 划出的动作不直接改任务，先经过二次确认，避免误触直接改状态。
    var pendingSwipe by remember { mutableStateOf<Pair<BoardTask, BoardTaskSwipeAction>?>(null) }
    val listState = rememberLazyListState()
    // 列表一滚动就收掉已划开的卡，不给「停在待点状态」的机会。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) setSwipedTaskId(null)
    }
    // 筛选条件变了就把划开状态收掉：被筛走的卡片会让悬浮按钮一直不复位。
    LaunchedEffect(statusFilter, filterWorkspaceId, query) {
        setSwipedTaskId(null)
        expandedTaskId = null
    }
    // 展开的卡片先吃返回键：再按一次才轮到页面返回。
    BackHandler(enabled = expandedTaskId != null) { expandedTaskId = null }
    val archiveOpen = !archiveCollapsed || query.isNotBlank() || statusFilter == "archived"
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
            modifier = Modifier.animateItem(),
        )
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BoardChoice(
                    label = workspaces.firstOrNull { it.id == filterWorkspaceId }?.name ?: "所有项目",
                    options = listOf("" to "所有项目") + workspaces.map { it.id to it.name },
                    onSelect = onFilterWorkspace,
                    leadingIcon = WandIcons.folder,
                    chip = true,
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
                    minHeight = 38.dp,
                    labelFontSize = 12.sp,
                    activeTextColor = WandColors.success,
                )
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = WandIcons.todo,
                    title = if (query.isNotBlank() || statusFilter.isNotBlank()) "没有匹配的任务" else "工作台还是空的",
                    subtitle = if (query.isNotBlank() || statusFilter.isNotBlank()) {
                        "换个筛选条件，或新建一条任务。"
                    } else {
                        "点右下角 +，用绿色勾选把事情做完。"
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
                    BoardSectionHeader(status = status, count = items.size, onAdd = onCreateForStatus)
                }
                items(items, key = { it.id }) { task -> taskRow(task) }
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
    WandCard(
        containerColor = WandColors.successSoft,
        shape = WandShapes.lg,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                projectName,
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showSearch) BoardSearchCapsule(value = query, onValueChange = onQueryChange)
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stats.remaining.toString(),
                color = WandColors.success,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                lineHeight = 42.sp,
            )
            Text(
                "未完成",
                color = WandColors.textMuted,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardMetricTile(
                label = "待办",
                value = stats.todo,
                total = stats.total,
                color = boardStatusColor("todo"),
                modifier = Modifier.weight(1f),
            )
            BoardMetricTile(
                label = "进行中",
                value = stats.doing,
                total = stats.total,
                color = boardStatusColor("doing"),
                modifier = Modifier.weight(1f),
            )
            BoardMetricTile(
                label = "已完成",
                value = stats.done,
                total = stats.total,
                color = boardStatusColor("done"),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BoardSearchCapsule(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .width(148.dp)
            .height(30.dp)
            .clip(WandShapes.full)
            .background(WandColors.surface.copy(alpha = 0.88f))
            .border(0.5.dp, WandColors.border.copy(alpha = 0.72f), WandShapes.full)
            .padding(horizontal = 10.dp),
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
            cursorBrush = SolidColor(WandColors.success),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            "搜索",
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
            Icon(
                WandIcons.close,
                contentDescription = "清除搜索",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(12.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun BoardMetricTile(
    label: String,
    value: Int,
    total: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val ratio = if (total > 0) value.toFloat() / total.toFloat() else 0f
    Column(
        modifier = modifier
            .clip(WandShapes.sm)
            .background(WandColors.surface.copy(alpha = 0.82f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(label, color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
        Text(
            value.toString(),
            color = color,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(WandShapes.full)
                .background(color.copy(alpha = 0.16f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(WandShapes.full)
                    .background(color),
            )
        }
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
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, top = 8.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            WandIcons.folder,
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
private fun BoardSectionHeader(status: String, count: Int, onAdd: (String) -> Unit) {
    val color = boardStatusColor(status)
    Row(
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
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
        WandIconButton(
            icon = WandIcons.add,
            contentDescription = "在${boardTaskStatusLabel(status)}中新建任务",
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

/**
 * 任务卡：标题行 / 摘要 / 元信息 / 会话 / 状态五段纵向排列，段与段之间留 10dp。
 *
 * - 勾选圈、标题、任务编号一行：编号贴右，扫一眼就能对上号；
 * - 元信息统一成 8dp 细边框小芯片（弹丸只留给状态圆点，不再把每个属性都胶囊化）；
 * - 会话不再是一排同名胶囊：左侧一根分组细线 + 每行「工具徽标 + 会话标题 + 运行跳动点」，
 *   点得到具体那一个终端，超出的会话折成「+N 个会话」；
 * - 状态行（正在处理 / 等待验收）压到卡片底部当页脚，顺序对齐 Web 任务卡。
 */
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
    WandCard(
        onClick = onToggleExpanded,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardStatusCheck(
                status = task.status,
                onClick = onToggleComplete,
                modifier = Modifier.padding(top = 2.dp),
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
                modifier = Modifier.weight(1f),
            )
            model.identifier?.let { identifier ->
                Text(
                    identifier,
                    color = WandColors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
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
                    BoardMetaChip(label = name, icon = WandIcons.folder)
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
                        borderColor = color.copy(alpha = 0.32f),
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
                        borderColor = if (due.overdue) WandColors.danger.copy(alpha = 0.32f) else null,
                    )
                }
                model.agentLabel?.let { label ->
                    BoardMetaChip(label = label)
                }
            }
        }
        if (visibleSessions.isNotEmpty() || extraSessionCount > 0) {
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
                    color = WandColors.success,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(BoardCardBlockShape)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "收起",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(BoardCardBlockShape)
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
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
                        .height(26.dp)
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
                        .fillMaxWidth()
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
            .size(18.dp)
            .clip(CircleShape)
            .border(1.5.dp, stroke, CircleShape)
            .background(fill)
            .clickable(role = Role.Checkbox, onClick = onClick),
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
 * 元信息芯片：8dp 圆角细边框，不是胶囊——胶囊只有状态圆点和计数才用。
 * 语义色芯片（优先级 / 逾期）靠弱底色 + 同色文字区分，不额外造一套图标。
 */
@Composable
private fun BoardMetaChip(
    label: String,
    icon: ImageVector? = null,
    color: Color = WandColors.textSecondary,
    containerColor: Color? = null,
    borderColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .height(BoardCardChipHeight)
            .clip(BoardCardChipShape)
            .background(containerColor ?: Color.Transparent)
            .border(0.5.dp, borderColor ?: WandColors.border, BoardCardChipShape)
            .padding(horizontal = 7.dp),
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
    busy: Boolean,
    onPatch: (JSONObject) -> Unit,
    onRemember: (BoardTaskAgent) -> Unit,
    onDispatch: (BoardTaskAgent, String) -> Unit,
    onDelete: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    onMoveSession: (com.wand.app.data.BoardTaskSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember(task.id, task.title) { mutableStateOf(task.title) }
    var agent by remember(task.id, task.agent) { mutableStateOf(task.agent ?: lastAgent) }
    val hasAgents = task.sessions.isNotEmpty()
    var composeOpen by remember(task.id) { mutableStateOf(!hasAgents) }
    var composePrompt by remember(task.id) { mutableStateOf(if (hasAgents) "" else task.description) }
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
                enabled = !busy && title.trim().isNotEmpty(),
                variant = WandButtonVariant.Secondary,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            WandButton(
                label = if (done) "重新打开" else "完成任务",
                onClick = { onPatch(patchBoardTaskBody(status = boardTaskToggledStatus(task.status))) },
                enabled = !busy,
                variant = if (done) WandButtonVariant.Secondary else WandButtonVariant.Success,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text("状态", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BOARD_TASK_DETAIL_STATUSES.forEach { status ->
                val selected = task.status == status
                val color = boardStatusColor(status)
                BoardFilterChip(
                    label = boardTaskStatusLabel(status),
                    selected = selected,
                    color = color,
                    onClick = { if (!busy && !selected) onPatch(patchBoardTaskBody(status = status)) },
                )
            }
        }
        WandCard(contentPadding = PaddingValues(12.dp)) {
            BoardChoice(
                label = "优先级 · ${boardTaskPriorityLabel(task.priority)}",
                options = BOARD_TASK_PRIORITIES.map { it to boardTaskPriorityLabel(it) },
                onSelect = { onPatch(patchBoardTaskBody(priority = it)) },
                enabled = !busy,
            )
            BoardChoice(
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
                    boardTaskProviderLabel(group.provider) +
                        (group.agent?.let {
                            " · ${if (it.model == "default") "默认模型" else it.model} · ${boardTaskModeLabel(it.mode)}"
                        } ?: ""),
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
                        WandCard(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            onClick = { onOpenSession(session.id, session.isStructured) },
                        ) {
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
                                            session.title.ifBlank { boardTaskProviderLabel(session.provider) },
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
                                            session.model.takeIf { it.isNotBlank() && it != "default" },
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
            WandCard(
                containerColor = WandColors.successSoft,
                contentPadding = PaddingValues(14.dp),
            ) {
                Text(
                    if (task.sessions.isEmpty()) "指派 Agent" else "再指派一个 Agent",
                    color = WandColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "先输入提示词，再选参数直接派发",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                WandTextField(
                    value = composePrompt,
                    onValueChange = { composePrompt = it },
                    label = "提示词",
                    placeholder = "输入这次派给 Agent 的提示词…",
                    minLines = 4,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                BoardAgentParamChoices(
                    models = models,
                    agent = agent,
                    providerLabel = "CLI 工具",
                    enabled = !busy,
                    onChange = { next ->
                        agent = next
                        onRemember(next)
                    },
                )
                Spacer(Modifier.height(4.dp))
                WandButton(
                    label = if (busy) "正在派发…" else "派发 Agent",
                    onClick = { onDispatch(agent, composePrompt.trim()) },
                    enabled = !busy && composePrompt.trim().isNotEmpty(),
                    loading = busy,
                    variant = WandButtonVariant.Success,
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

@Composable
private fun CreateBoardTaskDialog(
    workspaces: List<Workspace>,
    models: ModelsResponse?,
    lastAgent: BoardTaskAgent,
    defaultWorkspaceId: String,
    initialStatus: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String, status: String, priority: String, workspaceId: String?, agent: BoardTaskAgent) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(initialStatus) }
    var priority by remember { mutableStateOf("none") }
    var workspaceId by remember { mutableStateOf(defaultWorkspaceId) }
    var agent by remember { mutableStateOf(lastAgent) }
    // 「进行中」列的新建代表已经决定要跑，所以创建后立刻派 Agent；其他列只落库。
    val dispatches = boardCreateDispatches(status)
    WandDialog(
        title = "新建任务",
        onDismissRequest = onDismiss,
        confirm = WandDialogAction(
            label = if (busy) "创建中…" else if (dispatches && description.trim().isNotEmpty()) "创建并指派" else "创建任务",
            enabled = !busy && (title.trim().isNotEmpty() || description.trim().isNotEmpty()),
            onClick = {
                onCreate(title.trim(), description.trim(), status, priority, workspaceId.ifBlank { null }, agent)
            },
        ),
        dismiss = WandDialogAction(label = "取消", onClick = onDismiss, enabled = !busy),
    ) {
        Text("与会话树共用同一个任务分组。", color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall) }
        WandTextField(
            value = title,
            onValueChange = { title = it.replace("\n", "") },
            label = "任务标题（可选）",
            placeholder = "不填写则按描述自动生成",
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        WandTextField(
            value = description,
            onValueChange = { description = it },
            label = "描述",
            placeholder = if (dispatches) "将作为第一个 Agent 的指派内容" else "只创建任务，不指派 Agent",
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        BoardChoice(
            label = "工作区 · ${workspaces.firstOrNull { it.id == workspaceId }?.name ?: "未归属工作区"}",
            options = listOf("" to "未归属工作区（使用临时目录）") + workspaces.map { it.id to it.name },
            onSelect = { workspaceId = it },
        )
        if (dispatches) {
            BoardAgentParamChoices(
                models = models,
                agent = agent,
                providerLabel = "第一次指派",
                onChange = { agent = it },
            )
        }
        BoardChoice(
            label = "会话类型 · ${boardTaskKindLabel(agent.kind)}",
            options = BOARD_TASK_KINDS.map { it to boardTaskKindLabel(it) },
            onSelect = { agent = agent.copy(kind = it) },
        )
        BoardChoice(
            label = "运行模式 · ${boardTaskModeLabel(agent.mode)}",
            options = supportedBoardTaskModes(agent.provider).map { it to boardTaskModeLabel(it) },
            onSelect = { agent = agent.copy(mode = it) },
        )
        BoardChoice(
            label = "状态 · ${boardTaskStatusLabel(status)}",
            options = BOARD_TASK_STATUSES.map { it to boardTaskStatusLabel(it) },
            onSelect = { status = it },
        )
        BoardChoice(
            label = "优先级 · ${boardTaskPriorityLabel(priority)}",
            options = BOARD_TASK_PRIORITIES.map { it to boardTaskPriorityLabel(it) },
            onSelect = { priority = it },
        )
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
private fun BoardFilterChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val background = if (selected) color else color.copy(alpha = 0.14f)
    val foreground = if (selected) Color.White else color
    Text(
        label,
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(WandShapes.full)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 指派参数五连：CLI 工具 / 模型 / 思考深度 / 会话类型 / 运行模式。
 * 看板卡片的「再指派」表单与新建任务弹窗共用，避免两处下拉选项漂移。
 *
 * 会话类型与运行模式始终可选：只创建的任务也会把形态 / 模式记进服务端全局默认，
 * 否则下次派发会退回结构化与标准模式，用户在下拉里的选择会静默丢失。
 */
@Composable
private fun BoardAgentParamChoices(
    models: ModelsResponse?,
    agent: BoardTaskAgent,
    providerLabel: String,
    onChange: (BoardTaskAgent) -> Unit,
    enabled: Boolean = true,
) {
    val modelOptions = boardAgentModelOptions(models, agent.provider)
    BoardChoice(
        label = "$providerLabel · ${boardTaskProviderLabel(agent.provider)}",
        options = BOARD_TASK_PROVIDERS.map { it to boardTaskProviderLabel(it) },
        onSelect = { provider ->
            val nextModels = boardAgentModelOptions(models, provider)
            val model = nextModels.firstOrNull { it.id == agent.model }?.id
                ?: nextModels.firstOrNull()?.id
                ?: "default"
            onChange(
                agent.copy(
                    provider = provider,
                    model = model,
                    mode = normalizeBoardTaskAgentMode(provider, agent.mode),
                ),
            )
        },
        enabled = enabled,
    )
    BoardChoice(
        label = "模型 · ${modelOptions.firstOrNull { it.id == agent.model }?.label ?: agent.model}",
        options = modelOptions.map { it.id to it.label },
        onSelect = { onChange(agent.copy(model = it)) },
        enabled = enabled,
    )
    BoardChoice(
        label = "思考深度 · ${boardTaskEffortLabel(agent.thinkingEffort)}",
        options = BOARD_TASK_EFFORTS.map { it to boardTaskEffortLabel(it) },
        onSelect = { onChange(agent.copy(thinkingEffort = it)) },
        enabled = enabled,
    )
    BoardChoice(
        label = "会话类型 · ${boardTaskKindLabel(agent.kind)}",
        options = BOARD_TASK_KINDS.map { it to boardTaskKindLabel(it) },
        onSelect = { onChange(agent.copy(kind = it)) },
        enabled = enabled,
    )
    BoardChoice(
        label = "运行模式 · ${boardTaskModeLabel(agent.mode)}",
        options = supportedBoardTaskModes(agent.provider).map { it to boardTaskModeLabel(it) },
        onSelect = { onChange(agent.copy(mode = it)) },
        enabled = enabled,
    )
}

@Composable
private fun BoardChoice(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    chip: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .then(
                    if (chip) {
                        Modifier
                            .clip(WandShapes.full)
                            .background(WandColors.surfaceSoft.copy(alpha = 0.8f))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .clip(WandShapes.sm)
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    },
                )
                .clickable(enabled = enabled) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = WandColors.success,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                label,
                color = if (enabled) WandColors.textPrimary else WandColors.textMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (chip) Modifier else Modifier.weight(1f),
            )
            Icon(
                WandIcons.expand,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WandColors.bgElevated,
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
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
