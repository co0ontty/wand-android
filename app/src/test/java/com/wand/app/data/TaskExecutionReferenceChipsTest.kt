package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行卡片「目标」chip 行。
 *
 * 口径与 Web 端 `TaskExecutionReferences` 一致：展示的是编号（`TASK-133`），
 * 点开的是那个子任务；编号取不到就不出 chip，绝不把内部 UUID 顶上去。
 */
class TaskExecutionReferenceChipsTest {
    private val children = listOf(
        TaskCommentReference.Target("child-1", "TASK-133"),
        TaskCommentReference.Target("child-2", "TASK-134"),
    )

    private fun comment(
        referencedTaskIds: List<String> = emptyList(),
        referencedTaskIdentifiers: List<String> = emptyList(),
        dispatch: TaskCommentDispatch? = null,
    ) = TaskComment(
        id = "c1",
        taskId = "t1",
        authorName = "我",
        body = "@小虎 做 #TASK-133",
        createdAt = "2026-10-16T12:00:00Z",
        dispatch = dispatch,
        referencedTaskIds = referencedTaskIds,
        referencedTaskIdentifiers = referencedTaskIdentifiers,
    )

    @Test fun `chips show the identifier and keep the child id for navigation`() {
        val chips = TaskCommentReference.executionReferenceChips(
            comment(referencedTaskIds = listOf("child-1"), referencedTaskIdentifiers = listOf("TASK-133")),
            children,
        )
        assertEquals(1, chips.size)
        assertEquals("TASK-133", chips.single().identifier)
        assertEquals("child-1", chips.single().taskId)
    }

    @Test fun `identifiers fall back to the child list when the server sends only internal ids`() {
        // 老服务端只给 `referencedTaskIds`：编号要回到子任务列表里换，不能把 UUID 显示出去。
        val chips = TaskCommentReference.executionReferenceChips(
            comment(referencedTaskIds = listOf("child-2")),
            children,
        )
        assertEquals(1, chips.size)
        assertEquals("TASK-134", chips.single().identifier)
        assertEquals("child-2", chips.single().taskId)
    }

    @Test fun `dispatch level references are used when the comment carries none`() {
        val dispatch = TaskCommentDispatch(
            employeeId = "e1",
            employeeName = "小虎",
            state = "accepted",
            sessionId = null,
            status = "running",
            referencedTaskIds = listOf("child-1"),
        )
        val chips = TaskCommentReference.executionReferenceChips(comment(dispatch = dispatch), children)
        assertEquals("TASK-133", chips.single().identifier)
        assertEquals("child-1", chips.single().taskId)
    }

    @Test fun `chips keep the order of the referenced tasks`() {
        val chips = TaskCommentReference.executionReferenceChips(
            comment(referencedTaskIdentifiers = listOf("TASK-134", "TASK-133")),
            children,
        )
        assertEquals(listOf("TASK-134", "TASK-133"), chips.map { it.identifier })
        assertEquals(listOf("child-2", "child-1"), chips.map { it.taskId })
    }

    @Test fun `a reference outside this task children is not shown`() {
        val chips = TaskCommentReference.executionReferenceChips(
            comment(referencedTaskIdentifiers = listOf("TASK-999")),
            children,
        )
        assertTrue(chips.isEmpty())
    }

    @Test fun `a child without an identifier never shows a raw uuid`() {
        val chips = TaskCommentReference.executionReferenceChips(
            comment(referencedTaskIds = listOf("child-1"), referencedTaskIdentifiers = listOf("TASK-133")),
            listOf(TaskCommentReference.Target("child-1", "")),
        )
        assertTrue(chips.isEmpty())
    }

    @Test fun `a comment without references has no chip row`() {
        assertTrue(TaskCommentReference.executionReferenceChips(comment(), children).isEmpty())
    }

    @Test fun `comments parse the referenced identifiers from the api`() {
        val response = JSONObject(
            """{"comments":[{"id":"c1","taskId":"t1","authorName":"我","body":"做 #TASK-133","createdAt":"2026-10-16T12:00:00Z",
                "referencedTaskIds":["child-1"],"referencedTaskIdentifiers":["TASK-133"]}]}""",
        )
        val parsed = TaskComment.parseList(response, "t1").single()
        assertEquals(listOf("TASK-133"), parsed.referencedTaskIdentifiers)
        assertEquals(listOf("child-1"), parsed.referencedTaskIds)
    }

    @Test fun `comments without the identifier field keep working`() {
        val response = JSONObject(
            """{"comments":[{"id":"c1","taskId":"t1","authorName":"我","body":"做 #TASK-133","createdAt":"2026-10-16T12:00:00Z",
                "referencedTaskIds":["child-1"]}]}""",
        )
        val parsed = TaskComment.parseList(response, "t1").single()
        assertTrue(parsed.referencedTaskIdentifiers.isEmpty())
        assertEquals(listOf("child-1"), parsed.referencedTaskIds)
    }
}
