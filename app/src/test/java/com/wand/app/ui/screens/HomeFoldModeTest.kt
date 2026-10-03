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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页三段式折叠档位（展开 / 收起 / 在跑）：
 * 档位自身的持久化值与分段顺序、每层的解析（逐层继承）、按档位取哪几行，
 * 以及目录 peek 那一套「筛空就显示说明」的预览口径。
 */
class HomeFoldModeTest {

    @After
    fun tearDown() {
        SessionTitleStore.clear()
    }

    // MARK: - 档位与持久化

    @Test
    fun storageValuesRoundTripAndUnknownValuesFallBackToExpand() {
        HomeFoldMode.entries.forEach { mode ->
            assertEquals(mode, parseHomeFoldMode(homeFoldModeStorageValue(mode)))
        }
        // 默认档就是历史行为（按手动收放的记忆），所以旧值/脏值必须回落它，不能把列表锁在收起态。
        assertEquals(HomeFoldMode.Expand, parseHomeFoldMode(null))
        assertEquals(HomeFoldMode.Expand, parseHomeFoldMode(""))
        assertEquals(HomeFoldMode.Expand, parseHomeFoldMode("folded"))
    }

    @Test
    fun segmentOrderAndLabelsMatchTheThreeStates() {
        assertEquals(listOf("展开", "收起", "在跑"), HomeFoldMode.entries.map { it.label })
        assertEquals(
            listOf("全部展开", "全部收起", "只看在跑、刚完成、失败和待处理的会话"),
            HomeFoldMode.entries.map { it.actionLabel },
        )
        // 只有「收起」档是折起来的；「在跑」只筛内容，仍然展开。
        assertEquals(listOf(true, false, true), HomeFoldMode.entries.map { it.expanded })
        assertEquals(listOf(false, false, true), HomeFoldMode.entries.map { it.filtersRunning })
    }

    // MARK: - 按档位取行 / 展开

    @Test
    fun runningFoldKeepsRunningFailedAndWaitingSessions() {
        val sessions = listOf(
            session("alpha", "running"),
            session("failed", "failed"),
            session("waiting", "waiting-input"),
            session("underscored", "waiting_input"),
            session("blocked", "permission-blocked"),
            session("gamma", "idle"),
            session("gone", "exited"),
        )

        assertEquals(sessions, foldVisibleSessions(HomeFoldMode.Expand, sessions))
        assertEquals(sessions, foldVisibleSessions(HomeFoldMode.Collapse, sessions))
        assertEquals(
            listOf("alpha", "failed", "waiting", "underscored", "blocked"),
            foldVisibleSessions(HomeFoldMode.Running, sessions).map { it.id },
        )
    }

    @Test
    fun runningFoldKeepsTasksThatFailedOrAreWaiting() {
        val running = task("t1", listOf(session("alpha", "running")))
        val idleOnly = task("t2", listOf(session("gamma", "idle")))
        val mixed = task("t3", listOf(session("delta", "idle"), session("epsilon", "running")))
        val failedOnly = task("t4", listOf(session("failed", "failed")))
        val waitingOnly = task("t5", listOf(session("waiting", "permission")))
        val tasks = listOf(running, idleOnly, mixed, failedOnly, waitingOnly)

        // 展开 / 收起档照旧全列；在跑档留下有在跑、失败或待处理会话的任务。
        assertEquals(tasks, foldVisibleTasks(HomeFoldMode.Expand, tasks))
        assertEquals(tasks, foldVisibleTasks(HomeFoldMode.Collapse, tasks))
        assertEquals(
            listOf("t1", "t3", "t4", "t5"),
            foldVisibleTasks(HomeFoldMode.Running, tasks).map { it.id },
        )
        // 混合任务里空闲的那条仍然不露。
        assertEquals(
            listOf("epsilon"),
            foldVisibleSessions(HomeFoldMode.Running, mixed.sessions).map { it.id },
        )
    }

    @Test
    fun runningFoldKeepsFailedAndWaitingConversations() {
        val running = HomeRecentConversation(group("wand", emptyList()), null, session("alpha", "running"))
        val failed = HomeRecentConversation(group("wand", emptyList()), null, session("failed", "failed"))
        val waiting = HomeRecentConversation(group("wand", emptyList()), null, session("waiting", "waiting-input"))
        val idle = HomeRecentConversation(group("wand", emptyList()), null, session("gamma", "idle"))
        val conversations = listOf(running, failed, waiting, idle)

        assertEquals(conversations, foldVisibleConversations(HomeFoldMode.Expand, conversations))
        assertEquals(
            listOf("alpha", "failed", "waiting"),
            foldVisibleConversations(HomeFoldMode.Running, conversations).map { it.session.id },
        )
    }

    @Test
    fun runningFoldKeepsASessionBlockedOnTheLivePermissionOverlay() {
        val idle = session("overlay", "idle")
        SessionTitleStore.apply("overlay", permissionBlocked = true)

        assertEquals(listOf("overlay"), foldVisibleSessions(HomeFoldMode.Running, listOf(idle)).map { it.id })
    }

