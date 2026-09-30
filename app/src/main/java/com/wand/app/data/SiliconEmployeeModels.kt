package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A named conversation partner with ordered structured CLI candidates. */
data class SiliconEmployee(
    val id: String,
    val name: String,
    val duty: String,
    val prompt: String,
    val avatar: String,
    val agents: List<BoardTaskAgent>,
    val archivedAt: String? = null,
) {
    val archived: Boolean get() = !archivedAt.isNullOrBlank()

    companion object {
        fun parse(value: JSONObject?): SiliconEmployee? {
            val id = value?.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return SiliconEmployee(
                id = id,
                name = value.str("name") ?: id,
                duty = value.str("duty") ?: "",
                prompt = value.str("prompt") ?: "",
                avatar = value.str("avatar") ?: "",
                agents = value.arr("agents")?.parseEach { BoardTaskAgent.parse(it) } ?: emptyList(),
                archivedAt = value.str("archivedAt")?.takeIf { it.isNotBlank() },
            )
        }

        fun parseList(value: JSONArray?): List<SiliconEmployee> =
            value?.parseEach { parse(it) } ?: emptyList()
    }
}

data class SiliconEmployeeDraft(
    val name: String = "",
    val duty: String = "",
    val prompt: String = "",
    val avatar: String = "",
    val agents: List<BoardTaskAgent> = listOf(BoardTaskAgent.default()),
) {
    fun validationError(): String? = when {
        name.trim().isEmpty() -> "请填写员工名字。"
        name.trim().length > 40 -> "员工名字不能超过 40 个字符。"
        duty.trim().length > 2000 -> "员工职责不能超过 2000 个字符。"
        prompt.trim().length > 20_000 -> "员工设定不能超过 20000 个字符。"
        agents.isEmpty() -> "至少保留 1 个执行候选。"
        agents.size > 4 -> "最多 4 个执行候选。"
        agents.any { it.kind != "structured" } -> "硅基员工只能使用结构化会话。"
        agents.map(::boardTaskAgentKey).distinct().size != agents.size -> "执行候选存在重复。"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name.trim())
        .put("duty", duty.trim())
        .put("prompt", prompt.trim())
        .put("avatar", avatar)
        .put("agents", JSONArray().also { result ->
            agents.forEach { result.put(it.copy(kind = "structured").toJson()) }
        })

    companion object {
        fun from(employee: SiliconEmployee): SiliconEmployeeDraft = SiliconEmployeeDraft(
            name = employee.name,
            duty = employee.duty,
            prompt = employee.prompt,
            avatar = employee.avatar,
            agents = employee.agents,
        )
    }
}

/** One picker value shared by task dispatch and new structured conversations. */
data class ExecutionSubject(val type: String, val id: String) {
    val key: String get() = "$type:$id"
    fun toJson(): JSONObject = JSONObject().put("type", type).put("id", id)

    companion object {
        fun parse(value: JSONObject?): ExecutionSubject? {
            val type = value?.str("type") ?: return null
            val id = value.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return if (type in setOf("employee", "team", "cli")) ExecutionSubject(type, id) else null
        }

        fun cli(provider: String): ExecutionSubject = ExecutionSubject("cli", provider)
        fun employee(id: String): ExecutionSubject = ExecutionSubject("employee", id)
        fun team(id: String): ExecutionSubject = ExecutionSubject("team", id)
        fun fromKey(key: String, provider: String): ExecutionSubject {
            val type = key.substringBefore(':')
            val id = key.substringAfter(':', "")
            return if (type in setOf("employee", "team") && id.isNotBlank()) ExecutionSubject(type, id)
                else cli(provider)
        }
    }
}
