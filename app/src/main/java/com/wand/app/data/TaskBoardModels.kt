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
    else -> status
}

fun boardTaskPriorityLabel(priority: String): String = when (priority) {
    "urgent" -> "紧急"
    "high" -> "高"
    "medium" -> "中"
    "low" -> "低"
    else -> "无优先级"
}

fun boardTaskStatusEmpty(status: String): String = when (status) {
    "todo" -> "还没有待办任务"
    "doing" -> "暂无进行中的任务"
    "done" -> "还没有完成的任务"
    else -> "暂无任务"
}

val BOARD_TASK_STATUSES = listOf("todo", "doing", "done")
val BOARD_TASK_PRIORITIES = listOf("none", "urgent", "high", "medium", "low")
val BOARD_TASK_PROVIDERS = listOf("claude", "codex", "opencode", "grok", "qoder", "pi")
val BOARD_TASK_EFFORTS = listOf("off", "standard", "deep", "max")

fun boardTaskEffortLabel(effort: String): String = when (effort) {
    "standard" -> "标准"
    "deep" -> "深入"
    "max" -> "最大"
    else -> "关闭"
}

fun boardTaskProviderLabel(provider: String): String = when (provider) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "opencode" -> "OpenCode"
    "grok" -> "Grok"
    "qoder" -> "Qoder"
    "pi" -> "Pi"
    else -> provider.ifBlank { "Agent" }
}

fun createBoardTaskBody(
    title: String,
    description: String,
    status: String,
    priority: String,
    workspaceId: String?,
): JSONObject {
    val body = JSONObject()
        .put("title", title)
        .put("description", description)
        .put("status", status)
        .put("priority", priority)
        .put("labels", JSONArray())
    if (workspaceId.isNullOrBlank()) body.put("workspaceId", JSONObject.NULL)
    else body.put("workspaceId", workspaceId)
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
    val catalog = when (provider) {
        "codex" -> models?.codexModels.orEmpty()
        "opencode" -> models?.opencodeModels.orEmpty()
        "grok" -> models?.grokModels.orEmpty()
        "qoder" -> models?.qoderModels.orEmpty()
        "pi" -> models?.piModels.orEmpty()
        else -> models?.models.orEmpty()
    }
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
    suspend fun createBoardTask(
        title: String,
        description: String,
        status: String,
        priority: String,
        workspaceId: String?,
    ): BoardTask
    suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask
    suspend fun deleteBoardTask(id: String)
    suspend fun dispatchBoardTask(id: String, agent: BoardTaskAgent): BoardDispatchResult
    suspend fun listBoardWorkspaces(): List<Workspace>
    suspend fun boardModels(): ModelsResponse
}
