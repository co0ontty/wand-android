package com.wand.app.ui.workspaces

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskDetail
import com.wand.app.data.WorkspaceTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceWorkflowTest {

    private val mainDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun emptyTaskDetail(taskId: String = "task-1", workspaceId: String = "ws-1"): WorkspaceTaskDetail {
        val task = WorkspaceTask(
            id = taskId, workspaceId = workspaceId, name = "Test Task",
            worktree = null, layout = null, status = WorkspaceTaskStatus.Active,
            createdAt = null, lastOpenedAt = null,
        )
        return WorkspaceTaskDetail(task, "/cwd/task", false, null, emptyList())
    }

    private fun taskDetailWithSessions(
        taskId: String = "task-1",
        workspaceId: String = "ws-1",
        sessions: List<String>,
    ): WorkspaceTaskDetail {
        val summaries = sessions.mapIndexed { i, sid ->
            WorkspaceSessionSummary(sid, "claude", "pty", null, null, "idle", "/dir", "2026-0${i + 1}-01T00:00:00Z")
        }
        val task = WorkspaceTask(
            id = taskId, workspaceId = workspaceId, name = "Task With Sessions",
            worktree = null, layout = null, status = WorkspaceTaskStatus.Active,
            createdAt = null, lastOpenedAt = null,
        )
        return WorkspaceTaskDetail(task, "/cwd/task", false, null, summaries)
    }

    private fun testScope(): CoroutineScope =
        CoroutineScope(mainDispatcher + SupervisorJob())

    // MARK: - 空任务不自动创建会话

    @Test
    fun loadTask_emptySessions_showsWelcomeState_noCreateCalls() = runBlocking {
        val fake = FakeWorkspacePort().apply { taskDetail = emptyTaskDetail() }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue(workflow.taskState.value is WorkspaceTaskState.EmptySessions)
        assertEquals(0, fake.createCalls.size)
    }

    @Test
    fun loadTask_emptySessions_doesNotTriggerPost() = runBlocking {
        val fake = FakeWorkspacePort().apply { taskDetail = emptyTaskDetail() }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fake.createCalls.size)
        assertEquals(0, fake.saveLayoutCalls.size)
    }

    // MARK: - 已有会话恢复

    @Test
    fun loadTask_withSessions_showsContent_selectsFirst() = runBlocking {
        val fake = FakeWorkspacePort().apply {
            taskDetail = taskDetailWithSessions(sessions = listOf("s1", "s2"))
        }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        val state = workflow.taskState.value as WorkspaceTaskState.Content
        assertEquals(2, state.orderedSessions.size)
        assertEquals("s1", state.selectedSessionId)
    }

    // MARK: - 切任务丢弃旧结果

    @Test
    fun loadTask_switchingTask_discardsStaleResult() = runBlocking {
        val fake = FakeWorkspacePort()
        val workflow = WorkspaceWorkflow(fake, testScope())
        fake.taskDetail = emptyTaskDetail("task-1")
        workflow.loadTask("task-1")
        fake.taskDetail = taskDetailWithSessions(taskId = "task-2", sessions = listOf("sX"))
        workflow.loadTask("task-2")
        mainDispatcher.scheduler.advanceUntilIdle()

        val state = workflow.taskState.value
        assertTrue(state is WorkspaceTaskState.Content)
        assertEquals("task-2", (state as WorkspaceTaskState.Content).detail.id)
    }

    // MARK: - 创建流程

    @Test
    fun createTaskWindow_shell_sendsShellBinding() = runBlocking {
        val fake = FakeWorkspacePort().apply { taskDetail = emptyTaskDetail() }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        workflow.openTargetSheet()
        var created: SessionSnapshot? = null
        workflow.createTaskWindow(
            target = WorkspaceSessionTarget.Shell,
            workspaceId = "ws-1", taskId = "task-1", cwd = "/cwd/task",
        ) { created = it }
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fake.createCalls.size)
        val call = fake.createCalls.first()
        assertEquals(WorkspaceSessionTarget.Shell, call.first)
        assertEquals("ws-1", call.second.workspaceId)
        assertEquals("task-1", call.second.workspaceTaskId)
        assertEquals("/cwd/task", call.second.cwd)
        assertEquals(1, fake.saveLayoutCalls.size)
        assertNotNull(created)
    }

    @Test
    fun createTaskWindow_claude_sendsPtyBinding() = runBlocking {
        val fake = FakeWorkspacePort().apply { taskDetail = emptyTaskDetail() }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        workflow.createTaskWindow(
            target = WorkspaceSessionTarget.Claude,
            workspaceId = "ws-1", taskId = "task-1", cwd = "/cwd/task",
        ) {}
        mainDispatcher.scheduler.advanceUntilIdle()

        val call = fake.createCalls.first()
        assertEquals(WorkspaceSessionTarget.Claude, call.first)
        assertEquals("ws-1", call.second.workspaceId)
    }

    @Test
    fun createTaskWindow_qoder_sendsQoderBinding() = runBlocking {
        val fake = FakeWorkspacePort().apply { taskDetail = emptyTaskDetail() }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        workflow.createTaskWindow(
            target = WorkspaceSessionTarget.Qoder,
            workspaceId = "ws-1", taskId = "task-1", cwd = "/cwd",
        ) {}
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WorkspaceSessionTarget.Qoder, fake.createCalls.first().first)
    }

    // MARK: - Target Sheet 状态

    @Test
    fun openTargetSheet_setsSelectingState() {
        val workflow = WorkspaceWorkflow(FakeWorkspacePort(), testScope())
        workflow.openTargetSheet()
        assertTrue(workflow.targetState.value is WorkspaceTargetState.Selecting)
    }

    @Test
    fun closeTargetSheet_setsClosedState() {
        val workflow = WorkspaceWorkflow(FakeWorkspacePort(), testScope())
        workflow.openTargetSheet()
        workflow.closeTargetSheet()
        assertEquals(WorkspaceTargetState.Closed, workflow.targetState.value)
    }

    // MARK: - selectSession 本地切换

    @Test
    fun selectSession_updatesSelectedWithoutServerWrite() = runBlocking {
        val fake = FakeWorkspacePort().apply {
            taskDetail = taskDetailWithSessions(sessions = listOf("s1", "s2"))
        }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        workflow.selectSession("s2")
        val state = workflow.taskState.value as WorkspaceTaskState.Content
        assertEquals("s2", state.selectedSessionId)
        assertEquals(0, fake.saveLayoutCalls.size)
    }

    @Test
    fun deleteSession_surfacesPortError() = runBlocking {
        val fake = FakeWorkspacePort().apply {
            taskDetail = taskDetailWithSessions(sessions = listOf("s1", "s2"))
            deleteError = IllegalStateException("session busy")
        }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        var error: String? = null
        var deleted = false
        workflow.deleteSession("s1", onDeleted = { deleted = true }, onError = { error = it })
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("session busy", error)
        assertTrue(!deleted)
        assertEquals("s1", (workflow.taskState.value as WorkspaceTaskState.Content).selectedSessionId)
    }

    // MARK: - currentTaskCwd / currentWorkspaceId

    @Test
    fun currentTaskCwd_returnsDetailCwd() = runBlocking {
        val fake = FakeWorkspacePort().apply { taskDetail = emptyTaskDetail() }
        val workflow = WorkspaceWorkflow(fake, testScope())
        workflow.loadTask("task-1")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("/cwd/task", workflow.currentTaskCwd())
        assertEquals("ws-1", workflow.currentWorkspaceId())
    }

    // MARK: - Fake

    private class FakeWorkspacePort : WorkspacePort {
        var taskDetail: WorkspaceTaskDetail = WorkspaceTaskDetail(
            WorkspaceTask("t", "w", "n", null, null, WorkspaceTaskStatus.Active, null, null),
            "", false, null, emptyList(),
        )
        val createCalls = mutableListOf<Pair<WorkspaceSessionTarget, WorkspaceBinding>>()
        val saveLayoutCalls = mutableListOf<Pair<String, TaskWindowLayout?>>()
        var nextSessionId = "new-1"
        var deleteError: Throwable? = null

        override suspend fun listWorkspaces(): List<Workspace> = emptyList()
        override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> = emptyList()
        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask =
            taskDetail.task.copy(name = name)
        override suspend fun deleteWorkspaceTask(taskId: String) = Unit
        override suspend fun deleteWorkspaceSessions(sessionIds: List<String>): Int {
            deleteError?.let { throw it }
            taskDetail = taskDetail.copy(
                sessions = taskDetail.sessions.filterNot { sessionIds.contains(it.id) },
            )
            return sessionIds.size
        }
        override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail = taskDetail
        override suspend fun saveWorkspaceTaskLayout(taskId: String, layout: TaskWindowLayout?): TaskWindowLayout? {
            saveLayoutCalls += taskId to layout
            return layout
        }
        override suspend fun createWorkspaceTaskWindow(
            target: WorkspaceSessionTarget,
            binding: WorkspaceBinding,
            kind: WorkspaceSessionKind,
        ): SessionSnapshot {
            createCalls += target to binding
            return SessionSnapshot(
                id = nextSessionId,
                sessionKind = "pty",
                provider = if (target.isShell) null else target.raw,
                runner = null, command = null, cwd = binding.cwd, mode = null,
                status = "idle", exitCode = null, startedAt = null, endedAt = null,
                archived = null, summary = null, currentTaskTitle = null,
                selectedModel = null, thinkingEffort = null, claudeSessionId = null,
                messages = null, queuedMessages = null, structuredState = null,
                pendingEscalation = null, permissionBlocked = null,
                autoApprovePermissions = null,
                workspaceId = binding.workspaceId, workspaceTaskId = binding.workspaceTaskId,
            )
        }
    }
}
