package com.wand.app.ui.screens

import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.BoardTaskWorkspace
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
    fun placeholderHidesUnnamedSyncedWorkspaceTasks() {
        val emptyUnnamed = task(
            title = "未命名任务",
            description = "项目：wand\n目录：/tmp/wand",
        )
        val blankUnnamed = task(title = "  ", description = "")
        assertTrue(isPlaceholderBoardTask(emptyUnnamed))
        assertTrue(isPlaceholderBoardTask(blankUnnamed))
        assertFalse(
            isPlaceholderBoardTask(
                task(title = "未命名任务", description = "项目：wand\n目录：/tmp/wand", sessions = listOf(session())),
            ),
        )
        assertFalse(isPlaceholderBoardTask(task(title = "修登录", description = "")))
        assertFalse(
            isPlaceholderBoardTask(
                task(title = "未命名任务", description = "把登录页的错误提示修好"),
            ),
        )
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
        assertNull(slim.priority)
        assertNull(slim.agentLabel)
        assertTrue(slim.labels.isEmpty())
        assertNull(slim.processingLabel)
        assertTrue(slim.sessions.isEmpty())

        val rich = boardTaskCardModel(
            task(
                title = "修登录",
                priority = "high",
                workspaceName = "wand",
                workspaceId = "ws-1",
                labels = listOf("bug", "login", "extra"),
                agent = BoardTaskAgent("claude", "default", "off"),
                status = "doing",
                sessions = listOf(session(), session(id = "s2"), session(id = "s3"), session(id = "s4")),
            ),
            showWorkspace = true,
        )
        assertEquals("wand", rich.workspaceName)
        assertEquals("high", rich.priority)
        assertEquals("Claude", rich.agentLabel)
        assertEquals(listOf("bug", "login"), rich.labels)
        assertEquals("正在处理...", rich.processingLabel)
        assertEquals(3, rich.sessions.size)
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
