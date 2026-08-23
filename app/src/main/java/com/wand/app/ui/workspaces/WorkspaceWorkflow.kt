package com.wand.app.ui.workspaces

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTaskDetail
import com.wand.app.data.activeWorkWindowTab
import com.wand.app.data.addSessionWindow
import com.wand.app.data.orderWorkspaceSessions
import com.wand.app.data.reconcileTaskWindowLayout
import com.wand.app.data.workspaceSessionLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// MARK: - 任务详情状态

sealed class WorkspaceTaskState {
    data object Loading : WorkspaceTaskState()
    /** 空任务欢迎态：保留 detail 以提供 cwd / workspaceId / taskName，但不自动创建会话。 */
    data class EmptySessions(val detail: WorkspaceTaskDetail) : WorkspaceTaskState() {
        val cwd: String get() = detail.cwd
        val workspaceId: String get() = detail.workspaceId
        val taskId: String get() = detail.id
        val taskName: String get() = detail.name
    }
    data class Content(
        val detail: WorkspaceTaskDetail,
        /** 排序后的会话（按创建顺序）。 */
        val orderedSessions: List<com.wand.app.data.WorkspaceSessionSummary>,
        /** 当前选中的会话 ID（null = 未选中 / 空任务）。 */
        val selectedSessionId: String?,
        /** 调和后的布局（保留 split / 非会话 tab）。 */
        val layout: TaskWindowLayout,
    ) : WorkspaceTaskState() {
        /** 当前活动会话（基于布局活动 tab + 选中态）。 */
        val activeSessionId: String?
            get() = selectedSessionId
                ?: activeWorkWindowTab(layout)?.let { (it as? com.wand.app.data.PaneTab.Session)?.sessionId }
    }
    data class Error(val message: String) : WorkspaceTaskState()
}

// MARK: - 工作窗口选择器（Bottom Sheet）状态

sealed class WorkspaceTargetState {
    data object Closed : WorkspaceTargetState()
    data object Selecting : WorkspaceTargetState()
    data object Creating : WorkspaceTargetState()
    data class Error(val message: String) : WorkspaceTargetState()
}

/**
 * 工作空间 / 任务的 UI 状态机。状态按连接（serverId）隔离；Composable 用
 * LaunchedEffect(serverId, taskId) 驱动加载，本类负责竞态保护与创建流程。
 *
 * 关键不变量：
 * - 打开任务只 GET 不 POST；空任务进入欢迎态，不自动创建会话。
 * - 切换任务时丢弃上一任务未完成的结果（generation 守卫）。
 * - 创建期间 Sheet 不可 dismiss、主按钮 disabled，屏蔽重复点击。
 * - 创建成功但布局 PUT 失败时不删除会话；提示警告，下次详情刷新补齐。
 */
