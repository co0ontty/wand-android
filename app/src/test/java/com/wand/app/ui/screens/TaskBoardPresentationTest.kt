package com.wand.app.ui.screens

import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.BoardTaskWorkspace
import org.junit.Assert.assertEquals
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
    fun sortUsesStatusThenOrderThenNewestUpdate() {
        val sorted = sortBoardTasks(
            listOf(
                task(id = "done-old", status = "done", sortOrder = 0, updatedAt = "2026-01-01"),
                task(id = "todo-b", status = "todo", sortOrder = 2, updatedAt = "2026-02-01"),
                task(id = "todo-a", status = "todo", sortOrder = 1, updatedAt = "2026-01-01"),
                task(id = "doing", status = "doing", sortOrder = 0, updatedAt = "2026-03-01"),
            ),
        ).map { it.id }
        assertEquals(listOf("todo-a", "todo-b", "doing", "done-old"), sorted)
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
    }

    @Test
    fun processingLabelAndProgressFollowSessions() {
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
        val progress = boardTaskProgress(
            task(
                status = "doing",
                sessions = listOf(session(id = "a", status = "idle"), session(id = "b", status = "running")),
            ),
        )
        assertEquals(BoardTaskProgress(completed = 1, total = 4), progress)
        assertNull(boardTaskProgress(task(status = "todo")))
    }

    @Test
    fun displayIdFallsBackToShortId() {
        assertEquals("WAND-1", boardTaskDisplayId(task(identifier = "WAND-1")))
        assertEquals("abcdefgh", boardTaskDisplayId(task(id = "abcdefghijk", identifier = "")))
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
