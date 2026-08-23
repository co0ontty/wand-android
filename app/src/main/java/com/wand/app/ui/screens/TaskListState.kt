package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.RecentPath
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskCreation
import com.wand.app.data.addSessionWindow
import com.wand.app.data.reconcileTaskWindowLayout
import com.wand.app.ui.ScopedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Task-first root data. Workspace remains an internal directory binding, not a user-facing mode. */
class TaskListState(private val port: WorkspacePort) : ScopedStore() {
    var groups by mutableStateOf<List<TaskDirectoryGroup>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var mutationBusy by mutableStateOf(false)
        private set
    var mutationError by mutableStateOf<String?>(null)
        private set
    var defaultCwd by mutableStateOf<String?>(null)
        private set
    var recentPaths by mutableStateOf<List<RecentPath>>(emptyList())
        private set
    var defaultProvider by mutableStateOf("claude")
        private set
    var defaultSessionKind by mutableStateOf(WorkspaceSessionKind.Structured)
        private set
    var defaultTaskWorktree by mutableStateOf(true)
        private set
    var creationDefaultsLoading by mutableStateOf(false)
        private set
    var newTaskRequest by mutableLongStateOf(0L)
        private set

    private val loadMutex = Mutex()
    private val mutationMutex = Mutex()
    private val creationDefaultsMutex = Mutex()
    private var syncing = false
    private var consumedNewTaskRequest = 0L

    fun startSync() {
        if (syncing) return
        syncing = true
        scope.launch {
            load(silent = groups.isNotEmpty())
            while (true) {
                delay(10_000)
                load(silent = true)
            }
        }
    }

    fun requestNewTask() {
        newTaskRequest += 1
    }

    fun consumeNewTaskRequest(): Boolean {
        if (newTaskRequest <= consumedNewTaskRequest) return false
        consumedNewTaskRequest = newTaskRequest
        return true
    }

    suspend fun load(silent: Boolean = false): Boolean = loadMutex.withLock {
        loadUnlocked(silent)
    }

    private suspend fun loadUnlocked(silent: Boolean): Boolean {
        if (!silent) loading = true
        return try {
            val loaded = port.listTaskGroups()
            if (groups != loaded) groups = loaded
            loadError = null
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            loadError = error.message ?: "无法加载任务列表"
            false
        } finally {
            loading = false
        }
    }

