package com.wand.app.ui.screens

import com.wand.app.data.RecentPath
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskCreation
import com.wand.app.data.WorkspaceTaskDetail
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.layoutSessionIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TaskListStateTest {
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
    fun createTaskReusesWorkspaceAfterNormalizingTrailingSlash() = runBlocking {
        val existing = workspace("ws-existing", "/repo")
        val port = FakeWorkspacePort().apply { workspaces = mutableListOf(existing) }
        val state = TaskListState(port)

        val result = state.createTask("  修复恢复流程  ", "/repo/", worktree = true)

        assertSame(existing, result?.workspace)
        assertTrue(port.createdWorkspaces.isEmpty())
        assertEquals(TaskRequest("ws-existing", "修复恢复流程", true), port.taskRequests.single())
        assertNull(state.mutationError)
    }

    @Test
    fun createTaskCreatesImplicitWorkspaceFromDirectoryName() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        val result = state.createTask("新任务", "/work/wand", worktree = false)

        assertEquals("wand", port.createdWorkspaces.single().first)
        assertEquals("/work/wand", port.createdWorkspaces.single().second)
        assertEquals(port.workspaces.single().id, result?.workspace?.id)
        assertEquals(false, port.taskRequests.single().worktree)
    }

    @Test
    fun createTaskRecoversConcurrentWorkspaceCreation() = runBlocking {
        val raced = workspace("ws-raced", "/repo")
        val port = FakeWorkspacePort().apply {
            createWorkspaceFailure = IllegalStateException("已存在")
            workspaceAfterCreateFailure = raced
        }
        val state = TaskListState(port)

        val result = state.createTask("任务", "/repo", worktree = true)

        assertSame(raced, result?.workspace)
        assertEquals("ws-raced", port.taskRequests.single().workspaceId)
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
    fun renameAndDeleteRefreshTheSameAggregateSource() = runBlocking {
        val port = FakeWorkspacePort().apply { groups = listOf(group("ws-1", "/repo")) }
        val state = TaskListState(port)
        assertTrue(state.load())

        val renamed = state.renameTask("task-1", "  新名称 ")
        val cleared = state.clearTaskSessions("task-1")
        val deleted = state.deleteTask("task-1")

        assertEquals("新名称", renamed?.name)
        assertEquals(2, cleared)
        assertTrue(deleted)
        assertEquals(listOf("task-1"), port.clearedTaskIds)
        assertEquals(listOf("task-1"), port.deletedTaskIds)
        assertEquals(4, port.listGroupsCalls)
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
    fun invalidTaskNameNeverCallsMutationPort() = runBlocking {
        val port = FakeWorkspacePort()
        val state = TaskListState(port)

        assertNull(state.createTask("bad\nname", "/repo", worktree = true))
        assertNull(state.renameTask("task-1", ""))

        assertTrue(port.taskRequests.isEmpty())
        assertTrue(port.renamedTasks.isEmpty())
    }

    private class FakeWorkspacePort : WorkspacePort {
        var groups: List<TaskDirectoryGroup> = emptyList()
        var listGroupsFailure: Exception? = null
        var listGroupsCalls = 0
        var workspaces = mutableListOf<Workspace>()
        val createdWorkspaces = mutableListOf<Pair<String, String>>()
        var createWorkspaceFailure: Exception? = null
        var workspaceAfterCreateFailure: Workspace? = null
        val taskRequests = mutableListOf<TaskRequest>()
        val renamedTasks = mutableListOf<Pair<String, String>>()
        val clearedTaskIds = mutableListOf<String>()
        val deletedTaskIds = mutableListOf<String>()
        val createdWindowBindings = mutableListOf<WorkspaceBinding>()
        val savedLayouts = mutableListOf<Pair<String, TaskWindowLayout?>>()
        var taskDefault: String? = null
        var recent: List<RecentPath> = emptyList()

        override suspend fun listTaskGroups(): List<TaskDirectoryGroup> {
            listGroupsCalls += 1
            listGroupsFailure?.let { throw it }
            return groups
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
        ): WorkspaceTaskCreation {
            taskRequests += TaskRequest(workspaceId, name, worktree)
            return WorkspaceTaskCreation(
                id = "task-${taskRequests.size}",
                workspaceId = workspaceId,
                name = name,
                worktree = null,
                status = WorkspaceTaskStatus.Active,
            )
        }

        override suspend fun taskDefaultCwd(): String? = taskDefault

        override suspend fun recentTaskPaths(): List<RecentPath> = recent

        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask {
            renamedTasks += taskId to name
            return task(taskId, name)
        }

        override suspend fun clearWorkspaceTaskSessions(taskId: String): Int {
            clearedTaskIds += taskId
            return 2
        }

        override suspend fun deleteWorkspaceTask(taskId: String) {
            deletedTaskIds += taskId
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
        ): SessionSnapshot {
            createdWindowBindings += binding
            return SessionSnapshot.parse(JSONObject().put("id", "created-session"))
        }
    }

    private data class TaskRequest(
        val workspaceId: String,
        val name: String,
        val worktree: Boolean?,
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
