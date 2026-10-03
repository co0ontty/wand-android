package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDirectRun
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.WandApiException
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import kotlinx.coroutines.CancellationException

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
    val normalized = normalizeContactPath(cwd)
    val workspaceId = workspaces.firstOrNull {
        it.id != GLOBAL_WORKSPACE_ID && normalizeContactPath(it.cwd) == normalized
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

private fun normalizeContactPath(value: String): String =
    value.trim().replace(Regex("/+$"), "").ifEmpty { "/" }

/** 通讯录点团队：一次开工。未知回执不能靠重复点击盲目重建。 */
internal class RecentTeamConversation(
    val teamId: String,
    val workspaceId: String,
) {
    var busy by mutableStateOf(false)
        private set
    var runId by mutableStateOf<String?>(null)
        private set
    var creationUnconfirmed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun create(api: TaskBoardPort, team: AiTeam?): AiTeamDirectRun? {
        if (busy || runId != null || creationUnconfirmed) return null
        if (team?.id != teamId) {
            error = "团队已不存在，请刷新后重试。"
            return null
        }
        if (workspaceId.isBlank()) {
            error = "AI 团队需要先选择一个已有项目"
            return null
        }
        busy = true
        error = null
        try {
            val created = api.startDirectTeamRun(teamId, workspaceId, CONTACT_TEAM_NEW_CHAT_NOTE)
            val createdRunId = created.detail.run.id
            check(createdRunId.isNotBlank()) { "未收到有效的群聊回执" }
            runId = createdRunId
            return created
        } catch (failure: Exception) {
            val status = (failure as? WandApiException)?.status
            creationUnconfirmed = status == null || status >= 500 || status == 408 || status == 409
            error = if (creationUnconfirmed) {
                "创建结果未确认，请刷新列表并打开新群聊核对，勿重复新建。"
            } else {
                failure.message ?: "创建群聊失败，请稍后重试。"
            }
            if (failure is CancellationException) throw failure
            return null
        } finally {
            busy = false
        }
    }
}
