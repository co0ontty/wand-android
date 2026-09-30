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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.ExecutionSubject
import com.wand.app.data.ModelsResponse
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TeamRunAction
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.WandBreadcrumb
import com.wand.app.ui.components.WandCrumb
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
    /**
     * 面包屑首段的落点，与 Web 同口径：回「任务看板列表」。
     * 与 [onBack] 分开，是因为删除 / 消失那两条路仍按「退回上一层」走，不该一起改语义。
     */
    onBackToBoard: () -> Unit = onBack,
    onOpenSession: (TaskSessionRoute) -> Unit,
    /** 看板卡片「打开群聊」：参数是团队运行 id + 该卡短号，落到 IM 群聊页。 */
    onOpenTeamChat: (runId: String, taskIdentifier: String) -> Unit = { _, _ -> },
    onTaskGone: () -> Unit = onBack,
    onTaskChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var task by remember(taskId) { mutableStateOf<BoardTask?>(null) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var employees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var teamRun by remember(taskId) { mutableStateOf<AiTeamRunDetail?>(null) }
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

    /**
     * 最近一次团队运行（对齐 Web TaskTeamRunPanel：只展示最新一条）。
     * list / detail 任一请求失败都保留上一次的结果（老服务端 404 时保持为空），
     * 只有「确实没有 run」才清空，否则瞬时网络抖动会让运行卡 6s 闪烁消失。
     */
    suspend fun refreshTeamRun() {
        val runs = runCatching { api.teamRunsForTask(taskId) }.getOrNull() ?: return
        val latest = runs.firstOrNull() ?: run { teamRun = null; return }
        // 同一轮直接覆盖；换轮了但 detail 拉不下来时保留旧的，下一轮刷新再切。
        val detail = runCatching { api.aiTeamRunDetail(latest.id) }.getOrNull() ?: return
        teamRun = detail
    }

    suspend fun refresh(showProgress: Boolean = false) {
        if (showProgress) loading = true
        try {
            applyLoaded(api.getBoardTask(taskId))
            refreshTeamRun()
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
        teams = runCatching { api.listAiTeams() }.getOrDefault(emptyList())
        employees = runCatching { api.listSiliconEmployees() }.getOrDefault(emptyList())
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

    suspend fun dispatch(agent: BoardTaskAgent, prompt: String, subject: ExecutionSubject): Boolean {
        if (busy) return false
        busy = true
        var succeeded = false
        try {
            if (subject.type == "cli") runCatching { api.saveBoardTaskAgentDefaults(agent) }
            onTaskChanged()
            val result = api.dispatchBoardSubject(taskId, subject, agent, prompt, task?.workspaceId)
            succeeded = true
            // 成功即清掉上一轮红字；refresh 失败会写入新的 error。
            error = null
            refresh()
            if (subject.type == "team") return true
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
            error = e.message ?: "指派失败"
        } finally {
            busy = false
        }
        return succeeded
    }

    /** 动作成功后重拉；失败只记 error，下一次 6s 刷新会再同步。 */
    fun teamRunAction(action: TeamRunAction) {
        val runId = teamRun?.run?.id ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                teamRun = api.actOnTeamRun(runId, action)
                error = null
                onTaskChanged()
            } catch (e: Exception) {
                error = e.message ?: "团队操作失败"
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
            // 单层标题：面包屑「任务 › TASK-108」。任务正文本身由下面的「任务标题（可选）」字段编辑，
            // 顶栏不再重复一遍长标题。
            title = "",
            // 返回入口只留一个：面包屑首段。原来的箭头与它同功能、同一条栏，属重复。
            leading = null,
            titleContent = {
                Column(modifier = Modifier.weight(1f)) {
                    WandBreadcrumb(
                        crumbs = listOf(
                            // 没有可回的去处（宽屏右栏就是顶层）时不给假入口，onClick 传 null。
                            WandCrumb("任务", onClick = if (showBack) onBackToBoard else null),
                            WandCrumb(
                                current?.identifier?.takeIf { id -> id.isNotBlank() } ?: "任务详情",
                            ),
                        ),
                    )
                    Text(
                        current?.let { boardTaskStatusLabel(it.status) } ?: "加载中",
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                    teams = teams,
                    employees = employees,
                    teamRun = teamRun,
                    busy = busy,
                    actionError = error,
                    onPatch = { body -> patch(body) },
                    onRemember = { agent ->
                        lastAgent = agent
                        scope.launch { runCatching { api.saveBoardTaskAgentDefaults(agent) } }
                    },
                    onDispatch = { agent, prompt, subject -> dispatch(agent, prompt, subject) },
                    onTeamRunAction = ::teamRunAction,
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
                    onOpenTeamChat = onOpenTeamChat,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** 详情页顶栏标题：空标题退回描述首行，仍空才显示占位。 */
internal fun boardTaskDetailTitle(task: BoardTask): String = boardTaskCardTitle(task)
