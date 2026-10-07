package com.wand.app.data

import org.json.JSONObject

const val CONVERSATION_MIN_EMPLOYEES = 1
const val CONVERSATION_MAX_EMPLOYEES = 8
fun employeeConversationId(employeeId: String): String = "dm_$employeeId"

data class ConversationTarget(val taskId: String, val runId: String) {
    fun toJson() = JSONObject().put("taskId", taskId).put("runId", runId)
    companion object {
        fun parse(json: JSONObject?): ConversationTarget? = json?.let {
            val task = it.str("taskId") ?: return null
            val run = it.str("runId") ?: return null
            ConversationTarget(task, run)
        }
    }
}

data class ConversationLink(val conversationId: String, val taskId: String, val title: String) {
    companion object {
        fun parse(json: JSONObject?): ConversationLink? = json?.let {
            ConversationLink(it.str("conversationId") ?: return null, it.str("taskId") ?: return null, it.str("title").orEmpty())
        }
    }
}

data class ConversationSessionLink(val sessionId: String, val title: String) {
    companion object {
        fun parse(json: JSONObject?): ConversationSessionLink? = json?.let {
            ConversationSessionLink(it.str("sessionId") ?: return null, it.str("title").orEmpty())
        }
    }
}

data class ConversationSessionPreview(val status: String, val text: String) {
    companion object {
        fun parse(json: JSONObject?): ConversationSessionPreview? = json?.let {
            ConversationSessionPreview(it.str("status") ?: "starting", it.str("text").orEmpty().takeLast(4000))
        }
    }
}

data class ConversationSessionUpdate(val conversationId: String, val sessionId: String, val preview: ConversationSessionPreview) {
    companion object {
        fun parse(json: JSONObject?): ConversationSessionUpdate? = json?.let {
            ConversationSessionUpdate(it.str("conversationId") ?: return null, it.str("sessionId") ?: return null,
                ConversationSessionPreview.parse(it.obj("preview")) ?: return null)
        }
    }
}

/** Bounded read-only projection; history stays with the task run. */
data class ConversationTaskPreview(val runId: String?, val status: String, val text: String) {
    companion object {
        fun parse(json: JSONObject?): ConversationTaskPreview? = json?.let {
            ConversationTaskPreview(it.str("runId"), it.str("status") ?: "starting", it.str("text").orEmpty().takeLast(4000))
        }
    }
}

fun conversationTaskLiveText(steps: List<AiTeamLiveStep>): String = steps.filter { it.text.isNotBlank() }
    .joinToString("\n\n") { "${it.memberName}：\n${it.text.takeLast(4000)}" }.takeLast(4000)

data class ConversationTaskStartup(val state: String, val error: String?)

data class ConversationTask(val task: BoardTask, val runs: List<AiTeamRun>, val startup: ConversationTaskStartup? = null) {
    companion object {
        fun parse(json: JSONObject): ConversationTask? {
            val task = json.obj("task")?.let(BoardTask::parse) ?: return null
            val startup = json.obj("startup")?.let { ConversationTaskStartup(it.optString("state"), it.str("error")) }
            return ConversationTask(task, AiTeamRun.parseList(json.arr("runs")), startup)
        }
    }
}

data class ConversationInstance(
    val id: String,
    val kind: String,
    val title: String,
    val peerEmployeeId: String?,
    val team: AiTeam?,
    val memberVersion: Int,
    val sessionId: String?,
    val communicationSessionId: String?,
    val sourceTemplateId: String?,
    val nameSource: String,
    val preview: String,
    val messageAt: String,
    val unavailableReason: String?,
    val memberUnavailableReasons: Map<String, String>,
    val joinedVersions: Map<String, Int>,
    val tasks: List<ConversationTask>,
    val messages: List<ConversationTurn>,
    val runDetails: List<AiTeamRunDetail>,
    val pinnedAt: String? = null,
    val dissolvedAt: String? = null,
    val dissolvedBy: String? = null,
    val deleting: Boolean = false,
) {
    val leader: AiTeamMember? get() = team?.members?.firstOrNull { it.isLeader }
    companion object {
        fun parse(json: JSONObject): ConversationInstance? {
            val id = json.str("id") ?: return null
            val reasons = json.obj("memberUnavailableReasons")
            val joined = json.obj("joinedVersions")
            return ConversationInstance(id, json.str("kind") ?: return null, json.str("title").orEmpty(),
                json.str("peerEmployeeId"), json.obj("team")?.let(AiTeam::parse), json.int("memberVersion") ?: 1,
                json.str("sessionId"), json.str("communicationSessionId"), json.str("sourceTemplateId"), json.str("nameSource") ?: "auto",
                json.str("preview").orEmpty(), json.str("messageAt").orEmpty(), json.str("unavailableReason"),
                reasons?.keys()?.asSequence()?.associateWith { reasons.optString(it) } ?: emptyMap(),
                joined?.keys()?.asSequence()?.associateWith { joined.optInt(it) } ?: emptyMap(),
                json.arr("tasks")?.parseEach(ConversationTask::parse) ?: emptyList(),
                ConversationTurn.parseList(json.arr("messages")) ?: emptyList(),
                json.arr("runDetails")?.parseEach(AiTeamRunDetail::parse) ?: emptyList(), json.str("pinnedAt"), json.str("dissolvedAt"), json.str("dissolvedBy"), json.optBoolean("deleting"))
        }
        fun parseList(json: JSONObject): List<ConversationInstance> = json.arr("conversations")?.parseEach(::parse) ?: emptyList()
    }
}

data class ConversationReceipt(val requestId: String, val state: String, val conversationId: String,
    val taskId: String?, val runId: String?, val sessionId: String?, val error: String?, val startup: String? = null) {
    companion object {
        fun parse(json: JSONObject): ConversationReceipt {
            val id = json.str("requestId") ?: error("缺少请求回执")
            val state = json.str("state")?.takeIf { it in listOf("accepted", "pending", "rejected") } ?: error("无法确认接受状态")
            return ConversationReceipt(id, state, json.str("conversationId").orEmpty(), json.str("taskId"), json.str("runId"), json.str("sessionId"), json.str("error"), json.str("startup"))
        }
    }
}

class ConversationUnconfirmedException(val requestId: String) : Exception("送达未确认，请先核对记录，勿重复发送。")
