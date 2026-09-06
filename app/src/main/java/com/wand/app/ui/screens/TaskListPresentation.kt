package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
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
