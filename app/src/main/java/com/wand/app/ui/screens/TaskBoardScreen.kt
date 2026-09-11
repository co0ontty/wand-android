package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.wand.app.data.boardTaskEffortLabel
import com.wand.app.data.boardTaskPriorityLabel
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.boardTaskStatusEmpty
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.data.patchBoardTaskBody
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

@Composable
fun TaskBoardScreen(
    api: TaskBoardPort,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, isStructured: Boolean) -> Unit,
    linkedWorkspaceId: String? = null,
    embedded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filterWorkspaceId by remember { mutableStateOf(linkedWorkspaceId.orEmpty()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

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

    LaunchedEffect(api, linkedWorkspaceId) {
        if (!linkedWorkspaceId.isNullOrBlank() && filterWorkspaceId.isEmpty()) {
            filterWorkspaceId = linkedWorkspaceId
        }
        refresh(showProgress = true)
        workspaces = runCatching { api.listBoardWorkspaces() }.getOrDefault(emptyList())
        models = runCatching { api.boardModels() }.getOrNull()
    }

    val selected = tasks.firstOrNull { it.id == selectedId }
    val visible = tasks.filter { task ->
        val haystack = "${task.title} ${task.description} ${task.identifier} ${task.workspace?.name.orEmpty()}"
        (query.isBlank() || haystack.contains(query, ignoreCase = true)) &&
            (filterWorkspaceId.isBlank() || task.workspaceId == filterWorkspaceId)
    }.sortedWith(compareBy({ BOARD_TASK_STATUSES.indexOf(it.status).takeIf { index -> index >= 0 } ?: 99 }, { it.sortOrder }, { -it.updatedAt.hashCode() }))

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = if (selected != null) selected.title.ifBlank { "任务详情" } else "任务管理",
                subtitle = if (selected != null) {
                    selected.identifier.ifBlank { boardTaskStatusLabel(selected.status) }
                } else {
                    "看板 · 派发 Agent"
                },
                leading = {
                    WandDetailBackButton(onClick = {
                        if (selected != null) selectedId = null else onBack()
                    })
                },
                actions = {
                    if (selected == null) {
                        WandButton(
                            label = "新建",
                            onClick = { showCreate = true },
                            variant = WandButtonVariant.Secondary,
                            compact = true,
                        )
                    }
                },
            )
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
                    busy = busy,
                    onPatch = { body ->
                        scope.launch {
                            busy = true
                            try {
                                api.updateBoardTask(selected.id, body)
                                refresh()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onDispatch = { agent ->
                        scope.launch {
                            busy = true
                            try {
                                api.updateBoardTask(selected.id, patchBoardTaskBody(agent = agent))
                                val result = api.dispatchBoardTask(selected.id, agent)
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
                    color = WandColors.brand,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                else -> TaskBoardList(
                    tasks = visible,
                    workspaces = workspaces,
                    query = query,
                    filterWorkspaceId = filterWorkspaceId,
                    onQueryChange = { query = it },
                    onFilterWorkspace = { filterWorkspaceId = it },
                    onOpen = { selectedId = it.id },
                    onOpenSession = onOpenSession,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
            error?.let { message ->
                Surface(
                    color = WandColors.dangerSoft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) { Text(message, color = WandColors.danger, modifier = Modifier.padding(12.dp)) }
            }
        }
    }

    if (showCreate) {
        CreateBoardTaskDialog(
            workspaces = workspaces,
            defaultWorkspaceId = filterWorkspaceId,
            onDismiss = { showCreate = false },
            onCreate = { title, description, status, priority, workspaceId ->
                scope.launch {
                    try {
                        val created = api.createBoardTask(title, description, status, priority, workspaceId)
                        showCreate = false
                        refresh()
                        selectedId = created.id
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
    workspaces: List<Workspace>,
    query: String,
    filterWorkspaceId: String,
    onQueryChange: (String) -> Unit,
    onFilterWorkspace: (String) -> Unit,
    onOpen: (BoardTask) -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            WandTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索任务",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            BoardChoice(
                label = workspaces.firstOrNull { it.id == filterWorkspaceId }?.name ?: "所有项目",
                options = listOf("" to "所有项目") + workspaces.map { it.id to it.name },
                onSelect = onFilterWorkspace,
            )
        }
        BOARD_TASK_STATUSES.forEach { status ->
            val items = tasks.filter { it.status == status }
            item(key = "header-$status") {
                Text(
                    "${boardTaskStatusLabel(status)}  ${items.size}",
                    color = WandColors.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            if (items.isEmpty()) {
                item(key = "empty-$status") {
                    Text(boardTaskStatusEmpty(status), color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                items(items, key = { it.id }) { task ->
                    BoardTaskCard(task = task, onOpen = { onOpen(task) }, onOpenSession = onOpenSession)
                }
            }
        }
    }
}

@Composable
private fun BoardTaskCard(
    task: BoardTask,
    onOpen: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
) {
    WandCard(contentPadding = PaddingValues(14.dp), onClick = onOpen) {
        Text(
            task.identifier.ifBlank { task.id.take(8) },
            color = WandColors.textMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            task.title,
            color = WandColors.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (task.description.isNotBlank()) {
            Text(
                task.description,
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(task.workspace?.name ?: "未指定项目", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
            if (task.priority != "none") {
                Text(boardTaskPriorityLabel(task.priority), color = WandColors.warning, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                task.agent?.let { boardTaskProviderLabel(it.provider) } ?: "未指派",
                color = WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (task.sessions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                task.sessions.take(3).forEach { session ->
                    TextButton(onClick = { onOpenSession(session.id, session.isStructured) }) {
                        Icon(
                            painter = BrandLogos.painterForProvider(session.provider),
                            contentDescription = null,
                            tint = BrandLogos.tintForProvider(session.provider, WandColors.textPrimary),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            boardTaskProviderLabel(session.provider),
                            modifier = Modifier.padding(start = 4.dp),
                            color = WandColors.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskBoardDetail(
    task: BoardTask,
    workspaces: List<Workspace>,
    models: ModelsResponse?,
    busy: Boolean,
    onPatch: (org.json.JSONObject) -> Unit,
    onDispatch: (BoardTaskAgent) -> Unit,
    onDelete: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember(task.id, task.title) { mutableStateOf(task.title) }
    var description by remember(task.id, task.description) { mutableStateOf(task.description) }
    var agent by remember(task.id, task.agent) { mutableStateOf(task.agent ?: BoardTaskAgent.default()) }
    val modelOptions = boardAgentModelOptions(models, agent.provider)

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
            label = "任务标题",
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        WandTextField(
            value = description,
            onValueChange = { description = it },
            label = "描述",
            minLines = 4,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        WandButton(
            label = "保存标题与描述",
            onClick = {
                onPatch(patchBoardTaskBody(title = title.trim().ifBlank { task.title }, description = description))
            },
            enabled = !busy && title.trim().isNotEmpty(),
            variant = WandButtonVariant.Secondary,
            compact = true,
        )
        BoardChoice(
            label = "状态 · ${boardTaskStatusLabel(task.status)}",
            options = BOARD_TASK_STATUSES.map { it to boardTaskStatusLabel(it) },
            onSelect = { onPatch(patchBoardTaskBody(status = it)) },
            enabled = !busy,
        )
        BoardChoice(
            label = "优先级 · ${boardTaskPriorityLabel(task.priority)}",
            options = BOARD_TASK_PRIORITIES.map { it to boardTaskPriorityLabel(it) },
            onSelect = { onPatch(patchBoardTaskBody(priority = it)) },
            enabled = !busy,
        )
        BoardChoice(
            label = "项目 · ${task.workspace?.name ?: "未指定项目"}",
            options = listOf("" to "不指定项目（使用全局目录）") + workspaces.map { it.id to it.name },
            onSelect = { onPatch(patchBoardTaskBody(workspaceId = it.ifBlank { null })) },
            enabled = !busy,
        )
        Text("指派 Agent", color = WandColors.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text("只作用于这条任务", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
        BoardChoice(
            label = "CLI 工具 · ${boardTaskProviderLabel(agent.provider)}",
            options = BOARD_TASK_PROVIDERS.map { it to boardTaskProviderLabel(it) },
            onSelect = { provider ->
                val nextModels = boardAgentModelOptions(models, provider)
                val model = if (nextModels.any { it.id == agent.model }) agent.model else nextModels.firstOrNull()?.id ?: "default"
                agent = agent.copy(provider = provider, model = model)
            },
            enabled = !busy,
        )
        BoardChoice(
            label = "模型 · ${modelOptions.firstOrNull { it.id == agent.model }?.label ?: agent.model}",
            options = modelOptions.map { it.id to it.label },
            onSelect = { agent = agent.copy(model = it) },
            enabled = !busy,
        )
        BoardChoice(
            label = "思考深度 · ${boardTaskEffortLabel(agent.thinkingEffort)}",
            options = BOARD_TASK_EFFORTS.map { it to boardTaskEffortLabel(it) },
            onSelect = { agent = agent.copy(thinkingEffort = it) },
            enabled = !busy,
        )
        WandButton(
            label = if (task.sessions.isEmpty()) "派发 Agent" else "再派发一次",
            onClick = { onDispatch(agent) },
            enabled = !busy,
            loading = busy,
        )
        if (task.sessions.isNotEmpty()) {
            Text("已绑定会话", color = WandColors.textSecondary, style = MaterialTheme.typography.labelLarge)
            task.sessions.forEach { session ->
                TextButton(onClick = { onOpenSession(session.id, session.isStructured) }) {
                    Icon(
                        painter = BrandLogos.painterForProvider(session.provider),
                        contentDescription = null,
                        tint = BrandLogos.tintForProvider(session.provider, WandColors.textPrimary),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "${boardTaskProviderLabel(session.provider)}${if (session.model.isNotBlank()) " · ${session.model}" else ""}",
                        modifier = Modifier.padding(start = 6.dp),
                        color = WandColors.textPrimary,
                    )
                }
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
    defaultWorkspaceId: String,
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String, status: String, priority: String, workspaceId: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("todo") }
    var priority by remember { mutableStateOf("none") }
    var workspaceId by remember { mutableStateOf(defaultWorkspaceId) }
    WandDialog(
        title = "新建任务",
        onDismissRequest = onDismiss,
        confirm = WandDialogAction(
            label = "创建",
            enabled = title.trim().isNotEmpty(),
            onClick = {
                onCreate(title.trim(), description.trim(), status, priority, workspaceId.ifBlank { null })
            },
        ),
        dismiss = WandDialogAction(label = "取消", onClick = onDismiss),
    ) {
        WandTextField(
            value = title,
            onValueChange = { title = it.replace("\n", "") },
            label = "任务标题",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        WandTextField(
            value = description,
            onValueChange = { description = it },
            label = "描述",
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        BoardChoice(
            label = "项目 · ${workspaces.firstOrNull { it.id == workspaceId }?.name ?: "不指定项目"}",
            options = listOf("" to "不指定项目（使用全局目录）") + workspaces.map { it.id to it.name },
            onSelect = { workspaceId = it },
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

@Composable
private fun BoardChoice(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(label, color = WandColors.textPrimary)
            Icon(
                WandIcons.expand,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(16.dp).padding(start = 2.dp),
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
