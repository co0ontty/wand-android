package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 看板任务详情页（平板 / 折叠屏展开后的右侧主区，或窄屏推出的独立页面）。
 *
 * 侧栏只负责列表与选中，详情单独占一屏，这样：
 * - 侧栏只剩一条顶栏，不再出现「顶部菜单栏 + 详情标题」两条堆叠；
 * - 派发 Agent 的表单与参数有足够宽度，不再挤在 220dp 宽的侧栏里。
 */
@Composable
fun TaskBoardTaskScreen(
    api: TaskBoardPort,
    workspaceApi: WorkspacePort,
    taskId: String,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onTaskGone: () -> Unit = onBack,
    onTaskChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var task by remember(taskId) { mutableStateOf<BoardTask?>(null) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var lastAgent by remember { mutableStateOf(BoardTaskAgent.default()) }
    var loading by remember(taskId) { mutableStateOf(true) }
    var error by remember(taskId) { mutableStateOf<String?>(null) }
    var busy by remember(taskId) { mutableStateOf(false) }
    var movingSession by remember(taskId) { mutableStateOf<BoardTaskSession?>(null) }

    fun applyLoaded(loaded: BoardTask?) {
        if (loaded == null) {
            // 任务被别的客户端删除 / 归档：退回侧栏，不留在空白详情。
            onTaskGone()
            return
        }
        task = loaded
        error = null
    }

    suspend fun refresh(showProgress: Boolean = false) {
        if (showProgress) loading = true
        try {
            applyLoaded(api.getBoardTask(taskId))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "无法加载任务"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api, taskId) {
        refresh(showProgress = true)
        workspaces = runCatching { api.listBoardWorkspaces() }.getOrDefault(emptyList())
        models = runCatching { api.boardModels() }.getOrNull()
        lastAgent = runCatching { api.boardTaskAgentDefaults() }.getOrDefault(BoardTaskAgent.default())
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(api, lifecycleOwner, taskId) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            launch { api.taskChanges.collect { refresh() } }
            launch { while (true) { delay(6_000); refresh() } }
        }
    }

    fun patch(body: org.json.JSONObject, after: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                applyLoaded(api.updateBoardTask(taskId, body))
                after()
                onTaskChanged()
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
            } finally {
                busy = false
            }
        }
    }

    fun dispatch(agent: BoardTaskAgent, prompt: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                runCatching { api.saveBoardTaskAgentDefaults(agent) }
                onTaskChanged()
                val result = api.dispatchBoardTask(taskId, agent, prompt, task?.workspaceId)
                refresh()
                // 派发成功直接进入新 Agent 的会话：创建/指派之后不用再自己找一遍。
                // PTY 会话要落到终端页，不能一律当成结构化聊天。
                boardDispatchSessionId(result.sessionId)?.let { sessionId ->
                    val current = task
                    onOpenSession(
                        TaskSessionRoute(
                            sessionId = sessionId,
                            structured = result.isStructured,
                            workspaceId = current?.workspaceId,
                            taskId = taskId,
                            workspaceName = current?.workspace?.name,
                            taskName = current?.title,
                        ),
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: "派发 Agent 失败"
            } finally {
                busy = false
            }
        }
    }

    movingSession?.let { session ->
        SessionMoveSheet(workspaceApi, session.id, session.title.ifBlank { "CLI 会话" },
            onDismiss = { movingSession = null },
            onMoved = { scope.launch { refresh() } })
    }

    val current = task
    Column(modifier = Modifier.fillMaxSize()) {
        WandDetailTopBar(
            title = current?.let { boardTaskDetailTitle(it) } ?: "任务详情",
            subtitle = current?.let {
                listOfNotNull(
                    it.identifier.takeIf { id -> id.isNotBlank() },
                    boardTaskStatusLabel(it.status),
                ).joinToString(" · ")
            },
            leading = if (showBack) {
                { WandDetailBackButton(onClick = onBack, icon = WandIcons.back) }
            } else {
                null
            },
            actions = {
                ToolbarIconButton(
                    icon = WandIcons.refresh,
                    contentDescription = "刷新任务",
                    enabled = !busy,
                    onClick = { scope.launch { refresh(showProgress = true) } },
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WandColors.bgPrimary),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                current == null && loading -> CircularProgressIndicator(
                    color = WandColors.success,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                current == null -> Column(
                    modifier = Modifier.widthIn(max = 420.dp).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(WandIcons.error, contentDescription = null, tint = WandColors.danger)
                    Text(
                        error ?: "无法加载任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textSecondary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    WandButton(
                        label = "重试",
                        onClick = { scope.launch { refresh(showProgress = true) } },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                else -> TaskBoardDetailPane(
                    task = current,
                    workspaces = workspaces,
                    models = models,
                    lastAgent = lastAgent,
                    busy = busy,
                    onPatch = { body -> patch(body) },
                    onRemember = { agent ->
                        lastAgent = agent
                        scope.launch { runCatching { api.saveBoardTaskAgentDefaults(agent) } }
                    },
                    onDispatch = ::dispatch,
                    onDelete = delete@{
                        if (busy) return@delete
                        busy = true
                        scope.launch {
                            try {
                                api.deleteBoardTask(taskId)
                                onTaskChanged()
                                onBack()
                            } catch (e: Exception) {
                                error = e.message ?: "归档失败"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onOpenSession = { sessionId, structured ->
                        onOpenSession(
                            TaskSessionRoute(
                                sessionId = sessionId,
                                structured = structured,
                                workspaceId = current.workspaceId,
                                taskId = taskId,
                                workspaceName = current.workspace?.name,
                                taskName = current.title,
                            ),
                        )
                    },
                    onMoveSession = { movingSession = it },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** 详情页顶栏标题：空标题退回描述首行，仍空才显示占位。 */
internal fun boardTaskDetailTitle(task: BoardTask): String = boardTaskCardTitle(task)
