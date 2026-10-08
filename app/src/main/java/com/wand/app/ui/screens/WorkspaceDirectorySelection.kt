package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.DirectoryListing
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.normalizeWorkspacePath
import kotlinx.coroutines.CancellationException

/** 空目录表示未归属；未登记的目录只在真正提交时创建工作区。 */
internal data class WorkspaceDirectorySelection(val cwd: String, val workspaceId: String? = null)

internal fun selectableDirectoryWorkspaces(workspaces: List<Workspace>): List<Workspace> =
    workspaces.filter { it.id != GLOBAL_WORKSPACE_ID && it.cwd.isNotBlank() }
        .distinctBy { normalizeWorkspacePath(it.cwd) }

internal fun directorySelection(cwd: String, workspaces: List<Workspace>): WorkspaceDirectorySelection {
    val path = cwd.trim()
    val workspace = selectableDirectoryWorkspaces(workspaces).firstOrNull {
        normalizeWorkspacePath(it.cwd) == normalizeWorkspacePath(path)
    }
    return WorkspaceDirectorySelection(path, workspace?.id)
}

internal suspend fun resolveCreationWorkspace(api: WorkspacePort, selection: WorkspaceDirectorySelection): Workspace? {
    if (selection.cwd.isBlank() && selection.workspaceId == null) return null
    val workspaces = selectableDirectoryWorkspaces(api.listWorkspaces())
    if (selection.workspaceId != null) {
        return workspaces.firstOrNull { it.id == selection.workspaceId }
            ?: error("工作区已不存在，请重新选择目录")
    }
    val cwd = selection.cwd.trim()
    return workspaces.firstOrNull { normalizeWorkspacePath(it.cwd) == normalizeWorkspacePath(cwd) }
        ?: api.createWorkspace(cwd.trimEnd('/').substringAfterLast('/').ifBlank { "工作区" }, cwd)
}

internal fun parentWorkspaceDirectory(path: String): String =
    path.trim().trimEnd('/').ifEmpty { "/" }.substringBeforeLast('/', "").ifEmpty { "/" }

/** 请求代次只归选择器：旧目录回执不能覆盖新目录，也不能让失败路径变成可选。 */
internal class WorkspaceDirectoryPickerState(private val api: WorkspacePort, initialPath: String) {
    var workspaces by mutableStateOf<List<Workspace>>(emptyList())
        private set
    var choicesLoading by mutableStateOf(false)
        private set
    var choicesError by mutableStateOf<String?>(null)
        private set
    var path by mutableStateOf(initialPath.trim().ifBlank { "/" })
        private set
    var listing by mutableStateOf<DirectoryListing?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var revision = 0L
    val canSelectDirectory: Boolean get() = !loading && error == null && listing != null

    suspend fun loadChoices() {
        choicesLoading = true
        choicesError = null
        try {
            workspaces = selectableDirectoryWorkspaces(api.listWorkspaces())
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            choicesError = failure.message ?: "无法加载工作区，仍可浏览其他目录"
        } finally {
            choicesLoading = false
        }
    }

    suspend fun browse(requestedPath: String) {
        val request = ++revision
        path = requestedPath.trim().ifBlank { "/" }
        val requested = path
        loading = true
        listing = null
        error = null
        try {
            val loaded = api.listDirectory(requested)
            if (request == revision) listing = loaded
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (request == revision) error = failure.message ?: "无法读取目录，请检查路径后重试"
        } finally {
            if (request == revision) loading = false
        }
    }
}
