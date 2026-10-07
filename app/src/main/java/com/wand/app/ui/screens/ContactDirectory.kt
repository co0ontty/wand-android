package com.wand.app.ui.screens

import com.wand.app.data.normalizeWorkspacePath
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDirectRun
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding

/**
 * 通讯录落地页只展示在职员工名字。归档的人走头像进资料后处理，不占目录。
 */
internal fun contactDirectoryEmployees(
    employees: List<SiliconEmployee>,
    query: String = "",
): List<SiliconEmployee> = employees.filter { employee ->
    !employee.archived && contactMatchesQuery(
        query,
        listOf(employee.name, employee.duty) + employee.displayTags,
    )
}

/** 通讯录落地页只展示团队名称，不进管理卡。 */
internal fun contactDirectoryTeams(teams: List<AiTeam>, query: String = ""): List<AiTeam> =
    teams.filter { team ->
        contactMatchesQuery(query, listOf(team.name, team.description) + team.members.map { it.name })
    }

private fun contactMatchesQuery(query: String, fields: List<String>): Boolean =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { term ->
        fields.any { it.contains(term, ignoreCase = true) }
    }

/**
 * 通讯录按创建时间先后排列（早的在上），不按拼音/字母分组。
 * 旧服务不返回 [SiliconEmployee.createdAt] / [AiTeam.createdAt] 时排到最后，不挤掉有时间的项。
 */
internal fun contactOrderedEmployees(employees: List<SiliconEmployee>): List<SiliconEmployee> =
    employees.sortedWith(compareBy({ contactTimeKey(it.createdAt) }, { it.id }))

internal fun contactOrderedTeams(teams: List<AiTeam>): List<AiTeam> =
    teams.sortedWith(compareBy({ contactTimeKey(it.createdAt) }, { it.id }))

private fun contactTimeKey(createdAt: String): String =
    createdAt.trim().ifEmpty { "\uFFFF" }

/** 团队圆标用现有语义色轮换，不另建色板。 */
internal fun contactAccentIndex(id: String): Int {
    var hash = 0
    for (ch in id) hash = 31 * hash + ch.code
    val mod = hash % 4
    return if (mod < 0) mod + 4 else mod
}

internal sealed class ContactSlot {
    data object CreatePanel : ContactSlot()
    data object Error : ContactSlot()
    data object Loading : ContactSlot()
    data object NoResults : ContactSlot()
    data object ResultCount : ContactSlot()
    data object TeamHeader : ContactSlot()
    data object TeamEmpty : ContactSlot()
    data class Team(val id: String) : ContactSlot()
    data object GroupGap : ContactSlot()
    data object EmployeeHeader : ContactSlot()
    data object EmployeeEmpty : ContactSlot()
    data class Employee(val id: String) : ContactSlot()
}

/**
 * 通讯录列表的唯一顺序：创建面板、团队、员工，两段各自按时间先后。
 * 搜索时只留命中的那一段（另一段为空就不画标题）。
 */
internal fun contactDirectoryLayout(
    employeeIds: List<String>,
    teamIds: List<String>,
    queryBlank: Boolean,
    hasError: Boolean,
    loadingEmpty: Boolean,
): List<ContactSlot> {
    val slots = mutableListOf<ContactSlot>()
    fun add(slot: ContactSlot) {
        slots += slot
    }
    add(ContactSlot.CreatePanel)
    if (hasError) add(ContactSlot.Error)
    when {
        loadingEmpty -> add(ContactSlot.Loading)
        !queryBlank && employeeIds.isEmpty() && teamIds.isEmpty() -> add(ContactSlot.NoResults)
        else -> {
            if (!queryBlank) add(ContactSlot.ResultCount)
            val showTeams = teamIds.isNotEmpty() || queryBlank
            if (showTeams) {
                add(ContactSlot.TeamHeader)
                if (teamIds.isEmpty() && queryBlank && !hasError) add(ContactSlot.TeamEmpty)
                teamIds.forEach { add(ContactSlot.Team(it)) }
            }
            if (showTeams && (employeeIds.isNotEmpty() || queryBlank)) add(ContactSlot.GroupGap)
            if (queryBlank) add(ContactSlot.EmployeeHeader)
            if (employeeIds.isEmpty() && queryBlank && !hasError) add(ContactSlot.EmployeeEmpty)
            employeeIds.forEach { add(ContactSlot.Employee(it)) }
        }
    }
    return slots
}

internal fun contactAssignableEmployee(
    employeeId: String,
    employees: List<SiliconEmployee>,
): SiliconEmployee? = employees.firstOrNull {
    it.id == employeeId && !it.archived && it.agents.isNotEmpty()
}

internal fun contactAssignableTeam(teamId: String, teams: List<AiTeam>): AiTeam? =
    teams.firstOrNull { it.id == teamId }

/**
 * 通讯录没有「最近会话」那张卡上的项目上下文：优先默认目录，没有则用最近一个非 global 项目。
 * 不把新对话挂到旧任务或旧 worktree。
 */
internal fun contactConversationBinding(
    defaultCwd: String?,
    workspaces: List<Workspace>,
): WorkspaceBinding? {
    val cwd = defaultCwd?.trim()?.takeIf { it.isNotEmpty() }
        ?: teamStartProjectCandidates(workspaces).firstOrNull()?.cwd?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val normalized = normalizeWorkspacePath(cwd)
    val workspaceId = workspaces.firstOrNull {
        it.id != GLOBAL_WORKSPACE_ID && normalizeWorkspacePath(it.cwd) == normalized
    }?.id
    return WorkspaceBinding(workspaceId = workspaceId, cwd = cwd)
}

internal fun contactTeamStartWorkspaceId(workspaces: List<Workspace>): String =
    defaultTeamStartProjectId(workspaces)

/**
 * 团队开工说明不能空。通讯录点团队要和员工「+」一样一键开新对话，
 * 没有空白群聊入口，只能带这一句占位说明起 run。
 */
internal const val CONTACT_TEAM_NEW_CHAT_NOTE = "新对话"

/** 通讯录点团队：一次开工。未知回执不能靠重复点击盲目重建。 */
internal class RecentTeamConversation(
    val teamId: String,
    val workspaceId: String,
) : ConversationCreation<AiTeamDirectRun>("群聊") {
    val runId: String? get() = created?.detail?.run?.id

    suspend fun create(api: TaskBoardPort, team: AiTeam?): AiTeamDirectRun? {
        if (!canCreate) return null
        if (team?.id != teamId) {
            error = "团队已不存在，请刷新后重试。"
            return null
        }
        if (workspaceId.isBlank()) {
            error = "AI 团队需要先选择一个已有项目"
            return null
        }
        return createOnce {
            api.startDirectTeamRun(teamId, workspaceId, CONTACT_TEAM_NEW_CHAT_NOTE).also { created ->
                check(created.detail.run.id.isNotBlank()) { "未收到有效的群聊回执" }
            }
        }
    }
}
