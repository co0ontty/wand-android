package com.wand.app.ui.screens

import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.withLiveTitle

/** Web sidebarSearchMatches 口径：所有词可跨标题、身份、任务和目录命中。 */
internal fun homeSearchMatches(query: String, fields: List<String?>): Boolean {
    val text = fields.filterNotNull().joinToString(" ").lowercase()
    return query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .all { text.contains(it.lowercase()) }
}

internal fun homeSessionSearchFields(
    session: WorkspaceSessionSummary,
    employees: List<SiliconEmployee>,
): List<String?> = listOf(
    session.withLiveTitle().title, session.cwd, workspaceProviderLabel(session.provider),
    session.employeeName, employees.firstOrNull { it.id == session.employeeId }?.name,
    session.teamChat?.teamName, session.teamStep?.title, session.teamStep?.memberName,
    session.teamStep?.teamName,
)

/** 仅用于显示；保留原始 task/目录快照作为批量操作范围。 */
internal fun searchHomeGroups(
    groups: List<TaskDirectoryGroup>, query: String, employees: List<SiliconEmployee>,
): List<TaskDirectoryGroup> {
    if (query.isBlank()) return groups
    return groups.mapNotNull { group ->
        val context = listOf(group.workspaceName, group.workspaceCwd)
        val directoryMatches = homeSearchMatches(query, context)
        val tasks = group.tasks.mapNotNull { task ->
            val taskContext = context + listOf(task.name, task.cwd)
            val wholeTask = homeSearchMatches(query, taskContext)
            val sessions = task.sessions.filter {
                wholeTask || homeSearchMatches(query, taskContext + homeSessionSearchFields(it, employees))
            }
            if (wholeTask || sessions.isNotEmpty()) task.copy(sessions = sessions) else null
        }
        val loose = group.standaloneSessions.filter {
            directoryMatches || homeSearchMatches(query, context + homeSessionSearchFields(it, employees))
        }
        if (directoryMatches || tasks.isNotEmpty() || loose.isNotEmpty())
            group.copy(tasks = tasks, standaloneSessions = loose) else null
    }
}

/** 先分组再筛内容：搜索/活动变化不应改员工头的名字、头像与顺序。 */
internal fun searchHomeRecentGroups(
    groups: List<HomeGroup>, query: String, employees: List<SiliconEmployee>, attentionOnly: Boolean,
): List<HomeGroup> = groups.mapNotNull { group ->
    val conversations = group.conversations.filter { item ->
        (!attentionOnly || sessionNeedsYou(item.session)) && homeSearchMatches(query,
            homeSessionSearchFields(item.session, employees) + listOf(group.title, item.task?.name,
                item.task?.cwd, item.group.workspaceName, item.group.workspaceCwd))
    }
    if (conversations.isNotEmpty() || (query.isBlank() && !attentionOnly))
        group.copy(conversations = conversations) else null
}