    suspend fun loadCreationDefaults(): Boolean = creationDefaultsMutex.withLock {
        creationDefaultsLoading = true
        return try {
            defaultCwd = port.taskDefaultCwd()?.trim()?.takeIf { it.isNotEmpty() }
            recentPaths = port.recentTaskPaths()
                .filter { it.path.isNotBlank() }
                .distinctBy { normalizeWorkspacePath(it.path) }
            runCatching { port.serverConfig() }.getOrNull()?.let { config ->
                defaultProvider = config.defaultProvider?.takeIf { it.isNotBlank() } ?: defaultProvider
                defaultSessionKind = if (config.defaultSessionKind == "pty") {
                    WorkspaceSessionKind.Pty
                } else {
                    WorkspaceSessionKind.Structured
                }
                defaultTaskWorktree = config.defaultTaskWorktree != false
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "无法加载任务目录"
            false
        } finally {
            creationDefaultsLoading = false
        }
    }

    suspend fun createTask(
        name: String,
        cwd: String,
        worktree: Boolean,
    ): TaskCreationResult? = mutationMutex.withLock {
        val normalizedName = name.trim()
        val normalizedCwd = cwd.trim()
        if (!isValidTaskName(normalizedName)) {
            mutationError = if (normalizedName.isEmpty()) "请输入任务名称" else "任务名称无效或过长"
            return@withLock null
        }
        if (normalizedCwd.isEmpty()) {
            mutationError = "请选择任务目录"
            return@withLock null
        }
        mutationBusy = true
        mutationError = null
        try {
            val workspace = findOrCreateWorkspace(normalizedCwd)
            val task = port.createWorkspaceTask(
                workspaceId = workspace.id,
                name = normalizedName,
                worktree = worktree,
            )
            load(silent = true)
            TaskCreationResult(workspace, task)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "创建任务失败"
            null
        } finally {
            mutationBusy = false
        }
    }

    suspend fun renameTask(taskId: String, name: String): WorkspaceTask? = mutationMutex.withLock {
        val normalizedName = name.trim()
        if (!isValidTaskName(normalizedName)) {
            mutationError = if (normalizedName.isEmpty()) "请输入任务名称" else "任务名称无效或过长"
            return@withLock null
        }
        mutationBusy = true
        mutationError = null
        try {
            val updated = port.renameWorkspaceTask(taskId, normalizedName)
            load(silent = true)
            updated
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "重命名任务失败"
            null
        } finally {
            mutationBusy = false
        }
    }

    suspend fun deleteTask(taskId: String): Boolean = mutationMutex.withLock {
        mutationBusy = true
        mutationError = null
        try {
            port.deleteWorkspaceTask(taskId)
            load(silent = true)
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "删除任务失败"
            false
        } finally {
            mutationBusy = false
        }
    }

    suspend fun createTaskWindow(
        taskId: String,
        target: WorkspaceSessionTarget,
        kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
    ): SessionSnapshot? = mutationMutex.withLock {
        mutationBusy = true
        mutationError = null
        try {
            val detail = port.workspaceTask(taskId)
            val session = port.createWorkspaceTaskWindow(
                target,
                WorkspaceBinding(detail.workspaceId, detail.id, detail.cwd),
                kind,
            )
            val reconciled = reconcileTaskWindowLayout(
                detail.task.layout,
                detail.sessions.map { it.id },
            )
            val nextLayout = addSessionWindow(reconciled, session.id, activate = true)
            runCatching { port.saveWorkspaceTaskLayout(detail.id, nextLayout) }
            load(silent = true)
            session
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "创建工作窗口失败"
            null
        } finally {
            mutationBusy = false
        }
    }

    suspend fun clearTaskSessions(taskId: String): Int? = mutationMutex.withLock {
        mutationBusy = true
        mutationError = null
        try {
            val deleted = port.clearWorkspaceTaskSessions(taskId)
            load(silent = true)
            deleted
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "清空任务会话失败"
            null
        } finally {
            mutationBusy = false
        }
    }

    suspend fun deleteSessions(sessionIds: List<String>): Int? = mutationMutex.withLock {
        mutationBusy = true
        mutationError = null
        try {
            val deleted = port.deleteWorkspaceSessions(sessionIds)
            load(silent = true)
            deleted
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutationError = error.message ?: "删除终端失败"
            null
        } finally {
            mutationBusy = false
        }
    }

    suspend fun refreshAfterMutation(): Boolean = load(silent = true)

    fun rememberCreationChoice(
        defaultProvider: String? = null,
        defaultSessionKind: WorkspaceSessionKind? = null,
        defaultTaskWorktree: Boolean? = null,
    ) {
        if (defaultProvider != null) this.defaultProvider = defaultProvider
        if (defaultSessionKind != null) this.defaultSessionKind = defaultSessionKind
        if (defaultTaskWorktree != null) this.defaultTaskWorktree = defaultTaskWorktree
        scope.launch {
            runCatching {
                port.updateCreationDefaults(
                    defaultProvider = defaultProvider,
                    defaultSessionKind = defaultSessionKind?.raw,
                    defaultTaskWorktree = defaultTaskWorktree,
                )
            }
        }
    }

    fun clearLoadError(message: String) {
        if (loadError == message) loadError = null
    }

    fun clearMutationError() {
        mutationError = null
    }

    private suspend fun findOrCreateWorkspace(cwd: String): Workspace {
        val normalized = normalizeWorkspacePath(cwd)
        val existing = port.listWorkspaces()
            .firstOrNull { normalizeWorkspacePath(it.cwd) == normalized }
        if (existing != null) return existing
        return try {
            port.createWorkspace(directoryName(normalized), cwd)
        } catch (creationError: Exception) {
            if (creationError is CancellationException) throw creationError
            port.listWorkspaces()
                .firstOrNull { normalizeWorkspacePath(it.cwd) == normalized }
                ?: throw creationError
        }
    }

    companion object {
        const val MAX_TASK_NAME_LENGTH = 80

        internal fun normalizeWorkspacePath(path: String): String {
            val trimmed = path.trim()
            if (trimmed == "/") return trimmed
            return trimmed.trimEnd('/').ifEmpty { "/" }
        }

        internal fun directoryName(path: String): String =
            normalizeWorkspacePath(path).substringAfterLast('/').ifEmpty { "任务目录" }

        internal fun isValidTaskName(name: String): Boolean {
            val count = name.codePointCount(0, name.length)
            return name.isNotEmpty() && count <= MAX_TASK_NAME_LENGTH &&
                name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
        }
    }
}

data class TaskCreationResult(
    val workspace: Workspace,
    val task: WorkspaceTaskCreation,
)