class WorkspaceWorkflow(
    private val port: WorkspacePort,
    private val scope: CoroutineScope,
) {
    private val _taskState = MutableStateFlow<WorkspaceTaskState>(WorkspaceTaskState.Loading)
    val taskState: StateFlow<WorkspaceTaskState> = _taskState.asStateFlow()

    private val _targetState = MutableStateFlow<WorkspaceTargetState>(WorkspaceTargetState.Closed)
    val targetState: StateFlow<WorkspaceTargetState> = _targetState.asStateFlow()

    private var taskLoadJob: Job? = null
    /** 任务加载的代际：每次 loadTask 递增，回调比较 generation 丢弃过期结果。 */
    private var taskGeneration = 0
    private var createJob: Job? = null

    // ── 任务详情 ──

    /**
     * 加载任务详情。立即清空上一任务状态并显示 Loading；返回后按服务端 cwd 作为唯一创建目录，
     * 空任务进入欢迎态（不 POST）。重复调用时取消上一任务请求。
     */
    fun loadTask(taskId: String) {
        val generation = ++taskGeneration
        taskLoadJob?.cancel()
        _taskState.value = WorkspaceTaskState.Loading
        _targetState.value = WorkspaceTargetState.Closed
        taskLoadJob = scope.launch {
            try {
                val detail = port.workspaceTask(taskId)
                // 切任务丢弃旧结果。
                if (generation != taskGeneration) return@launch
                applyTaskDetail(detail, preferredSessionId = null)
            } catch (e: Exception) {
                if (generation != taskGeneration) return@launch
                _taskState.value = WorkspaceTaskState.Error(e.message ?: "无法加载任务")
            }
        }
    }

    private fun applyTaskDetail(detail: WorkspaceTaskDetail, preferredSessionId: String?) {
        val ordered = orderWorkspaceSessions(detail.sessions)
        val sessionIds = ordered.map { it.id }
        val layout = reconcileTaskWindowLayout(detail.task.layout, sessionIds, preferredSessionId)
        if (ordered.isEmpty()) {
            _taskState.value = WorkspaceTaskState.EmptySessions(detail)
            return
        }
        val preferred = preferredSessionId
            ?.takeIf { sessionIds.contains(it) }
            ?: layout.let { activeWorkWindowTab(it) }
                ?.let { (it as? com.wand.app.data.PaneTab.Session)?.sessionId }
            ?: sessionIds.first()
        _taskState.value = WorkspaceTaskState.Content(
            detail = detail,
            orderedSessions = ordered,
            selectedSessionId = preferred,
            layout = layout,
        )
    }

    /** 本地切换可见会话（不写 activeWindowId，减少与 Web 桌面布局的争用）。 */
    fun selectSession(sessionId: String) {
        val current = _taskState.value as? WorkspaceTaskState.Content ?: return
        if (current.selectedSessionId == sessionId) return
        _taskState.value = current.copy(selectedSessionId = sessionId)
    }

    // ── 工作窗口选择器 ──

    fun openTargetSheet() {
        if (_targetState.value is WorkspaceTargetState.Creating) return
        _targetState.value = WorkspaceTargetState.Selecting
    }

    fun closeTargetSheet() {
        if (_targetState.value is WorkspaceTargetState.Creating) return
        _targetState.value = WorkspaceTargetState.Closed
    }

    /**
     * 创建并绑定 Agent PTY / Shell。创建期间 Sheet 不可 dismiss。
     * 成功后追加新 window 到布局（写一次 PUT），失败提示并保持 Selecting 供重试。
     */
    fun createTaskWindow(
        target: WorkspaceSessionTarget,
        workspaceId: String,
        taskId: String,
        cwd: String,
        onCreated: (SessionSnapshot) -> Unit,
    ) {
        if (_targetState.value is WorkspaceTargetState.Creating) return
        val requestTaskId = taskId
        val requestGeneration = taskGeneration
        _targetState.value = WorkspaceTargetState.Creating
        createJob?.cancel()
        createJob = scope.launch {
            try {
                val session = port.createWorkspaceTaskWindow(
                    target = target,
                    binding = WorkspaceBinding(workspaceId, requestTaskId, cwd),
                )
                // 切任务丢弃延迟响应。
                if (requestTaskId != currentTaskId() || requestGeneration != taskGeneration) return@launch
                // 先追加布局（尽力 PUT，失败不回滚已创建的会话）。
                val baseLayout = (_taskState.value as? WorkspaceTaskState.Content)?.layout
                    ?: TaskWindowLayout.EMPTY
                val nextLayout = addSessionWindow(baseLayout, session.id, activate = true)
                runCatching { port.saveWorkspaceTaskLayout(requestTaskId, nextLayout) }
                // 以任务详情为准重拉，补齐会话与布局（layout PUT 失败时下次刷新自愈）。
                refreshTaskAfterCreate(requestTaskId, session.id)
                _targetState.value = WorkspaceTargetState.Closed
                onCreated(session)
            } catch (e: Exception) {
                if (requestTaskId != currentTaskId() || requestGeneration != taskGeneration) return@launch
                _targetState.value = WorkspaceTargetState.Error(e.message ?: "无法新建工作窗口")
            }
        }
    }

    private suspend fun refreshTaskAfterCreate(taskId: String, createdSessionId: String) {
        try {
            val detail = port.workspaceTask(taskId)
            if (taskId != currentTaskId()) return
            applyTaskDetail(detail, preferredSessionId = createdSessionId)
        } catch (_: Exception) {
            // 详情刷新失败不阻塞创建成功提示；下次 loadTask 补齐。
        }
    }

    private fun currentTaskId(): String? = when (val s = _taskState.value) {
        is WorkspaceTaskState.Content -> s.detail.id
        is WorkspaceTaskState.EmptySessions -> s.detail.id
        else -> null
    }

    /** 当前任务的实际 cwd（创建工作窗口的唯一目录）。 */
    fun currentTaskCwd(): String? = when (val s = _taskState.value) {
        is WorkspaceTaskState.Content -> s.detail.cwd
        is WorkspaceTaskState.EmptySessions -> s.detail.cwd
        else -> null
    }

    /** 当前任务的 workspaceId（创建请求绑定用）。 */
    fun currentWorkspaceId(): String? = when (val s = _taskState.value) {
        is WorkspaceTaskState.Content -> s.detail.workspaceId
        is WorkspaceTaskState.EmptySessions -> s.detail.workspaceId
        else -> null
    }
}
