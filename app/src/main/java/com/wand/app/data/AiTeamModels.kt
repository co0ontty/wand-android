package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 团队只读模型（真源 src/ai-team-types.ts）。
 * Android 侧只做「指派给团队 + 看运行」，不建团队 / 不改人设，所以字段解析从宽：
 * 缺字段回落空值，不因单个字段缺失整条解析失败。
 */

data class AiTeamMember(
    val id: String,
    val name: String,
    val duty: String,
    val agents: List<BoardTaskAgent>,
    val isLeader: Boolean,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamMember? {
            val id = item.str("id") ?: return null
            val agents = item.arr("agents")
                ?.parseEach { BoardTaskAgent.parse(it) }
                ?.filter { it.provider.isNotBlank() }
                ?: emptyList()
            val single = BoardTaskAgent.parse(item.obj("agent"))
            return AiTeamMember(
                id = id,
                name = item.str("name") ?: id,
                duty = item.str("duty") ?: "",
                agents = if (agents.isNotEmpty()) agents else listOfNotNull(single),
                isLeader = item.bool("isLeader") ?: false,
            )
        }

        fun parseList(array: JSONArray?): List<AiTeamMember> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

data class AiTeam(
    val id: String,
    val name: String,
    val description: String,
    val members: List<AiTeamMember>,
) {
    companion object {
        fun parse(item: JSONObject): AiTeam? {
            val id = item.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return AiTeam(
                id = id,
                name = item.str("name") ?: id,
                description = item.str("description") ?: "",
                members = AiTeamMember.parseList(item.arr("members")),
            )
        }

        fun parseList(array: JSONArray?): List<AiTeam> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

data class AiTeamRun(
    val id: String,
    val teamId: String,
    val team: AiTeam?,
    val taskId: String,
    val objective: String,
    val status: String,
    val statusDetail: String,
    val stepsUsed: Int,
    val stepLimit: Int,
    val chatSessionId: String?,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamRun? {
            val id = item.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return AiTeamRun(
                id = id,
                teamId = item.str("teamId") ?: "",
                team = item.obj("team")?.let { AiTeam.parse(it) },
                taskId = item.str("taskId") ?: "",
                objective = item.str("objective") ?: "",
                status = item.str("status") ?: "running",
                statusDetail = item.str("statusDetail") ?: "",
                stepsUsed = item.int("stepsUsed") ?: 0,
                stepLimit = item.int("stepLimit") ?: 0,
                chatSessionId = item.str("chatSessionId")?.takeIf { it.isNotBlank() },
            )
        }

        fun parseList(array: JSONArray?): List<AiTeamRun> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

data class AiTeamStep(
    val id: String,
    val seq: Int,
    val kind: String,
    val memberId: String,
    val title: String,
    val status: String,
) {
    val isLeader: Boolean
        get() = kind == "leader"

    companion object {
        fun parse(item: JSONObject): AiTeamStep? {
            val id = item.str("id") ?: return null
            return AiTeamStep(
                id = id,
                seq = item.int("seq") ?: 0,
                kind = item.str("kind") ?: "work",
                memberId = item.str("memberId") ?: "",
                title = item.str("title") ?: "",
                status = item.str("status") ?: "queued",
            )
        }

        fun parseList(array: JSONArray?): List<AiTeamStep> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

data class AiTeamRunDetail(
    val run: AiTeamRun,
    val steps: List<AiTeamStep>,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamRunDetail? {
            val run = item.obj("run")?.let { AiTeamRun.parse(it) } ?: return null
            return AiTeamRunDetail(run = run, steps = AiTeamStep.parseList(item.arr("steps")))
        }
    }
}

/**
 * 直接开工（§4.2）的响应：服务端原子建 `team_direct` 卡后起 run，
 * 返回 `{ ...AiTeamRunDetail, taskId }`。旧服务端没有 taskId 字段时回落空串，
 * 由调用方退化到 `detail.run.taskId`。
 */
data class AiTeamDirectRun(
    val detail: AiTeamRunDetail,
    val taskId: String,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamDirectRun? {
            val detail = AiTeamRunDetail.parse(item) ?: return null
            return AiTeamDirectRun(
                detail = detail,
                taskId = item.str("taskId")?.takeIf { it.isNotBlank() } ?: "",
            )
        }
    }
}

/** 运行上的用户动作；HTTP 路径与请求体映射见 WandApi.kt 的 teamRunActionRequest。 */
sealed class TeamRunAction {
    object Approve : TeamRunAction()
    data class Reject(val feedback: String) : TeamRunAction()
    data class Reply(val text: String) : TeamRunAction()
    data class Continue(val extraSteps: Int) : TeamRunAction()
    object Stop : TeamRunAction()
}

fun aiTeamRunStatusLabel(status: String): String = when (status) {
    "running" -> "进行中"
    "awaiting_approval" -> "等你批准计划"
    "waiting_user" -> "等你回复"
    "done" -> "已完成"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status
}

fun aiTeamRunActive(status: String): Boolean =
    status == "running" || status == "awaiting_approval" || status == "waiting_user"

fun aiTeamStepStatusLabel(status: String): String = when (status) {
    "queued" -> "排队"
    "running" -> "进行中"
    "done" -> "完成"
    "failed" -> "失败"
    "skipped" -> "跳过"
    else -> status
}

/** 步骤归属显示名：leader 步显示「负责人」，work 步按快照找成员名。 */
fun aiTeamStepOwnerLabel(step: AiTeamStep, detail: AiTeamRunDetail): String {
    if (step.isLeader) return "负责人"
    val member = detail.run.team?.members?.firstOrNull { it.id == step.memberId }
    return member?.name ?: step.memberId.ifBlank { "成员" }
}
