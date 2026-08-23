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

/** 任务隔离状态。不要写成「共享目录」，否则会和上层目录分组撞名。 */
internal fun taskIsolationCaption(isolated: Boolean, branch: String? = null): String {
    if (!isolated) return "共享"
    val ref = branch?.trim().orEmpty()
    return if (ref.isEmpty()) "隔离" else "隔离"
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
