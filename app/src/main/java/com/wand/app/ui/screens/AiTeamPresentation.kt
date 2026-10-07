package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDirectRun
import com.wand.app.data.AiTeamMember
import com.wand.app.data.AiTeamDraft
import com.wand.app.data.AI_TEAM_MAX_MEMBERS
import com.wand.app.data.AI_TEAM_MAX_CANDIDATES
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.Workspace
import com.wand.app.data.isRequestOutcomeUnconfirmed

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

/** 已选项目失效时要求重新选择，不能在刷新后把开工悄悄转到另一个项目。 */
fun teamStartSelectedProjectId(workspaces: List<Workspace>, pickedId: String): String {
    val candidates = teamStartProjectCandidates(workspaces)
    return if (pickedId.isBlank()) candidates.firstOrNull()?.id.orEmpty()
    else candidates.firstOrNull { it.id == pickedId }?.id.orEmpty()
}

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

/** 请求已发出后仅明确的 HTTP 拒收可重试；网络/解析错误及服务端结果未知不能重复派工。 */
internal fun teamDirectFailureUnconfirmed(failure: Throwable): Boolean = isRequestOutcomeUnconfirmed(failure)

internal const val TEAM_DIRECT_UNCONFIRMED_MESSAGE =
    "开工结果尚未确认。请刷新上方协作动态或任务列表核对；此处已暂停再次开工，避免重复执行。"

/** 组织图排序：负责人在最前，其余保持团队定义顺序（只读页面用）。 */
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

/** 列表卡副标题：N 位成员 · 1 位负责人。负责人名字已经在标题行的徽标旁说过一次，这里不再拼第二遍。 */
fun aiTeamSummaryLine(team: AiTeam): String {
    val base = "${team.members.size} 位成员"
    return if (aiTeamLeader(team) == null) base else "$base · 1 位负责人"
}

/** 直发结果的落点任务卡：优先顶层 taskId，旧服务端缺失时回落 run 快照里的 taskId。 */
fun aiTeamDirectRunTaskId(direct: AiTeamDirectRun): String =
    direct.taskId.ifBlank { direct.detail.run.taskId }

/** 只邀请有效结构化员工；不读取/复制 prompt、知识或私聊。 */
fun teamEmployeeInviteError(
    employee: SiliconEmployee,
    members: List<AiTeamMember>,
    replacingIndex: Int? = null,
): String? = when {
    employee.id.isBlank() -> "员工身份不可用"
    employee.archived -> "员工已归档，请先在通讯录恢复"
    employee.agents.isEmpty() || employee.agents.size > AI_TEAM_MAX_CANDIDATES ||
        employee.agents.any { it.kind != "structured" || it.provider.isBlank() } ||
        teamCandidateListError(employee.agents).isNotEmpty() -> "员工没有有效的结构化候选"
    members.withIndex().any { (index, member) ->
        index != replacingIndex && member.employeeId == employee.id
    } -> "已在团队中"
    replacingIndex != null && replacingIndex !in members.indices -> "成员已变更，请重新选择"
    replacingIndex == null && members.size >= AI_TEAM_MAX_MEMBERS -> "团队成员已达上限"
    else -> null
}

/** 仅投影员工拥有的字段，职责/role/负责人/id 始终保留团队草稿。 */
fun teamEmployeeProjection(member: AiTeamMember, employees: List<SiliconEmployee>?): AiTeamMember {
    val employee = employees?.firstOrNull { it.id == member.employeeId } ?: return member
    return member.copy(name = employee.name, avatar = employee.avatar, agents = employee.agents)
}

fun teamEmployeeBindingStatus(member: AiTeamMember, employees: List<SiliconEmployee>?): String? {
    val id = member.employeeId ?: return null
    if (employees == null) return "员工资料尚未加载；绑定保留，可重试通讯录"
    val employee = employees.firstOrNull { it.id == id }
        ?: return "员工已删除或不可用；绑定保留，请替换员工或显式改为手工 CLI"
    return if (employee.archived) "员工已归档；绑定保留，请在通讯录恢复或替换"
    else if (teamEmployeeInviteError(employee, emptyList()) != null) "员工候选不可用；请在通讯录修复或替换"
    else "已绑定通讯录员工 · 名字、头像与候选由员工资料决定"
}

/** 邀请/替换针对当前草稿计算；失效选择原样返回，不重置任何未保存输入。 */
fun inviteTeamEmployee(
    draft: AiTeamDraft,
    employee: SiliconEmployee,
    replacingIndex: Int? = null,
): AiTeamDraft {
    if (teamEmployeeInviteError(employee, draft.members, replacingIndex) != null) return draft
    val base = replacingIndex?.let { draft.members[it] } ?: newTeamMember()
    val bound = base.copy(
        employeeId = employee.id,
        name = employee.name,
        avatar = employee.avatar,
        agents = employee.agents,
    )
    val members = if (replacingIndex == null) draft.members + bound
        else replaceTeamMember(draft.members, replacingIndex, bound)
    return draft.copy(members = members)
}

/** 唯一主动解绑入口；保留当前只读投影为手工配置的起点。 */
fun unbindTeamEmployee(member: AiTeamMember, employees: List<SiliconEmployee>?): AiTeamMember =
    teamEmployeeProjection(member, employees).copy(employeeId = null)

fun aiTeamEmployeeDraftErrors(draft: AiTeamDraft): List<String> {
    val ids = draft.members.mapNotNull { it.employeeId?.takeIf(String::isNotBlank) }
    return aiTeamDraftErrors(draft) + if (ids.distinct().size != ids.size) {
        listOf("同一员工不能重复加入团队。")
    } else emptyList()
}