    @Test
    fun contentExpandsOnlyWhenSomethingIsActuallyVisible() {
        assertTrue(foldExpandsContent(HomeFoldMode.Expand, 2))
        assertFalse(foldExpandsContent(HomeFoldMode.Collapse, 2))
        assertTrue(foldExpandsContent(HomeFoldMode.Running, 1))
        // 在跑档下一条在跑、失败或待处理都没有：收成一级行（这就是与「全部收起」的区别所在）。
        assertFalse(foldExpandsContent(HomeFoldMode.Running, 0))
    }

    // MARK: - 目录 peek（预览里筛空就显示说明）

    @Test
    fun peekKeepsRunningSessionsAndDropsIdleAncestors() {
        val visible = foldModeGroups(HomeFoldMode.Running, twoTaskGroup())

        assertEquals(listOf("wand"), visible.map { it.id })
        assertEquals(listOf("t1"), visible.single().tasks.map { it.id })
        assertEquals(listOf("alpha"), visible.single().tasks.single().sessions.map { it.id })
        // 计数跟着筛选结果走：peek 只画这几行，报总数会自相矛盾。
        assertEquals(1, visible.single().tasks.single().totalSessions)
    }

    @Test
    fun peekLeavesEverythingAloneInTheOtherModes() {
        val groups = twoTaskGroup()

        assertEquals(groups, foldModeGroups(HomeFoldMode.Expand, groups))
        assertEquals(groups, foldModeGroups(HomeFoldMode.Collapse, groups))
    }

    @Test
    fun peekKeepsTheDirectoryIdentityWhenEverythingIsFilteredOut() {
        val group = group("wand", listOf(task("t1", listOf(session("gamma", "idle")))))

        val filtered = foldModeGroup(HomeFoldMode.Running, group)

        // 整条被筛掉时目录还在（名字/路径照旧），只把内容清空——否则 peek 会看起来像目录不存在。
        assertEquals(group.id, filtered.id)
        assertEquals(group.workspaceName, filtered.workspaceName)
        assertTrue(filtered.tasks.isEmpty())
        assertTrue(filtered.standaloneSessions.isEmpty())
        assertEquals(group, foldModeGroup(HomeFoldMode.Expand, group))
    }

    @Test
    fun peekKeepsFailedAndWaitingSessionsButNotAProviderCliAtItsPrompt() {
        val groups = listOf(
            group(
                "wand",
                listOf(
                    task(
                        "t1",
                        listOf(
                            session("waiting", "permission"),
                            session("failed", "failed"),
                            session("idle-in-task", "idle"),
                        ),
                    ),
                ),
                standalone = listOf(
                    session("at-prompt", "running", inFlight = false),
                    session("standalone-running", "running"),
                    session("standalone-failed", "failed"),
                ),
            ),
        )

        val visible = foldModeGroups(HomeFoldMode.Running, groups)

        // 失败和等你处理要露出来；provider CLI 停在提示符上、空闲会话仍然不算。
        assertEquals(listOf("waiting", "failed"), visible.single().tasks.single().sessions.map { it.id })
        assertEquals(2, visible.single().tasks.single().totalSessions)
        assertEquals(
            listOf("standalone-running", "standalone-failed"),
            visible.single().standaloneSessions.map { it.id },
        )
    }

    @Test
    fun emptyNoteExplainsWhyTheRunningModeHidEverything() {
        assertEquals("这个目录没有在跑、失败或待处理的会话。", foldModeEmptyNote(HomeFoldMode.Running))
        assertEquals("这个目录还没有任务或终端。", foldModeEmptyNote(HomeFoldMode.Expand))
        assertEquals("这个目录还没有任务或终端。", foldModeEmptyNote(HomeFoldMode.Collapse))
    }

    /** 两个目录：wand 里 t1 一条在跑一条空闲、t2 全空闲；other 里全是空闲。 */
    private fun twoTaskGroup() = listOf(
        group(
            "wand",
            listOf(
                task("t1", listOf(session("alpha", "running"), session("gamma", "idle"))),
                task("t2", listOf(session("delta", "idle"))),
            ),
        ),
        group("other", listOf(task("t3", listOf(session("epsilon", "idle"))))),
    )

    // MARK: - fixtures

    private fun group(
        id: String,
        tasks: List<WorkspaceTaskSummary>,
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

    /** structured 会话只有 `inFlight = true` 才算真的在跑；其余状态按服务端原值归一。 */
    private fun session(
        id: String,
        status: String,
        inFlight: Boolean? = status == "running",
    ) = WorkspaceSessionSummary(
        id = id,
        provider = "claude",
        sessionKind = "structured",
        runner = null,
        title = id,
        status = status,
        cwd = "/tmp/$id",
        startedAt = "2026-10-02T11:55:00Z",
        inFlight = inFlight,
    )
}
