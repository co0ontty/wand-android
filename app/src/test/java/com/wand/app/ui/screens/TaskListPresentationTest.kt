package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskListPresentationTest {
    @Test
    fun taskSessionRouteCarriesStableTaskIdentity() {
        val group = group()
        val task = task()

        val route = taskSessionRoute(session("structured", "structured"), group, task)

        assertEquals("session-1", route.sessionId)
        assertTrue(route.structured)
        assertEquals("workspace-1", route.workspaceId)
        assertEquals("task-1", route.taskId)
        assertEquals("Repo", route.workspaceName)
        assertEquals("Fix", route.taskName)
    }

    @Test
    fun standaloneRouteRemainsReachableWithoutInventingTaskBinding() {
        val route = taskSessionRoute(session("pty", null), group(), null)

        assertFalse(route.structured)
        assertNull(route.workspaceId)
        assertNull(route.taskId)
        assertNull(route.workspaceName)
        assertNull(route.taskName)
    }

    @Test
    fun directoryPathKeepsLeafAndDropsHomePrefix() {
        assertEquals("…/vibe_coding/wand", shortenWorkspacePath("/Users/me/Self/vibe_coding/wand"))
        assertEquals("/tmp/wand", shortenWorkspacePath("/tmp/wand"))
        assertEquals(null, directoryPathCaption("wand", "wand"))
        assertEquals("…/vibe_coding/wand", directoryPathCaption("wand", "/Users/me/Self/vibe_coding/wand"))
    }

    @Test
    fun taskIsolationNeverSaysSharedDirectory() {
        assertEquals("共享", taskIsolationCaption(false))
        assertEquals("隔离", taskIsolationCaption(true, "wand/ui"))
    }

    @Test
    fun listSessionLabelAvoidsRepeatingDirectoryName() {
        val session = session("pty", null).copy(title = "wand", cwd = "/Users/me/wand")
        assertEquals("Claude 1", listSessionLabel(session, 0, listOf("wand")))
    }

    @Test
    fun structuredRunnerFallbackRoutesToChat() {
        val route = taskSessionRoute(session(null, "structured"), group(), task())

        assertTrue(route.structured)
    }

    private fun group() = TaskDirectoryGroup(
        workspaceId = "workspace-1",
        workspaceName = "Repo",
        workspaceCwd = "/repo",
        synthetic = false,
        tasks = listOf(task()),
        standaloneSessions = emptyList(),
    )

    private fun task() = WorkspaceTaskSummary(
        task = WorkspaceTask(
            id = "task-1",
            workspaceId = "workspace-1",
            name = "Fix",
            worktree = null,
            layout = null,
            status = WorkspaceTaskStatus.Active,
            createdAt = null,
            lastOpenedAt = null,
        ),
        cwd = "/repo",
        isolated = false,
        worktreeError = null,
        sessions = emptyList(),
        totalSessions = 0,
    )

    private fun session(kind: String?, runner: String?) = WorkspaceSessionSummary(
        id = "session-1",
        provider = "claude",
        sessionKind = kind,
        runner = runner,
        title = "Session",
        status = "idle",
        cwd = "/repo",
        startedAt = null,
    )
}
