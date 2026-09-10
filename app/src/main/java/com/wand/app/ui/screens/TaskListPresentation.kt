package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.workspaceProviderLabel

internal const val DIRECTORY_PATH_MIN_TAIL = 2

/** 无宽度信息时的兜底：至少保留最后两层目录。 */
internal fun shortenWorkspacePath(path: String, minTail: Int = DIRECTORY_PATH_MIN_TAIL): String {
    val (rooted, parts) = workspacePathParts(path)
    if (parts.isEmpty()) return path
    if (parts.size <= minTail) return renderWorkspacePath(rooted, parts, truncated = false)
    return renderWorkspacePath(rooted, parts.takeLast(minTail), truncated = true)
}

internal fun directoryPathCaption(name: String, cwd: String): String? {
    val shortened = shortenWorkspacePath(cwd)
    if (shortened.isEmpty() || shortened == name) return null
    return shortened
}

/**
 * 有空间就尽量展开完整路径；不够时从左边收，但始终保住最后 [minTail] 层。
 * [measureWidth] 返回字符串像素宽，测试里可用字符数代替。
 */
internal fun fitWorkspacePath(
    path: String,
    availableWidthPx: Float,
    measureWidth: (String) -> Float,
    minTail: Int = DIRECTORY_PATH_MIN_TAIL,
): String {
    val (rooted, parts) = workspacePathParts(path)
    if (parts.isEmpty()) return path
    if (parts.size <= minTail) return renderWorkspacePath(rooted, parts, truncated = false)

    val full = renderWorkspacePath(rooted, parts, truncated = false)
    if (!availableWidthPx.isFinite() || measureWidth(full) <= availableWidthPx) return full

    for (count in (parts.size - 1) downTo minTail) {
        val candidate = renderWorkspacePath(rooted, parts.takeLast(count), truncated = true)
        if (measureWidth(candidate) <= availableWidthPx) return candidate
    }
    return renderWorkspacePath(rooted, parts.takeLast(minTail), truncated = true)
}

internal fun fitDirectoryPathCaption(
    name: String,
    cwd: String,
    availableWidthPx: Float,
    measureWidth: (String) -> Float,
): String? {
    if (cwd.isBlank()) return null
    val fitted = fitWorkspacePath(cwd, availableWidthPx, measureWidth)
    if (fitted.isEmpty() || fitted == name) return null
    return fitted
}

private fun workspacePathParts(path: String): Pair<Boolean, List<String>> {
    val normalized = path.replace('\\', '/').trimEnd('/')
    if (normalized.isEmpty()) return false to emptyList()
    val rooted = normalized.startsWith('/')
    return rooted to normalized.split('/').filter { it.isNotEmpty() }
}

private fun renderWorkspacePath(rooted: Boolean, parts: List<String>, truncated: Boolean): String {
    val joined = parts.joinToString("/")
    return when {
        truncated -> "…/$joined"
        rooted -> "/$joined"
        else -> joined
    }
}

/** 对齐 iOS 目录头：`2 任务 · 5 会话`，不单独再画文件夹图标。 */
internal fun directoryGroupMetaLabel(taskCount: Int, sessionCount: Int): String =
    "$taskCount 任务 · $sessionCount 会话"

internal fun directoryGroupSessionTotal(group: TaskDirectoryGroup): Int =
    group.tasks.sumOf { it.totalSessions } + group.standaloneSessions.size

/** 首页概览使用真实目录数，而不是服务端分组数；同一目录下的多个项目只算一个目录。 */
internal data class TaskListMetrics(
    val directoryCount: Int,
    val taskCount: Int,
    val sessionCount: Int,
)

internal fun taskListMetrics(groups: List<TaskDirectoryGroup>): TaskListMetrics {
    val visibleGroups = groups.filter { it.tasks.isNotEmpty() || it.standaloneSessions.isNotEmpty() }
    val directoryKeys = mutableSetOf<String>()
    val taskIds = mutableSetOf<String>()
    visibleGroups.forEach { group ->
        directoryKeys += directoryMetricKey(group)
        group.tasks.forEach { taskIds += it.id }
    }
    return TaskListMetrics(
        directoryCount = directoryKeys.size,
        taskCount = taskIds.size,
        sessionCount = visibleGroups.sumOf(::directoryGroupSessionTotal),
    )
}

/** 目录组偶尔会因历史项目绑定产生重复项；空 cwd 时退回 group id，不能误合并。 */
private fun directoryMetricKey(group: TaskDirectoryGroup): String {
    val trimmed = group.workspaceCwd.replace('\\', '/').trim()
    if (trimmed.isEmpty()) return "id:${group.id}"
    val normalized = trimmed.trimEnd('/').ifEmpty { "/" }
    return "cwd:$normalized"
}

internal fun homeTaskSummaryLabel(metrics: TaskListMetrics): String = when {
    metrics.directoryCount == 0 -> "按工作目录整理你的任务"
    metrics.taskCount == 0 -> "${metrics.directoryCount} 个目录 · 暂无任务"
    else -> "${metrics.directoryCount} 个目录 · ${metrics.taskCount} 个任务"
}

/** 展示层目录排序：活跃目录优先，其次按最近打开的任务排序；同值保留服务端顺序。 */
internal fun directoryTreeGroups(groups: List<TaskDirectoryGroup>): List<TaskDirectoryGroup> =
    groups
        .filter { it.tasks.isNotEmpty() || it.standaloneSessions.isNotEmpty() }
        .mapIndexed { index, group ->
            IndexedDirectoryGroup(
                group = group,
                index = index,
                active = groupHasLiveActivity(group),
                latestOpenedAt = group.tasks.mapNotNull { it.task.lastOpenedAt }.maxOrNull(),
            )
        }
        .sortedWith { left, right ->
            when {
                left.active != right.active -> if (left.active) -1 else 1
                left.latestOpenedAt != right.latestOpenedAt -> compareNullableTimestamp(
                    right.latestOpenedAt,
                    left.latestOpenedAt,
                )
                else -> left.index - right.index
            }
        }
        .map { it.group }

/** 目录内先展示活跃任务，再展示最近使用任务，完成任务自然沉到底部。 */
internal fun orderedTaskSummaries(tasks: List<WorkspaceTaskSummary>): List<WorkspaceTaskSummary> =
    tasks
        .mapIndexed { index, task ->
            IndexedTaskSummary(
                task = task,
                index = index,
                active = task.status == WorkspaceTaskStatus.Active,
                live = task.sessions.any(::sessionHasLiveActivity),
            )
        }
        .sortedWith { left, right ->
            when {
                left.live != right.live -> if (left.live) -1 else 1
                left.active != right.active -> if (left.active) -1 else 1
                left.task.task.lastOpenedAt != right.task.task.lastOpenedAt -> compareNullableTimestamp(
                    right.task.task.lastOpenedAt,
                    left.task.task.lastOpenedAt,
                )
                else -> left.index - right.index
            }
        }
        .map { it.task }

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
    val active: Boolean,
    val latestOpenedAt: String?,
)

private data class IndexedTaskSummary(
    val task: WorkspaceTaskSummary,
    val index: Int,
    val active: Boolean,
    val live: Boolean,
)

/** 默认任务不标「共享」——那是常态，占标题栏却没有信息量。隔离才值得露出来。 */
internal fun taskIsolationCaption(isolated: Boolean, branch: String? = null): String? {
    if (!isolated) return null
    return "隔离"
}

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
