package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

data class BoardTaskAgent(
    val provider: String,
    val model: String,
    val thinkingEffort: String,
    /** 派发时的执行模式：托管 / 全权限 / 标准。缺省时按标准模式处理。 */
    val mode: String = "default",
    /** 派发出来的会话是结构化对话还是 PTY 终端。缺省 / 老服务端不返回时按结构化处理。 */
    val kind: String = "structured",
    /** 执行引擎：`sdk` = Wand Agent（进程内 SDK）；缺省 = CLI。仅 pi + 结构化有效。 */
    val engine: String? = null,
) {
    /** 下拉选中的执行工具 id：Pi CLI 与 Wand Agent 是两条选项。 */
    val toolId: String get() = agentToolId(provider, engine)

    fun toJson(): JSONObject = JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("thinkingEffort", thinkingEffort)
        .put("mode", mode)
        .put("kind", kind)
        .apply { if (engine == WandAgentEngine.Sdk.raw) put("engine", WandAgentEngine.Sdk.raw) }

    companion object {
        fun default(provider: String = "claude"): BoardTaskAgent =
            BoardTaskAgent(
                provider,
                "default",
                "off",
                normalizeBoardTaskAgentMode(provider, "default"),
                normalizeBoardTaskAgentKind(null),
            )

        /** 执行工具（provider + 引擎）→ agent 配置；形态按工具能力收敛。 */
        fun fromTool(toolId: String, previous: BoardTaskAgent): BoardTaskAgent {
            val tool = agentToolOption(toolId) ?: return previous
            return previous.copy(
                provider = tool.provider,
                kind = if (tool.isStructuredOnly) "structured" else previous.kind,
                mode = normalizeBoardTaskAgentMode(tool.provider, previous.mode),
                engine = tool.engine?.raw,
            )
        }

        fun parse(item: JSONObject?): BoardTaskAgent? {
            val provider = item?.str("provider")?.takeIf { it.isNotBlank() } ?: return null
            val kind = normalizeBoardTaskAgentKind(item.str("kind"))
            // engine 是后加字段：只有 pi + 结构化才认，其它形状按 CLI 读，不因此丢整条配置。
            val engine = item.str("engine")?.trim()?.lowercase()
                ?.takeIf { it == WandAgentEngine.Sdk.raw && provider == "pi" && kind == "structured" }
            return BoardTaskAgent(
                provider = provider,
                model = item.str("model")?.takeIf { it.isNotBlank() } ?: "default",
                thinkingEffort = item.str("thinkingEffort")?.takeIf { it.isNotBlank() } ?: "off",
                // mode / kind 是后加字段：老服务端不返回时按标准模式 / 结构化读，不因此整条配置退化成 null。
                mode = normalizeBoardTaskAgentMode(provider, item.str("mode")),
                kind = kind,
                engine = engine,
            )
        }
    }
}

data class BoardTaskWorkspace(
    val id: String,
    val name: String,
    val cwd: String,
) {
    companion object {
        fun parse(item: JSONObject?): BoardTaskWorkspace? {
            val id = item?.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return BoardTaskWorkspace(
                id = id,
                name = item.str("name") ?: id,
                cwd = item.str("cwd") ?: "",
            )
        }
    }
}

/** 任务所属里程碑；名字由服务端 DTO 解好，卡片直接显示。 */
data class BoardTaskMilestone(
    val id: String,
    val name: String,
) {
    companion object {
        fun parse(item: JSONObject?): BoardTaskMilestone? {
            val id = item?.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return BoardTaskMilestone(id = id, name = item.str("name") ?: id)
        }
    }
}

data class BoardTaskSession(
    val id: String,
    val provider: String,
    val sessionKind: String,
    val title: String,
    val status: String,
    val cwd: String,
    val model: String,
    val thinkingEffort: String,
    /** 执行引擎：`sdk` = Wand Agent（进程内 SDK）；缺省 / `cli` 都是命令行。 */
    val engine: String? = null,
) {
    val isStructured: Boolean
        get() = !isTerminalSessionKind(sessionKind)

    /** 会话卡 / 指派记录里的工具名：同一 provider 的两条执行路径要分开。 */
    val toolLabel: String get() = boardTaskAgentLabel(provider, engine)

    companion object {
        fun parseList(array: JSONArray?): List<BoardTaskSession> = array?.parseEach { item ->
            val id = item.str("id") ?: return@parseEach null
            val sessionKind = item.str("sessionKind") ?: ""
            BoardTaskSession(
                id = id,
                provider = item.str("provider") ?: "",
                sessionKind = sessionKind,
                title = item.str("title") ?: id,
                status = item.str("status") ?: "",
                cwd = item.str("cwd") ?: "",
                model = item.str("model") ?: "",
                thinkingEffort = item.str("thinkingEffort") ?: "off",
                engine = item.str("engine")?.trim()?.lowercase()
                    ?.takeIf { it == WandAgentEngine.Sdk.raw && !isTerminalSessionKind(sessionKind) },
            )
        } ?: emptyList()
    }
}

