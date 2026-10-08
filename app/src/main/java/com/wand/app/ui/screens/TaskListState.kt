package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.normalizeWorkspacePath
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.RecentPath
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WandAgentEngine
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
    /** 上次选的执行引擎；选过 Wand Agent 就继续沿用它。 */
    var defaultEngine by mutableStateOf(WandAgentEngine.Cli.raw)
        private set
    var defaultSessionKind by mutableStateOf(WorkspaceSessionKind.Structured)
    var defaultThinkingEffort by mutableStateOf("off")
        private set
    var creationDefaultsLoading by mutableStateOf(false)
        private set
    var newTaskRequest by mutableLongStateOf(0L)
        private set

    /** 首页「最近对话」那一区的折叠档位（员工/团队/终端分组）。 */
    var recentFold by mutableStateOf(expansionStore.sectionFold(FOLD_SECTION_RECENT))
        private set

    /** 首页「任务与工作区」那一区的折叠档位（工作区 → 任务 → 终端）。 */
    var workspaceFold by mutableStateOf(expansionStore.sectionFold(FOLD_SECTION_WORKSPACE))
        private set

    /** 仅本次浏览有效的分组展开，不写入全局档位或持久化存储。 */
    private val temporaryExpandedGroups = mutableStateMapOf<Pair<String, String>, Boolean>()
    val hasTemporaryExpansion: Boolean get() = temporaryExpandedGroups.isNotEmpty()

    fun groupFold(section: String, groupId: String): HomeFoldMode =
        if (temporaryExpandedGroups[section to groupId] == true) HomeFoldMode.Expand
        else sectionFold(section)

    fun toggleTemporaryExpansion(section: String, groupId: String) {
        if (sectionFold(section) == HomeFoldMode.Expand) return
        val key = section to groupId
        if (temporaryExpandedGroups.remove(key) == null) temporaryExpandedGroups[key] = true
    }

    fun clearTemporaryExpansions() {
        temporaryExpandedGroups.clear()
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

    /** 某一区的折叠档位。区内每一层都跟随它 —— 控制只在小节表头那一行上。 */
    fun sectionFold(section: String): HomeFoldMode = when (section) {
        FOLD_SECTION_RECENT -> recentFold
        else -> workspaceFold
    }

    /** 改某一区：档位按服务端持久化，区内所有层一起变。 */
    fun selectSectionFold(section: String, mode: HomeFoldMode) {
        temporaryExpandedGroups.keys.filter { it.first == section }.forEach {
            temporaryExpandedGroups.remove(it)
        }
        if (sectionFold(section) == mode) return
        if (section == FOLD_SECTION_RECENT) {
            recentFold = mode
        } else {
            workspaceFold = mode
        }
        expansionStore.setSectionFold(section, mode)
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
                defaultThinkingEffort = config.defaultThinkingEffort?.takeIf { it.isNotBlank() } ?: "off"
                // A slow defaults request must not undo a choice made in the open dialog.
                if (choiceRevision != creationChoiceRevision) return@let
                defaultProvider = config.defaultProvider?.takeIf { it.isNotBlank() } ?: defaultProvider
                defaultEngine = if (config.defaultProvider == "pi" && config.defaultEngine == WandAgentEngine.Sdk.raw) {
                    WandAgentEngine.Sdk.raw
                } else {
                    WandAgentEngine.Cli.raw
                }
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
        parentTaskId: String? = null,
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
                parentTaskId = parentTaskId,
            )
        } else {
            port.createStandaloneTask(
                name = normalizedName.ifEmpty { "新任务" },
                cwd = normalizedCwd.ifEmpty { null },
                worktree = if (normalizedCwd.isEmpty()) false else worktree,
                description = normalizedDescription.ifEmpty { null },
                parentTaskId = parentTaskId,
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
        model: String? = null,
        thinkingEffort: String? = null,
        employeeId: String? = null,
    ): SessionSnapshot? = mutate("创建工作窗口失败") {
        val detail = port.workspaceTask(taskId)
        val binding = WorkspaceBinding(detail.workspaceId, detail.id, detail.cwd)
        val session = if (employeeId != null) port.createEmployeeWorkspaceTaskWindow(
            employeeId, binding, prompt,
        ) else port.createWorkspaceTaskWindow(
            target,
            binding,
            kind,
            prompt,
            model,
            thinkingEffort,
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

    /**
     * 没有指定任务的开工：会话只带目录（和可选的项目）归属，不为它建任务卡，
     * 落进侧栏该目录的「未分组任务」。要成卡时由用户在那一行「归纳为新任务」。
     * 未分组会话没有任务布局可改，所以不碰 layout。
     */
    suspend fun createUngroupedSession(
        workspaceId: String?,
        cwd: String,
        target: WorkspaceSessionTarget,
        kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
        prompt: String? = null,
        model: String? = null,
        thinkingEffort: String? = null,
        employeeId: String? = null,
    ): SessionSnapshot? = mutate("启动会话失败") {
        val binding = WorkspaceBinding(
            // 隐藏的全局暂存区不是项目，不写进会话归属。
            workspaceId = workspaceId?.takeIf { it.isNotBlank() && it != GLOBAL_WORKSPACE_ID },
            cwd = cwd.trim(),
        )
        val session = if (employeeId != null) port.createEmployeeWorkspaceTaskWindow(employeeId, binding, prompt)
        else port.createWorkspaceTaskWindow(target, binding, kind, prompt, model, thinkingEffort)
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

    /**
     * 长按时先把被拖的工作区收成标题行；落点后保持收起。
     * 收放只有小节表头那一个入口，所以这里改的是整个「任务与工作区」那一区。
     */
    fun startDirectoryReorder(draggedId: String) {
        dragStartOrder = groupIdsInOrder(groups)
        dragPreviousPendingOrder = pendingGroupOrder
        val dragged = groups.firstOrNull { it.id == draggedId }
        if (dragged != null && (dragged.tasks.isNotEmpty() || dragged.standaloneSessions.isNotEmpty()) &&
            workspaceFold.expanded
        ) {
            selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Collapse)
        }
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
        defaultEngine: String? = null,
    ) {
        creationChoiceRevision += 1
        if (defaultProvider != null) this.defaultProvider = defaultProvider
        if (defaultSessionKind != null) this.defaultSessionKind = defaultSessionKind
        if (defaultEngine != null) this.defaultEngine = defaultEngine
        scope.launch {
            runCatching {
                port.updateCreationDefaults(
                    defaultProvider = defaultProvider,
                    defaultSessionKind = defaultSessionKind?.raw,
                    defaultTaskWorktree = defaultTaskWorktree,
                    defaultEngine = defaultEngine,
                )
            }
        }
    }

    fun clearMutationError() {
        mutationError = null
    }

    companion object {
        const val MAX_TASK_NAME_LENGTH = 80

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
