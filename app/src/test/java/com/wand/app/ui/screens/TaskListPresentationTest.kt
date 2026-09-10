package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.ui.Screen
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
    fun horizontalSwipeSelectsAdjacentTaskSession() {
        val sessions = listOf(
            session("structured", "structured").copy(id = "session-1"),
            session("pty", null).copy(id = "session-2"),
            session("structured", "structured").copy(id = "session-3"),
        )

        assertEquals("session-2", taskSessionSwipeTarget(sessions, "session-1", -80f)?.id)
        assertEquals("session-1", taskSessionSwipeTarget(sessions, "session-2", 80f)?.id)
        assertNull(taskSessionSwipeTarget(sessions, "session-1", -40f))
        assertNull(taskSessionSwipeTarget(sessions, "session-3", -80f))
        assertNull(taskSessionSwipeTarget(sessions, "missing", 80f))
    }

    @Test
    fun taskSessionTransitionDirectionMatchesTabMovement() {
        val sessions = listOf(
            session("structured", "structured").copy(id = "session-1"),
            session("pty", null).copy(id = "session-2"),
            session("structured", "structured").copy(id = "session-3"),
        )

        assertEquals(
            1,
            taskSessionTransitionDirection(
                Screen.Chat("session-1", taskId = "task-1"),
                Screen.PtyTerminal("session-2", taskId = "task-1"),
                sessions,
            ),
        )
        assertEquals(
            -1,
            taskSessionTransitionDirection(
                Screen.Chat("session-3", taskId = "task-1"),
                Screen.Chat("session-2", taskId = "task-1"),
                sessions,
            ),
        )
        assertNull(
            taskSessionTransitionDirection(
                Screen.Chat("session-1", taskId = "task-1"),
                Screen.Chat("session-2", taskId = "task-2"),
                sessions,
            ),
        )
    }

    @Test
    fun directoryTreePutsLiveAndRecentlyOpenedDirectoriesFirst() {
        val idle = group().copy(
            workspaceId = "idle",
            workspaceName = "Idle",
            tasks = listOf(task().copy(task = task().task.copy(lastOpenedAt = "2026-01-01T00:00:00Z"))),
        )
        val recent = group().copy(
            workspaceId = "recent",
            workspaceName = "Recent",
            tasks = listOf(task().copy(task = task().task.copy(lastOpenedAt = "2026-03-01T00:00:00Z"))),
        )
        val active = group().copy(
            workspaceId = "active",
            workspaceName = "Active",
            standaloneSessions = listOf(session("structured", "structured").copy(status = "running")),
        )

        assertEquals(
            listOf("Active", "Recent", "Idle"),
            directoryTreeGroups(listOf(idle, recent, active)).map { it.workspaceName },
        )
    }

    @Test
    fun taskTreeKeepsLiveTasksAboveDoneTasksAndUsesRecentTimeAsTieBreaker() {
        val done = task().copy(
            task = task().task.copy(
                id = "done",
                status = WorkspaceTaskStatus.Done,
                lastOpenedAt = "2026-04-01T00:00:00Z",
            ),
        )
        val activeOld = task().copy(
            task = task().task.copy(
                id = "active-old",
                lastOpenedAt = "2026-01-01T00:00:00Z",
            ),
        )
        val activeRecent = task().copy(
            task = task().task.copy(
                id = "active-recent",
                lastOpenedAt = "2026-03-01T00:00:00Z",
            ),
            sessions = listOf(session("structured", "structured").copy(status = "running")),
        )

        assertEquals(
            listOf("active-recent", "active-old", "done"),
            orderedTaskSummaries(listOf(done, activeOld, activeRecent)).map { it.id },
        )
    }

    @Test
    fun homeMetricsDeduplicateDirectoryAliasesAndTaskIds() {
        val duplicateDirectory = group().copy(
            workspaceId = "workspace-2",
            workspaceName = "Repo alias",
            workspaceCwd = "/repo/",
            tasks = listOf(
                task().copy(totalSessions = 2),
                task().copy(
                    task = task().task.copy(id = "task-2", name = "Review"),
                    totalSessions = 1,
                ),
            ),
            standaloneSessions = listOf(session("pty", null)),
        )

        assertEquals(
            TaskListMetrics(directoryCount = 1, taskCount = 2, sessionCount = 4),
            taskListMetrics(listOf(group(), duplicateDirectory)),
        )
        assertEquals(
            1,
            taskListMetrics(
                listOf(
                    group().copy(workspaceCwd = "/"),
                    group().copy(workspaceId = "workspace-3", workspaceCwd = "///"),
                ),
            ).directoryCount,
        )
    }

    @Test
    fun homeSummaryExplainsDirectoryWithOnlyLegacySessions() {
        val metrics = TaskListMetrics(directoryCount = 1, taskCount = 0, sessionCount = 2)

        assertEquals("1 个目录 · 暂无任务", homeTaskSummaryLabel(metrics))
        assertEquals("按工作目录整理你的任务", homeTaskSummaryLabel(metrics.copy(directoryCount = 0)))
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
    fun taskRowSelectionYieldsToVisibleChildSession() {
        val visible = listOf("session-1")

        assertTrue(
            isTaskRowSelected(
                taskId = "task-1",
                visibleSessionIds = visible,
                selectedTaskId = "task-1",
                selectedSessionId = null,
            ),
        )
        assertFalse(
            isTaskRowSelected(
                taskId = "task-1",
                visibleSessionIds = visible,
                selectedTaskId = "task-1",
                selectedSessionId = "session-1",
            ),
        )
        assertTrue(
            isTaskRowSelected(
                taskId = "task-1",
                visibleSessionIds = visible,
                selectedTaskId = "task-1",
                selectedSessionId = "session-missing",
            ),
        )
        assertFalse(
            isTaskRowSelected(
                taskId = "task-1",
                visibleSessionIds = visible,
                selectedTaskId = "task-other",
                selectedSessionId = null,
            ),
        )
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
