package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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
class TaskListState(
    private val port: WorkspacePort,
    private val expansionStore: TaskListExpansionStore = MemoryTaskListExpansionStore(),
) : ScopedStore() {
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
    var orderSaveError by mutableStateOf<String?>(null)
        private set
    var defaultCwd by mutableStateOf<String?>(null)
        private set
    var recentPaths by mutableStateOf<List<RecentPath>>(emptyList())
        private set
    var defaultProvider by mutableStateOf("claude")
        private set
    var defaultSessionKind by mutableStateOf(WorkspaceSessionKind.Structured)
        private set
    var creationDefaultsLoading by mutableStateOf(false)
        private set
    var newTaskRequest by mutableLongStateOf(0L)
        private set

    private val directoryExpansion = mutableStateMapOf<String, Boolean>().apply {
        expansionStore.collapsedIds(TASK_LIST_EXPANSION_DIRS).forEach { put(it, false) }
    }
    private val taskExpansion = mutableStateMapOf<String, Boolean>().apply {
        expansionStore.collapsedIds(TASK_LIST_EXPANSION_TASKS).forEach { put(it, false) }
    }
    private val standaloneExpansion = mutableStateMapOf<String, Boolean>().apply {
        expansionStore.collapsedIds(TASK_LIST_EXPANSION_LOOSE).forEach { put(it, false) }
    }
    private val loadMutex = Mutex()
    private val mutationMutex = Mutex()
    private val orderSaveMutex = Mutex()
    private val creationDefaultsMutex = Mutex()
    private var creationChoiceRevision = 0L
    /**
     * 拖动排序后、服务端确认前的本地顺序。
     * 轮询可能比保存请求先回来，用它在中间这段时间压住服务端的旧顺序，避免卡片回弹。
     */
    private var pendingGroupOrder: List<String>? = null
    private var dragStartOrder: List<String>? = null
    private var dragPreviousPendingOrder: List<String>? = null
    private var syncing = false
    private var consumedNewTaskRequest = 0L
    private var groupsRevision: String? = null

    fun startSync() {
        if (syncing) return
        syncing = true
        scope.launch { port.taskChanges.collect { load(silent = true) } }
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

    fun isDirectoryCollapsed(groupId: String): Boolean = directoryExpansion[groupId] == false

    fun toggleDirectory(groupId: String) {
        directoryExpansion[groupId] = isDirectoryCollapsed(groupId)
        persistDirectoryExpansion()
    }

    fun isTaskCollapsed(taskId: String): Boolean = taskExpansion[taskId] == false

    fun toggleTask(taskId: String) {
        taskExpansion[taskId] = isTaskCollapsed(taskId)
        persistTaskExpansion()
    }

    fun isStandaloneCollapsed(groupId: String): Boolean = standaloneExpansion[groupId] == false

    fun toggleStandalone(groupId: String) {
        standaloneExpansion[groupId] = isStandaloneCollapsed(groupId)
        persistStandaloneExpansion()
    }

    /** Keep the selected branch visible after returning from a task or session detail. */
    fun expandPathToSelection(taskId: String?, sessionId: String?) {
        if (taskId == null && sessionId == null) return
        groups.forEach { group ->
            val selectedTask = group.tasks.firstOrNull { task ->
                task.id == taskId || task.sessions.any { it.id == sessionId }
            }
            if (selectedTask != null) {
                directoryExpansion[group.id] = true
                taskExpansion[selectedTask.id] = true
            } else if (group.standaloneSessions.any { it.id == sessionId }) {
                directoryExpansion[group.id] = true
                standaloneExpansion[group.id] = true
            }
        }
        persistDirectoryExpansion()
        persistTaskExpansion()
        persistStandaloneExpansion()
    }

    private fun persistDirectoryExpansion() {
        expansionStore.setCollapsedIds(
            TASK_LIST_EXPANSION_DIRS,
            directoryExpansion.filterValues { !it }.keys,
        )
    }

    private fun persistTaskExpansion() {
        expansionStore.setCollapsedIds(
            TASK_LIST_EXPANSION_TASKS,
            taskExpansion.filterValues { !it }.keys,
        )
    }

    private fun persistStandaloneExpansion() {
        expansionStore.setCollapsedIds(
            TASK_LIST_EXPANSION_LOOSE,
            standaloneExpansion.filterValues { !it }.keys,
        )
    }

    suspend fun load(silent: Boolean = false): Boolean = loadMutex.withLock {
        loadUnlocked(silent)
    }

    /**
     * 统一的变更流程：串行化 → 置 busy → 清旧错误 → 异常转用户可见文案。
     * [block] 返回 null 表示本次变更失败（校验不过或异常），成功则返回结果。
     * CancellationException 原样抛出，不会被当成失败文案。
     */
    private suspend fun <T> mutate(failureMessage: String, block: suspend () -> T?): T? =
        mutationMutex.withLock {
            mutationBusy = true
            mutationError = null
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutationError = error.message ?: failureMessage
                null
            } finally {
                mutationBusy = false
            }
        }

    private suspend fun loadUnlocked(silent: Boolean): Boolean {
        if (!silent) loading = true
        return try {
            val page = port.listTaskGroupsPage(groupsRevision)
            if (page.unchanged) {
                loadError = null
                return true
            }
            val incoming = applyPendingGroupOrder(page.groups, pendingGroupOrder)
            if (groups != incoming) groups = incoming
            groupsRevision = page.revision
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
        val choiceRevision = creationChoiceRevision
        return try {
            defaultCwd = port.taskDefaultCwd()?.trim()?.takeIf { it.isNotEmpty() }
            recentPaths = port.recentTaskPaths()
                .filter { it.path.isNotBlank() }
                .distinctBy { normalizeWorkspacePath(it.path) }
            runCatching { port.serverConfig() }.getOrNull()?.let { config ->
                // A slow defaults request must not undo a choice made in the open dialog.
                if (choiceRevision != creationChoiceRevision) return@let
                defaultProvider = config.defaultProvider?.takeIf { it.isNotBlank() } ?: defaultProvider
                defaultSessionKind = WorkspaceSessionKind.fromRaw(config.defaultSessionKind)
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
        workspaceId: String? = null,
        description: String? = null,
    ): TaskCreationResult? = mutate("创建任务失败") {
        // Task names belong to the container, never to a session's prompt.
        val normalizedName = name.trim()
        val normalizedCwd = cwd.trim()
        // 首个会话的提示词：任务没起名时交给服务端据它总结标题。
        val normalizedDescription = description?.trim().orEmpty()
        if (!isValidOptionalTaskName(normalizedName)) {
            mutationError = "任务名称无效或过长"
            return@mutate null
        }
        val workspaces = port.listWorkspaces()
        val project = when {
            workspaceId == com.wand.app.data.GLOBAL_WORKSPACE_ID -> null
            !workspaceId.isNullOrBlank() -> workspaces.firstOrNull { it.id == workspaceId }
                ?: throw IllegalStateException("工作区已不存在，请重新选择目录")
            normalizedCwd.isNotEmpty() -> workspaces.firstOrNull {
                normalizeWorkspacePath(it.cwd) == normalizeWorkspacePath(normalizedCwd)
            } ?: port.createWorkspace(
                normalizedCwd.trimEnd('/').substringAfterLast('/').ifBlank { "工作区" }, normalizedCwd,
            )
            else -> null
        }
        val task = if (project != null) {
            port.createWorkspaceTask(
                workspaceId = project.id,
                name = normalizedName.ifEmpty { "新任务" },
                worktree = worktree,
                description = normalizedDescription.ifEmpty { null },
            )
        } else {
            port.createStandaloneTask(
                name = normalizedName.ifEmpty { "新任务" },
                cwd = normalizedCwd.ifEmpty { null },
                worktree = if (normalizedCwd.isEmpty()) false else worktree,
                description = normalizedDescription.ifEmpty { null },
            )
        }
        val workspace = project
            ?: Workspace(
                id = task.workspaceId,
                name = "",
                cwd = task.cwd.ifEmpty { normalizedCwd },
                defaultProvider = null,
                layout = null,
                createdAt = null,
                lastOpenedAt = null,
            )
        load(silent = true)
        TaskCreationResult(workspace, task)
    }

    suspend fun renameTask(taskId: String, name: String): WorkspaceTask? = mutate("重命名任务失败") {
        val normalizedName = name.trim()
        if (!isValidTaskName(normalizedName)) {
            mutationError = if (normalizedName.isEmpty()) "请输入任务名称" else "任务名称无效或过长"
            return@mutate null
        }
        val updated = port.renameWorkspaceTask(taskId, normalizedName)
        load(silent = true)
        updated
    }

    /**
     * 重命名目录（工作区显示名）：合成目录走目录接口，已有项目走项目接口，
     * 服务端会把两边名字写成同一个。
     */
    suspend fun renameDirectory(group: TaskDirectoryGroup, name: String): Boolean =
        mutate("重命名工作区失败") {
            val normalizedName = name.trim()
            if (!isValidTaskName(normalizedName)) {
                mutationError = if (normalizedName.isEmpty()) "请输入工作区名称" else "工作区名称无效或过长"
                return@mutate null
            }
            if (group.synthetic) {
                port.renameSessionDirectory(group.workspaceCwd, normalizedName)
            } else {
                port.renameWorkspace(group.workspaceId, normalizedName)
            }
            load(silent = true)
            true
        } ?: false

    suspend fun deleteTask(taskId: String): Boolean =
        mutate("删除任务失败") {
            port.deleteWorkspaceTask(taskId)
            load(silent = true)
            true
        } ?: false

    /**
     * 归档任务（软删除）：终端继续运行、worktree 保留，只从侧栏隐藏并移入看板归档。
     * 对应服务端 POST /api/workspace-tasks/:taskId/archive，与详情页「归档」同一条路径。
     */
    suspend fun archiveTask(taskId: String): Boolean =
        mutate("归档任务失败") {
            port.archiveWorkspaceTask(taskId)
            load(silent = true)
            true
        } ?: false

    suspend fun createTaskWindow(
        taskId: String,
        target: WorkspaceSessionTarget,
        kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
        prompt: String? = null,
    ): SessionSnapshot? = mutate("创建工作窗口失败") {
        val detail = port.workspaceTask(taskId)
        val session = port.createWorkspaceTaskWindow(
            target,
            WorkspaceBinding(detail.workspaceId, detail.id, detail.cwd),
            kind,
            prompt,
        )
        val reconciled = reconcileTaskWindowLayout(
            detail.task.layout,
            detail.sessions.map { it.id },
        )
        val nextLayout = addSessionWindow(reconciled, session.id, activate = true)
        runCatching { port.saveWorkspaceTaskLayout(detail.id, nextLayout) }
        load(silent = true)
        session
    }

    suspend fun clearTaskSessions(taskId: String): Int? = mutate("清空任务会话失败") {
        val deleted = port.clearWorkspaceTaskSessions(taskId)
        load(silent = true)
        deleted
    }

    suspend fun deleteSessions(sessionIds: List<String>): Int? = mutate("删除终端失败") {
        val deleted = port.deleteWorkspaceSessions(sessionIds)
        load(silent = true)
        deleted
    }

    /** 按下时记住原顺序；拖动过程中只改内存，松手后才发一次保存请求。 */
    fun startDirectoryReorder() {
        dragStartOrder = groupIdsInOrder(groups)
        dragPreviousPendingOrder = pendingGroupOrder
    }

    fun moveDirectory(draggedId: String, targetId: String) {
        if (dragStartOrder == null || draggedId == targetId || draggedId.isEmpty() || targetId.isEmpty()) return
        val current = groups
        val from = current.indexOfFirst { it.id == draggedId }
        val to = current.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0) return
        val next = movedItem(current, from, to)
        if (next == current) return
        groups = next
        pendingGroupOrder = groupIdsInOrder(next)
    }

    fun finishDirectoryReorder() {
        val start = dragStartOrder ?: return
        val previousPending = dragPreviousPendingOrder
        dragStartOrder = null
        dragPreviousPendingOrder = null
        if (groupIdsInOrder(groups) != start) persistPendingGroupOrder()
        else pendingGroupOrder = previousPending
    }

    fun cancelDirectoryReorder() {
        dragStartOrder?.let { groups = applyPendingGroupOrder(groups, it) }
        pendingGroupOrder = dragPreviousPendingOrder
        dragStartOrder = null
        dragPreviousPendingOrder = null
    }

    /** 保存串行化；多次拖动叠加时，先前响应不能抹掉后来还没保存的顺序。 */
    private fun persistPendingGroupOrder() {
        orderSaveError = null
        scope.launch {
            orderSaveMutex.withLock {
                val order = pendingGroupOrder ?: return@withLock
                try {
                    port.saveWorkspaceGroupOrder(order)
                    if (pendingGroupOrder == order) {
                        pendingGroupOrder = null
                        orderSaveError = null
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (pendingGroupOrder == order) {
                        orderSaveError = error.message ?: "保存目录顺序失败，请重试或更新服务端"
                    }
                }
            }
        }
    }

    suspend fun deleteDirectory(group: TaskDirectoryGroup, cascade: Boolean): Boolean =
        mutate("删除工作区失败") {
            port.deleteWorkspace(group.workspaceId, cascade)
            pendingGroupOrder = pendingGroupOrder?.filterNot { it == group.id }
            load(silent = true)
            true
        } ?: false

    /** 拖动保存失败后的重试：把当前本地顺序再提交一次。 */
    fun retrySaveGroupOrder() {
        if (pendingGroupOrder != null) persistPendingGroupOrder()
    }

    suspend fun refreshAfterMutation(): Boolean = load(silent = true)

    fun rememberCreationChoice(
        defaultProvider: String? = null,
        defaultSessionKind: WorkspaceSessionKind? = null,
        defaultTaskWorktree: Boolean? = null,
    ) {
        creationChoiceRevision += 1
        if (defaultProvider != null) this.defaultProvider = defaultProvider
        if (defaultSessionKind != null) this.defaultSessionKind = defaultSessionKind
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

    fun clearMutationError() {
        mutationError = null
    }

    companion object {
        const val MAX_TASK_NAME_LENGTH = 80

        internal fun normalizeWorkspacePath(path: String): String {
            val trimmed = path.trim()
            if (trimmed == "/") return trimmed
            return trimmed.trimEnd('/').ifEmpty { "/" }
        }

        internal fun isValidTaskName(name: String): Boolean {
            val count = name.codePointCount(0, name.length)
            return name.isNotEmpty() && count <= MAX_TASK_NAME_LENGTH &&
                name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
        }

        /**
         * 任务名称是可选字段：空串合法（使用“新任务”），
         * 只拦截超长和控制字符。
         */
        internal fun isValidOptionalTaskName(name: String): Boolean {
            val count = name.codePointCount(0, name.length)
            return count <= MAX_TASK_NAME_LENGTH &&
                name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
        }
    }
}

data class TaskCreationResult(
    val workspace: Workspace,
    val task: WorkspaceTaskCreation,
)
