package com.wand.app.ui.screens

import com.wand.app.data.ConversationInstance
import com.wand.app.data.ConversationTask
import com.wand.app.data.TaskDirectoryGroup

internal enum class ConversationTaskStage(val value: String, val label: String) {
    All("all", "全部状态"), Backlog("backlog", "待立项"), Todo("todo", "等待认领"),
    Doing("doing", "执行中"), Review("review", "待验收"), Ready("ready", "待归档"),
    Archived("archived", "已归档"), None("none", "未关联任务"), Unknown("unknown", "任务状态未知");

    companion object {
        fun fromBoard(raw: String?): ConversationTaskStage =
            if (raw == "done") Review else entries.firstOrNull {
                it.value == raw && it !in listOf(All, None, Unknown)
            } ?: Unknown
    }
}

internal const val UnassignedConversationWorkspace = "__unassigned__"

internal data class ConversationListFilters(
    val stage: ConversationTaskStage = ConversationTaskStage.All,
    val workspaceId: String? = null,
    val tier: ConversationListTier = ConversationListTier.All,
    val activeOnly: Boolean = false,
) {
    val count: Int get() = listOf(stage != ConversationTaskStage.All, workspaceId != null,
        tier != ConversationListTier.All, activeOnly).count { it }
    val hasTaskConditions: Boolean get() = stage != ConversationTaskStage.All || workspaceId != null || activeOnly
}

internal fun conversationWorkspaceId(group: TaskDirectoryGroup): String =
    if (group.synthetic || group.isGlobal) UnassignedConversationWorkspace else group.workspaceId

internal fun conversationWorkspaceLabel(group: TaskDirectoryGroup): String =
    if (conversationWorkspaceId(group) == UnassignedConversationWorkspace) "未分配"
    else group.workspaceName.ifBlank { group.workspaceId }

internal fun conversationSessionStage(item: HomeRecentConversation, associationsAvailable: Boolean = true): ConversationTaskStage = when {
    !associationsAvailable -> ConversationTaskStage.Unknown
    item.task != null -> ConversationTaskStage.fromBoard(item.task.boardTaskStatus)
    !item.session.workspaceTaskId.isNullOrBlank() -> ConversationTaskStage.Unknown
    else -> ConversationTaskStage.None
}

internal fun conversationFilterSessions(groups: List<TaskDirectoryGroup>): List<HomeRecentConversation> =
    groups.flatMap { group ->
        group.tasks.flatMap { task -> task.sessions.map { HomeRecentConversation(group, task, it) } } +
            group.standaloneSessions.map { HomeRecentConversation(group, null, it) }
    }.distinctBy { it.session.id }.sortedWith(homeRecentConversationOrder)

internal fun conversationSessionArchived(item: HomeRecentConversation): Boolean =
    item.session.archived == true || item.task?.archived == true

internal fun conversationSessionMatches(
    item: HomeRecentConversation,
    filters: ConversationListFilters,
    associationsAvailable: Boolean = true,
): Boolean =
    (filters.stage == ConversationTaskStage.All || conversationSessionStage(item, associationsAvailable) == filters.stage) &&
        (filters.workspaceId == null || (associationsAvailable && conversationWorkspaceId(item.group) == filters.workspaceId)) &&
        (when (filters.tier) {
            ConversationListTier.All -> true
            ConversationListTier.Active -> !conversationSessionArchived(item)
            ConversationListTier.Archived -> conversationSessionArchived(item)
        }) && (!filters.activeOnly || (!conversationSessionArchived(item) && sessionKeptByRunningFold(item.session)))

internal fun conversationGroupTaskWorkspaceId(task: ConversationTask): String? = when {
    !task.workspaceKnown -> null
    task.task.workspaceId == null || task.task.workspaceId == "wand-global" -> UnassignedConversationWorkspace
    else -> task.task.workspaceId
}

internal fun conversationGroupTaskMatches(task: ConversationTask, filters: ConversationListFilters): Boolean =
    (filters.stage == ConversationTaskStage.All || ConversationTaskStage.fromBoard(task.boardTaskStatus) == filters.stage) &&
        (filters.workspaceId == null || conversationGroupTaskWorkspaceId(task) == filters.workspaceId) &&
        (!filters.activeOnly || task.runs.any { conversationRunActive(it.status) } ||
            task.task.executionStatus in listOf("queued", "running"))

internal data class ConversationFilteredRow(
    val item: ConversationInstance,
    val sessions: List<HomeRecentConversation>,
    val tasks: List<ConversationTask>,
)

internal data class ConversationListProjection(
    val rows: List<ConversationFilteredRow>,
    val unbound: List<HomeRecentConversation>,
)

/** Every child satisfies the whole intersection before its parent is retained or counted. */
internal fun projectConversationList(
    items: List<ConversationInstance>,
    groups: List<TaskDirectoryGroup>,
    filters: ConversationListFilters,
    query: String,
    associationsAvailable: Boolean = true,
): ConversationListProjection {
    val term = query.trim()
    val sessions = conversationFilterSessions(groups).filter { it.session.teamChat == null }
    fun matchesText(text: String): Boolean = term.isEmpty() || text.contains(term, ignoreCase = true)
    fun sessionText(item: HomeRecentConversation): Boolean =
        matchesText(listOfNotNull(item.session.title, item.task?.name, item.session.employeeName).joinToString(" "))
    val byEmployee = sessions.filter { it.session.employeeId != null }.groupBy { it.session.employeeId }
    val rows = items.mapNotNull { item ->
        val parentInScope = filterConversationList(listOf(item), filters.tier, "").isNotEmpty()
        if (item.kind != "dm" && !parentInScope) return@mapNotNull null
        val parentText = matchesText(item.title + " " + item.preview)
        val children = byEmployee[item.peerEmployeeId].orEmpty().filter {
            conversationSessionMatches(it, filters, associationsAvailable) && (parentText || sessionText(it))
        }
        val tasks = item.tasks.distinctBy { it.task.id }.filter {
            conversationGroupTaskMatches(it, filters) && (parentText || matchesText(it.task.title))
        }
        val hasChildren = if (item.kind == "dm") children.isNotEmpty() else tasks.isNotEmpty()
        if (item.kind == "dm" && !hasChildren && (!parentInScope ||
                (filters.tier != ConversationListTier.All && byEmployee[item.peerEmployeeId].orEmpty().isNotEmpty()))) return@mapNotNull null
        if ((filters.hasTaskConditions && !hasChildren) || (!parentText && !hasChildren)) null
        else ConversationFilteredRow(item, children, tasks)
    }
    return ConversationListProjection(rows, sessions.filter {
        it.session.employeeId == null && conversationSessionMatches(it, filters, associationsAvailable) && sessionText(it)
    })
}

internal fun conversationWorkspaceOptions(groups: List<TaskDirectoryGroup>): List<Pair<String, String>> =
    (listOf(UnassignedConversationWorkspace to "未分配") + groups.map {
        conversationWorkspaceId(it) to conversationWorkspaceLabel(it)
    }).distinctBy { it.first }
