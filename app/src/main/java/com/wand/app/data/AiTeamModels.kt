package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 团队模型（真源 src/ai-team-types.ts）。
 * Android 侧既能指派给团队、看运行，也能建团队 / 改成员（AiTeamEditorScreen），
 * 所以字段解析从宽：缺字段回落空值，不因单个字段缺失整条解析失败。
 */

data class AiTeamMember(
    val id: String,
    val name: String,
    val duty: String,
    val agents: List<BoardTaskAgent>,
    val isLeader: Boolean,
    /** 头像：空串按 id 哈希选毛色、`cat:<n>` 指定毛色、`data:image/…` 是上传的小图。 */
    val avatar: String = "",
    /** 团队拥有的职责标注（plan / work / verify / any）；缺省视同 any。 */
    val role: String? = null,
    /** 通讯录身份；旧成员不按名字猜绑定，只有显式选择员工才设置。 */
    val employeeId: String? = null,
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
                avatar = item.str("avatar") ?: "",
                role = item.str("role")?.takeIf { it.isNotBlank() },
                employeeId = item.str("employeeId")?.trim()?.takeIf { it.isNotEmpty() },
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
    /** 协作指令：写进负责人和每位成员的提示词（≤ 4000 字）。 */
    val instructions: String = "",
    /** 首个计划是否要用户批准（服务端缺省 true）。 */
    val requirePlanApproval: Boolean = true,
    /** Leader 轮次 + 成员步骤合计上限（5–200）。 */
    val maxSteps: Int = AI_TEAM_DEFAULT_MAX_STEPS,
) {
    companion object {
        fun parse(item: JSONObject): AiTeam? {
            val id = item.str("id")?.takeIf { it.isNotBlank() } ?: return null
            return AiTeam(
                id = id,
                name = item.str("name") ?: id,
                description = item.str("description") ?: "",
                members = AiTeamMember.parseList(item.arr("members")),
                instructions = item.str("instructions") ?: "",
                requirePlanApproval = item.bool("requirePlanApproval") ?: true,
                maxSteps = item.int("maxSteps") ?: AI_TEAM_DEFAULT_MAX_STEPS,
            )
        }

        fun parseList(array: JSONArray?): List<AiTeam> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

/**
 * 建 / 改团队提交给服务端的整份草稿（真源 `parseAiTeamInput`）。
 * 服务端 PUT 是整体替换，所以每个成员必须把 `avatar` / `role` 这类 Android 不编辑的字段原样带上，
 * 否则一次改名就会抹掉 Web 上选好的头像与标注。
 */
data class AiTeamDraft(
    val name: String,
    val description: String,
    val instructions: String,
    val requirePlanApproval: Boolean,
    val maxSteps: Int,
    val members: List<AiTeamMember>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name.trim())
        .put("description", description.trim())
        .put("instructions", instructions.trim())
        .put("requirePlanApproval", requirePlanApproval)
        .put("maxSteps", maxSteps)
        .put("members", JSONArray().also { array -> members.forEach { array.put(it.toJson()) } })

    companion object {
        /** 编辑既有团队时的初始草稿：`agent` 兼容字段由服务端按 `agents[0]` 强制重写，这里不重复塞。 */
        fun from(team: AiTeam): AiTeamDraft = AiTeamDraft(
            name = team.name,
            description = team.description,
            instructions = team.instructions,
            requirePlanApproval = team.requirePlanApproval,
            maxSteps = team.maxSteps,
            members = team.members,
        )
    }
}

/**
 * 成员提交体：`agents` 是首选在前（服务端不接受重复候选）、`avatar` / `role` 原样回写，
 * `id` 空串表示新成员，服务端会生成 `m_xxxxxxxx`。
 */
fun AiTeamMember.toJson(): JSONObject = JSONObject()
    .put("id", id)
    // 显式 null 才能解除旧绑定；省略字段会让服务端为旧客户端保留绑定。
    .put("employeeId", employeeId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    .put("name", name.trim())
    .put("duty", duty.trim())
    .put("isLeader", isLeader)
    .put("avatar", avatar)
    .put("agents", JSONArray().also { array -> agents.forEach { array.put(it.toJson()) } })
    .also { body -> role?.takeIf { it.isNotBlank() }?.let { body.put("role", it) } }

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
    val taskTitle: String = "",
    val taskIdentifier: String = "",
    val updatedAt: String = "",
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
                taskTitle = item.str("taskTitle") ?: "",
                taskIdentifier = item.str("taskIdentifier") ?: "",
                updatedAt = item.str("updatedAt") ?: "",
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
    val sessionId: String? = null,
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
                sessionId = item.str("sessionId")?.takeIf { it.isNotBlank() },
            )
        }

        fun parseList(array: JSONArray?): List<AiTeamStep> =
            array?.parseEach(::parse) ?: emptyList()
    }
}