data class BoardTask(
    val id: String,
    val workspaceId: String?,
    val identifier: String,
    val title: String,
    /** "auto" = 标题由服务端按描述自动生成，客户端不再把它当成用户手写标题。 */
    val titleSource: String,
    val description: String,
    val status: String,
    val priority: String,
    val labels: List<String>,
    val dueDate: String?,
    val sortOrder: Int,
    val agent: BoardTaskAgent?,
    val executionSubject: ExecutionSubject? = null,
    val createdAt: String,
    val updatedAt: String,
    val sessionIds: List<String>,
    val sessions: List<BoardTaskSession>,
    val workspace: BoardTaskWorkspace?,
    val milestone: BoardTaskMilestone?,
    val workspaceTaskId: String? = null,
    val parentTaskId: String? = null,
) {
    companion object {
        fun parse(item: JSONObject): BoardTask? {
            val id = item.str("id") ?: return null
            return BoardTask(
                id = id,
                workspaceId = item.str("workspaceId")?.takeIf { it.isNotBlank() },
                identifier = item.str("identifier") ?: "",
                title = item.str("title") ?: "任务",
                titleSource = item.str("titleSource") ?: "user",
                description = item.str("description") ?: "",
                status = item.str("status") ?: "todo",
                priority = item.str("priority") ?: "none",
                labels = item.arr("labels")?.stringItems(ignoreBlank = true) ?: emptyList(),
                dueDate = item.str("dueDate")?.takeIf { it.isNotBlank() },
                sortOrder = item.int("sortOrder") ?: 0,
                agent = BoardTaskAgent.parse(item.obj("agent")),
                executionSubject = ExecutionSubject.parse(item.obj("executionSubject")),
                createdAt = item.str("createdAt") ?: "",
                updatedAt = item.str("updatedAt") ?: "",
                sessionIds = item.arr("sessionIds")?.stringItems(ignoreBlank = true) ?: emptyList(),
                sessions = BoardTaskSession.parseList(item.arr("sessions")),
                workspace = BoardTaskWorkspace.parse(item.obj("workspace")),
                milestone = BoardTaskMilestone.parse(item.obj("milestone")),
                workspaceTaskId = item.str("workspaceTaskId")?.takeIf { it.isNotBlank() },
                parentTaskId = item.str("parentTaskId")?.takeIf { it.isNotBlank() },
            )
        }

        fun parseList(array: JSONArray?): List<BoardTask> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

data class BoardDispatchResult(
    val ok: Boolean,
    val taskId: String,
    val sessionId: String,
    val provider: String,
    val cwd: String,
    /** 派发出来的会话形态："pty" / "structured"；老服务端不返回时留空，调用方回落到结构化。 */
    val sessionKind: String = "",
) {
    /** 应当以哪种页面打开新会话：PTY 走终端页，其余走 Chat。 */
    val isStructured: Boolean
        get() = !isTerminalSessionKind(sessionKind)

    companion object {
        fun parse(item: JSONObject): BoardDispatchResult {
            val session = item.obj("session")
            return BoardDispatchResult(
                ok = item.optBoolean("ok", true),
                taskId = item.str("taskId") ?: "",
                sessionId = session?.str("id") ?: "",
                provider = session?.str("provider") ?: "",
                cwd = session?.str("cwd") ?: "",
                sessionKind = session?.str("sessionKind") ?: "",
            )
        }
    }
}

fun boardTaskStatusLabel(status: String): String = when (status) {
    "todo" -> "待办"
    "doing" -> "进行中"
    "done" -> "已完成"
    "archived" -> "归档"
    else -> status
}

fun boardTaskPriorityLabel(priority: String): String = when (priority) {
    "urgent" -> "紧急"
    "high" -> "高"
    "medium" -> "中"
    "low" -> "低"
    else -> "无优先级"
}

val BOARD_TASK_STATUSES = listOf("todo", "doing", "done")
val BOARD_TASK_DETAIL_STATUSES = listOf("todo", "doing", "done", "archived")
val BOARD_TASK_PRIORITIES = listOf("none", "urgent", "high", "medium", "low")
val BOARD_TASK_PROVIDERS = WandProvider.entries.map { it.id }

/** 任务看板的执行工具：Pi CLI 与 Wand Agent 是两条独立选项（与新建会话同一份清单）。 */
val BOARD_TASK_TOOLS = AGENT_TOOL_OPTIONS.map { it.id }
val BOARD_TASK_EFFORTS = listOf("off", "standard", "deep", "max")

/** 任务派发允许的执行模式；顺序即下拉顺序。Codex 只有 full-access 一个有效值。 */
val BOARD_TASK_MODES = listOf("managed", "full-access", "default")

/** 任务派发允许的会话形态；顺序即下拉顺序。 */
val BOARD_TASK_KINDS = listOf("structured", "pty")

fun supportedBoardTaskModes(provider: String): List<String> =
    if (provider == "codex") listOf("full-access") else BOARD_TASK_MODES

/** 会话形态判定：pty / shell 都算终端，其余（含老服务端缺省）按结构化处理。 */
private fun isTerminalSessionKind(kind: String?): Boolean {
    val normalized = kind?.trim().orEmpty()
    return normalized == "pty" || normalized == "shell"
}

/** 把任意（含旧数据 / 其它客户端缺省的）会话形态收敛成合法值。 */
fun normalizeBoardTaskAgentKind(kind: String?): String =
    if (isTerminalSessionKind(kind)) "pty" else "structured"

fun boardTaskKindLabel(kind: String): String = when (kind) {
    "pty" -> "PTY 终端"
    else -> "结构化对话"
}

/** 把任意（含旧数据 / 其它客户端缺省的）模式夹到该 provider 真正支持的值。 */
fun normalizeBoardTaskAgentMode(provider: String, mode: String?): String {
    val supported = supportedBoardTaskModes(provider)
    val value = mode?.trim().orEmpty()
    return if (value in supported) value else supported.firstOrNull { it == "default" } ?: supported.first()
}

fun boardTaskModeLabel(mode: String): String = when (mode) {
    "managed" -> "托管"
    "full-access" -> "全权限"
    else -> "标准"
}

fun boardTaskEffortLabel(effort: String): String = when (effort) {
    "standard" -> "标准"
    "deep" -> "深入"
    "max" -> "最大"
    else -> "关闭"
}

fun boardTaskProviderLabel(provider: String): String =
    WandProvider.fromId(provider)?.displayName ?: when (provider) {
        "shell", "session" -> "终端"
        "" -> "Agent"
        else -> provider
    }

/** 执行工具标签：同一 provider 的 Pi CLI 与 Wand Agent 必须分开显示；终端仍按终端处理。 */
fun boardTaskAgentLabel(provider: String, engine: String?): String =
    if (provider == "pi" && engine == WandAgentEngine.Sdk.raw) "Wand Agent" else boardTaskProviderLabel(provider)

/** 模型字段里的「跟随服务端默认」哨兵值：不是模型 id，写进请求前必须换掉。 */
const val BOARD_AGENT_DEFAULT_MODEL = "default"

/** 「跟随 X 默认」这种没写明模型的文案不算名字，其余去掉「（X 默认）」尾巴后就是 CLI 报出来的默认模型。 */
private val DEFAULT_MODEL_LABEL_TAIL = Regex("""\s*[（(][^（()）]*默认[^（()）]*[）)]\s*$""")
private val GENERIC_DEFAULT_MODEL_LABEL = Regex("""^跟随.*默认$""")

/**
 * 界面上要显示的模型名：`default` / 空值是「跟随服务端默认」的哨兵值、不是模型名，
 * 换成真正会用的那个模型：先看服务端为该 CLI 配置的默认模型，再看 CLI 自己报出来的默认项
 * （Codex / Grok 的目录项里写了具体模型名）。三处都拿不到名字返回空串，由调用方决定兜底。
 * 口径与 Web `wandModelDisplayName` 一致。
 */
fun boardAgentModelName(models: ModelsResponse?, provider: String, model: String?): String {
    val id = model?.trim().orEmpty()
    if (id.isNotEmpty() && id != BOARD_AGENT_DEFAULT_MODEL) return modelSelectionName(models, provider, id)
    // 认不出的 provider（`session` / `shell` = 终端）不猜默认模型，否则会把 Claude 的默认值安到别人头上。
    if (WandProvider.fromId(provider) == null) return ""
    val configured = models?.defaultModelFor(provider)?.trim().orEmpty()
    if (configured.isNotEmpty() && configured != BOARD_AGENT_DEFAULT_MODEL) return modelSelectionName(models, provider, configured)
    val label = models?.modelsFor(provider)?.firstOrNull { it.id == BOARD_AGENT_DEFAULT_MODEL }?.label.orEmpty()
    val stripped = DEFAULT_MODEL_LABEL_TAIL.replace(label, "").trim()
    return if (GENERIC_DEFAULT_MODEL_LABEL.matches(stripped)) "" else stripped
}

data class BoardAgentGroup(
    val provider: String,
    /** 组内执行引擎；`sdk` = Wand Agent。CLI 组为空。 */
    val engine: String?,
    val agent: BoardTaskAgent?,
    val sessions: List<BoardTaskSession>,
) {
    /** 组标题：同一 provider 的两条执行路径要分开。 */
    val label: String get() = boardTaskAgentLabel(provider, engine)
}

/** 打开任务时按 CLI 工具列出已执行 / 已指派的 Agent。 */
fun groupBoardSessionsByAgent(
    sessions: List<BoardTaskSession>,
    assigned: BoardTaskAgent? = null,
): List<BoardAgentGroup> {
    data class Builder(
        val provider: String,
        val engine: String?,
        var agent: BoardTaskAgent?,
        val sessions: MutableList<BoardTaskSession>,
    )
    val builders = mutableListOf<Builder>()
    val index = linkedMapOf<String, Builder>()
    fun ensure(provider: String, engine: String?, agent: BoardTaskAgent?): Builder {
        val key = "${provider.ifBlank { "session" }}:${engine ?: WandAgentEngine.Cli.raw}"
        index[key]?.let {
            if (it.agent == null && agent != null) it.agent = agent
            return it
        }
        val next = Builder(provider.ifBlank { "session" }, engine, agent, mutableListOf())
        index[key] = next
        builders += next
        return next
    }
    if (assigned != null && assigned.provider in BOARD_TASK_PROVIDERS) {
        ensure(assigned.provider, assigned.engine, assigned)
    }
    for (session in sessions) {
        val agent = if (session.provider in BOARD_TASK_PROVIDERS) {
            BoardTaskAgent(
                provider = session.provider,
                model = session.model.ifBlank { "default" },
                thinkingEffort = session.thinkingEffort.ifBlank { "off" },
                mode = normalizeBoardTaskAgentMode(session.provider, null),
                // 会话形态按会话自身的 sessionKind 还原，PTY 会话在「再指派」时不会被误当结构化。
                kind = if (session.sessionKind == "pty") "pty" else "structured",
                engine = session.engine,
            )
        } else {
            null
        }
        ensure(agent?.provider ?: session.provider, agent?.engine, agent).sessions += session
    }
    return builders.map { BoardAgentGroup(it.provider, it.engine, it.agent, it.sessions.toList()) }
}

fun boardTaskAgentLabels(sessions: List<BoardTaskSession>, assigned: BoardTaskAgent?): String? {
    val labels = groupBoardSessionsByAgent(sessions, assigned).map { it.label }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 服务端只允许关联同项目、正在处理的任务；全局任务的 workspaceId 是 null。 */
fun boardParentTaskOptions(tasks: List<BoardTask>, workspaceId: String?): List<Pair<String, String>> =
    listOf("" to "不关联父任务") + tasks.filter { it.workspaceId == workspaceId && it.status == "doing" }
        .map { task ->
            task.id to listOfNotNull(task.identifier.takeIf { it.isNotBlank() }, task.title)
                .joinToString(" · ")
        }

/**
 * workspace task 与看板卡是两张表、两个 id：看板 DTO 上的 `workspaceTaskId`
 * 是唯一把两者对起来的字段，老服务端没有别的查询口。找不到返回 null，由调用方决定文案。
 */
fun findBoardTaskByWorkspaceTaskId(
    cards: List<BoardTask>,
    workspaceTaskId: String?,
): BoardTask? =
    workspaceTaskId?.takeIf { it.isNotBlank() }
        ?.let { id -> cards.firstOrNull { it.workspaceTaskId == id } }

/** 老服务端的 /api/tasks 创建仍忽略 parentTaskId：以看板 DTO 核对，必要时 PATCH 补写。 */
suspend fun ensureBoardTaskParent(
    api: TaskBoardPort,
    workspaceTaskId: String,
    parentTaskId: String,
) {
    val card = findBoardTaskByWorkspaceTaskId(api.listBoardTasks(), workspaceTaskId)
        ?: throw IllegalStateException("新任务已创建，但暂时找不到对应的看板任务")
    if (card.parentTaskId == parentTaskId) return
    val linked = api.updateBoardTask(card.id, JSONObject().put("parentTaskId", parentTaskId))
    if (linked.parentTaskId != parentTaskId) {
        throw IllegalStateException("服务端未保存父任务关联")
    }
}

fun createBoardTaskBody(
    title: String,
    description: String,
    status: String,
    priority: String,
    workspaceId: String?,
    agent: BoardTaskAgent? = null,
    parentTaskId: String? = null,
    executionSubject: ExecutionSubject? = null,
): JSONObject {
    val body = JSONObject()
        .put("title", title)
        .put("description", description)
        .put("status", status)
        .put("priority", priority)
        .put("labels", JSONArray())
    if (workspaceId.isNullOrBlank()) body.put("workspaceId", JSONObject.NULL)
    else body.put("workspaceId", workspaceId)
    if (agent != null && executionSubject?.type != "employee" && executionSubject?.type != "team") {
        body.put("agent", agent.toJson())
    }
    if (!parentTaskId.isNullOrBlank()) body.put("parentTaskId", parentTaskId)
    executionSubject?.let { body.put("executionSubject", it.toJson()) }
    return body
}

fun patchBoardTaskBody(
    title: String? = null,
    description: String? = null,
    status: String? = null,
    priority: String? = null,
    workspaceId: String? = UNSET_WORKSPACE,
    agent: BoardTaskAgent? = null,
    sortOrder: Int? = null,
): JSONObject {
    val body = JSONObject()
    if (title != null) body.put("title", title)
    if (description != null) body.put("description", description)
    if (status != null) body.put("status", status)
    if (priority != null) body.put("priority", priority)
    if (workspaceId !== UNSET_WORKSPACE) {
        if (workspaceId.isNullOrBlank()) body.put("workspaceId", JSONObject.NULL)
        else body.put("workspaceId", workspaceId)
    }
    if (agent != null) body.put("agent", agent.toJson())
    if (sortOrder != null) body.put("sortOrder", sortOrder)
    return body
}

fun boardDispatchSubjectBody(
    subject: ExecutionSubject,
    agent: BoardTaskAgent,
    prompt: String? = null,
    workspaceId: String? = UNSET_WORKSPACE,
): JSONObject = JSONObject().put("subject", subject.toJson()).also { body ->
    if (subject.type == "cli") body.put("agent", agent.toJson())
    if (!prompt.isNullOrBlank()) body.put("prompt", prompt)
    if (workspaceId !== UNSET_WORKSPACE) {
        body.put("workspaceId", workspaceId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    }
}

val UNSET_WORKSPACE: String? = "__wand_unset_workspace__"

fun boardAgentModelOptions(models: ModelsResponse?, provider: String): List<ModelInfo> {
    val catalog = models?.modelsFor(provider).orEmpty()
    val hasDefault = catalog.any { it.id == "default" }
    return if (hasDefault) catalog
    else listOf(
        ModelInfo(
            id = "default",
            label = models?.defaultModelFor(provider)
                ?.takeIf { it.isNotBlank() }
                ?.let { "跟随服务端默认（$it）" }
                ?: "跟随服务端默认",
            alias = null,
            reasoningEfforts = emptyList(),
            defaultReasoningEffort = null,
        ),
    ) + catalog
}

interface TaskBoardPort : TaskChangeSource {
    suspend fun listBoardTasks(workspaceId: String? = null): List<BoardTask>
    /** 单条任务：新建后用来确认后台自动标题是否已生成。 */
    suspend fun getBoardTask(id: String): BoardTask?
    suspend fun createBoardTask(
        title: String,
        description: String,
        status: String,
        priority: String,
        workspaceId: String?,
        agent: BoardTaskAgent? = null,
        parentTaskId: String? = null,
    ): BoardTask
    suspend fun createBoardSubjectTask(
        title: String,
        description: String,
        status: String,
        priority: String,
        workspaceId: String?,
        agent: BoardTaskAgent?,
        parentTaskId: String?,
        executionSubject: ExecutionSubject,
    ): BoardTask = createBoardTask(title, description, status, priority, workspaceId, agent, parentTaskId)
    suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask
    suspend fun deleteBoardTask(id: String)
    suspend fun dispatchBoardTask(
        id: String,
        agent: BoardTaskAgent,
        prompt: String? = null,
        workspaceId: String? = UNSET_WORKSPACE,
    ): BoardDispatchResult
    suspend fun dispatchBoardSubject(
        id: String,
        subject: ExecutionSubject,
        agent: BoardTaskAgent,
        prompt: String? = null,
        workspaceId: String? = UNSET_WORKSPACE,
    ): BoardDispatchResult = dispatchBoardTask(id, agent, prompt, workspaceId)
    suspend fun listBoardWorkspaces(): List<Workspace>
    suspend fun boardModels(): ModelsResponse
    suspend fun boardTaskAgentDefaults(): BoardTaskAgent
    suspend fun saveBoardTaskAgentDefaults(agent: BoardTaskAgent): BoardTaskAgent

    suspend fun listSiliconEmployees(includeArchived: Boolean = false): List<SiliconEmployee> = emptyList()

    // MARK: - AI 团队（服务端 src/server-ai-team-routes.ts；鉴权同普通登录）

    /** 团队定义列表，供指派选择器与团队页。缺省 = 该端口不提供团队能力。 */
    suspend fun listAiTeams(): List<AiTeam> =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /** 新建团队（POST /api/ai-teams），返回服务端归一后的定义（成员 id 由服务端生成）。 */
    suspend fun createAiTeam(draft: AiTeamDraft): AiTeam =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /** 整体替换团队（PUT /api/ai-teams/{id}）：服务端按请求体重写，未带的字段回落到旧值。 */
    suspend fun updateAiTeam(teamId: String, draft: AiTeamDraft): AiTeam =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /** 删除团队定义（已有运行保留自己的快照，不受影响）。 */
    suspend fun deleteAiTeam(teamId: String) {
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")
    }

    /** 任务上按创建倒序的团队运行；面板只展示最近一次。 */
    suspend fun teamRunsForTask(taskId: String): List<AiTeamRun> =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /** 团队页最近协作；不传 teamId 时跨团队取最近记录。 */
    suspend fun listAiTeamRuns(teamId: String? = null, limit: Int = 50): List<AiTeamRun> =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    suspend fun aiTeamRunDetail(runId: String): AiTeamRunDetail =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /** 把任务交给团队（POST /api/wand-tasks/{id}/team-runs）；note 可为空。 */
    suspend fun startTeamRun(taskId: String, teamId: String, note: String): AiTeamRunDetail =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /** 直接开工（POST /api/ai-teams/{id}/runs，§4.2）：workspaceId 必须是非 global 的已有项目。 */
    suspend fun startDirectTeamRun(
        teamId: String,
        workspaceId: String,
        note: String,
    ): AiTeamDirectRun =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    suspend fun actOnTeamRun(runId: String, action: TeamRunAction): AiTeamRunDetail =
        throw UnsupportedOperationException("当前客户端不支持 AI 团队。")

    /**
     * 无指派派工第一步（POST /api/team-dispatch/plan）：不指定员工，
     * 由本机决策模型按开工说明给出建议名单。只读，不建任何东西。
     */
    suspend fun planTeamDispatch(note: String, maxMembers: Int? = null): TeamDispatchPlan =
        throw UnsupportedOperationException("当前服务不支持无指派派工。")

    /**
     * 无指派派工第二步：确认名单后才建临时团队、建卡、起 run。
     * 名单由调用方回传（服务端会重新校验员工身份）。
     */
    suspend fun startTeamDispatch(
        workspaceId: String,
        note: String,
        members: List<TeamDispatchPick>,
    ): AiTeamDispatchRun = throw UnsupportedOperationException("当前服务不支持无指派派工。")
}
