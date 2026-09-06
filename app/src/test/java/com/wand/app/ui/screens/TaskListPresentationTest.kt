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
    fun fitWorkspacePathExpandsWhenSpaceAllowsAndKeepsLastTwo() {
        val path = "/Users/me/Self/vibe_coding/wand"
        val measure = { text: String -> text.length.toFloat() }

        assertEquals(path, fitWorkspacePath(path, 1000f, measure))
        assertEquals(
            "…/Self/vibe_coding/wand",
            fitWorkspacePath(path, "…/Self/vibe_coding/wand".length.toFloat(), measure),
        )
        assertEquals(
            "…/vibe_coding/wand",
            fitWorkspacePath(path, "…/vibe_coding/wand".length.toFloat(), measure),
        )
        assertEquals("…/vibe_coding/wand", fitWorkspacePath(path, 1f, measure))
        assertEquals("/tmp/wand", fitWorkspacePath("/tmp/wand", 1f, measure))

        assertNull(fitDirectoryPathCaption("wand", "wand", 1000f, measure))
        assertEquals(path, fitDirectoryPathCaption("wand", path, 1000f, measure))
        assertEquals(
            "…/vibe_coding/wand",
            fitDirectoryPathCaption("wand", path, "…/vibe_coding/wand".length.toFloat(), measure),
        )
    }

    @Test
    fun directoryGroupMetaLabelMatchesIosCountFormat() {
        assertEquals("2 任务 · 5 会话", directoryGroupMetaLabel(2, 5))
        assertEquals("0 任务 · 0 会话", directoryGroupMetaLabel(0, 0))
        val grouped = group().copy(
            tasks = listOf(
                task().copy(totalSessions = 3),
                task().copy(
                    task = task().task.copy(id = "task-2", name = "Review"),
                    totalSessions = 1,
                ),
            ),
            standaloneSessions = listOf(session("pty", null)),
        )
        assertEquals(5, directoryGroupSessionTotal(grouped))
        assertEquals("2 任务 · 5 会话", directoryGroupMetaLabel(grouped.tasks.size, directoryGroupSessionTotal(grouped)))
    }

    @Test
    fun taskIsolationOmitsDefaultSharedLabel() {
        assertNull(taskIsolationCaption(false))
        assertEquals("隔离", taskIsolationCaption(true, "wand/ui"))
    }

    @Test
    fun treeDisclosureHidesNeedlessCaretsAndKeepsTerminalsOpen() {
        assertFalse(showsDirectoryDisclosure(1))
        assertTrue(showsDirectoryDisclosure(2))
        assertTrue(isDirectoryExpanded(userCollapsed = true, directoryCount = 1))
        assertFalse(isDirectoryExpanded(userCollapsed = true, directoryCount = 2))
        assertTrue(isDirectoryExpanded(userCollapsed = false, directoryCount = 2))

        assertFalse(showsTaskSessionDisclosure(0))
        assertTrue(showsTaskSessionDisclosure(1))
        assertTrue(isTaskSessionsExpanded(userCollapsed = true, sessionCount = 0))
        assertFalse(isTaskSessionsExpanded(userCollapsed = true, sessionCount = 2))
        assertTrue(isTaskSessionsExpanded(userCollapsed = false, sessionCount = 2))
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
