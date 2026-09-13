package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

data class BoardTaskAgent(
    val provider: String,
    val model: String,
    val thinkingEffort: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("thinkingEffort", thinkingEffort)

    companion object {
        fun default(): BoardTaskAgent = BoardTaskAgent("claude", "default", "off")

        fun parse(item: JSONObject?): BoardTaskAgent? {
            val provider = item?.str("provider")?.takeIf { it.isNotBlank() } ?: return null
            return BoardTaskAgent(
                provider = provider,
                model = item.str("model")?.takeIf { it.isNotBlank() } ?: "default",
                thinkingEffort = item.str("thinkingEffort")?.takeIf { it.isNotBlank() } ?: "off",
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

data class BoardTaskSession(
    val id: String,
    val provider: String,
    val sessionKind: String,
    val title: String,
    val status: String,
    val cwd: String,
    val model: String,
    val thinkingEffort: String,
) {
    val isStructured: Boolean
        get() = sessionKind != "pty" && sessionKind != "shell"

    companion object {
        fun parseList(array: JSONArray?): List<BoardTaskSession> = array?.parseEach { item ->
            val id = item.str("id") ?: return@parseEach null
            BoardTaskSession(
                id = id,
                provider = item.str("provider") ?: "",
                sessionKind = item.str("sessionKind") ?: "",
                title = item.str("title") ?: id,
                status = item.str("status") ?: "",
                cwd = item.str("cwd") ?: "",
                model = item.str("model") ?: "",
                thinkingEffort = item.str("thinkingEffort") ?: "off",
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
    val createdAt: String,
    val updatedAt: String,
    val sessionIds: List<String>,
    val sessions: List<BoardTaskSession>,
    val workspace: BoardTaskWorkspace?,
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
                labels = item.arr("labels")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                } ?: emptyList(),
                dueDate = item.str("dueDate")?.takeIf { it.isNotBlank() },
                sortOrder = item.int("sortOrder") ?: 0,
                agent = BoardTaskAgent.parse(item.obj("agent")),
                createdAt = item.str("createdAt") ?: "",
                updatedAt = item.str("updatedAt") ?: "",
                sessionIds = item.arr("sessionIds")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                } ?: emptyList(),
                sessions = BoardTaskSession.parseList(item.arr("sessions")),
                workspace = BoardTaskWorkspace.parse(item.obj("workspace")),
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
) {
    companion object {
        fun parse(item: JSONObject): BoardDispatchResult {
            val session = item.obj("session")
            return BoardDispatchResult(
                ok = item.optBoolean("ok", true),
                taskId = item.str("taskId") ?: "",
                sessionId = session?.str("id") ?: "",
                provider = session?.str("provider") ?: "",
                cwd = session?.str("cwd") ?: "",
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
val BOARD_TASK_PROVIDERS = listOf("claude", "codex", "opencode", "grok", "qoder", "pi")
val BOARD_TASK_EFFORTS = listOf("off", "standard", "deep", "max")

fun boardTaskEffortLabel(effort: String): String = when (effort) {
    "standard" -> "标准"
    "deep" -> "深入"
    "max" -> "最大"
    else -> "关闭"
}

fun boardTaskProviderLabel(provider: String): String =
    WandProvider.fromId(provider)?.displayName ?: provider.ifBlank { "Agent" }

data class BoardAgentGroup(
    val provider: String,
    val agent: BoardTaskAgent?,
    val sessions: List<BoardTaskSession>,
)

/** 打开任务时按 CLI 工具列出已执行 / 已指派的 Agent。 */
fun groupBoardSessionsByAgent(
    sessions: List<BoardTaskSession>,
    assigned: BoardTaskAgent? = null,
): List<BoardAgentGroup> {
    data class Builder(val provider: String, var agent: BoardTaskAgent?, val sessions: MutableList<BoardTaskSession>)
    val builders = mutableListOf<Builder>()
    val index = linkedMapOf<String, Builder>()
    fun ensure(provider: String, agent: BoardTaskAgent?): Builder {
        val key = provider.ifBlank { "session" }
        index[key]?.let {
            if (it.agent == null && agent != null) it.agent = agent
            return it
        }
        val next = Builder(key, agent, mutableListOf())
        index[key] = next
        builders += next
        return next
    }
    if (assigned != null && assigned.provider in BOARD_TASK_PROVIDERS) {
        ensure(assigned.provider, assigned)
    }
    for (session in sessions) {
        val agent = if (session.provider in BOARD_TASK_PROVIDERS) {
            BoardTaskAgent(
                provider = session.provider,
                model = session.model.ifBlank { "default" },
                thinkingEffort = session.thinkingEffort.ifBlank { "off" },
            )
        } else {
            null
        }
        ensure(agent?.provider ?: session.provider, agent).sessions += session
    }
    return builders.map { BoardAgentGroup(it.provider, it.agent, it.sessions.toList()) }
}

fun boardTaskAgentLabels(sessions: List<BoardTaskSession>, assigned: BoardTaskAgent?): String? {
    val labels = groupBoardSessionsByAgent(sessions, assigned).map { boardTaskProviderLabel(it.provider) }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

fun createBoardTaskBody(
    title: String,
    description: String,
    status: String,
    priority: String,
    workspaceId: String?,
    agent: BoardTaskAgent? = null,
): JSONObject {
    val body = JSONObject()
        .put("title", title)
        .put("description", description)
        .put("status", status)
        .put("priority", priority)
        .put("labels", JSONArray())
    if (workspaceId.isNullOrBlank()) body.put("workspaceId", JSONObject.NULL)
    else body.put("workspaceId", workspaceId)
    if (agent != null) body.put("agent", agent.toJson())
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

interface TaskBoardPort {
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
    ): BoardTask
    suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask
    suspend fun deleteBoardTask(id: String)
    suspend fun dispatchBoardTask(
        id: String,
        agent: BoardTaskAgent,
        prompt: String? = null,
        workspaceId: String? = UNSET_WORKSPACE,
    ): BoardDispatchResult
    suspend fun listBoardWorkspaces(): List<Workspace>
    suspend fun boardModels(): ModelsResponse
    suspend fun boardTaskAgentDefaults(): BoardTaskAgent
    suspend fun saveBoardTaskAgentDefaults(agent: BoardTaskAgent): BoardTaskAgent
}
