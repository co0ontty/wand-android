package com.wand.app.ui.screens

import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.ExecutionSubject
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTeamChat
import com.wand.app.data.WorkspaceSessionTeamStep
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
    fun siblingSessionsPreserveUnnamedTaskBoundary() {
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
            listOf("unnamed-1"),
            siblingSessionsFor(groups, taskId = "task-unnamed", sessionId = "unnamed-1").map { it.id },
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
    fun unnamedTasksKeepTheirIdentityAndSessions() {
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

        val tree = directoryTreeGroups(listOf(group)).single()
        assertEquals(listOf("task-1", "task-unnamed"), tree.tasks.map { it.id })
        assertEquals(listOf("loose-1"), tree.tasks.last().sessions.map { it.id })

        assertEquals(
            listOf("legacy-1"),
            directoryTreeGroups(listOf(group)).single().standaloneSessions.map { it.id },
        )
    }

    @Test
    fun directoryTreeHidesCompletedTasksWithoutChangingStoredMembership() {
        val child = session("pty", "pty")
        val completed = task().copy(task = task().task.copy(status = WorkspaceTaskStatus.Done), sessions = listOf(child))
        val active = task().copy(task = task().task.copy(id = "active"))
        val loose = child.copy(id = "loose", status = "exited")
        val source = group().copy(tasks = listOf(completed, active), standaloneSessions = listOf(loose))
        val visible = directoryTreeGroups(listOf(source)).single()
        assertEquals(listOf("active"), visible.tasks.map { it.id })
        assertEquals(listOf(loose), visible.standaloneSessions)
        assertEquals(2, source.tasks.size)
        assertEquals(listOf(child), source.tasks.first().sessions)
        val reopened = completed.copy(task = completed.task.copy(status = WorkspaceTaskStatus.Active))
        assertEquals(listOf(reopened), directoryTreeGroups(listOf(source.copy(tasks = listOf(reopened)))).single().tasks)
        assertTrue(directoryTreeGroups(listOf(source.copy(tasks = listOf(completed)))).single().tasks.isEmpty())
    }

    @Test
    fun legacyGlobalTasksUseNamedFallbackAndKeepServerOrder() {
        val global = group().copy(workspaceId = "wand-global", workspaceName = "全局")
        val project = group()
        // 顺序交给服务端（它按用户拖动保存的顺序返回），客户端不再把未归属压到最后。
        val visible = directoryTreeGroups(listOf(global, project))
        assertEquals(listOf(global.workspaceId, project.workspaceId), visible.map { it.workspaceId })
        assertEquals("未归属工作区", visible.first().workspaceName)
        assertEquals("全局", global.workspaceName)
        assertEquals(global.tasks, visible.first().tasks)
        assertTrue(directoryTreeGroups(listOf(global.copy(tasks = emptyList()))).isEmpty())
        val done = task().copy(task = task().task.copy(status = WorkspaceTaskStatus.Done))
        assertTrue(directoryTreeGroups(listOf(global.copy(tasks = listOf(done)))).isEmpty())
        val loose = session("pty", "pty")
        assertEquals(listOf(loose), directoryTreeGroups(listOf(global.copy(tasks = emptyList(), standaloneSessions = listOf(loose)))).single().standaloneSessions)
    }

    @Test
    fun directoryTreeKeepsEmptyWorkspaces() {
        val empty = group().copy(tasks = emptyList(), standaloneSessions = emptyList())
        assertEquals(listOf(empty), directoryTreeGroups(listOf(empty)))
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
            listOf("Older", "Newer", "Running"),
            directoryTreeGroups(listOf(older, newer, running)).map { it.workspaceName },
        )
    }

    @Test
    fun taskTreeKeepsServerOrderWithoutLocalResorting() {
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

        // 顺序只有一个真源（服务端）：客户端不再按时间/状态二次排序，
        // 已完成的卡片只从会话树里隐藏（看板仍保留）。
        assertEquals(
            listOf("older", "newer"),
            directoryTreeGroups(listOf(groupWithTasks(listOf(older, done, newer))))
                .single()
                .tasks
                .map { it.id },
        )
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
        assertFalse(showsTaskSessionDisclosure(0))
        assertTrue(showsTaskSessionDisclosure(1))
        // 展开档：没有终端的任务默认收起来，只有它是目录里唯一任务时才展开引导建首个会话。
        assertFalse(isTaskSessionsExpanded(HomeFoldMode.Expand, visibleSessionCount = 0, totalSessions = 0))
        assertTrue(
            isTaskSessionsExpanded(
                HomeFoldMode.Expand,
                visibleSessionCount = 0,
                totalSessions = 0,
                isOnlyTask = true,
            ),
        )
        assertTrue(isTaskSessionsExpanded(HomeFoldMode.Expand, visibleSessionCount = 2, totalSessions = 2))
        // 收起档永不展开。
        assertFalse(isTaskSessionsExpanded(HomeFoldMode.Collapse, visibleSessionCount = 2, totalSessions = 2))
        // 在跑档：露出在跑、失败或待处理的会话才展开；一条都没有 → 收成任务行（一级行留着）。
        assertTrue(isTaskSessionsExpanded(HomeFoldMode.Running, visibleSessionCount = 1, totalSessions = 3))
        assertFalse(isTaskSessionsExpanded(HomeFoldMode.Running, visibleSessionCount = 0, totalSessions = 3))
    }

    @Test
    fun listSessionLabelKeepsTitleEvenWhenItRepeatsTheTaskName() {
        val session = session("structured", "structured").copy(title = "重构会话恢复流程")
        assertEquals("重构会话恢复流程", listSessionLabel(session, 0))
    }

    @Test
    fun listSessionLabelFallsBackForDirectoryLeafAndPlaceholderTitles() {
        val leaf = session("pty", null).copy(title = "wand", cwd = "/Users/me/wand")
        assertEquals("Claude 1", listSessionLabel(leaf, 0))
        val placeholder = session("structured", "structured").copy(title = "会话")
        assertEquals("Claude 1", listSessionLabel(placeholder, 0))
        val provider = session("structured", "structured").copy(title = "claude")
        assertEquals("Claude 1", listSessionLabel(provider, 0))
        val already = session("structured", "structured").copy(title = "Claude 1")
        assertEquals("Claude 1", listSessionLabel(already, 0))
    }

    @Test
    fun managedActionKeepsTaskOwnedSessionsOutOfDeletion() {
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
        val resolved = resolveManagedAction(
            SidebarManageSelection(
                taskIds = setOf("task-1"),
                sessionIds = setOf("session-1", "loose-1"),
            ),
            groups,
        )
        assertEquals(setOf("task-1"), resolved.taskIds)
        assertEquals(setOf("loose-1"), resolved.sessionIds)
    }

    /** 任务在批量里是归档，只有终端才真删：动作名 / 危险色 / 确认文案三端一致。 */
    @Test
    fun managedActionDescribesArchiveAndDeletePerSelection() {
        val sessionsOnly = SidebarManageSelection(sessionIds = setOf("loose-1"))
        assertEquals("删除终端", describeManagedAction(sessionsOnly))
        assertTrue(managedSelectionIsDestructive(sessionsOnly))
        assertEquals("将结束所选终端，此操作无法撤销。", describeManagedConfirmMessage(sessionsOnly))

        val tasksOnly = SidebarManageSelection(taskIds = setOf("task-1"))
        assertEquals("归档任务", describeManagedAction(tasksOnly))
        assertFalse(managedSelectionIsDestructive(tasksOnly))
        assertEquals(
            "所选任务会从侧栏隐藏并移入看板归档，终端与 Worktree 都保留。",
            describeManagedConfirmMessage(tasksOnly),
        )

        val both = SidebarManageSelection(taskIds = setOf("task-1"), sessionIds = setOf("loose-1"))
        assertEquals("归档任务并删除终端", describeManagedAction(both))
        assertTrue(managedSelectionIsDestructive(both))
        assertEquals(
            "所选任务会移入看板归档（终端与 Worktree 保留），同时结束所选终端。",
            describeManagedConfirmMessage(both),
        )
    }

    @Test
    fun collapsedRailShowsOneFolderPerDirectoryInTreeOrder() {
        val doneOnly = group().copy(
            workspaceId = "done-dir",
            workspaceName = "DoneDir",
            tasks = listOf(
                task().copy(task = task().task.copy(id = "done", status = WorkspaceTaskStatus.Done)),
            ),
        )
        val alpha = group().copy(workspaceId = "alpha", workspaceName = "Alpha")
        val global = group().copy(
            workspaceId = GLOBAL_WORKSPACE_ID,
            workspaceName = "ignored",
            global = true,
            tasks = emptyList(),
            standaloneSessions = listOf(session("pty", null).copy(id = "loose")),
        )
        val hiddenGlobal = group().copy(
            workspaceId = "empty-global",
            workspaceName = "Empty",
            global = true,
            tasks = emptyList(),
            standaloneSessions = emptyList(),
        )
        val rail = collapsedRailDirectories(listOf(global, doneOnly, hiddenGlobal, alpha))

        // 窄栏顺序与首页一致：直接沿用服务端给的顺序，客户端不再单独重排。
        assertEquals(listOf(GLOBAL_WORKSPACE_ID, "done-dir", "alpha"), rail.map { it.group.workspaceId })
        assertEquals("未归属工作区", rail.first().group.workspaceName)
        assertTrue(rail[1].group.tasks.isEmpty())
    }

    @Test
    fun collapsedRailDirectoryActivityPrefersAttentionOverRunning() {
        val attention = task().copy(
            task = task().task.copy(id = "attention"),
            sessions = listOf(session("structured", "structured").copy(id = "blocked", status = "waiting-input")),
            totalSessions = 1,
        )
        val running = task().copy(
            task = task().task.copy(id = "running"),
            sessions = listOf(session("structured", "structured").copy(id = "busy", inFlight = true)),
            totalSessions = 1,
        )
        val idle = group().copy(workspaceId = "idle", workspaceName = "Idle")
        val mixed = group().copy(workspaceId = "mixed", workspaceName = "Mixed", tasks = listOf(attention, running))
        val runningOnly = group().copy(
            workspaceId = "run",
            workspaceName = "Run",
            tasks = emptyList(),
            standaloneSessions = listOf(session("pty", null).copy(id = "shell", status = "thinking")),
        )
        val rail = collapsedRailDirectories(listOf(idle, mixed, runningOnly))

        assertEquals(listOf(null, "attention", "running"), rail.map { it.activity })
    }

    @Test
    fun directoryContainsSelectionMatchesTaskSessionOrStandalone() {
        val grouped = task().copy(
            sessions = listOf(session("structured", "structured")),
            totalSessions = 1,
        )
        val directory = group().copy(
            tasks = listOf(grouped),
            standaloneSessions = listOf(session("pty", null).copy(id = "loose")),
        )

        assertTrue(directoryContainsSelection(directory, "task-1", null))
        assertTrue(directoryContainsSelection(directory, "task-1", "session-1"))
        assertTrue(directoryContainsSelection(directory, null, "loose"))
        assertFalse(directoryContainsSelection(directory, "other", "other-session"))
        assertFalse(directoryContainsSelection(directory, null, null))
        assertFalse(directoryContainsSelection(directory, null, "  "))
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
        assertEquals("board", HomeListMode.Tasks.storageValue)
        assertEquals("sessions", HomeListMode.Sessions.storageValue)
    }

    @Test
    fun moveTargetsIncludeEmptyAndUnnamedTasksAndNeverMergeMatchingNames() {
        val current = task().copy(sessions = listOf(session("pty", "pty")), totalSessions = 1)
        val empty = task().copy(task = task().task.copy(id = "empty"))
        val unnamed = task().copy(task = task().task.copy(id = "unnamed", name = "未命名任务"))
        val groups = listOf(group().copy(tasks = listOf(current, empty, unnamed)))
        val targets = sessionMoveTargets(groups, "session-1", "")
        assertEquals(listOf("task-1", "empty", "unnamed"), targets.map { it.id })
        assertEquals(listOf(true, false, false), targets.map { it.current })
        assertEquals(2, sessionMoveTargets(groups, "session-1", "fix").size)
        assertEquals(3, sessionMoveTargets(groups, "session-1", " REPO ").size)
        assertTrue(sessionMoveTargets(groups, "session-1", "missing").isEmpty())
        assertFalse(sessionMoveTargets(groups, "ungrouped-session", "").any { it.current })
    }

    @Test
    fun movingUpdatesNavigationAndSiblingsWithoutReplacingSession() {
        val moved = session("pty", "pty")
        val destination = task().copy(task = task().task.copy(id = "destination", name = "Target"), sessions = listOf(moved))
        val groups = listOf(group().copy(tasks = listOf(task(), destination)))
        val nav = com.wand.app.ui.NavState()
        nav.push(Screen.PtyTerminal("session-1", taskId = "task-1"))
        nav.syncTaskMembership(groups)
        val screen = nav.current as Screen.PtyTerminal
        assertEquals("session-1", screen.sessionId)
        assertEquals("destination", screen.taskId)
        assertEquals("Target", screen.taskName)
        assertEquals("workspace-1", screen.workspaceId)
        assertEquals(listOf(moved), siblingSessionsFor(groups, "task-1", "session-1"))
        nav.syncTaskMembership(emptyList())
        assertEquals(screen, nav.current)
    }

    private fun group() = groupWithTasks(listOf(task()))

    @Test
    fun recentConversationsSortBySessionTimeAndKeepTaskContext() {
        val older = session("structured", "codex").copy(id = "older",
            startedAt = "2026-09-28T08:00:00.000Z")
        val newer = session("structured", "codex").copy(id = "newer",
            startedAt = "2026-09-30T08:00:00.000Z")
        val standalone = session("pty", "pty").copy(id = "standalone",
            startedAt = "2026-09-29T08:00:00.000Z")
        val group = groupWithTasks(listOf(task().copy(sessions = listOf(older, newer))))
            .copy(standaloneSessions = listOf(standalone, newer))
        val recent = recentHomeConversations(listOf(group))
        assertEquals(listOf("newer", "standalone", "older"), recent.map { it.session.id })
        assertEquals("task-1", recent.first().task?.id)
        assertEquals(null, recent[1].task)
        assertEquals(listOf("newer", "standalone"),
            recentHomeConversations(listOf(group), limit = 2).map { it.session.id })
    }

    @Test
    fun recentHomeGroupsFoldEmployeeConversationsUnderOneHeader() {
        val older = session("structured", "codex").copy(id = "e-older",
            employeeId = "e-1", employeeName = "虎妞", startedAt = "2026-09-28T08:00:00.000Z")
        val newer = session("structured", "codex").copy(id = "e-newer",
            employeeId = "e-1", employeeName = "虎妞", startedAt = "2026-09-30T08:00:00.000Z")
        val other = session("structured", "codex").copy(id = "other",
            employeeId = "e-2", employeeName = "初二", startedAt = "2026-09-29T08:00:00.000Z")
        val conversations = recentHomeConversations(listOf(groupWithTasks(listOf(
            task().copy(sessions = listOf(older, newer, other))))))

        val groups = recentHomeGroups(conversations)

        assertEquals(listOf("employee:e-1", "employee:e-2", "blank-terminal"),
            groups.map { it.key })
        assertEquals(listOf("e-newer", "e-older"), groups[0].conversations.map { it.session.id })
        assertTrue(groups[0].isCollapsible)
        // 单条会话的组不折叠：一级行本身就是那张会话卡。
        assertFalse(groups[1].isCollapsible)
    }

    @Test
    fun recentHomeGroupsKeepTerminalsApartFromEmployees() {
        val pty = session("pty", "pty").copy(id = "pty-1", provider = "codex",
            startedAt = "2026-09-30T08:00:00.000Z")
        val blank = session("pty", "pty").copy(id = "blank-1", provider = null,
            startedAt = "2026-09-29T08:00:00.000Z")
        val shell = session("pty", "pty").copy(id = "shell-1", provider = "shell",
            startedAt = "2026-09-28T08:00:00.000Z")
        val conversations = recentHomeConversations(listOf(groupWithTasks(listOf(
            task().copy(sessions = listOf(blank, shell, pty))))))

        val groups = recentHomeGroups(conversations)

        assertEquals(listOf("pty", "blank-terminal"), groups.map { it.key })
        assertEquals(listOf("PTY 终端", "空白终端"), groups.map { it.title })
        // 空 provider 与显式 shell 都是「空白终端」，各自不拆散。
        assertEquals(listOf("blank-1", "shell-1"), groups[1].conversations.map { it.session.id })
    }

    @Test
    fun recentHomeGroupsProjectLiveEmployeeIdentity() {
        val older = session("structured", "codex").copy(id = "a", employeeId = "e-1",
            employeeName = "旧名字", employeeAvatar = "old", startedAt = "2026-09-30T08:00:00.000Z")
        val newer = session("structured", "codex").copy(id = "b", employeeId = "e-1",
            employeeName = "旧名字", employeeAvatar = "old", startedAt = "2026-09-29T08:00:00.000Z")
        val conversations = recentHomeConversations(listOf(groupWithTasks(listOf(
            task().copy(sessions = listOf(older, newer))))))

        val renamed = recentHomeGroups(conversations,
            listOf(employee("e-1", name = "赛博虎妞", avatar = "new")))
        assertEquals("赛博虎妞", renamed.first().title)
        assertEquals("new", renamed.first().avatar)
        // 定义被删除：退回会话自己的身份快照，不显示空标题。
        val deleted = recentHomeGroups(conversations)
        assertEquals("旧名字", deleted.first().title)
        assertEquals("old", deleted.first().avatar)
    }

    @Test
    fun homeGroupIdentityUsesTeamThenTerminalFallbacks() {
        val team = session("structured", "codex").copy(id = "team",
            teamChat = WorkspaceSessionTeamChat("run-1", "设计组", 2, "team-1"))

        // 团队一级只放群聊，同一团队的多次开工合成一行；老消息缺 teamId 退回 runId。
        assertEquals("team:team-1", homeGroupKeyOf(team))
        assertEquals("team:run-1", homeGroupKeyOf(team.copy(
            teamChat = team.teamChat?.copy(teamId = null))))
        // 防御：没有员工身份的派发步骤按一次运行归团队，不丢会话。
        assertEquals("team-run:run-1", homeGroupKeyOf(team.copy(
            teamChat = null, teamStep = teamStep("run-1", "虎妞"))))
        assertEquals("pty", homeGroupKeyOf(session("pty", "pty").copy(provider = "claude")))
        assertEquals("blank-terminal", homeGroupKeyOf(session("pty", "pty").copy(provider = null)))
    }

    @Test
    fun recentHomeGroupsKeepTeamDispatchedWorkUnderItsEmployee() {
        val chat = session("structured", "codex").copy(id = "chat",
            startedAt = "2026-09-30T09:00:00.000Z",
            teamChat = WorkspaceSessionTeamChat("run-1", "前端组", 3, "team-1"))
        // 团队派发的成员会话自带 employeeId：它算这位员工的，只在员工下面标出来源团队。
        val member = session("structured", "codex").copy(id = "member", employeeId = "e-1",
            employeeName = "虎妞", startedAt = "2026-09-30T08:00:00.000Z",
            teamStep = teamStep("run-1", "虎妞"))
        val direct = session("structured", "codex").copy(id = "direct", employeeId = "e-1",
            employeeName = "虎妞", startedAt = "2026-09-30T07:00:00.000Z")
        val conversations = recentHomeConversations(listOf(groupWithTasks(listOf(
            task().copy(sessions = listOf(chat, member, direct))))))

        val groups = recentHomeGroups(conversations)

        assertEquals(listOf("team:team-1", "employee:e-1", "blank-terminal"),
            groups.map { it.key })
        assertEquals("前端组", groups[0].title)
        // 派发的成员会话与员工自己的会话同属一行，团队不把它们抢走。
        assertEquals(listOf("member", "direct"), groups[1].conversations.map { it.session.id })
        assertEquals("前端组", groups[1].conversations.first().session.teamStep?.teamName)
    }

    @Test
    fun contactsChooseLatestMatchingHistoryWithoutConfusingCliAndEmployee() {
        val employee = session("structured", "codex").copy(id = "employee",
            employeeId = "e-1", startedAt = "2026-09-30T08:00:00.000Z")
        val team = session("structured", "codex").copy(id = "team",
            teamChat = WorkspaceSessionTeamChat("run-1", "设计组", 2, "team-1"),
            startedAt = "2026-09-29T08:00:00.000Z")
        val cli = session("pty", "pty").copy(id = "cli", provider = "codex",
            startedAt = "2026-09-28T08:00:00.000Z")
        val conversations = recentHomeConversations(listOf(groupWithTasks(listOf(task().copy(
            sessions = listOf(employee, team, cli))))))
        assertEquals("employee", contactConversation(conversations,
            ExecutionSubject.employee("e-1"))?.session?.id)
        assertEquals("team", contactConversation(conversations,
            ExecutionSubject.team("team-1"))?.session?.id)
        assertEquals("cli", contactConversation(conversations,
            ExecutionSubject.cli("codex"))?.session?.id)
        assertNull(contactConversation(conversations, ExecutionSubject.employee("missing")))

        val oldTeamMarker = conversations.map { conversation ->
            if (conversation.session.id == "team") conversation.copy(session = team.copy(
                teamChat = team.teamChat?.copy(teamId = null))) else conversation
        }
        assertEquals("team", contactConversation(oldTeamMarker,
            ExecutionSubject.team("team-1"), setOf("run-1"))?.session?.id)
    }

    @Test
    fun homeGroupShowsHeaderForTeamChatButNotForLoneEmployeeSession() {
        val lone = session("structured", "codex").copy(id = "only", employeeId = "e-1",
            employeeName = "虎妞", startedAt = "2026-09-30T08:00:00.000Z")
        val chat = session("structured", "codex").copy(id = "chat",
            startedAt = "2026-09-30T09:00:00.000Z",
            teamChat = WorkspaceSessionTeamChat("run-1", "前端组", 3, "team-1"))
        val groups = recentHomeGroups(recentHomeConversations(listOf(groupWithTasks(listOf(
            task().copy(sessions = listOf(chat, lone)))))))

        assertEquals(listOf("team:team-1", "employee:e-1", "blank-terminal"),
            groups.map { it.key })
        // 团队：即使只挂一条群聊，也保留「一级 = 团队名」这一行。
        assertFalse(groups[0].isCollapsible)
        assertTrue(homeGroupShowsHeader(groups[0]))
        // 员工单条：一级行就是那张会话卡，不套空壳。
        assertFalse(homeGroupShowsHeader(groups[1]))
    }

    private fun groupWithTasks(tasks: List<WorkspaceTaskSummary>) = TaskDirectoryGroup(
        workspaceId = "workspace-1",
        workspaceName = "Repo",
        workspaceCwd = "/repo",
        synthetic = false,
        tasks = tasks,
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

    private fun employee(id: String, name: String = "虎妞", avatar: String = "cat") =
        com.wand.app.data.SiliconEmployee(
            id = id,
            name = name,
            duty = "",
            prompt = "",
            avatar = avatar,
            agents = emptyList(),
        )

    private fun teamStep(runId: String, memberName: String) = WorkspaceSessionTeamStep(
        runId = runId,
        stepId = "step-$memberName",
        kind = "work",
        title = "任务",
        memberId = "m-1",
        memberName = memberName,
        teamName = "前端组",
        stepStatus = "running",
        runStatus = "running",
        runFinished = false,
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
