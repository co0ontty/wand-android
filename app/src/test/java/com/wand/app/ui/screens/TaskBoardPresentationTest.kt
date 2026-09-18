package com.wand.app.ui.screens

import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.BoardTaskMilestone
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.BoardTaskWorkspace
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardPresentationTest {
    @Test
    fun statsCountRemainingAndHighPriority() {
        val stats = boardTaskStats(
            listOf(
                task(id = "1", status = "todo", priority = "urgent"),
                task(id = "2", status = "doing", priority = "high"),
                task(id = "3", status = "done", priority = "low"),
                task(id = "4", status = "todo"),
            ),
        )
        assertEquals(4, stats.total)
        assertEquals(2, stats.todo)
        assertEquals(1, stats.doing)
        assertEquals(1, stats.done)
        assertEquals(3, stats.remaining)
        assertEquals(2, stats.high)
    }

    @Test
    fun filterMatchesQueryWorkspaceAndStatus() {
        val tasks = listOf(
            task(id = "1", title = "Fix login", workspaceId = "alpha", workspaceName = "Alpha"),
            task(id = "2", title = "Write docs", status = "doing", workspaceId = "beta", workspaceName = "Beta"),
            task(id = "3", identifier = "WAND-9", title = "Login polish", workspaceId = "alpha", workspaceName = "Alpha"),
        )
        assertEquals(listOf("1", "3"), filterBoardTasks(tasks, "login", "", "").map { it.id })
        assertEquals(listOf("2"), filterBoardTasks(tasks, "", "beta", "").map { it.id })
        assertEquals(listOf("3"), filterBoardTasks(tasks, "wand-9", "alpha", "todo").map { it.id })
        assertTrue(filterBoardTasks(tasks, "missing", "", "").isEmpty())
    }

    @Test
    fun sortPreservesServerOrder() {
        val sorted = sortBoardTasks(
            listOf(
                task(id = "done-old", status = "done", sortOrder = 0, updatedAt = "2026-01-01"),
                task(id = "todo-b", status = "todo", sortOrder = 2, updatedAt = "2026-02-01"),
                task(id = "todo-a", status = "todo", sortOrder = 1, updatedAt = "2026-01-01"),
                task(id = "doing", status = "doing", sortOrder = 0, updatedAt = "2026-03-01"),
            ),
        ).map { it.id }
        assertEquals(listOf("done-old", "todo-b", "todo-a", "doing"), sorted)
    }

    @Test
    fun groupedBoardKeepsCanonicalStatusOrder() {
        val grouped = groupedBoardTasks(
            listOf(
                task(id = "d", status = "done"),
                task(id = "t", status = "todo"),
            ),
        )
        assertEquals(listOf("todo", "doing", "done"), grouped.map { it.first })
        assertEquals(listOf("t"), grouped[0].second.map { it.id })
        assertTrue(grouped[1].second.isEmpty())
        assertEquals(listOf("d"), grouped[2].second.map { it.id })
    }

    @Test
    fun completeToggleReopensDoneTasks() {
        assertEquals("done", boardTaskToggledStatus("todo"))
        assertEquals("done", boardTaskToggledStatus("doing"))
        assertEquals("todo", boardTaskToggledStatus("done"))
        assertEquals("todo", boardTaskToggledStatus("archived"))
    }

    @Test
    fun archivedTasksStayOutOfDoneStatsAndMainGroups() {
        val tasks = listOf(
            task(id = "t", status = "todo"),
            task(id = "d", status = "done"),
            task(id = "a", status = "archived"),
        )
        val stats = boardTaskStats(tasks)
        assertEquals(1, stats.done)
        assertEquals(1, stats.remaining)
        assertEquals(listOf("a"), boardArchivedTasks(tasks).map { it.id })
        assertEquals(listOf("d"), groupedBoardTasks(tasks)[2].second.map { it.id })
        assertEquals(listOf("a"), filterBoardTasks(tasks, "", "", "archived").map { it.id })
    }

    @Test
    fun processingLabelFollowsSessions() {
        assertNull(boardTaskProcessingLabel(task(status = "todo")))
        assertEquals("等待派发", boardTaskProcessingLabel(task(status = "doing")))
        assertEquals(
            "正在处理...",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "running")))),
        )
        assertEquals(
            "等待验收",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "idle")))),
        )
        assertEquals(
            "等待验收",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "exited")))),
        )
        assertEquals(
            "暂停处理",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "stopped")))),
        )
    }

    @Test
    fun unnamedAndEmptyTasksRemainAvailableInTheBoard() {
        val tasks = listOf(task(title = "未命名任务", description = "项目：wand\n目录：/tmp/wand"),
            task(title = "", description = ""))
        assertEquals(tasks, filterBoardTasks(tasks, "", "", ""))
    }

    @Test
    fun cardModelKeepsOnlyUsefulFields() {
        val placeholderTitle = boardTaskCardTitle(
            task(title = "", description = "项目：wand\n把登录页修好\n目录：/tmp"),
        )
        assertEquals("把登录页修好", placeholderTitle)

        val slim = boardTaskCardModel(
            task(
                title = "修登录",
                workspaceName = "wand",
                workspaceId = "ws-1",
            ),
            showWorkspace = false,
        )
        assertEquals("修登录", slim.title)
        assertNull(slim.workspaceName)
        assertNull(slim.milestoneName)
        assertNull(slim.priority)
        assertNull(slim.agentLabel)
        assertTrue(slim.labels.isEmpty())
        assertNull(slim.processingLabel)
        assertTrue(slim.sessions.isEmpty())
        assertFalse(slim.hasChips)

        val rich = boardTaskCardModel(
            task(
                title = "修登录",
                priority = "high",
                workspaceName = "wand",
                workspaceId = "ws-1",
                milestoneName = "  1.2 看板重构  ",
                labels = listOf("bug", "login", "extra"),
                agent = BoardTaskAgent("claude", "default", "off"),
                status = "doing",
                sessions = listOf(session(), session(id = "s2"), session(id = "s3"), session(id = "s4")),
            ),
            showWorkspace = true,
        )
        assertEquals("wand", rich.workspaceName)
        assertEquals("1.2 看板重构", rich.milestoneName)
        assertEquals("high", rich.priority)
        assertEquals("Claude", rich.agentLabel)
        assertEquals(listOf("bug", "login"), rich.labels)
        assertEquals("正在处理...", rich.processingLabel)
        assertEquals(3, rich.sessions.size)
        assertTrue(rich.hasChips)
    }

    @Test
    fun cardCapsLabelsAndSessionsWithExplicitOverflow() {
        val model = boardTaskCardModel(
            task(
                labels = listOf("bug", "login", "extra"),
                sessions = listOf(session(), session(id = "s2"), session(id = "s3"), session(id = "s4")),
            ),
            showWorkspace = false,
        )
        assertEquals(listOf("bug", "login"), model.labels)
        assertEquals(1, model.extraLabelCount)
        assertEquals(BOARD_TASK_CARD_SESSION_LIMIT, model.sessions.size)
        assertEquals(1, model.extraSessionCount)
    }

    @Test
    fun cardCarriesIdentifierDueStampAndRunningFlag() {
        val model = boardTaskCardModel(
            task(
                identifier = "WAND-42",
                status = "doing",
                dueDate = "2026-03-14",
                sessions = listOf(session(status = "running"), session(id = "s2", status = "idle")),
            ),
            showWorkspace = true,
            today = LocalDate.parse("2026-03-20"),
        )
        assertEquals("WAND-42", model.identifier)
        assertEquals("逾期 · 3/14", model.due?.label)
        assertEquals(true, model.due?.overdue)
        assertTrue(model.running)
        assertEquals(listOf(true, false), model.sessions.map { it.running })
    }

    @Test
    fun closedTasksAndMalformedDatesNeverReadAsOverdue() {
        val today = LocalDate.parse("2026-03-20")
        assertFalse(boardTaskIsOverdue("2026-03-14", "done", today))
        assertFalse(boardTaskIsOverdue("2026-03-14", "archived", today))
        assertTrue(boardTaskIsOverdue("2026-03-14", "doing", today))
        assertFalse(boardTaskIsOverdue("2026-03-20", "todo", today))
        assertNull(boardTaskCardDue("下周三", "todo", today))
        assertNull(boardTaskCardDue(null, "todo", today))
        assertEquals("3/25", boardTaskCardDue("2026-03-25", "todo", today)?.label)
        assertEquals(false, boardTaskCardDue("2026-03-25", "todo", today)?.overdue)
    }

    @Test
    fun cardOnlyFallsBackToDescriptionWhenNothingIsRunning() {
        val bare = task(description = "项目：wand\n把登录页修好\n目录：/tmp")
        assertEquals("把登录页修好", boardTaskCardBody(bare))
        // 自动标题就是描述首行生成的，卡片不能再把这行当摘要重复一遍。
        assertEquals(
            "第二行补充",
            boardTaskCardBody(bare.copy(title = "把登录页修好", titleSource = "auto", description = "把登录页修好\n第二行补充")),
        )
        assertNull(boardTaskCardBody(bare.copy(sessions = listOf(session()))))
        assertNull(boardTaskCardBody(bare.copy(agent = BoardTaskAgent("claude", "default", "off"))))
    }

    @Test
    fun sessionRowLabelFallsBackToProviderName() {
        assertEquals("session-1", boardSessionCardLabel(session()))
        assertEquals("Claude", boardSessionCardLabel(session().copy(title = "")))
        assertEquals("Claude", boardSessionCardLabel(session().copy(title = "claude")))
        assertEquals("修自动填充", boardSessionCardLabel(session().copy(title = "修自动填充")))
        assertEquals("终端", boardSessionCardLabel(session(provider = "shell").copy(title = "")))
    }

    private fun task(
        id: String = "task-1",
        title: String = "Fix login",
        description: String = "",
        status: String = "todo",
        priority: String = "none",
        identifier: String = "WAND-1",
        workspaceId: String? = null,
        workspaceName: String? = null,
        milestoneName: String? = null,
        sortOrder: Int = 0,
        updatedAt: String = "2026-01-02",
        createdAt: String = "2026-01-01",
        sessions: List<BoardTaskSession> = emptyList(),
        labels: List<String> = emptyList(),
        dueDate: String? = null,
        agent: BoardTaskAgent? = null,
        titleSource: String = "user",
    ): BoardTask = BoardTask(
        id = id,
        workspaceId = workspaceId,
        identifier = identifier,
        title = title,
        titleSource = titleSource,
        description = description,
        status = status,
        priority = priority,
        labels = labels,
        dueDate = dueDate,
        sortOrder = sortOrder,
        agent = agent,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sessionIds = sessions.map { it.id },
        sessions = sessions,
        workspace = workspaceName?.let { BoardTaskWorkspace(workspaceId ?: "workspace", it, "/tmp") },
        milestone = milestoneName?.let { BoardTaskMilestone("milestone-1", it) },
    )

    private fun session(
        id: String = "session-1",
        status: String = "running",
        provider: String = "claude",
    ): BoardTaskSession = BoardTaskSession(
        id = id,
        provider = provider,
        sessionKind = "structured",
        title = id,
        status = status,
        cwd = "/tmp",
        model = "default",
        thinkingEffort = "off",
    )
}