data class AiTeamRunDetail(
    val run: AiTeamRun,
    val steps: List<AiTeamStep>,
    /**
     * 群聊回合：服务端只下发最近一段（`ai-team-runner.ts` detail()，`AI_TEAM_DETAIL_CHAT_TURNS`）。
     * 老服务端缺字段时为空列表，群聊页显示「还没有消息」而不是崩。
     */
    val chatTurns: List<ConversationTurn> = emptyList(),
    /** 运行中步骤所在会话的实时状态（sessionId → working / needs_permission …），缺字段即空表。 */
    val memberStates: Map<String, String> = emptyMap(),
    /** 展示身份按稳定 id 合并最新团队定义；运行快照仍在 run.team，不用于展示改名。 */
    val displayTeam: AiTeam? = null,
    /** 服务端从当前任务标题派生的群名，与团队定义/执行快照分开。 */
    val chatTitle: String? = null,
    val delivery: AiTeamDeliverySummary? = null,
) {
    val presentationTeam: AiTeam? get() = displayTeam ?: run.team
    val presentationChatTitle: String get() = chatTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "任务处理群"

    companion object {
        fun parse(item: JSONObject): AiTeamRunDetail? {
            val run = item.obj("run")?.let { AiTeamRun.parse(it) } ?: return null
            return AiTeamRunDetail(
                run = run,
                steps = AiTeamStep.parseList(item.arr("steps")),
                chatTurns = ConversationTurn.parseList(item.arr("chatTurns")) ?: emptyList(),
                memberStates = parseStateMap(item.obj("memberStates")),
                displayTeam = item.obj("displayTeam")?.let { AiTeam.parse(it) },
                chatTitle = item.str("chatTitle"),
                delivery = AiTeamDeliverySummary.parse(item.obj("delivery"))?.takeIf { it.runId == run.id },
            )
        }

        /** `{sessionId: state}` 的宽容解析：形状不对就当没有，不抛。 */
        private fun parseStateMap(source: JSONObject?): Map<String, String> {
            if (source == null) return emptyMap()
            val out = linkedMapOf<String, String>()
            try {
                for (key in source.keys()) {
                    val state = source.str(key)
                    if (key.isNotBlank() && !state.isNullOrBlank()) out[key] = state
                }
            } catch (_: Exception) {
                return out
            }
            return out
        }
    }
}

/**
 * 一个正在干活的运行中步骤（真源 `AiTeamLiveStep`，src/ai-team-types.ts）：
 * `text` 是服务端 `renderLiveStepText` 渲染好的尾部片段（至多 2000 字），
 * 超出部分只回报 `omittedChars`，客户端显示「已省略前面 N 字」。
 */
data class AiTeamLiveStep(
    val stepId: String,
    val seq: Int,
    val memberId: String,
    val memberName: String,
    val provider: String,
    /** 该步实际使用的候选模型；老服务端不带 → null，署名只显 provider（空串不进布局）。 */
    val model: String? = null,
    /** 该步实际使用的思考深度（`off` / `deep` / `qoder:low` 这类）；缺字段 → null。 */
    val thinkingEffort: String? = null,
    val sessionId: String,
    /** `AgentActivityState`：working / needs_input / needs_permission / done / failed。 */
    val state: String,
    val text: String,
    val omittedChars: Int,
    val updatedAt: String,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamLiveStep? {
            val stepId = item.str("stepId")?.takeIf { it.isNotBlank() } ?: return null
            return AiTeamLiveStep(
                stepId = stepId,
                seq = item.int("seq") ?: 0,
                memberId = item.str("memberId") ?: "",
                memberName = item.str("memberName") ?: "",
                provider = item.str("provider") ?: "",
                model = item.str("model"),
                thinkingEffort = item.str("thinkingEffort"),
                sessionId = item.str("sessionId") ?: "",
                state = item.str("state")?.takeIf { it.isNotBlank() } ?: "working",
                text = item.str("text") ?: "",
                omittedChars = item.int("omittedChars") ?: 0,
                updatedAt = item.str("updatedAt") ?: "",
            )
        }

        /** 单项形状不认识就跳过那一条，不拖垮整份列表。 */
        fun parseList(array: JSONArray?): List<AiTeamLiveStep> =
            array?.parseEachSafely(::parse) ?: emptyList()
    }
}

/** `GET /api/ai-team-runs/:id/live` 的回包（§4.9.1）：一次运行当前在输出的那些步骤。 */
data class AiTeamRunLive(
    val runId: String,
    val steps: List<AiTeamLiveStep>,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamRunLive? {
            val runId = item.str("runId")?.takeIf { it.isNotBlank() } ?: return null
            return AiTeamRunLive(runId = runId, steps = AiTeamLiveStep.parseList(item.arr("steps")))
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

/** 团队规模与步数上限（镜像 src/ai-team-types.ts 的常量，服务端校验同值）。 */
const val AI_TEAM_MIN_MEMBERS = 2
const val AI_TEAM_MAX_MEMBERS = 8
const val AI_TEAM_MAX_CANDIDATES = 4
const val AI_TEAM_DEFAULT_MAX_STEPS = 30
const val AI_TEAM_MIN_STEPS = 5
const val AI_TEAM_MAX_STEPS = 200
const val AI_TEAM_NAME_MAX = 60
const val AI_TEAM_DESCRIPTION_MAX = 500
const val AI_TEAM_INSTRUCTIONS_MAX = 4000
const val AI_TEAM_MEMBER_NAME_MAX = 40
const val AI_TEAM_MEMBER_DUTY_MAX = 2000

/**
 * 候选身份：五元组精确匹配（服务端 `agentKey`，§3.5）。同成员内重复会被 400 拒绝，
 * 编辑器据此在提交前就地标红，不靠服务器报错。
 */
fun boardTaskAgentKey(agent: BoardTaskAgent): String =
    "${agent.provider}|${agent.model}|${agent.thinkingEffort}|${agent.mode}|${agent.kind}"

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
