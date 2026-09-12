package com.wand.app.ui.screens

import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.BOARD_TASK_EFFORTS
import com.wand.app.data.BOARD_TASK_PRIORITIES
import com.wand.app.data.BOARD_TASK_PROVIDERS
import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.Workspace
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.data.groupBoardSessionsByAgent
import com.wand.app.data.boardTaskEffortLabel
import com.wand.app.data.boardTaskPriorityLabel
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.data.patchBoardTaskBody
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandChoiceStrip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun TaskBoardScreen(
    api: TaskBoardPort,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, isStructured: Boolean) -> Unit,
    linkedWorkspaceId: String? = null,
    embedded: Boolean = false,
    refreshNonce: Int = 0,
) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filterWorkspaceId by remember { mutableStateOf(linkedWorkspaceId.orEmpty()) }
    var statusFilter by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastAgent by remember { mutableStateOf(BoardTaskAgent.default()) }

    suspend fun refresh(showProgress: Boolean = false) {
        if (showProgress) loading = true
        try {
            tasks = api.listBoardTasks()
            error = null
        } catch (e: Exception) {
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

    val selected = tasks.firstOrNull { it.id == selectedId }
    val scoped = filterBoardTasks(
        tasks.filterNot(::isPlaceholderBoardTask),
        query,
        filterWorkspaceId,
    )
    val visible = sortBoardTasks(
        if (statusFilter.isBlank()) scoped else scoped.filter { it.status == statusFilter },
    )
    val stats = boardTaskStats(scoped)
    val projectName = workspaces.firstOrNull { it.id == filterWorkspaceId }?.name ?: "全部任务"

    val showChrome = !embedded || selected != null
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (showChrome) {
                WandDetailTopBar(
                    title = if (selected != null) selected.title.ifBlank { "任务详情" } else "工作台",
                    subtitle = if (selected != null) {
                        selected.identifier.ifBlank { boardTaskStatusLabel(selected.status) }
                    } else {
                        "任务管理 · ${stats.remaining} 项未完成"
                    },
                    leading = {
                        WandDetailBackButton(onClick = {
                            if (selected != null) selectedId = null else onBack()
                        })
                    },
                    actions = {
                        if (selected == null) {
                            WandIconButton(
                                icon = WandIcons.refresh,
                                contentDescription = "刷新任务",
                                onClick = { scope.launch { refresh() } },
                                variant = WandIconButtonVariant.Toolbar,
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selected == null) {
                FloatingActionButton(
                    onClick = { showCreate = true },
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
                selected != null -> TaskBoardDetail(
                    task = selected,
                    workspaces = workspaces,
                    models = models,
                    lastAgent = lastAgent,
                    busy = busy,
                    onPatch = { body -> patchTask(selected.id, body) },
                    onRemember = { agent ->
                        lastAgent = agent
                        scope.launch { runCatching { api.saveBoardTaskAgentDefaults(agent) } }
                    },
                    onDispatch = { agent, prompt ->
                        lastAgent = agent
                        scope.launch {
                            busy = true
                            try {
                                runCatching { api.saveBoardTaskAgentDefaults(agent) }
                                api.updateBoardTask(selected.id, patchBoardTaskBody(agent = agent, workspaceId = selected.workspaceId))
                                val result = api.dispatchBoardTask(selected.id, agent, prompt, selected.workspaceId)
                                refresh()
                                if (result.sessionId.isNotBlank()) {
                                    onOpenSession(result.sessionId, true)
                                }
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            busy = true
                            try {
                                api.deleteBoardTask(selected.id)
                                selectedId = null
                                refresh()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onOpenSession = onOpenSession,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
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
                    filterWorkspaceId = filterWorkspaceId,
                    statusFilter = statusFilter,
                    onQueryChange = { query = it },
                    onFilterWorkspace = { filterWorkspaceId = it },
                    onStatusFilter = { statusFilter = it },
                    onOpen = { selectedId = it.id },
                    onToggleComplete = { task ->
                        patchTask(task.id, patchBoardTaskBody(status = boardTaskToggledStatus(task.status)))
                    },
                    onOpenSession = onOpenSession,
                    onCreate = { showCreate = true },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
            error?.let { message ->
                Surface(
                    color = WandColors.dangerSoft,
                    shape = WandShapes.sm,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = if (selected == null) 88.dp else 16.dp),
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
            onDismiss = { showCreate = false },
            onCreate = { title, description, status, priority, workspaceId, agent ->
                scope.launch {
                    try {
                        val created = api.createBoardTask(title, description, status, priority, workspaceId, agent)
                        lastAgent = agent
                        runCatching { api.saveBoardTaskAgentDefaults(agent) }
                        showCreate = false
                        selectedId = created.id
                        if (description.isNotBlank()) {
                            runCatching { api.dispatchBoardTask(created.id, agent, description, workspaceId) }
                                .onFailure { error = it.message ?: "任务已创建，但第一次指派失败。" }
                        }
                        refresh()
                        if (title.isBlank() && created.titleSource == "auto") {
                            awaitGeneratedBoardTaskTitle(created.id, created.title)
                        }
                    } catch (e: Exception) {
                        error = e.message
                    }
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
    filterWorkspaceId: String,
    statusFilter: String,
    onQueryChange: (String) -> Unit,
    onFilterWorkspace: (String) -> Unit,
    onStatusFilter: (String) -> Unit,
    onOpen: (BoardTask) -> Unit,
    onToggleComplete: (BoardTask) -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = groupedBoardTasks(tasks)
    val showWorkspace = filterWorkspaceId.isBlank()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "hero") {
            TaskBoardHero(
                projectName = projectName,
                stats = stats,
                query = query,
                onQueryChange = onQueryChange,
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
                    onAction = onCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
            }
        } else if (statusFilter.isBlank()) {
            grouped.forEach { (status, items) ->
                if (items.isEmpty()) return@forEach
                item(key = "header-$status") {
                    BoardSectionHeader(status = status, count = items.size)
                }
                items(items, key = { it.id }) { task ->
                    BoardTaskCard(
                        task = task,
                        showWorkspace = showWorkspace,
                        onOpen = { onOpen(task) },
                        onToggleComplete = { onToggleComplete(task) },
                        onOpenSession = onOpenSession,
                    )
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                BoardTaskCard(
                    task = task,
                    showWorkspace = showWorkspace,
                    onOpen = { onOpen(task) },
                    onToggleComplete = { onToggleComplete(task) },
                    onOpenSession = onOpenSession,
                )
            }
        }
    }
}

@Composable
private fun TaskBoardHero(
    projectName: String,
    stats: BoardTaskStats,
    query: String,
    onQueryChange: (String) -> Unit,
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
            BoardSearchCapsule(value = query, onValueChange = onQueryChange)
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
private fun BoardSectionHeader(status: String, count: Int) {
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
    }
}

@Composable
private fun BoardTaskCard(
    task: BoardTask,
    showWorkspace: Boolean,
    onOpen: () -> Unit,
    onToggleComplete: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
) {
    val done = task.status == "done"
    val model = boardTaskCardModel(task, showWorkspace)
    val hasChips = model.workspaceName != null ||
        model.priority != null ||
        model.agentLabel != null ||
        model.labels.isNotEmpty()
    WandCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoardStatusCheck(status = task.status, onClick = onToggleComplete)
            Text(
                model.title,
                color = if (done) WandColors.textMuted else WandColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
            )
        }
        Column(modifier = Modifier.clickable(onClick = onOpen)) {
            if (hasChips) {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    model.workspaceName?.let { name ->
                        BoardChip(label = name, icon = WandIcons.folder)
                    }
                    model.priority?.let { priority ->
                        BoardChip(
                            label = boardTaskPriorityLabel(priority),
                            color = boardPriorityColor(priority),
                        )
                    }
                    model.agentLabel?.let { label ->
                        BoardChip(label = label)
                    }
                    model.labels.forEach { label ->
                        BoardChip(label = label)
                    }
                }
            }
            model.processingLabel?.let { label ->
                BoardProcessingRow(
                    label = label,
                    running = task.sessions.any { boardSessionRunning(it.status) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (model.sessions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    model.sessions.forEach { session ->
                        Row(
                            modifier = Modifier
                                .clip(WandShapes.full)
                                .background(WandColors.surfaceSoft.copy(alpha = 0.8f))
                                .clickable { onOpenSession(session.id, session.isStructured) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                painter = BrandLogos.painterForProvider(session.provider),
                                contentDescription = null,
                                tint = BrandLogos.tintForProvider(session.provider, WandColors.textPrimary),
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                boardTaskProviderLabel(session.provider),
                                color = WandColors.textSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardStatusCheck(
    status: String,
    onClick: () -> Unit,
) {
    val done = status == "done"
    val doing = status == "doing"
    val green = WandColors.success
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (done) green else green.copy(alpha = 0.55f), CircleShape)
            .background(if (done) green else Color.Transparent)
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

@Composable
private fun BoardChip(
    label: String,
    icon: ImageVector? = null,
    color: Color = WandColors.textSecondary,
) {
    Row(
        modifier = Modifier
            .clip(WandShapes.full)
            .border(0.5.dp, WandColors.border, WandShapes.full)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        }
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun TaskBoardDetail(
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
    modifier: Modifier = Modifier,
) {
    var title by remember(task.id, task.title) { mutableStateOf(task.title) }
    var agent by remember(task.id, task.agent) { mutableStateOf(task.agent ?: lastAgent) }
    val hasAgents = task.sessions.isNotEmpty()
    var composeOpen by remember(task.id) { mutableStateOf(!hasAgents) }
    var composePrompt by remember(task.id) { mutableStateOf(if (hasAgents) "" else task.description) }
    val modelOptions = boardAgentModelOptions(models, agent.provider)
    val done = task.status == "done"
    val workspaceChoices = buildList {
        add("" to "不指定项目（使用全局目录）")
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
            BOARD_TASK_STATUSES.forEach { status ->
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
                label = "项目 · ${task.workspace?.name ?: "未指定项目"}",
                options = workspaceChoices,
                onSelect = { onPatch(patchBoardTaskBody(workspaceId = it.ifBlank { null })) },
                enabled = !busy,
            )
        }
        Text("已指派的 Agent", color = WandColors.textSecondary, style = MaterialTheme.typography.labelLarge)
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
                        (group.agent?.let { " · ${if (it.model == "default") "默认模型" else it.model}" } ?: ""),
                    color = WandColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (group.sessions.isEmpty()) {
                    Text(
                        "已指派，等待派发",
                        color = WandColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    group.sessions.forEach { session ->
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
                                    Text(
                                        session.title.ifBlank { boardTaskProviderLabel(session.provider) },
                                        color = WandColors.textPrimary,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                    )
                                    Text(
                                        listOfNotNull(
                                            session.model.takeIf { it.isNotBlank() && it != "default" },
                                            boardSessionStatusLabel(session.status),
                                        ).joinToString(" · "),
                                        color = WandColors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Icon(
                                    WandIcons.chevronRight,
                                    contentDescription = null,
                                    tint = WandColors.textMuted,
                                    modifier = Modifier.size(16.dp),
                                )
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
                BoardChoice(
                    label = "CLI 工具 · ${boardTaskProviderLabel(agent.provider)}",
                    options = BOARD_TASK_PROVIDERS.map { it to boardTaskProviderLabel(it) },
                    onSelect = { provider ->
                        val nextModels = boardAgentModelOptions(models, provider)
                        val model = if (nextModels.any { it.id == agent.model }) agent.model else nextModels.firstOrNull()?.id ?: "default"
                        val next = agent.copy(provider = provider, model = model)
                        agent = next
                        onRemember(next)
                    },
                    enabled = !busy,
                )
                BoardChoice(
                    label = "模型 · ${modelOptions.firstOrNull { it.id == agent.model }?.label ?: agent.model}",
                    options = modelOptions.map { it.id to it.label },
                    onSelect = {
                        val next = agent.copy(model = it)
                        agent = next
                        onRemember(next)
                    },
                    enabled = !busy,
                )
                BoardChoice(
                    label = "思考深度 · ${boardTaskEffortLabel(agent.thinkingEffort)}",
                    options = BOARD_TASK_EFFORTS.map { it to boardTaskEffortLabel(it) },
                    onSelect = {
                        val next = agent.copy(thinkingEffort = it)
                        agent = next
                        onRemember(next)
                    },
                    enabled = !busy,
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(WandShapes.sm)
                    .clickable(enabled = !busy) {
                        composePrompt = ""
                        composeOpen = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(WandIcons.add, contentDescription = "再指派一个 Agent", tint = WandColors.textSecondary)
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

@Composable
private fun CreateBoardTaskDialog(
    workspaces: List<Workspace>,
    models: ModelsResponse?,
    lastAgent: BoardTaskAgent,
    defaultWorkspaceId: String,
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String, status: String, priority: String, workspaceId: String?, agent: BoardTaskAgent) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("todo") }
    var priority by remember { mutableStateOf("none") }
    var workspaceId by remember { mutableStateOf(defaultWorkspaceId) }
    var agent by remember { mutableStateOf(lastAgent) }
    val modelOptions = boardAgentModelOptions(models, agent.provider)
    WandDialog(
        title = "新建任务",
        onDismissRequest = onDismiss,
        confirm = WandDialogAction(
            label = if (description.trim().isNotEmpty()) "创建并指派" else "创建任务",
            enabled = title.trim().isNotEmpty() || description.trim().isNotEmpty(),
            onClick = {
                onCreate(title.trim(), description.trim(), status, priority, workspaceId.ifBlank { null }, agent)
            },
        ),
        dismiss = WandDialogAction(label = "取消", onClick = onDismiss),
    ) {
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
            placeholder = "将作为第一个 Agent 的指派内容",
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        BoardChoice(
            label = "目录 · ${workspaces.firstOrNull { it.id == workspaceId }?.name ?: "不指定目录"}",
            options = listOf("" to "不指定目录（使用全局目录）") + workspaces.map { it.id to it.name },
            onSelect = { workspaceId = it },
        )
        BoardChoice(
            label = "第一次指派 · ${boardTaskProviderLabel(agent.provider)}",
            options = BOARD_TASK_PROVIDERS.map { it to boardTaskProviderLabel(it) },
            onSelect = { provider ->
                val nextModels = boardAgentModelOptions(models, provider)
                val model = if (nextModels.any { it.id == agent.model }) agent.model else nextModels.firstOrNull()?.id ?: "default"
                agent = agent.copy(provider = provider, model = model)
            },
        )
        BoardChoice(
            label = "模型 · ${modelOptions.firstOrNull { it.id == agent.model }?.label ?: agent.model}",
            options = modelOptions.map { it.id to it.label },
            onSelect = { agent = agent.copy(model = it) },
        )
        BoardChoice(
            label = "思考深度 · ${boardTaskEffortLabel(agent.thinkingEffort)}",
            options = BOARD_TASK_EFFORTS.map { it to boardTaskEffortLabel(it) },
            onSelect = { agent = agent.copy(thinkingEffort = it) },
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
