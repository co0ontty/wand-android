package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.ui.SessionTitleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomePresentationTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @After
    fun tearDown() {
        // 权限态是全局 overlay，用例之间不能互相串。
        SessionTitleStore.clear()
    }

    @Test
    fun pulseSeparatesRunningFromWaitingAndQuiet() {
        assertEquals(HomeSessionPulse.Running, homeSessionPulse("running"))
        assertEquals(HomeSessionPulse.Running, homeSessionPulse("thinking"))
        // 重连只是链路抖动，任务还在推进，不该催促用户。
        assertEquals(HomeSessionPulse.Running, homeSessionPulse("reconnecting"))
        assertEquals(HomeSessionPulse.NeedsYou, homeSessionPulse("permission"))
        assertEquals(HomeSessionPulse.NeedsYou, homeSessionPulse("permission-blocked"))
        assertEquals(HomeSessionPulse.NeedsYou, homeSessionPulse("waiting-input"))
        assertEquals(HomeSessionPulse.NeedsYou, homeSessionPulse("waiting_input"))
        assertEquals(HomeSessionPulse.NeedsYou, homeSessionPulse("failed"))
        assertEquals(HomeSessionPulse.Quiet, homeSessionPulse("idle"))
        assertEquals(HomeSessionPulse.Quiet, homeSessionPulse("exited"))
        assertEquals(HomeSessionPulse.Quiet, homeSessionPulse(null))
    }

    @Test
    fun pulseAlsoHonoursTheLivePermissionOverlay() {
        val structured = session("a", "running")

        // 结构化会话「在跑」要看 inFlight；权限弹窗到了则一律算等你。
        assertEquals(HomeSessionPulse.Quiet, sessionPulse(structured))
        assertEquals(HomeSessionPulse.Running, sessionPulse(structured.copy(inFlight = true)))
        SessionTitleStore.apply("a", permissionBlocked = true)
        assertEquals(HomeSessionPulse.NeedsYou, sessionPulse(structured.copy(inFlight = true)))
        SessionTitleStore.apply("a", permissionBlocked = false)
        assertEquals(HomeSessionPulse.Running, sessionPulse(structured.copy(inFlight = true)))
    }

    @Test
    fun overviewCountsSessionsAcrossTasksAndStandaloneRows() {
        val groups = listOf(
            group(
                id = "workspace-1",
                tasks = listOf(
                    task(
                        "task-1",
                        sessions = listOf(
                            session("a", "running").copy(inFlight = true),
                            session("b", "idle"),
                        ),
                    ),
                    task("task-4", sessions = listOf(session("g", "idle"))),
                ),
                standalone = listOf(session("c", "idle")),
            ),
            // 空目录只是没会话，不该把计数拉高。
            group(id = "workspace-2"),
        )

        val overview = homeOverview(groups)

        assertEquals(1, overview.running)
        assertEquals(0, overview.needsYou)
        assertEquals(4, overview.sessions)
        assertFalse(overview.isQuiet)
    }

    @Test
    fun overviewReportsQuietWhenNothingIsRunningOrWaiting() {
        val overview = homeOverview(listOf(group(id = "wand-global")))

        assertEquals(0, overview.sessions)
        assertTrue(overview.isQuiet)
    }

    @Test
    fun padLandingPrioritizesAttentionThenRunningAndLimitsRows() {
        val groups = listOf(group(
            tasks = listOf(task("task-1", sessions = listOf(
                session("quiet-1", "idle"),
                session("running", "running").copy(inFlight = true),
                session("attention", "permission"),
                session("quiet-2", "idle"),
                session("quiet-3", "idle"),
            ))),
        ))

        val rows = padLandingSessions(groups)

        assertEquals(listOf("attention", "running", "quiet-1", "quiet-2"), rows.map { it.session.id })
        assertEquals("task-1", rows.first().task?.id)
    }

    @Test
    fun attentionFilterDropsEmptyContainersAndKeepsOnlyWaitingSessions() {
        val groups = listOf(
            group(
                id = "workspace-1",
                tasks = listOf(
                    task("task-1", sessions = listOf(session("a", "permission"), session("b", "idle"))),
                    task("task-2", sessions = listOf(session("c", "running").copy(inFlight = true))),
                ),
                standalone = listOf(session("d", "permission"), session("e", "exited")),
            ),
            group(
                id = "workspace-2",
                tasks = listOf(task("task-3", sessions = listOf(session("f", "running").copy(inFlight = true)))),
            ),
        )

        val filtered = attentionOnlyGroups(groups)

        assertEquals(1, filtered.size)
        assertEquals("workspace-1", filtered.single().workspaceId)
        assertEquals(listOf("task-1"), filtered.single().tasks.map { it.id })
        assertEquals(listOf("a"), filtered.single().tasks.single().sessions.map { it.id })
        // totalSessions 要跟着过滤后的可见数量走，否则行尾会显示一个点不开的数字。
        assertEquals(1, filtered.single().tasks.single().totalSessions)
        assertEquals(listOf("d"), filtered.single().standaloneSessions.map { it.id })
    }

    @Test
    fun movedItemShiftsNeighboursInsteadOfSwapping() {
        // 把 0 拖到 2：中间两项要整体上移，而不是和目标互换。
        assertEquals(listOf("b", "c", "a"), movedItem(listOf("a", "b", "c"), from = 0, to = 2))
        assertEquals(listOf("c", "a", "b"), movedItem(listOf("a", "b", "c"), from = 2, to = 0))
        // 原地不动 / 越界都不产生新列表，调用方据此判断"不用发保存请求"。
        val items = listOf("a", "b")
        assertTrue(movedItem(items, 1, 1) === items)
        assertTrue(movedItem(items, 0, 5) === items)
        assertTrue(movedItem(items, -1, 1) === items)
    }

    @Test
    fun pendingOrderWinsOverServerOrderUntilItIsSaved() {
        val groups = listOf(
            group(id = "a"),
            group(id = "b"),
            group(id = "c"),
        )

        // 服务端还按 a,b,c 返回，本地已经拖成 c,a,b：中间这一刻必须按本地渲染。
        val pending = applyPendingGroupOrder(groups, listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), pending.map { it.id })
        // 没设置过顺序时原样返回（不复制列表，避免白白触发一次重绘）。
        assertTrue(applyPendingGroupOrder(groups, null) === groups)
        assertTrue(applyPendingGroupOrder(groups, emptyList()) === groups)
    }

    @Test
    fun pendingOrderKeepsUnknownGroupsAfterTheKnownOnes() {
        val groups = listOf(group(id = "a"), group(id = "new"), group(id = "b"))

        val ordered = applyPendingGroupOrder(groups, listOf("b", "a"))

        // 后建的 "new" 不在待保存顺序里，跟着排在后面，而不是被丢掉或插到前面。
        assertEquals(listOf("b", "a", "new"), ordered.map { it.id })
    }

    @Test
    fun groupIdsInOrderFeedsThePersistedPayload() {
        assertEquals(
            listOf("wand-global", "cwd:/tmp/x", "workspace-1"),
            groupIdsInOrder(listOf(group(id = "wand-global"), group(id = "cwd:/tmp/x"), group(id = "workspace-1"))),
        )
    }

    @Test
    fun relativeTimeFoldsRecentDeltasIntoHumanWords() {
        assertEquals("刚刚", sessionRelativeTime("2026-09-25T11:59:40Z", now, utc))
        assertEquals("刚刚", sessionRelativeTime("2026-09-25T12:00:00Z", now, utc))
        assertEquals("3 分钟前", sessionRelativeTime("2026-09-25T11:57:00Z", now, utc))
        assertEquals("2 小时前", sessionRelativeTime("2026-09-25T10:00:00Z", now, utc))
        assertEquals("昨天", sessionRelativeTime("2026-09-24T09:00:00Z", now, utc))
        assertEquals("3 天前", sessionRelativeTime("2026-09-22T12:00:00Z", now, utc))
        assertEquals("9 月 1 日", sessionRelativeTime("2026-09-01T12:00:00Z", now, utc))
    }

    @Test
    fun relativeTimeNeverShowsNegativeTimeWhenDeviceClockLags() {
        assertEquals("刚刚", sessionRelativeTime("2026-09-25T12:05:00Z", now, utc))
    }

    @Test
    fun relativeTimeReturnsNullForUnparseableInput() {
        assertNull(sessionRelativeTime(null, now, utc))
        assertNull(sessionRelativeTime("", now, utc))
        assertNull(sessionRelativeTime("not-a-date", now, utc))
    }

    @Test
    fun metaLineJoinsFormAndTimeAndDegradesCleanly() {
        val session = session("a", "running").copy(sessionKind = "structured")

        assertEquals("结构化 · 5 分钟前", sessionMetaLine(session, now))
        assertEquals("结构化", sessionMetaLine(session.copy(startedAt = null), now))
        assertEquals("PTY 终端", sessionMetaLine(session.copy(sessionKind = "pty", provider = "claude"), now).substringBefore(" · "))
        assertEquals("空白终端", sessionMetaLine(session.copy(sessionKind = "pty", provider = null), now).substringBefore(" · "))
    }

    @Test
    fun workspaceSummarySegmentsCarrySemanticTonesWithoutRepeatingCounts() {
        val segments = workspaceSummarySegments(3, HomeSessionCounts(running = 1, needsYou = 2))

        assertEquals(listOf("3 个会话", "1 运行中", "2 待处理"), segments.map { it.text })
        assertEquals(
            listOf(HomeSummaryTone.Muted, HomeSummaryTone.Running, HomeSummaryTone.NeedsYou),
            segments.map { it.tone },
        )
        // 安静的工作区只说会话数，不留「0 运行中」这种噪音。
        assertEquals(
            listOf(HomeSummaryTone.Muted),
            workspaceSummarySegments(2, HomeSessionCounts()).map { it.tone },
        )
    }

    @Test
    fun taskSummaryMarksDoneTasksWithoutLosingActivityCounts() {
        val segments = taskSummarySegments(5, done = true, counts = HomeSessionCounts(running = 1))
        assertEquals("5 个会话 · 已完成 · 1 运行中", segments.joinToString(" · ") { it.text })
    }

    @Test
    fun workspaceSummaryLineOnlyMentionsNonZeroActivity() {
        assertEquals(
            "3 个会话 · 1 运行中 · 2 待处理",
            workspaceSummaryLine(3, HomeSessionCounts(running = 1, needsYou = 2)),
        )
        assertEquals("4 个会话", workspaceSummaryLine(4, HomeSessionCounts()))
    }

    private fun List<com.wand.app.data.TaskDirectoryGroup>.sameAs(
        other: List<com.wand.app.data.TaskDirectoryGroup>,
    ): Boolean = this === other

    private fun group(
        id: String = "workspace-1",
        tasks: List<WorkspaceTaskSummary> = emptyList(),
        standalone: List<WorkspaceSessionSummary> = emptyList(),
    ) = TaskDirectoryGroup(
        workspaceId = id,
        workspaceName = id,
        workspaceCwd = "/tmp/$id",
        synthetic = false,
        tasks = tasks,
        standaloneSessions = standalone,
        createdAt = null,
        global = false,
    )

    private fun task(id: String, sessions: List<WorkspaceSessionSummary>) = WorkspaceTaskSummary(
        task = WorkspaceTask(
            id = id,
            workspaceId = "workspace-1",
            name = id,
            worktree = null,
            layout = null,
            status = WorkspaceTaskStatus.Active,
            createdAt = null,
            lastOpenedAt = null,
        ),
        cwd = "/tmp/$id",
        isolated = false,
        worktreeError = null,
        sessions = sessions,
        totalSessions = sessions.size,
    )

    private fun session(id: String, status: String) = WorkspaceSessionSummary(
        id = id,
        provider = "claude",
        sessionKind = "structured",
        runner = null,
        title = id,
        status = status,
        cwd = "/tmp/$id",
        // 5 分钟前，供相对时间断言复用。
        startedAt = "2026-09-25T11:55:00Z",
        inFlight = null,
    )
}
