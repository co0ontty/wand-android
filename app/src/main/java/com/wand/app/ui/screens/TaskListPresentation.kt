package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.workspaceProviderLabel

internal const val UNNAMED_TASK_NAME = "未命名任务"

/** 首页模式：会话树（默认）或任务管理看板。点标题栏即可切换并持久化。 */
enum class HomeListMode(val storageValue: String) {
    Sessions("sessions"),
    Tasks("board");

    val label: String
        get() = if (this == Tasks) "任务模式" else "会话模式"

    val next: HomeListMode
        get() = if (this == Tasks) Sessions else Tasks

    companion object {
        fun fromStorage(value: String?): HomeListMode = when (value) {
            "board" -> Tasks
            else -> Sessions
        }
    }
}

internal fun isUnnamedTaskName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isEmpty() || trimmed == UNNAMED_TASK_NAME
}

/** 未命名任务不单独占一行，会话并入该目录的未分组终端。 */
internal fun flattenUnnamedTasksIntoStandalone(group: TaskDirectoryGroup): TaskDirectoryGroup {
    val named = ArrayList<WorkspaceTaskSummary>(group.tasks.size)
    val extraStandalone = ArrayList<WorkspaceSessionSummary>()
    for (task in group.tasks) {
        if (isUnnamedTaskName(task.name)) extraStandalone += task.sessions
        else named += task
    }
    if (named.size == group.tasks.size) return group
    return group.copy(
        tasks = named,
        standaloneSessions = group.standaloneSessions + extraStandalone,
    )
}


/** 目录位置按创建时间固定：新建在前，打开/运行不会改位置。 */
internal fun directoryTreeGroups(groups: List<TaskDirectoryGroup>): List<TaskDirectoryGroup> =
    groups
        .map(::flattenUnnamedTasksIntoStandalone)
        .filter { it.tasks.isNotEmpty() || it.standaloneSessions.isNotEmpty() }
        .mapIndexed { index, group -> IndexedDirectoryGroup(group = group, index = index) }
        .sortedWith { left, right ->
            val created = compareNullableTimestamp(
                directoryCreatedAt(right.group),
                directoryCreatedAt(left.group),
            )
            if (created != 0) created else left.index - right.index
        }
        .map { it.group }

/** 目录内任务按创建时间固定：新建在前，打开不会改位置。 */
internal fun orderedTaskSummaries(tasks: List<WorkspaceTaskSummary>): List<WorkspaceTaskSummary> =
    tasks
        .mapIndexed { index, task -> IndexedTaskSummary(task = task, index = index) }
        .sortedWith { left, right ->
            val created = compareNullableTimestamp(right.task.task.createdAt, left.task.task.createdAt)
            if (created != 0) created else left.index - right.index
        }
        .map { it.task }

internal fun directoryCreatedAt(group: TaskDirectoryGroup): String? {
    group.createdAt?.takeIf { it.isNotBlank() }?.let { return it }
    val times = group.tasks.mapNotNull { it.task.createdAt?.takeIf(String::isNotBlank) } +
        group.standaloneSessions.mapNotNull { it.startedAt?.takeIf(String::isNotBlank) }
    return times.minOrNull()
}

internal fun groupHasLiveActivity(group: TaskDirectoryGroup): Boolean =
    group.standaloneSessions.any(::sessionHasLiveActivity) ||
        group.tasks.any { task -> task.sessions.any(::sessionHasLiveActivity) }

private fun sessionHasLiveActivity(session: WorkspaceSessionSummary): Boolean =
    session.inFlight == true || session.status in setOf(
        "running",
        "thinking",
        "permission",
        "waiting-input",
        "reconnecting",
    )

private fun compareNullableTimestamp(left: String?, right: String?): Int {
    val leftValue = left?.takeIf { it.isNotBlank() }
    val rightValue = right?.takeIf { it.isNotBlank() }
    return when {
        leftValue == null && rightValue == null -> 0
        leftValue == null -> 1
        rightValue == null -> -1
        else -> leftValue.compareTo(rightValue)
    }
}

private data class IndexedDirectoryGroup(
    val group: TaskDirectoryGroup,
    val index: Int,
)

private data class IndexedTaskSummary(
    val task: WorkspaceTaskSummary,
    val index: Int,
)

/** 只有多个目录时才显示展开控件；单个目录始终展开，避免空箭头占位。 */
internal fun showsDirectoryDisclosure(directoryCount: Int): Boolean = directoryCount > 1

/** 任务下没有终端时不显示箭头；空状态直接展示，无需先展开。 */
internal fun showsTaskSessionDisclosure(sessionCount: Int): Boolean = sessionCount > 0

/** 目录默认展开。单个目录不可收起。 */
internal fun isDirectoryExpanded(userCollapsed: Boolean, directoryCount: Int): Boolean =
    !showsDirectoryDisclosure(directoryCount) || !userCollapsed

/** 终端默认展开。无终端时始终展示空提示。 */
internal fun isTaskSessionsExpanded(userCollapsed: Boolean, sessionCount: Int): Boolean =
    !showsTaskSessionDisclosure(sessionCount) || !userCollapsed

