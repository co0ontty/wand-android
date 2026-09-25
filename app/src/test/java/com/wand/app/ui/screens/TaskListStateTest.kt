package com.wand.app.ui.screens

import com.wand.app.data.RecentPath
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskCreation
import com.wand.app.data.WorkspaceTaskDetail
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.ServerConfigInfo
import com.wand.app.data.layoutSessionIds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TaskListStateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lateDefaultsCannotReplaceExplicitStructuredChoiceBeforeCreation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val defaults = CompletableDeferred<ServerConfigInfo>()
        val port = FakeWorkspacePort().apply { pendingConfig = defaults }
        val state = TaskListState(port)
        try {
            val loading = async { state.loadCreationDefaults() }
            testScheduler.runCurrent()
            state.rememberCreationChoice("codex", WorkspaceSessionKind.Structured)
            defaults.complete(ServerConfigInfo.parse(JSONObject()
                .put("defaultProvider", "claude").put("defaultSessionKind", "pty")))
            assertTrue(loading.await())
            assertEquals("codex", state.defaultProvider)
            assertEquals(WorkspaceSessionKind.Structured, state.defaultSessionKind)
            assertNotNull(state.createTaskWindow("task-1", WorkspaceSessionTarget.Codex, state.defaultSessionKind))
            assertEquals(WorkspaceSessionTarget.Codex to WorkspaceSessionKind.Structured, port.createdWindowChoices.single())
        } finally {
            state.shutdown()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loadPublishesGroupsAndFailureKeepsCachedSnapshot() = runBlocking {
        val cached = group("ws-1", "/repo")
        val port = FakeWorkspacePort().apply { groups = listOf(cached) }
        val state = TaskListState(port)

        assertTrue(state.load())
        assertSame(cached, state.groups.single())

        port.listGroupsFailure = IllegalStateException("暂时不可用")
        assertFalse(state.load(silent = true))

        assertSame(cached, state.groups.single())
        assertEquals("暂时不可用", state.loadError)
        assertFalse(state.loading)
    }

    @Test
    fun createTaskUnderProjectKeepsWorkspaceId() = runBlocking {
        val existing = workspace("ws-existing", "/repo")
        val port = FakeWorkspacePort().apply { workspaces = mutableListOf(existing) }
        val state = TaskListState(port)

        val result = state.createTask("  修复恢复流程  ", "/repo/", worktree = true, workspaceId = "ws-existing")

        assertSame(existing, result?.workspace)
        assertTrue(port.createdWorkspaces.isEmpty())
        assertTrue(port.standaloneRequests.isEmpty())
        assertEquals(TaskRequest("ws-existing", "修复恢复流程", true), port.taskRequests.single())
        assertNull(state.mutationError)
    }

    @Test
    fun createTaskInNewDirectoryCreatesWorkspaceWithoutWorktree() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        val result = state.createTask("新任务", "/work/wand", worktree = false)

        assertEquals(listOf("wand" to "/work/wand"), port.createdWorkspaces)
        assertEquals(TaskRequest("ws-1", "新任务", false), port.taskRequests.single())
        assertTrue(port.standaloneRequests.isEmpty())
        assertEquals("ws-1", result?.workspace?.id)
        assertEquals("/work/wand", result?.workspace?.cwd)
    }

    @Test
    fun createTaskInExistingDirectoryReusesWorkspaceWithoutMergingNames() = runBlocking {
        val port = FakeWorkspacePort().apply { workspaces = mutableListOf(workspace("existing", "/repo")) }
        val state = TaskListState(port)
        state.createTask("Same", "/repo/", false)
        state.createTask("Same", "/repo", false)
        assertTrue(port.createdWorkspaces.isEmpty())
        assertEquals(2, port.taskRequests.size)
        assertTrue(port.taskRequests.all { it.workspaceId == "existing" && it.worktree == false })
    }

    @Test
    fun createTaskWithoutDirectoryUsesGlobalScratch() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        val result = state.createTask("随口问问", "", worktree = true)

        assertEquals(StandaloneRequest("随口问问", null, false), port.standaloneRequests.single())
        assertEquals("/scratch", result?.task?.cwd)
        assertNull(state.mutationError)
    }

    @Test
    fun creationDefaultsDeduplicateNormalizedRecentPaths() = runBlocking {
        val port = FakeWorkspacePort().apply {
            taskDefault = "/default"
            recent = listOf(
                RecentPath("/repo/", "Repo", null),
                RecentPath("/repo", "Duplicate", null),
                RecentPath("/other", null, null),
            )
        }
        val state = TaskListState(port)

        assertTrue(state.loadCreationDefaults())

        assertEquals("/default", state.defaultCwd)
        assertEquals(listOf("/repo/", "/other"), state.recentPaths.map { it.path })
    }

    @Test
    fun renameDirectoryWritesTheWorkspaceNameAndRefreshesTheList() = runBlocking {
        val port = FakeWorkspacePort().apply {
            groups = listOf(group("ws-1", "/repo"), group("cwd:/loose", "/loose").copy(synthetic = true))
        }
        val state = TaskListState(port)
        assertTrue(state.load())

        assertTrue(state.renameDirectory(port.groups[0], "  核心工作区 "))
        assertTrue(state.renameDirectory(port.groups[1], "临时目录"))

        assertEquals(listOf("ws-1" to "核心工作区"), port.renamedWorkspaces)
        assertEquals(listOf("/loose" to "临时目录"), port.renamedDirectories)
        // 合成目录不能走项目接口。
        assertEquals(1, port.renamedWorkspaces.size)
    }

    @Test
    fun invalidDirectoryNameNeverCallsMutationPort() = runBlocking {
        val port = FakeWorkspacePort().apply { groups = listOf(group("ws-1", "/repo")) }
        val state = TaskListState(port)

        assertFalse(state.renameDirectory(port.groups[0], "   "))

        assertTrue(port.renamedWorkspaces.isEmpty())
        assertTrue(port.renamedDirectories.isEmpty())
    }

    @Test
    fun renameAndDeleteRefreshTheSameAggregateSource() = runBlocking {
        val port = FakeWorkspacePort().apply { groups = listOf(group("ws-1", "/repo")) }
        val state = TaskListState(port)
        assertTrue(state.load())

        val renamed = state.renameTask("task-1", "  新名称 ")
        val cleared = state.clearTaskSessions("task-1")
        val deleted = state.deleteTask("task-1")
        val archived = state.archiveTask("task-1")

        assertEquals("新名称", renamed?.name)
        assertEquals(2, cleared)
        assertTrue(deleted)
        assertTrue(archived)
        assertEquals(listOf("task-1"), port.clearedTaskIds)
        assertEquals(listOf("task-1"), port.deletedTaskIds)
        assertEquals(listOf("task-1"), port.archivedTaskIds)
        assertEquals(5, port.listGroupsCalls)
    }

    @Test
    fun createTaskWindowUsesServerDetailBindingAndPersistsLayout() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        val session = state.createTaskWindow("task-1", WorkspaceSessionTarget.Codex)

        assertEquals("created-session", session?.id)
        assertEquals(
            WorkspaceBinding("ws-1", "task-1", "/repo"),
            port.createdWindowBindings.single(),
        )
        val saved = port.savedLayouts.single().second!!
        assertEquals(listOf("created-session"), saved.windows.flatMap { layoutSessionIds(it.layout) })
    }

    @Test
    fun newTaskRequestsAreConsumedExactlyOnce() {
        val state = TaskListState(FakeWorkspacePort())

        assertFalse(state.consumeNewTaskRequest())
        state.requestNewTask()
        assertTrue(state.consumeNewTaskRequest())
        assertFalse(state.consumeNewTaskRequest())
        state.requestNewTask()
        assertTrue(state.consumeNewTaskRequest())
    }

    @Test
    fun treeExpansionSurvivesNavigationAndRevealsSelectedSession() = runBlocking {
        val selectedSession = WorkspaceSessionSummary(
            id = "session-1",
            provider = "claude",
            sessionKind = "structured",
            runner = "structured",
            title = "Selected",
            status = "idle",
            cwd = "/repo",
            startedAt = null,
        )
        val selectedTask = WorkspaceTaskSummary(
            task = task("task-1", "Task"),
            cwd = "/repo",
            isolated = false,
            worktreeError = null,
            sessions = listOf(selectedSession),
            totalSessions = 1,
        )
        val group = group("ws-1", "/repo").copy(tasks = listOf(selectedTask))
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) })
        assertTrue(state.load())

        state.toggleDirectory(group.id)
        state.toggleTask(selectedTask.id)
        assertTrue(state.isDirectoryCollapsed(group.id))
        assertTrue(state.isTaskCollapsed(selectedTask.id))

        state.expandPathToSelection(taskId = selectedTask.id, sessionId = selectedSession.id)

        assertFalse(state.isDirectoryCollapsed(group.id))
        assertFalse(state.isTaskCollapsed(selectedTask.id))
        state.toggleDirectory(group.id)
        assertTrue(state.isDirectoryCollapsed(group.id))
    }

    @Test
    fun treeExpansionTracksStandaloneSections() = runBlocking {
        val standalone = WorkspaceSessionSummary(
            id = "standalone-1",
            provider = "shell",
            sessionKind = "pty",
            runner = "pty",
            title = null,
            status = "idle",
            cwd = "/repo",
            startedAt = null,
        )
        val group = group("ws-1", "/repo").copy(standaloneSessions = listOf(standalone))
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) })
        assertTrue(state.load())

        state.toggleDirectory(group.id)
        state.toggleStandalone(group.id)
        assertTrue(state.isDirectoryCollapsed(group.id))
        assertTrue(state.isStandaloneCollapsed(group.id))

        state.expandPathToSelection(taskId = null, sessionId = standalone.id)

        assertFalse(state.isDirectoryCollapsed(group.id))
        assertFalse(state.isStandaloneCollapsed(group.id))
    }

    @Test
    fun emptyTaskNameForwardsPromptSoServerCanNameIt() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        // 没起名时把首个提示词交给服务端总结标题；占位名只作为兜底回传，服务端会据提示词改写。
        val result = state.createTask("", "/work/wand", worktree = false, description = "帮我重构会话恢复流程")

        assertNotNull(result)
        assertEquals("新任务", port.taskRequests.single().name)
        assertEquals("帮我重构会话恢复流程", port.taskRequests.single().description)
        assertNull(state.mutationError)
    }

    @Test
    fun createTaskSessionForwardsPromptWithoutRenamingTask() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)
        val session = state.createTaskWindow("task-1", WorkspaceSessionTarget.Claude,
            WorkspaceSessionKind.Structured, prompt = "Investigate tests")
        assertEquals("created-session", session?.id)
        assertEquals("task-1", port.createdWindowBindings.single().workspaceTaskId)
        assertEquals(listOf("Investigate tests"), port.createdWindowPrompts)
        assertTrue(port.renamedTasks.isEmpty())
        assertNull(state.mutationError)
    }

    @Test
    fun unnamedTaskSelectionExpandsItsTaskNotStandaloneSection() = runBlocking {
        val unnamedSession = WorkspaceSessionSummary(
            id = "unnamed-session",
            provider = "claude",
            sessionKind = "structured",
            runner = "structured",
            title = "Loose",
            status = "idle",
            cwd = "/repo",
            startedAt = null,
        )
        val unnamedTask = WorkspaceTaskSummary(
            task = task("task-unnamed", "未命名任务"),
            cwd = "/repo",
            isolated = false,
            worktreeError = null,
            sessions = listOf(unnamedSession),
            totalSessions = 1,
        )
        val group = group("ws-1", "/repo").copy(tasks = listOf(unnamedTask))
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) })
        assertTrue(state.load())

        state.toggleDirectory(group.id)
        state.toggleStandalone(group.id)
        assertTrue(state.isDirectoryCollapsed(group.id))
        assertTrue(state.isStandaloneCollapsed(group.id))

        state.toggleTask(unnamedTask.id)
        state.expandPathToSelection(taskId = unnamedTask.id, sessionId = unnamedSession.id)

        assertFalse(state.isDirectoryCollapsed(group.id))
        assertFalse(state.isTaskCollapsed(unnamedTask.id))
        assertTrue(state.isStandaloneCollapsed(group.id))
    }

    @Test
    fun expansionStateSurvivesStoreReuse() = runBlocking {
        val store = MemoryTaskListExpansionStore()
        val group = group("ws-1", "/repo")
        val first = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) }, store)
        assertTrue(first.load())
        first.toggleDirectory(group.id)
        first.toggleTask("task-1")

        val restored = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) }, store)
        assertTrue(restored.isDirectoryCollapsed(group.id))
        assertTrue(restored.isTaskCollapsed("task-1"))
        assertFalse(restored.isStandaloneCollapsed(group.id))
    }

    @Test
    fun invalidTaskNameNeverCallsMutationPort() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        assertNull(state.createTask("bad\nname", "/repo", worktree = true))
        assertNull(state.renameTask("task-1", ""))

        assertTrue(port.taskRequests.isEmpty())
        assertTrue(port.renamedTasks.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dragSavesOnceOnReleaseAndKeepsLatestOrderAcrossOverlappingSaves() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val firstSave = CompletableDeferred<Unit>()
        val port = FakeWorkspacePort().apply {
            groups = listOf(group("a", "/a"), group("b", "/b"), group("c", "/c"))
            pendingGroupSave = firstSave
        }
        val state = TaskListState(port)
        try {
            state.load()
            state.startDirectoryReorder()
            state.moveDirectory("a", "b")
            state.moveDirectory("a", "c")
            assertEquals(listOf("b", "c", "a"), state.groups.map { it.id })
            assertTrue(port.savedGroupOrders.isEmpty())
            state.finishDirectoryReorder()
            assertEquals(listOf(listOf("b", "c", "a")), port.savedGroupOrders)

            state.startDirectoryReorder()
            state.moveDirectory("c", "b")
            state.finishDirectoryReorder()
            assertEquals(listOf("c", "b", "a"), state.groups.map { it.id })
            // 第一笔还没回来，第二笔不能抢跑、也不能被第一笔的成功清掉。
            assertEquals(1, port.savedGroupOrders.size)
            firstSave.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(listOf(listOf("b", "c", "a"), listOf("c", "b", "a")), port.savedGroupOrders)
            assertEquals(listOf("c", "b", "a"), state.groups.map { it.id })
            assertNull(state.orderSaveError)
        } finally {
            state.shutdown()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun saveFailureSurvivesUnchangedPollingAndCanRetry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val port = FakeWorkspacePort().apply {
            groups = listOf(group("a", "/a"), group("b", "/b"))
            saveOrderFailure = IllegalStateException("服务端未更新")
        }
        val state = TaskListState(port)
        try {
            state.load()
            state.startDirectoryReorder()
            state.moveDirectory("a", "b")
            state.finishDirectoryReorder()
            assertEquals("服务端未更新", state.orderSaveError)
            port.groupsUnchanged = true
            assertTrue(state.load(silent = true))
            assertEquals(listOf("b", "a"), state.groups.map { it.id })
            port.groupsUnchanged = false
            assertTrue(state.load(silent = true))
            assertEquals(listOf("b", "a"), state.groups.map { it.id })
            port.saveOrderFailure = null
            state.retrySaveGroupOrder()
            testScheduler.runCurrent()
            assertEquals(listOf("b", "a"), port.groups.map { it.id })
            assertNull(state.orderSaveError)
        } finally {
            state.shutdown()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledAndUndoneDragsDoNotSendOrder() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val port = FakeWorkspacePort().apply { groups = listOf(group("a", "/a"), group("b", "/b")) }
        val state = TaskListState(port)
        try {
            state.load()
            state.startDirectoryReorder()
            state.moveDirectory("a", "b")
            state.cancelDirectoryReorder()
            assertEquals(listOf("a", "b"), state.groups.map { it.id })
            state.startDirectoryReorder()
            state.moveDirectory("a", "b")
            state.moveDirectory("a", "b")
            state.finishDirectoryReorder()
            assertEquals(listOf("a", "b"), state.groups.map { it.id })
            assertTrue(port.savedGroupOrders.isEmpty())
        } finally {
            state.shutdown()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun deleteDirectoryUsesRequestedCascadeAndRefreshesGroups() = runBlocking {
        val group = group("only-test", "/test")
        val port = FakeWorkspacePort().apply { groups = listOf(group) }
        val state = TaskListState(port)
        assertTrue(state.load())
        assertTrue(state.deleteDirectory(group, cascade = false))
        assertEquals(listOf("only-test" to false), port.deletedWorkspaces)
        assertTrue(state.groups.isEmpty())
    }

    private class FakeWorkspacePort : WorkspacePort {
        var groups: List<TaskDirectoryGroup> = emptyList()
        var groupsUnchanged = false
        val savedGroupOrders = mutableListOf<List<String>>()
        var saveOrderFailure: Exception? = null
        var pendingGroupSave: CompletableDeferred<Unit>? = null
        val deletedWorkspaces = mutableListOf<Pair<String, Boolean>>()
        var listGroupsFailure: Exception? = null
        var listGroupsCalls = 0
        var workspaces = mutableListOf<Workspace>()
        val createdWorkspaces = mutableListOf<Pair<String, String>>()
        var createWorkspaceFailure: Exception? = null
        var workspaceAfterCreateFailure: Workspace? = null
        val taskRequests = mutableListOf<TaskRequest>()
        val standaloneRequests = mutableListOf<StandaloneRequest>()
        val renamedTasks = mutableListOf<Pair<String, String>>()
        val renamedWorkspaces = mutableListOf<Pair<String, String>>()
        val renamedDirectories = mutableListOf<Pair<String, String?>>()
        val clearedTaskIds = mutableListOf<String>()
        val deletedTaskIds = mutableListOf<String>()
        val archivedTaskIds = mutableListOf<String>()
        val createdWindowBindings = mutableListOf<WorkspaceBinding>()
        val createdWindowPrompts = mutableListOf<String?>()
        val createdWindowChoices = mutableListOf<Pair<WorkspaceSessionTarget, WorkspaceSessionKind>>()
        var pendingConfig: CompletableDeferred<ServerConfigInfo>? = null
        val savedLayouts = mutableListOf<Pair<String, TaskWindowLayout?>>()
        var taskDefault: String? = null
        var recent: List<RecentPath> = emptyList()

        override suspend fun serverConfig(): ServerConfigInfo = pendingConfig?.await()
            ?: ServerConfigInfo.parse(JSONObject())

        override suspend fun updateCreationDefaults(
            defaultProvider: String?, defaultSessionKind: String?, defaultTaskWorktree: Boolean?,
        ) = Unit

        override suspend fun listTaskGroups(): List<TaskDirectoryGroup> {
            listGroupsCalls += 1
            listGroupsFailure?.let { throw it }
            return groups
        }

        override suspend fun listTaskGroupsPage(revision: String?): com.wand.app.data.TaskGroupsPage =
            if (groupsUnchanged) com.wand.app.data.TaskGroupsPage(emptyList(), unchanged = true)
            else com.wand.app.data.TaskGroupsPage(listTaskGroups())

        override suspend fun saveWorkspaceGroupOrder(ids: List<String>) {
            savedGroupOrders += ids
            pendingGroupSave?.await()
            saveOrderFailure?.let { throw it }
            groups = applyPendingGroupOrder(groups, ids)
        }

        override suspend fun deleteWorkspace(workspaceId: String, cascade: Boolean) {
            deletedWorkspaces += workspaceId to cascade
            groups = groups.filterNot { it.id == workspaceId }
        }

        override suspend fun listWorkspaces(): List<Workspace> = workspaces.toList()

        override suspend fun createWorkspace(name: String, cwd: String): Workspace {
            createdWorkspaces += name to cwd
            createWorkspaceFailure?.let { failure ->
                workspaceAfterCreateFailure?.let { if (workspaces.none { item -> item.id == it.id }) workspaces += it }
                throw failure
            }
            return workspace("ws-${workspaces.size + 1}", cwd).also(workspaces::add)
        }

        override suspend fun createWorkspaceTask(
            workspaceId: String,
            name: String,
            baseRef: String?,
            worktree: Boolean?,
            cwd: String?,
            description: String?,
        ): WorkspaceTaskCreation {
            taskRequests += TaskRequest(workspaceId, name, worktree, cwd, description)
            return WorkspaceTaskCreation(
                id = "task-${taskRequests.size}",
                workspaceId = workspaceId,
                name = name,
                worktree = null,
                status = WorkspaceTaskStatus.Active,
                cwd = cwd.orEmpty().ifEmpty { "/scratch" },
            )
        }

        override suspend fun createStandaloneTask(
            name: String,
            cwd: String?,
            worktree: Boolean?,
            description: String?,
        ): WorkspaceTaskCreation {
            standaloneRequests += StandaloneRequest(name, cwd, worktree, description)
            return WorkspaceTaskCreation(
                id = "task-standalone-${standaloneRequests.size}",
                workspaceId = "wand-global",
                name = name,
                worktree = null,
                status = WorkspaceTaskStatus.Active,
                cwd = cwd.orEmpty().ifEmpty { "/scratch" },
            )
        }

        override suspend fun taskDefaultCwd(): String? = taskDefault

        override suspend fun recentTaskPaths(): List<RecentPath> = recent

        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask {
            renamedTasks += taskId to name
            return task(taskId, name)
        }

        override suspend fun renameWorkspace(workspaceId: String, name: String): Workspace {
            renamedWorkspaces += workspaceId to name
            return workspace(workspaceId, "/repo").copy(name = name)
        }

        override suspend fun renameSessionDirectory(cwd: String, name: String?) {
            renamedDirectories += cwd to name
        }

        override suspend fun clearWorkspaceTaskSessions(taskId: String): Int {
            clearedTaskIds += taskId
            return 2
        }

        override suspend fun deleteWorkspaceTask(taskId: String) {
            deletedTaskIds += taskId
        }

        override suspend fun archiveWorkspaceTask(taskId: String): WorkspaceTask {
            archivedTaskIds += taskId
            return task(taskId, "Task")
        }

        override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> = emptyList()

        override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail =
            WorkspaceTaskDetail(task(taskId, "Task"), "/repo", false, null, emptyList())

        override suspend fun saveWorkspaceTaskLayout(
            taskId: String,
            layout: TaskWindowLayout?,
        ): TaskWindowLayout? {
            savedLayouts += taskId to layout
            return layout
        }

        override suspend fun createWorkspaceTaskWindow(
            target: WorkspaceSessionTarget,
            binding: WorkspaceBinding,
            kind: WorkspaceSessionKind,
            prompt: String?,
        ): SessionSnapshot {
            createdWindowBindings += binding
            createdWindowPrompts += prompt
            createdWindowChoices += target to kind
            return SessionSnapshot.parse(JSONObject().put("id", "created-session"))
        }
    }

    private data class TaskRequest(
        val workspaceId: String,
        val name: String,
        val worktree: Boolean?,
        val cwd: String? = null,
        val description: String? = null,
    )

    private data class StandaloneRequest(
        val name: String,
        val cwd: String?,
        val worktree: Boolean?,
        val description: String? = null,
    )

    companion object {
        private fun workspace(id: String, cwd: String) = Workspace(
            id = id,
            name = id,
            cwd = cwd,
            defaultProvider = null,
            layout = null,
            createdAt = null,
            lastOpenedAt = null,
        )

        private fun task(id: String, name: String) = WorkspaceTask(
            id = id,
            workspaceId = "ws-1",
            name = name,
            worktree = null,
            layout = null,
            status = WorkspaceTaskStatus.Active,
            createdAt = null,
            lastOpenedAt = null,
        )

        private fun group(id: String, cwd: String) = TaskDirectoryGroup(
            workspaceId = id,
            workspaceName = id,
            workspaceCwd = cwd,
            synthetic = false,
            tasks = emptyList(),
            standaloneSessions = emptyList(),
        )
    }
}
