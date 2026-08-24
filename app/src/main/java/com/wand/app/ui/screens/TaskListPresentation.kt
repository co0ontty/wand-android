package com.wand.app.ui.screens

import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.workspaceProviderLabel

/** 目录只是分组元数据：路径收成末两段，避免整条绝对路径压过任务名。 */
internal fun shortenWorkspacePath(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    if (normalized.isEmpty()) return path
    val rooted = normalized.startsWith('/')
    val parts = normalized.split('/').filter { it.isNotEmpty() }
    if (parts.size <= 2) {
        val joined = parts.joinToString("/")
        return if (rooted) "/$joined" else joined.ifEmpty { normalized }
    }
    return "…/${parts.takeLast(2).joinToString("/")}"
}

internal fun directoryPathCaption(name: String, cwd: String): String? {
    val shortened = shortenWorkspacePath(cwd)
    if (shortened.isEmpty() || shortened == name) return null
    return shortened
}

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
