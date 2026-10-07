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
    val systemKey: String? = null,
    val archivedAt: String? = null,
    val tags: List<String> = emptyList(),
    /** 创建时间（ISO-8601）。通讯录按它做时间先后排序；旧服务不返回时为空串。 */
    val createdAt: String = "",
) {
    val archived: Boolean get() = !archivedAt.isNullOrBlank()

    /**
     * Wand 内置员工（系统运维 / 默认伙伴）：名字、职责、人设、头像由服务端锁定，
     * 不可归档、不可删除，只有执行候选由用户维护。
     */
    val builtin: Boolean get() = !systemKey.isNullOrBlank()

    /** 内置「系统运维」员工（Wand 自有 AI 调用的执行者），不作为默认的对话伙伴展示。 */
    val isSystemEmployee: Boolean get() = systemKey == SYSTEM_EMPLOYEE_KEY

    /** 旧服务未返回 tags 时，内置标签仍按稳定身份展示；不能被自定义标签覆盖。 */
    val displayTags: List<String> get() = when (systemKey) {
        SYSTEM_EMPLOYEE_KEY -> listOf(SYSTEM_EMPLOYEE_TAG)
        DEFAULT_EMPLOYEE_KEY -> listOf(DEFAULT_EMPLOYEE_TAG)
        else -> tags
    }

    companion object {
        const val SYSTEM_EMPLOYEE_KEY = "wand-ops"
        const val DEFAULT_EMPLOYEE_KEY = "wand-default"
        const val SYSTEM_EMPLOYEE_TAG = "系统用户"
        const val DEFAULT_EMPLOYEE_TAG = "默认用户"
        const val MAX_TAGS = 8
        const val TAG_MAX_CHARS = 20

        fun parse(value: JSONObject?): SiliconEmployee? {
            val id = value?.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return SiliconEmployee(
                id = id,
                name = value.str("name") ?: id,
                duty = value.str("duty") ?: "",
                prompt = value.str("prompt") ?: "",
                avatar = value.str("avatar") ?: "",
                agents = value.arr("agents")?.parseEach { BoardTaskAgent.parse(it) } ?: emptyList(),
                systemKey = value.str("systemKey")?.takeIf { it.isNotBlank() },
                archivedAt = value.str("archivedAt")?.takeIf { it.isNotBlank() },
                tags = value.arr("tags")?.let { array ->
                    (0 until array.length()).mapNotNull { array.opt(it) as? String }
                } ?: emptyList(),
                createdAt = value.str("createdAt") ?: "",
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
    val tagInput: String = "",
) {
    fun tags(): List<String> = tagInput.split(',', '，', '、', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun validationError(validateTags: Boolean = true): String? = when {
        name.trim().isEmpty() -> "请填写员工名字。"
        name.trim().length > 40 -> "员工名字不能超过 40 个字符。"
        duty.trim().length > 2000 -> "员工职责不能超过 2000 个字符。"
        prompt.trim().length > 20_000 -> "员工设定不能超过 20000 个字符。"
        validateTags && tags().size > SiliconEmployee.MAX_TAGS -> "最多 8 个员工标签。"
        validateTags && tags().any { it.length > SiliconEmployee.TAG_MAX_CHARS } ->
            "每个员工标签不能超过 20 个字符。"
        validateTags && tags().any { tag -> tag.any { it.code < 32 || it.code == 127 } } ->
            "员工标签不能包含控制字符。"
        validateTags && tags().any {
            it == SiliconEmployee.SYSTEM_EMPLOYEE_TAG || it == SiliconEmployee.DEFAULT_EMPLOYEE_TAG
        } -> "「系统用户」「默认用户」是内置标签，不可自定义。"
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
        .put("tags", JSONArray(tags()))
        .put("agents", agentsJson())

    /** 内置员工只允许改执行候选，锁定字段不回传（服务端会拒绝与现值不一致的字段）。 */
    fun agentsJson(): JSONArray = JSONArray().also { result ->
        agents.forEach { result.put(it.copy(kind = "structured").toJson()) }
    }

    companion object {
        fun from(employee: SiliconEmployee): SiliconEmployeeDraft = SiliconEmployeeDraft(
            name = employee.name,
            duty = employee.duty,
            prompt = employee.prompt,
            avatar = employee.avatar,
            agents = employee.agents,
            tagInput = employee.displayTags.joinToString("，"),
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
