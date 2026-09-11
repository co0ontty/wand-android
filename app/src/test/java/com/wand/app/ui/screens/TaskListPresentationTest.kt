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
    fun collapsedIdCodecRoundTripsAndDropsBlanks() {
        assertEquals("a\nb", encodeCollapsedIds(listOf("b", "a", "")))
        assertEquals(setOf("a", "b"), decodeCollapsedIds("b\n a \n\n"))
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
    fun siblingSessionsForTaskUsesTaskWindows() {
        val taskSessions = listOf(
            session("structured", "structured").copy(id = "a"),
            session("pty", null).copy(id = "b"),
        )
        val groups = listOf(
            group().copy(tasks = listOf(task().copy(sessions = taskSessions, totalSessions = 2))),
        )

        assertEquals(
            listOf("a", "b"),
            siblingSessionsFor(groups, taskId = "task-1", sessionId = "a").map { it.id },
        )
    }

    @Test
    fun siblingSessionsForStandaloneUsesDirectoryLooseSessions() {
        val loose = listOf(
            session("structured", "structured").copy(id = "loose-1"),
            session("pty", null).copy(id = "loose-2"),
        )
        val groups = listOf(group().copy(tasks = emptyList(), standaloneSessions = loose))

        assertEquals(
            listOf("loose-1", "loose-2"),
            siblingSessionsFor(groups, taskId = null, sessionId = "loose-1").map { it.id },
        )
        assertTrue(siblingSessionsFor(groups, taskId = null, sessionId = "missing").isEmpty())
        assertTrue(siblingSessionsFor(groups, taskId = null, sessionId = null).isEmpty())
    }

    @Test
    fun siblingSessionsForStandaloneIncludesFlattenedUnnamedTaskSessions() {
        val loose = session("pty", null).copy(id = "loose-1")
        val unnamed = task().copy(
            task = task().task.copy(id = "task-unnamed", name = "未命名任务"),
            sessions = listOf(session("structured", "structured").copy(id = "unnamed-1")),
            totalSessions = 1,
        )
        val groups = listOf(
            group().copy(tasks = listOf(unnamed), standaloneSessions = listOf(loose)),
        )

        assertEquals(
            listOf("loose-1", "unnamed-1"),
            siblingSessionsFor(groups, taskId = null, sessionId = "unnamed-1").map { it.id },
        )
    }

    @Test
    fun standaloneSessionsAlsoAnimateSwipeTransition() {
        val sessions = listOf(
            session("structured", "structured").copy(id = "loose-1"),
            session("pty", null).copy(id = "loose-2"),
        )

        assertEquals(
            1,
            taskSessionTransitionDirection(
                Screen.Chat("loose-1"),
                Screen.PtyTerminal("loose-2"),
                sessions,
            ),
        )
        assertEquals(
            -1,
            taskSessionTransitionDirection(
                Screen.PtyTerminal("loose-2"),
                Screen.Chat("loose-1"),
                sessions,
            ),
        )
        assertNull(
            taskSessionTransitionDirection(
                Screen.Chat("loose-1"),
                Screen.PtyTerminal("other"),
                sessions,
            ),
        )
    }

    @Test
    fun unnamedTasksFlattenIntoDirectoryStandaloneSessions() {
        val named = task()
        val unnamedSession = session("pty", null).copy(id = "loose-1", title = "Loose")
        val unnamed = task().copy(
            task = task().task.copy(id = "task-unnamed", name = "未命名任务"),
            sessions = listOf(unnamedSession),
            totalSessions = 1,
        )
        val existingStandalone = session("structured", "structured").copy(id = "legacy-1")
        val group = group().copy(
            tasks = listOf(named, unnamed),
            standaloneSessions = listOf(existingStandalone),
        )

        val flattened = flattenUnnamedTasksIntoStandalone(group)
        assertEquals(listOf("task-1"), flattened.tasks.map { it.id })
        assertEquals(listOf("legacy-1", "loose-1"), flattened.standaloneSessions.map { it.id })

        assertEquals(
            listOf("legacy-1", "loose-1"),
            directoryTreeGroups(listOf(group)).single().standaloneSessions.map { it.id },
        )
    }

    @Test
    fun directoryTreeKeepsCreatedOrderWithNewFoldersFirst() {
        val older = group().copy(
            workspaceId = "older",
            workspaceName = "Older",
            createdAt = "2026-01-01T00:00:00Z",
            tasks = listOf(task().copy(task = task().task.copy(lastOpenedAt = "2026-06-01T00:00:00Z"))),
        )
        val newer = group().copy(
            workspaceId = "newer",
            workspaceName = "Newer",
            createdAt = "2026-05-01T00:00:00Z",
            tasks = listOf(task().copy(task = task().task.copy(lastOpenedAt = "2026-02-01T00:00:00Z"))),
        )
        val running = group().copy(
            workspaceId = "running",
            workspaceName = "Running",
            createdAt = "2026-03-01T00:00:00Z",
            standaloneSessions = listOf(session("structured", "structured").copy(status = "running")),
        )

        assertEquals(
            listOf("Newer", "Running", "Older"),
            directoryTreeGroups(listOf(older, newer, running)).map { it.workspaceName },
        )
    }

    @Test
    fun taskTreeKeepsCreatedOrderWithNewTasksFirst() {
        val done = task().copy(
            task = task().task.copy(
                id = "done",
                status = WorkspaceTaskStatus.Done,
                createdAt = "2026-04-01T00:00:00Z",
                lastOpenedAt = "2026-06-01T00:00:00Z",
            ),
        )
        val older = task().copy(
            task = task().task.copy(
                id = "older",
                createdAt = "2026-01-01T00:00:00Z",
                lastOpenedAt = "2026-05-01T00:00:00Z",
            ),
        )
        val newer = task().copy(
            task = task().task.copy(
                id = "newer",
                createdAt = "2026-03-01T00:00:00Z",
                lastOpenedAt = "2026-02-01T00:00:00Z",
            ),
            sessions = listOf(session("structured", "structured").copy(status = "running")),
        )

        assertEquals(
            listOf("done", "newer", "older"),
            orderedTaskSummaries(listOf(older, done, newer)).map { it.id },
        )
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
    fun managedDeleteCascadesTaskSessionsAndKeepsLooseTerminals() {
        val groups = listOf(
            group().copy(
                tasks = listOf(
                    task().copy(
                        sessions = listOf(
                            session("structured", "structured").copy(id = "session-1"),
                            session("pty", null).copy(id = "session-2"),
                        ),
                        totalSessions = 2,
                    ),
                    task().copy(
                        task = task().task.copy(id = "task-2", name = "Docs"),
                        sessions = listOf(session("pty", null).copy(id = "session-3")),
                        totalSessions = 1,
                    ),
                ),
                standaloneSessions = listOf(session("pty", null).copy(id = "loose-1")),
            ),
        )
        val resolved = resolveManagedDeletion(
            SidebarManageSelection(
                taskIds = setOf("task-1"),
                sessionIds = setOf("session-1", "loose-1"),
            ),
            groups,
        )
        assertEquals(setOf("task-1"), resolved.taskIds)
        assertEquals(setOf("loose-1"), resolved.sessionIds)
        assertEquals("1 个任务和1 个终端", describeManagedDeletion(resolved))
    }

    @Test
    fun collapsedRailPrefersActiveAndAttentionTasks() {
        val idle = task().copy(task = task().task.copy(id = "idle", name = "旧任务", lastOpenedAt = "2026-09-01T00:00:00Z"))
        val active = task().copy(task = task().task.copy(id = "active", name = "当前", lastOpenedAt = "2026-09-08T00:00:00Z"))
        val attention = task().copy(
            task = task().task.copy(id = "attention", name = "待处理", lastOpenedAt = "2026-09-07T00:00:00Z"),
            sessions = listOf(session("structured", "structured").copy(id = "blocked", status = "waiting-input")),
            totalSessions = 1,
        )
        val running = task().copy(
            task = task().task.copy(id = "running", name = "运行中", lastOpenedAt = "2026-09-06T00:00:00Z"),
            sessions = listOf(session("structured", "structured").copy(id = "busy", inFlight = true)),
            totalSessions = 1,
        )
        val rail = collapsedRailTasks(
            listOf(group().copy(tasks = listOf(idle, active, attention, running))),
            activeTaskId = "active",
            limit = 3,
        )
        assertEquals(listOf("active", "attention", "running"), rail.items.map { it.task.id })
        assertEquals(1, rail.overflow)
    }

    @Test
    fun structuredRunnerFallbackRoutesToChat() {
        val route = taskSessionRoute(session(null, "structured"), group(), task())

        assertTrue(route.structured)
    }

    @Test
    fun homeListModeParsesBoardAsTasksAndKeepsTreeAsDefault() {
        assertEquals(HomeListMode.Sessions, HomeListMode.fromStorage(null))
        assertEquals(HomeListMode.Sessions, HomeListMode.fromStorage("tasks"))
        assertEquals(HomeListMode.Sessions, HomeListMode.fromStorage("sessions"))
        assertEquals(HomeListMode.Tasks, HomeListMode.fromStorage("board"))
        assertEquals("任务模式", HomeListMode.Tasks.label)
        assertEquals("会话模式", HomeListMode.Sessions.label)
        assertEquals(HomeListMode.Tasks, HomeListMode.Sessions.next)
        assertEquals(HomeListMode.Sessions, HomeListMode.Tasks.next)
        assertEquals("board", HomeListMode.Tasks.storageValue)
        assertEquals("sessions", HomeListMode.Sessions.storageValue)
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
