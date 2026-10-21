package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
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
import com.wand.app.data.AiTeamDetailRequestGuard
import com.wand.app.data.mergeAiTeamRunDetail
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
import com.wand.app.data.displayStatusLabel
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.WandBreadcrumb
import com.wand.app.ui.components.WandCrumb
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private object TaskAcceptanceDraftCache {
    val values = linkedMapOf<String, Pair<String, String>>()
    fun save(id: String, evidence: String, blocker: String) {
        if (values.size >= 100 && !values.containsKey(id)) values.remove(values.keys.first())
        values[id] = evidence to blocker
    }
}

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
    baseUrl: String = "",
    showBack: Boolean = true,
    onBack: () -> Unit,
    /**
     * 面包屑首段的落点，与 Web 同口径：回「任务看板列表」。
     * 与 [onBack] 分开，是因为删除 / 消失那两条路仍按「退回上一层」走，不该一起改语义。
     */
    onBackToBoard: () -> Unit = onBack,
    onOpenSession: (TaskSessionRoute) -> Unit,
    /** 打开另一个任务（可引用任务区点子任务时用）。 */
    onOpenTaskDetail: (String) -> Unit = {},
    /** 看板卡片「打开群聊」：参数是团队运行 id + 该卡短号，落到 IM 群聊页。 */
    onOpenTeamChat: (runId: String, taskIdentifier: String) -> Unit = { _, _ -> },
    onTaskGone: () -> Unit = onBack,
    onTaskChanged: () -> Unit = {},
    onOpenIm: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var task by remember(taskId) { mutableStateOf<BoardTask?>(null) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var employees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var teamRun by remember(taskId) { mutableStateOf<AiTeamRunDetail?>(null) }
    val teamRequests = remember(taskId, api) { AiTeamDetailRequestGuard() }
    val teamActionRequests = remember(taskId, api) { AiTeamDetailRequestGuard() }
    DisposableEffect(teamRequests, teamActionRequests) {
        onDispose { teamRequests.invalidate(); teamActionRequests.invalidate() }
    }
    var lastAgent by remember { mutableStateOf(BoardTaskAgent.default()) }
    var loading by remember(taskId) { mutableStateOf(true) }
    var error by remember(taskId) { mutableStateOf<String?>(null) }
    var busy by remember(taskId) { mutableStateOf(false) }
    var movingSession by remember(taskId) { mutableStateOf<BoardTaskSession?>(null) }
    var childTasks by remember(taskId) { mutableStateOf<List<BoardTask>>(emptyList()) }
    var acceptanceEvidence by remember(taskId) { mutableStateOf("") }
    var acceptanceBlocker by remember(taskId) { mutableStateOf("") }
    var acceptanceDraftTaskId by remember(taskId) { mutableStateOf<String?>(null) }
    var acceptanceDraftDirty by remember(taskId) { mutableStateOf(false) }

    fun applyLoaded(loaded: BoardTask?) {
        if (loaded == null) {
            // 任务被别的客户端删除 / 归档：退回侧栏，不留在空白详情。
            onTaskGone()
            return
        }
        task = loaded
        if (acceptanceDraftTaskId != loaded.id) {
            acceptanceEvidence = TaskAcceptanceDraftCache.values[loaded.id]?.first ?: loaded.acceptance?.evidence.orEmpty()
            acceptanceBlocker = TaskAcceptanceDraftCache.values[loaded.id]?.second ?: loaded.acceptance?.blocker.orEmpty()
            acceptanceDraftTaskId = loaded.id
            acceptanceDraftDirty = TaskAcceptanceDraftCache.values.containsKey(loaded.id)
        }
        error = null
    }

    /**
     * 最近一次团队运行（对齐 Web TaskTeamRunPanel：只展示最新一条）。
     * list / detail 任一请求失败都保留上一次的结果（老服务端 404 时保持为空），
     * 只有「确实没有 run」才清空，否则瞬时网络抖动会让运行卡 6s 闪烁消失。
     */
    suspend fun refreshTeamRun() {
        val ticket = teamRequests.begin()
        val runs = runCatching { api.teamRunsForTask(taskId) }.getOrNull() ?: return
        if (!teamRequests.accepts(ticket)) return
        val latest = runs.firstOrNull { it.taskId == taskId } ?: run { teamRun = null; return }
        val detail = runCatching { api.aiTeamRunDetail(latest.id) }.getOrNull() ?: return
        if (!teamRequests.accepts(ticket) || detail.run.taskId != taskId) return
        teamRun = mergeAiTeamRunDetail(teamRun, detail, latest.id)
    }

    suspend fun refresh(showProgress: Boolean = false) {
        if (showProgress) loading = true
        try {
            val loaded = api.getBoardTask(taskId)
            applyLoaded(loaded)
            if (loaded != null) {
                childTasks = runCatching { api.listBoardTaskChildren(taskId) }.getOrDefault(emptyList())
            }
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
                if (body.has("acceptance")) acceptanceDraftDirty = false
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
        val ticket = teamActionRequests.begin()
        scope.launch {
            try {
                val received = api.actOnTeamRun(runId, action)
                if (!teamActionRequests.accepts(ticket) || teamRun?.run?.id != runId || received.run.taskId != taskId) return@launch
                teamRun = mergeAiTeamRunDetail(teamRun, received, runId)
                error = null
                onTaskChanged()
            } catch (e: Exception) {
                if (teamActionRequests.accepts(ticket)) error = e.message ?: "团队操作失败"
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
                        current?.displayStatusLabel() ?: "加载中",
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
                onOpenIm?.let { WandButton("聊天", it, modifier = Modifier.size(48.dp), variant = WandButtonVariant.Text) }
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
                    color = WandColors.brand,
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
                else -> Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                ) {
                    val acceptancePane: @Composable () -> Unit = {
                    TaskBoardAcceptancePane(
                        task = current,
                        childTasks = childTasks,
                        evidence = acceptanceEvidence,
                        blocker = acceptanceBlocker,
                        busy = busy,
                        dirty = acceptanceDraftDirty,
                        onEvidenceChange = {
                            acceptanceEvidence = it
                            acceptanceDraftDirty = true
                            TaskAcceptanceDraftCache.save(taskId, acceptanceEvidence, acceptanceBlocker)
                        },
                        onBlockerChange = {
                            acceptanceBlocker = it
                            acceptanceDraftDirty = true
                            TaskAcceptanceDraftCache.save(taskId, acceptanceEvidence, acceptanceBlocker)
                        },
                        onSave = { accepted ->
                            val evidence = acceptanceEvidence.trim()
                            val blocker = acceptanceBlocker.trim()
                            val incompleteChildren = childTasks.any { it.acceptance?.acceptedAt.isNullOrBlank() }
                            if (accepted && (evidence.isEmpty() || blocker.isNotEmpty() || incompleteChildren)) return@TaskBoardAcceptancePane
                            val value = org.json.JSONObject()
                                .put("evidence", acceptanceEvidence)
                                .put("blocker", acceptanceBlocker)
                                .put("acceptedAt", if (accepted) Instant.now().toString() else org.json.JSONObject.NULL)
                            patch(org.json.JSONObject().put("acceptance", value))
                        },
                    )
                    }
                    TaskBoardDetailPane(
                    acceptanceContent = acceptancePane,
                    baseUrl = baseUrl,
                    commentsApi = api,
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
                    onOpenTask = onOpenTaskDetail,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 交付与验收单独固定在任务详情顶部，避免被滚动会话列表挤出视口。
 * 草稿只由输入事件修改；轮询刷新任务实体时不会覆盖未提交内容。
 */
@Composable
private fun TaskBoardAcceptancePane(
    task: BoardTask,
    childTasks: List<BoardTask>,
    evidence: String,
    blocker: String,
    busy: Boolean,
    dirty: Boolean,
    onEvidenceChange: (String) -> Unit,
    onBlockerChange: (String) -> Unit,
    onSave: (Boolean) -> Unit,
) {
    val incompleteChildren = childTasks.count { it.acceptance?.acceptedAt.isNullOrBlank() }
    val accepted = !task.acceptance?.acceptedAt.isNullOrBlank()
    val canAccept = evidence.trim().isNotEmpty() && blocker.trim().isEmpty() && incompleteChildren == 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("交付与验收", style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
        Text(
            "由任务负责人统一核对各会话产物。会话空闲、结束、失败或归档，都不代表任务验收通过。",
            style = MaterialTheme.typography.bodySmall,
            color = WandColors.textSecondary,
        )
        WandTextField(
            value = evidence,
            onValueChange = onEvidenceChange,
            label = "交付证据、产物路径或源会话",
            placeholder = "填写可复核的交付证据",
            minLines = 2,
            maxLines = 6,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        WandTextField(
            value = blocker,
            onValueChange = onBlockerChange,
            label = "待处理原因、授权或部署阻塞（无则留空）",
            placeholder = "等待信息／授权／部署／验收：写明原因与下一步；无则留空",
            minLines = 2,
            maxLines = 4,
            enabled = !busy,
            isError = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (childTasks.isNotEmpty()) {
            Text(
                if (incompleteChildren == 0) "直接子任务均已验收通过"
                else "仍有 $incompleteChildren 个直接子任务未验收通过，主目标不能通过",
                style = MaterialTheme.typography.labelSmall,
                color = if (incompleteChildren == 0) WandColors.success else WandColors.warning,
            )
        }
        if (accepted) {
            Text("验收通过 · 已完成", style = MaterialTheme.typography.labelLarge, color = WandColors.success)
            WandButton(label = "保存并重新验收", onClick = { onSave(false) }, enabled = !busy, variant = WandButtonVariant.Secondary)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WandButton(
                    label = "保存交付与缺口",
                    onClick = { onSave(false) },
                    enabled = !busy && dirty,
                    variant = WandButtonVariant.Secondary,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                WandButton(
                    label = "确认验收通过",
                    onClick = { onSave(true) },
                    enabled = !busy && canAccept,
                    variant = WandButtonVariant.Success,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 详情页顶栏标题：空标题退回描述首行，仍空才显示占位。 */
internal fun boardTaskDetailTitle(task: BoardTask): String = boardTaskCardTitle(task)
