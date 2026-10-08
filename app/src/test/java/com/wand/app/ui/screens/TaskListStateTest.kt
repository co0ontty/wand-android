package com.wand.app.ui.screens

import com.wand.app.data.RecentPath
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.WandAgentEngine
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceSessionSummary
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
    fun creationChoiceRemembersTheEngineAndRestoresItFromConfig() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val port = FakeWorkspacePort()
        val state = TaskListState(port)
        try {
            // 选了 Wand Agent：provider 仍是 pi，引擎单独记。
            state.rememberCreationChoice(defaultProvider = "pi", defaultEngine = WandAgentEngine.Sdk.raw)
            assertEquals("pi", state.defaultProvider)
            assertEquals("sdk", state.defaultEngine)
            assertEquals("sdk", port.rememberedEngine)

            // 服务端带着引擎回来时，恢复出来的目标必须是 Wand Agent 而不是 Pi CLI。
            port.pendingConfig = CompletableDeferred(ServerConfigInfo.parse(JSONObject()
                .put("defaultProvider", "pi").put("defaultEngine", "sdk")))
            val restored = TaskListState(port)
            assertTrue(restored.loadCreationDefaults())
            assertEquals("sdk", restored.defaultEngine)
            assertEquals(
                WorkspaceSessionTarget.WandAgent,
                WorkspaceSessionTarget.fromPreference(restored.defaultProvider, restored.defaultEngine),
            )

            // 老服务端没有该字段：按 Pi CLI 处理，不冒充 Wand Agent。
            assertEquals(
                WorkspaceSessionTarget.Pi,
                WorkspaceSessionTarget.fromPreference("pi", null),
            )
            restored.shutdown()
        } finally {
            state.shutdown()
            Dispatchers.resetMain()
        }
    }

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
                .put("defaultProvider", "claude").put("defaultSessionKind", "pty")
                .put("defaultThinkingEffort", "max")))
            assertTrue(loading.await())
            assertEquals("codex", state.defaultProvider)
            assertEquals(WorkspaceSessionKind.Structured, state.defaultSessionKind)
            assertEquals("max", state.defaultThinkingEffort)
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
    fun createChildTaskPassesParentIdToBothCreationRoutes() = runBlocking {
        val workspace = workspace("ws-existing", "/repo")
        val port = FakeWorkspacePort().apply { workspaces += workspace }
        val state = TaskListState(port)

        state.createTask("子任务", "/repo", false, workspaceId = workspace.id, parentTaskId = "board-parent")
        state.createTask("全局子任务", "", false, parentTaskId = "global-parent")

        assertEquals("board-parent", port.taskRequests.single().parentTaskId)
        assertEquals("global-parent", port.standaloneRequests.single().parentTaskId)
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
    fun ungroupedStartCarriesDirectoryOnlyBindingAndBuildsNoCard() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        val session = state.createUngroupedSession(
            workspaceId = "ws-1",
            cwd = " /repo ",
            target = WorkspaceSessionTarget.Codex,
            kind = WorkspaceSessionKind.Pty,
            prompt = "先看一眼",
            model = "gpt-5",
            thinkingEffort = "low",
        )

        assertEquals("created-session", session?.id)
        // 只带目录与项目归属、没有 workspaceTaskId —— 这条会话落进侧栏「未分组任务」。
        assertEquals(WorkspaceBinding("ws-1", null, "/repo"), port.createdWindowBindings.single())
        assertEquals(listOf(WorkspaceSessionTarget.Codex to WorkspaceSessionKind.Pty), port.createdWindowChoices)
        assertEquals(listOf("先看一眼"), port.createdWindowPrompts)
        assertTrue("没有指定任务就不建卡", port.taskRequests.isEmpty() && port.standaloneRequests.isEmpty())
        assertTrue("未分组会话没有任务布局可改", port.savedLayouts.isEmpty())
    }

    @Test
    fun ungroupedEmployeeStartDropsGlobalScratchBinding() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        val session = state.createUngroupedSession(
            workspaceId = com.wand.app.data.GLOBAL_WORKSPACE_ID,
            cwd = "/scratch",
            target = WorkspaceSessionTarget.Claude,
            employeeId = "e-1",
        )

        assertEquals("created-employee-session", session?.id)
        assertTrue(port.createdWindowBindings.isEmpty())
        val request = port.employeeWindowRequests.single()
        assertEquals("e-1", request.employeeId)
        assertNull("全局暂存区不写成项目归属", request.binding.workspaceId)
        assertNull(request.binding.workspaceTaskId)
        assertEquals("/scratch", request.binding.cwd)
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
    fun sectionFoldsDefaultToExpandAndPersistPerSection() = runBlocking {
        val group = group("ws-1", "/repo")
        val store = MemoryTaskListExpansionStore()
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) }, store)
        assertTrue(state.load())

        assertEquals(HomeFoldMode.Expand, state.sectionFold(FOLD_SECTION_RECENT))
        assertEquals(HomeFoldMode.Expand, state.sectionFold(FOLD_SECTION_WORKSPACE))

        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Running)
        state.selectSectionFold(FOLD_SECTION_RECENT, HomeFoldMode.Collapse)

        assertEquals(HomeFoldMode.Running, state.workspaceFold)
        assertEquals(HomeFoldMode.Collapse, state.recentFold)
        // 两个区各存各的：改一个不影响另一个。
        val restored = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) }, store)
        assertEquals(HomeFoldMode.Running, restored.sectionFold(FOLD_SECTION_WORKSPACE))
        assertEquals(HomeFoldMode.Collapse, restored.sectionFold(FOLD_SECTION_RECENT))
    }

    @Test
    fun reselectingTheSameSectionFoldKeepsItAndOtherSectionsUntouched() = runBlocking {
        val group = group("ws-1", "/repo")
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) })
        assertTrue(state.load())

        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Collapse)
        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Collapse)

        assertEquals(HomeFoldMode.Collapse, state.workspaceFold)
        assertEquals(HomeFoldMode.Expand, state.recentFold)
    }

    @Test
    fun draggingAWorkspaceCollapsesTheWorkspaceSection() = runBlocking {
        val group = group("a", "/a").copy(tasks = listOf(WorkspaceTaskSummary(
            task = task("task-1", "任务"),
            cwd = "/a",
            isolated = false,
            worktreeError = null,
            sessions = emptyList(),
            totalSessions = 0,
        )))
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) })
        assertTrue(state.load())
        assertEquals(HomeFoldMode.Expand, state.workspaceFold)

        // 拿起卡片 = 这一区收成标题行（收放只有小节表头那一个入口）。
        state.startDirectoryReorder(group.id)

        assertEquals(HomeFoldMode.Collapse, state.workspaceFold)
        assertEquals(HomeFoldMode.Expand, state.recentFold)
    }

    @Test
    fun sectionFoldCanStillPersistExpand() = runBlocking {
        val group = group("ws-1", "/repo")
        val state = TaskListState(FakeWorkspacePort().apply { groups = listOf(group) })
        assertTrue(state.load())

        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Collapse)
        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Expand)

        assertEquals(HomeFoldMode.Expand, state.workspaceFold)
    }

    @Test
    fun temporaryExpansionRevealsOnlyChosenGroupAndNeverPersists() = runBlocking {
        for (section in listOf(FOLD_SECTION_RECENT, FOLD_SECTION_WORKSPACE)) {
            for (mode in listOf(HomeFoldMode.Collapse, HomeFoldMode.Running)) {
                val store = MemoryTaskListExpansionStore()
                val state = TaskListState(FakeWorkspacePort(), store)
                state.selectSectionFold(section, mode)
                state.toggleTemporaryExpansion(section, "chosen")

                assertEquals(HomeFoldMode.Expand, state.groupFold(section, "chosen"))
                assertEquals(mode, state.groupFold(section, "other"))
                assertEquals(mode, state.sectionFold(section))
                assertEquals(mode, store.sectionFold(section))
                assertEquals(mode, TaskListState(FakeWorkspacePort(), store).groupFold(section, "chosen"))
                // 后台刷新不打断正在查找；离页清理后重新跟随全局。
                assertTrue(state.load(silent = true))
                assertEquals(HomeFoldMode.Expand, state.groupFold(section, "chosen"))
                state.clearTemporaryExpansions()
                assertFalse(state.hasTemporaryExpansion)
                assertEquals(mode, state.groupFold(section, "chosen"))
            }
        }
    }

    @Test
    fun repeatedHeaderClickAndExplicitModeSelectionRestoreGlobalDisplay() {
        val state = TaskListState(FakeWorkspacePort())
        state.selectSectionFold(FOLD_SECTION_RECENT, HomeFoldMode.Running)
        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Collapse)
        state.toggleTemporaryExpansion(FOLD_SECTION_RECENT, "same-id")
        state.toggleTemporaryExpansion(FOLD_SECTION_WORKSPACE, "same-id")
        state.toggleTemporaryExpansion(FOLD_SECTION_RECENT, "same-id")
        assertEquals(HomeFoldMode.Running, state.groupFold(FOLD_SECTION_RECENT, "same-id"))
        assertEquals(HomeFoldMode.Expand, state.groupFold(FOLD_SECTION_WORKSPACE, "same-id"))

        // 再点已选中的全局档位，也应取消这一区的临时覆盖。
        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Collapse)
        assertFalse(state.hasTemporaryExpansion)
        assertEquals(HomeFoldMode.Collapse, state.groupFold(FOLD_SECTION_WORKSPACE, "same-id"))
        state.selectSectionFold(FOLD_SECTION_RECENT, HomeFoldMode.Expand)
        state.toggleTemporaryExpansion(FOLD_SECTION_RECENT, "same-id")
        assertFalse(state.hasTemporaryExpansion)
    }

    @Test
    fun temporaryExpandRestoresIdleSessionsAndEmptyTasksHiddenByRunningMode() {
        val state = TaskListState(FakeWorkspacePort())
        state.selectSectionFold(FOLD_SECTION_WORKSPACE, HomeFoldMode.Running)
        val idle = WorkspaceSessionSummary(
            id = "idle", provider = "claude", sessionKind = "structured", runner = null,
            title = "空闲会话", status = "idle", cwd = "/repo", startedAt = null, inFlight = false,
        )
        val tasks = listOf(
            WorkspaceTaskSummary(task("task-1", "有会话"), "/repo", false, null, listOf(idle), 1),
            WorkspaceTaskSummary(task("task-2", "待办"), "/repo", false, null, emptyList(), 0),
        )
        assertTrue(foldVisibleTasks(state.groupFold(FOLD_SECTION_WORKSPACE, "ws"), tasks).isEmpty())
        state.toggleTemporaryExpansion(FOLD_SECTION_WORKSPACE, "ws")
        val fold = state.groupFold(FOLD_SECTION_WORKSPACE, "ws")
        assertEquals(tasks, foldVisibleTasks(fold, tasks))
        assertEquals(listOf(idle), foldVisibleSessions(fold, listOf(idle)))
        assertTrue(isTaskSessionsExpanded(fold, 1, 1))
        state.clearTemporaryExpansions()
        assertTrue(foldVisibleTasks(state.groupFold(FOLD_SECTION_WORKSPACE, "ws"), tasks).isEmpty())
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
            state.startDirectoryReorder("a")
            state.moveDirectory("a", "b")
            state.moveDirectory("a", "c")
            assertEquals(listOf("b", "c", "a"), state.groups.map { it.id })
            assertTrue(port.savedGroupOrders.isEmpty())
            state.finishDirectoryReorder()
            assertEquals(listOf(listOf("b", "c", "a")), port.savedGroupOrders)

            state.startDirectoryReorder("c")
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
            state.startDirectoryReorder("a")
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
            state.startDirectoryReorder("a")
            state.moveDirectory("a", "b")
            state.cancelDirectoryReorder()
            assertEquals(listOf("a", "b"), state.groups.map { it.id })
            state.startDirectoryReorder("a")
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
        val createdWindowModels = mutableListOf<String?>()
        val createdWindowEfforts = mutableListOf<String?>()
        val createdWindowChoices = mutableListOf<Pair<WorkspaceSessionTarget, WorkspaceSessionKind>>()
        val employeeWindowRequests = mutableListOf<EmployeeWindowRequest>()
        var pendingConfig: CompletableDeferred<ServerConfigInfo>? = null
        val savedLayouts = mutableListOf<Pair<String, TaskWindowLayout?>>()
        var taskDefault: String? = null
        var recent: List<RecentPath> = emptyList()
        /** 最近一次写回服务端的默认执行引擎。 */
        var rememberedEngine: String? = null

        override suspend fun serverConfig(): ServerConfigInfo = pendingConfig?.await()
            ?: ServerConfigInfo.parse(JSONObject())

        override suspend fun updateCreationDefaults(
            defaultProvider: String?, defaultSessionKind: String?, defaultTaskWorktree: Boolean?,
            defaultEngine: String?,
        ) {
            rememberedEngine = defaultEngine
        }

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
            parentTaskId: String?,
        ): WorkspaceTaskCreation {
            taskRequests += TaskRequest(workspaceId, name, worktree, cwd, description, parentTaskId)
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
            parentTaskId: String?,
        ): WorkspaceTaskCreation {
            standaloneRequests += StandaloneRequest(name, cwd, worktree, description, parentTaskId)
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

        override suspend fun createEmployeeWorkspaceTaskWindow(
            employeeId: String,
            binding: WorkspaceBinding,
            prompt: String?,
        ): SessionSnapshot {
            employeeWindowRequests += EmployeeWindowRequest(employeeId, binding, prompt)
            return SessionSnapshot.parse(JSONObject().put("id", "created-employee-session"))
        }

        override suspend fun createWorkspaceTaskWindow(
            target: WorkspaceSessionTarget,
            binding: WorkspaceBinding,
            kind: WorkspaceSessionKind,
            prompt: String?,
            model: String?,
            thinkingEffort: String?,
        ): SessionSnapshot {
            createdWindowBindings += binding
            createdWindowPrompts += prompt
            createdWindowModels += model
            createdWindowEfforts += thinkingEffort
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
        val parentTaskId: String? = null,
    )

    private data class StandaloneRequest(
        val name: String,
        val cwd: String?,
        val worktree: Boolean?,
        val description: String? = null,
        val parentTaskId: String? = null,
    )

    private data class EmployeeWindowRequest(
        val employeeId: String,
        val binding: WorkspaceBinding,
        val prompt: String?,
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
