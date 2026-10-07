package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 无指派派工的服务端契约（镜像 src/team-dispatch.ts）。
 * 第一步 `plan` 只给建议名单，第二步 `start` 才建临时团队、建卡、起 run。
 */
data class TeamDispatchMember(
    val employeeId: String,
    val name: String,
    val duty: String,
    val tags: List<String>,
    val avatar: String,
    /** 本地决策给出的参与概率（0–1）；仅作参考，不是正确率。 */
    val probability: Double,
    val isLeader: Boolean,
) {
    companion object {
        fun parse(item: JSONObject?): TeamDispatchMember? {
            item ?: return null
            val employeeId = item.str("employeeId")?.takeIf { it.isNotBlank() } ?: return null
            return TeamDispatchMember(
                employeeId = employeeId,
                name = item.str("name")?.takeIf { it.isNotBlank() } ?: employeeId,
                duty = item.str("duty").orEmpty(),
                tags = item.arr("tags")?.stringItems(ignoreBlank = true).orEmpty(),
                avatar = item.str("avatar").orEmpty(),
                probability = item.dbl("probability") ?: 0.0,
                isLeader = item.bool("isLeader") == true,
            )
        }
    }
}

data class TeamDispatchPlan(
    val members: List<TeamDispatchMember>,
    /** 达到门槛但超出人数上限的备选。 */
    val bench: List<TeamDispatchMember>,
    val considered: Int,
    val omitted: Int,
    val threshold: Double,
    val maxMembers: Int,
    /** 人类可读说明：空名单、被截断、确认后才开工等。 */
    val note: String,
    val calls: Int,
    val model: String?,
) {
    companion object {
        fun parse(item: JSONObject?): TeamDispatchPlan? {
            item ?: return null
            val members = item.arr("members").toMemberList()
            val decision = item.obj("decision")
            return TeamDispatchPlan(
                members = members,
                bench = item.arr("bench").toMemberList(),
                considered = item.int("considered") ?: members.size,
                omitted = item.int("omitted") ?: 0,
                threshold = item.dbl("threshold") ?: 0.0,
                maxMembers = item.int("maxMembers") ?: AI_TEAM_MAX_MEMBERS,
                note = item.str("note").orEmpty(),
                calls = decision?.int("calls") ?: 0,
                model = decision?.str("model")?.takeIf { it.isNotBlank() },
            )
        }

        private fun JSONArray?.toMemberList(): List<TeamDispatchMember> =
            this?.parseEach(TeamDispatchMember::parse).orEmpty()
    }
}

/** 回传给 `start` 的名单项：负责人标记只会落在一个人身上。 */
data class TeamDispatchPick(val employeeId: String, val isLeader: Boolean)

/** 派工开工回包：run detail + 服务端建的临时团队与任务卡。 */
data class AiTeamDispatchRun(
    val detail: AiTeamRunDetail,
    val teamId: String,
    val taskId: String,
) {
    companion object {
        fun parse(item: JSONObject): AiTeamDispatchRun? {
            val detail = AiTeamRunDetail.parse(item) ?: return null
            return AiTeamDispatchRun(
                detail = detail,
                teamId = item.str("teamId")?.takeIf { it.isNotBlank() } ?: "",
                taskId = item.str("taskId")?.takeIf { it.isNotBlank() } ?: "",
            )
        }
    }
}

/** 与 Web 同口径的派工规则常量。 */
const val DISPATCH_CANDIDATES_PER_CALL = 8

/**
 * 面板里的名单选择：成员集合 + 负责人（员工 id）。
 * 纯数据 + 纯函数，行为与 Web `team-dispatch.tsx` 的同一组规则保持一致，便于单测。
 */
data class TeamDispatchSelection(
    val members: List<TeamDispatchMember> = emptyList(),
    val leaderId: String = "",
) {
    companion object {
        /** 初始选择 = 服务端建议的全员（负责人沿用服务端标记，缺省取第一位）。 */
        fun initial(plan: TeamDispatchPlan): TeamDispatchSelection {
            val leader = plan.members.firstOrNull { it.isLeader } ?: plan.members.firstOrNull()
            return TeamDispatchSelection(plan.members, leader?.employeeId.orEmpty())
        }
    }
}

/** 勾选/取消一名员工；取消负责人时负责人顺延到剩下第一位，超员不动原状态。 */
fun toggleDispatchMember(
    selection: TeamDispatchSelection,
    member: TeamDispatchMember,
): TeamDispatchSelection {
    val exists = selection.members.any { it.employeeId == member.employeeId }
    val members = if (exists) {
        selection.members.filterNot { it.employeeId == member.employeeId }
    } else {
        (selection.members + member).sortedWith(
            compareByDescending<TeamDispatchMember> { it.probability }.thenBy { it.name },
        )
    }
    if (members.size > AI_TEAM_MAX_MEMBERS) return selection
    val leaderId = selection.leaderId.takeIf { id -> members.any { it.employeeId == id } }
        ?: members.firstOrNull()?.employeeId.orEmpty()
    return TeamDispatchSelection(members, leaderId)
}

/** 指定负责人；不在名单里的 id 不接受（UI 也只给名单内的行提供入口）。 */
fun setDispatchLeader(selection: TeamDispatchSelection, employeeId: String): TeamDispatchSelection =
    if (selection.members.any { it.employeeId == employeeId }) selection.copy(leaderId = employeeId) else selection

fun dispatchSelectionPicks(selection: TeamDispatchSelection): List<TeamDispatchPick> =
    selection.members.map { member -> TeamDispatchPick(member.employeeId, member.employeeId == selection.leaderId) }

/**
 * 开工按钮为什么不能点：返回人类可读原因，空串表示可以开工。
 * UI 只用它拼文案与 enabled，规则本身仍然由服务端复验。
 */
fun dispatchStartBlockedReason(
    selection: TeamDispatchSelection,
    workspaceId: String,
    note: String,
    busy: Boolean,
): String = when {
    busy -> "正在处理…"
    note.isBlank() -> "先写清这次要做什么。"
    workspaceId.isBlank() -> "先选一个项目。"
    selection.members.size < AI_TEAM_MIN_MEMBERS -> "团队开工至少需要 $AI_TEAM_MIN_MEMBERS 名员工（含负责人）。"
    else -> ""
}

/** 概率只作参考：展示成整数百分比，不是正确率。 */
fun dispatchProbabilityLabel(probability: Double): String {
    if (probability.isNaN() || probability.isInfinite()) return ""
    val clamped = probability.coerceIn(0.0, 1.0)
    return "${Math.round(clamped * 100)}%"
}

/** 备选行给不给“加入”入口：名单已满就不再提供。 */
fun canAddDispatchMember(selection: TeamDispatchSelection): Boolean =
    selection.members.size < AI_TEAM_MAX_MEMBERS
