package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDirectRun
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.Workspace

/**
 * AI 团队页面的纯函数（可单测，不含 Compose 状态）。
 * 契约真源 docs/ai-teams-v2-design.md §4.2 / §6.2 与 Web 的 teamStartProjects
 * （src/web-ui/react/ai-teams/teams-page.tsx），两端过滤口径保持一致。
 */

/** 开工说明长度上限，对齐服务端 TEAM_DIRECT_NOTE_MAX。 */
const val TEAM_DIRECT_NOTE_MAX = 4000

/** 直接开工可选的项目：剔除 global 暂存区（服务端同口径 400 拒绝）与没有工作目录的项目。 */
fun teamStartProjectCandidates(workspaces: List<Workspace>): List<Workspace> =
    workspaces.filter { it.id != GLOBAL_WORKSPACE_ID && it.cwd.isNotBlank() }

/** 缺省项目 = 候选第一个（服务端按创建时间倒序返回，也就是「最近一个」）；没有则空串。 */
fun defaultTeamStartProjectId(workspaces: List<Workspace>): String =
    teamStartProjectCandidates(workspaces).firstOrNull()?.id ?: ""

/** null = 可以提交；否则为原位提示的文案（不做 Toast）。workspaceId 由调用方保证取自候选列表。 */
fun teamDirectSubmitError(projects: List<Workspace>, note: String): String? = when {
    teamStartProjectCandidates(projects).isEmpty() -> "AI 团队需要先选择一个已有项目"
    else -> teamDirectNoteError(note)
}

/** 与服务端 boundedText 同口径：trim 后判 1..4000（首尾空白不吃配额）。 */
fun teamDirectNoteError(note: String): String? = when {
    note.isBlank() -> "请填写开工说明。"
    note.trim().length > TEAM_DIRECT_NOTE_MAX -> "开工说明不能超过 $TEAM_DIRECT_NOTE_MAX 个字符。"
    else -> null
}

/** 组织图排序：负责人在最前，其余保持团队定义顺序。 */
fun aiTeamOrderedMembers(team: AiTeam): List<AiTeamMember> {
    val leaders = team.members.filter { it.isLeader }
    val rest = team.members.filterNot { it.isLeader }
    return leaders + rest
}

fun aiTeamLeader(team: AiTeam): AiTeamMember? = team.members.firstOrNull { it.isLeader }

fun aiTeamPreferredAgent(member: AiTeamMember): BoardTaskAgent? = member.agents.firstOrNull()

/** 候选行标签：首选 / 备用 1..N（上限 4，§11-Q5），越界也按序号兑。 */
fun aiTeamCandidateRoleLabel(index: Int): String =
    if (index == 0) "首选" else "备用 $index"

/** 列表卡副标题：N 位成员 · 负责人 xxx。 */
fun aiTeamSummaryLine(team: AiTeam): String {
    val leader = aiTeamLeader(team)
    val base = "${team.members.size} 位成员"
    return if (leader == null) base else "$base · 负责人 ${leader.name}"
}

/** 直发结果的落点任务卡：优先顶层 taskId，旧服务端缺失时回落 run 快照里的 taskId。 */
fun aiTeamDirectRunTaskId(direct: AiTeamDirectRun): String =
    direct.taskId.ifBlank { direct.detail.run.taskId }
