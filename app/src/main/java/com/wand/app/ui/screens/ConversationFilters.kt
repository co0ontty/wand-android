package com.wand.app.ui.screens

import com.wand.app.ui.withLiveTitle

import com.wand.app.data.activityStatus
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

internal enum class ConversationRuntime(val label: String, val hint: String?) {
    Confirmation("需要我确认", "等你授权或补充回答的会话"),
    Running("运行中", "含刚完成但未查看的会话"),
    Queued("排队中", "等待开始执行的会话"),
    Idle("空闲", "本轮已跑完，等你下一次输入"),
    Finished("已结束", "进程已退出或被停止的会话"),
    Failed("运行失败", "执行失败的会话"),
}

internal fun conversationRuntime(item: HomeRecentConversation): ConversationRuntime? {
    if (com.wand.app.ui.SessionTitleStore.permissionBlockedOf(item.session.id) == true) return ConversationRuntime.Confirmation
    val status = item.session.withLiveTitle().activityStatus()
    if (status == "failed") return ConversationRuntime.Failed
    // 归档行不参与「当前状态」筛选，只有运行失败与已结束能把它找回来（与 Web/macOS/iOS 同一口径）。
    // 能不能借「已结束」看到归档行只由归档档决定：conversationSessionMatches 里档位是 AND，
    // 默认「未归档」把它挡住，选到「全部（含已归档）」或「已归档」才放行。
    if (conversationSessionArchived(item)) return if (status in listOf("exited", "stopped")) ConversationRuntime.Finished else null
    return when (status) {
        "permission", "permission-blocked", "waiting-input", "waiting_input" -> ConversationRuntime.Confirmation
        "queued" -> ConversationRuntime.Queued
        "running", "thinking", "reconnecting", "just-completed" -> ConversationRuntime.Running
        "exited", "stopped" -> ConversationRuntime.Finished
        "idle" -> ConversationRuntime.Idle
        else -> null
    }
}

internal data class ConversationListFilters(
    val runtimes: Set<ConversationRuntime> = emptySet(),
    val workspaceIds: Set<String> = emptySet(),
    val stage: ConversationTaskStage = ConversationTaskStage.All,
    val workspaceId: String? = null,
    val tier: ConversationListTier = ConversationListTier.Active,
    val activeOnly: Boolean = false,
    /** Server employee IDs; "none" is the explicit unassigned value. */
    val employeeIds: Set<String> = emptySet(),
) {
    /** 默认档「未归档」不算加了条件；显式选「全部（含已归档）」或「已归档」才是筛选。 */
    val count: Int get() = listOf(runtimes.isNotEmpty(), workspaceIds.isNotEmpty(), employeeIds.isNotEmpty(), stage != ConversationTaskStage.All, workspaceId != null,
        tier != ConversationListTier.Active, activeOnly).count { it }
    val hasTaskConditions: Boolean get() = runtimes.isNotEmpty() || workspaceIds.isNotEmpty() || stage != ConversationTaskStage.All || workspaceId != null || activeOnly
}

internal fun conversationWorkspaceId(group: TaskDirectoryGroup): String =
    if (!group.userAdded || group.synthetic || group.isGlobal) UnassignedConversationWorkspace else group.workspaceId

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
    (filters.runtimes.isEmpty() || conversationRuntime(item) in filters.runtimes) &&
        (filters.workspaceIds.isEmpty() || (associationsAvailable && conversationWorkspaceId(item.group) in filters.workspaceIds)) &&
        (filters.employeeIds.isEmpty() || (item.session.employeeId ?: "none") in filters.employeeIds) &&
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
    // A group task has no single employee; employee filtering only keeps matching session rows.
    filters.employeeIds.isEmpty() &&
        (filters.workspaceIds.isEmpty() || conversationGroupTaskWorkspaceId(task) in filters.workspaceIds) &&
        (filters.runtimes.isEmpty() || filters.runtimes.any { runtime ->
            when (runtime) {
                ConversationRuntime.Confirmation -> task.runs.any { it.status in listOf("permission", "permission-blocked", "waiting-input", "waiting_input") }
                ConversationRuntime.Running -> task.task.executionStatus == "running" || task.runs.any { it.status in listOf("running", "thinking", "just-completed") }
                ConversationRuntime.Queued -> task.task.executionStatus == "queued" || task.runs.any { it.status == "queued" }
                ConversationRuntime.Idle -> task.task.executionStatus == null && task.runs.any { it.status == "idle" }
                ConversationRuntime.Finished -> task.runs.any { it.status in listOf("exited", "stopped") }
                ConversationRuntime.Failed -> task.task.executionStatus == "failed" || task.runs.any { it.status == "failed" }
            }
        }) &&
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
    (listOf(UnassignedConversationWorkspace to "未分配") + groups.filter { it.userAdded }.map {
        conversationWorkspaceId(it) to conversationWorkspaceLabel(it)
    }).distinctBy { it.first }

/** Employees come from the sessions on screen; "all" is rendered by the capsule itself. */
internal fun conversationEmployeeOptions(entries: List<HomeRecentConversation>): List<Pair<String, String>> {
    val people = entries.mapNotNull { item ->
        item.session.employeeId?.takeIf { it != "none" }?.let { id -> id to (item.session.employeeName ?: "员工") }
    }.distinctBy { it.first }.sortedBy { it.second }
    return listOf("none" to "未分配员工") + people
}
