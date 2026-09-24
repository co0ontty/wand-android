package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskStatus
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

/** 目录顺序以 GET /api/tasks 返回为准。 */
internal fun directoryTreeGroups(groups: List<TaskDirectoryGroup>): List<TaskDirectoryGroup> =
    groups.map { group ->
        // Presentation only: retain completed tasks in the board and session navigation.
        group.copy(
            workspaceName = if (group.isGlobal) "未归属工作区" else group.workspaceName,
            tasks = group.tasks.filter { it.status != WorkspaceTaskStatus.Done },
        )
    }.filter { !it.isGlobal || it.tasks.isNotEmpty() || it.standaloneSessions.isNotEmpty() }
        .sortedBy { it.isGlobal }

/** 目录内任务顺序以 GET /api/tasks 返回为准。 */
internal fun orderedTaskSummaries(tasks: List<WorkspaceTaskSummary>): List<WorkspaceTaskSummary> =
    tasks

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

/** Even a single workspace can be collapsed; the disclosure never changes meaning. */
@Suppress("UNUSED_PARAMETER")
internal fun showsDirectoryDisclosure(directoryCount: Int): Boolean = true

/** 任务下没有终端时不显示箭头；空状态直接展示，无需先展开。 */
internal fun showsTaskSessionDisclosure(sessionCount: Int): Boolean = sessionCount > 0

/** 目录默认展开，尊重用户折叠选择。 */
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

internal data class SidebarManageSelection(
    val taskIds: Set<String> = emptySet(),
    val sessionIds: Set<String> = emptySet(),
) {
    val count: Int get() = taskIds.size + sessionIds.size
    val isEmpty: Boolean get() = count == 0
}

/** 窄栏一项一个目录，顺序与展开后的目录树一致。 */
internal data class CollapsedRailDirectory(
    val group: TaskDirectoryGroup,
    val activity: String?,
)

private val RAIL_ATTENTION_STATUSES = setOf(
    "failed",
    "waiting-input",
    "waiting_input",
    "permission-blocked",
    "reconnecting",
)

internal fun sessionRailActivity(session: WorkspaceSessionSummary): String? = when {
    session.status in RAIL_ATTENTION_STATUSES -> "attention"
    session.inFlight == true || session.ptyBusy == true || session.status == "thinking" -> "running"
    else -> null
}

internal fun directoryRailActivity(group: TaskDirectoryGroup): String? {
    val sessions = group.tasks.flatMap { it.sessions } + group.standaloneSessions
    if (sessions.any { sessionRailActivity(it) == "attention" }) return "attention"
    if (sessions.any { sessionRailActivity(it) == "running" }) return "running"
    return null
}

/** 当前任务或终端落在这个目录里时，窄栏文件夹保持选中。 */
internal fun directoryContainsSelection(
    group: TaskDirectoryGroup,
    selectedTaskId: String?,
    selectedSessionId: String?,
): Boolean {
    if (!selectedTaskId.isNullOrBlank() && group.tasks.any { it.id == selectedTaskId }) return true
    val sessionId = selectedSessionId?.takeIf { it.isNotBlank() } ?: return false
    if (group.standaloneSessions.any { it.id == sessionId }) return true
    return group.tasks.any { task -> task.sessions.any { it.id == sessionId } }
}

/** 平板窄栏：每个可见目录一个文件夹，不再把任务摊平成图标。 */
internal fun collapsedRailDirectories(groups: List<TaskDirectoryGroup>): List<CollapsedRailDirectory> =
    directoryTreeGroups(groups).map { group ->
        CollapsedRailDirectory(group = group, activity = directoryRailActivity(group))
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

/**
 * 归档会连同任务里的终端一起隐藏，所以被选中任务名下的终端不再进删除集合，
 * 只保留真正独立选中的终端（对齐 web 端「两者互不覆盖」）。
 */
internal fun resolveManagedAction(
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

/**
 * 批量操作里任务是归档（终端继续跑、worktree 保留），只有显式选中的终端才真删除。
 * 对齐 web 端 `describeManagedAction` 与 iOS 同类实现，三端用同一句动作名。
 */
internal fun describeManagedAction(selection: SidebarManageSelection): String = when {
    selection.taskIds.isNotEmpty() && selection.sessionIds.isNotEmpty() -> "归档任务并删除终端"
    selection.taskIds.isNotEmpty() -> "归档任务"
    else -> "删除终端"
}

/** 只有真的会杀终端时才用危险样式；纯归档不该渲染成红色破坏性操作。 */
internal fun managedSelectionIsDestructive(selection: SidebarManageSelection): Boolean =
    selection.sessionIds.isNotEmpty()

/** 批量确认弹窗正文：说清归档与删除各自会发生什么（归档是软删除）。 */
internal fun describeManagedConfirmMessage(selection: SidebarManageSelection): String = when {
    selection.taskIds.isEmpty() -> "将结束所选终端，此操作无法撤销。"
    selection.sessionIds.isEmpty() ->
        "所选任务会从侧栏隐藏并移入看板归档，终端与 Worktree 都保留。"
    else ->
        "所选任务会移入看板归档（终端与 Worktree 保留），同时结束所选终端。"
}