/**
 * 侧栏任务行只在「当前详情就是这个任务」时高亮。
 * 已经选中该任务下某个可见会话时，只高亮会话行，避免父子两层各画一个选中矩形。
 * 选中会话不在当前可见列表里时，任务行继续作为位置提示。
 */
internal fun isTaskRowSelected(
    taskId: String,
    visibleSessionIds: Collection<String>,
    selectedTaskId: String?,
    selectedSessionId: String?,
): Boolean {
    if (taskId != selectedTaskId) return false
    val sessionId = selectedSessionId?.takeIf { it.isNotBlank() } ?: return true
    return sessionId !in visibleSessionIds
}

/** 列表里的终端名：不要把目录名/路径叶子再当标题，避免三层都叫 wand。 */
internal fun listSessionLabel(
    session: WorkspaceSessionSummary,
    index: Int,
    parentNames: Collection<String> = emptyList(),
): String {
    val title = session.title?.trim().orEmpty()
    val leaf = session.cwd
        ?.replace('\\', '/')
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        .orEmpty()
    val repeatsParent = title.isNotEmpty() && (
        parentNames.any { it.equals(title, ignoreCase = true) } ||
            (leaf.isNotEmpty() && title.equals(leaf, ignoreCase = true))
    )
    if (title.isNotEmpty() && !repeatsParent) return title
    return "${workspaceProviderLabel(session.provider)} ${index + 1}"
}

internal const val COLLAPSED_RAIL_LIMIT = 8

internal data class SidebarManageSelection(
    val taskIds: Set<String> = emptySet(),
    val sessionIds: Set<String> = emptySet(),
) {
    val count: Int get() = taskIds.size + sessionIds.size
    val isEmpty: Boolean get() = count == 0
}

internal data class CollapsedRailTask(
    val group: TaskDirectoryGroup,
    val task: WorkspaceTaskSummary,
    val activity: String?,
)

internal data class CollapsedRailModel(
    val items: List<CollapsedRailTask>,
    val overflow: Int,
)

internal fun taskRailActivity(task: WorkspaceTaskSummary): String? {
    if (task.sessions.any { session ->
            session.status in setOf(
                "failed",
                "waiting-input",
                "waiting_input",
                "permission-blocked",
                "reconnecting",
            )
        }
    ) {
        return "attention"
    }
    if (task.sessions.any { session ->
            session.inFlight == true || session.ptyBusy == true || session.status == "thinking"
        }
    ) {
        return "running"
    }
    return null
}

internal fun collectManagedIds(groups: List<TaskDirectoryGroup>): SidebarManageSelection {
    val taskIds = linkedSetOf<String>()
    val sessionIds = linkedSetOf<String>()
    groups.forEach { group ->
        group.tasks.forEach { task ->
            taskIds += task.id
            task.sessions.forEach { sessionIds += it.id }
        }
        group.standaloneSessions.forEach { sessionIds += it.id }
    }
    return SidebarManageSelection(taskIds, sessionIds)
}

internal fun resolveManagedDeletion(
    selection: SidebarManageSelection,
    groups: List<TaskDirectoryGroup>,
): SidebarManageSelection {
    val owned = groups.asSequence()
        .flatMap { it.tasks }
        .filter { it.id in selection.taskIds }
        .flatMap { it.sessions }
        .map { it.id }
        .toSet()
    return SidebarManageSelection(
        taskIds = selection.taskIds,
        sessionIds = selection.sessionIds.filterNot { it in owned }.toSet(),
    )
}

internal fun describeManagedDeletion(selection: SidebarManageSelection): String {
    val parts = buildList {
        if (selection.taskIds.isNotEmpty()) add("${selection.taskIds.size} 个任务")
        if (selection.sessionIds.isNotEmpty()) add("${selection.sessionIds.size} 个终端")
    }
    return parts.joinToString("和").ifEmpty { "所选项目" }
}

internal fun collapsedRailTasks(
    groups: List<TaskDirectoryGroup>,
    activeTaskId: String?,
    limit: Int = COLLAPSED_RAIL_LIMIT,
): CollapsedRailModel {
    val items = groups.flatMap { group ->
        group.tasks.map { task ->
            CollapsedRailTask(group = group, task = task, activity = taskRailActivity(task))
        }
    }.sortedWith { left, right ->
        val rank = railRank(left, activeTaskId).compareTo(railRank(right, activeTaskId))
        if (rank != 0) rank
        else compareNullableTimestamp(
            right.task.task.lastOpenedAt ?: right.task.task.createdAt,
            left.task.task.lastOpenedAt ?: left.task.task.createdAt,
        )
    }
    val visible = items.take(limit.coerceAtLeast(0))
    return CollapsedRailModel(items = visible, overflow = (items.size - visible.size).coerceAtLeast(0))
}

private fun railRank(item: CollapsedRailTask, activeTaskId: String?): Int = when {
    item.task.id == activeTaskId -> 0
    item.activity == "attention" -> 1
    item.activity == "running" -> 2
    else -> 3
}
