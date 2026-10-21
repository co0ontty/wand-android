package com.wand.app.data

import com.wand.app.ui.screens.CommentDraft
import com.wand.app.ui.screens.TaskCommentDrafts
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskCommentDispatchTest {
    @Test fun trustedDispatchAndSourceDecodeSeparatelyFromBody() {
        val response = JSONObject("""{"comments":[{"id":"c","taskId":"task","authorName":"用户","body":"@文本不猜测状态","createdAt":"now","sourceCommentId":"source","dispatch":{"employeeId":"stable-id","employeeName":"员工","state":"accepted","sessionId":"exact-session","status":"stopped"}}]}""")
        val comment = TaskComment.parseList(response, "task").single()
        assertEquals("source", comment.sourceCommentId)
        assertEquals("stable-id", comment.dispatch?.employeeId)
        assertEquals("exact-session", comment.dispatch?.sessionId)
        assertEquals("已停止", comment.dispatch?.statusLabel)
    }

    /**
     * 状态词表必须与 Web 任务流一致（服务端 `src/task-comment-status.ts` 为唯一真源）。
     * 这里逐条钉住文案，任何一端改词都会在这里失败。
     */
    @Test fun statusWordingMatchesSharedContract() {
        val expected = mapOf(
            "queued" to "排队中",
            "pending" to "等待确认",
            "accepted" to "已创建执行",
            "running" to "执行中",
            "blocked" to "等待审批",
            "idle" to "本轮已结束",
            "exited" to "会话已退出",
            "stopped" to "已停止",
            "failed" to "执行失败",
            "done" to "已完成",
            "unknown" to "送达未知 · 不会重放",
            "history" to "历史会话",
            "unavailable" to "会话不可用",
        )
        expected.forEach { (status, wording) ->
            assertEquals(wording, TaskCommentDispatch("stable", "员工", status, "session", status).statusLabel)
        }
        // 未知状态统一回落成「状态未确认」，不给猜测。
        assertEquals("状态未确认", TaskCommentDispatch("stable", "员工", "mystery", "session", "mystery").statusLabel)
        assertEquals("状态未确认", TaskCommentDispatch("stable", "员工", "mystery", null, null).statusLabel)
    }

    @Test fun lateReceiptsCannotClearNewInputOrAnotherTask() {
        val submitted = TaskCommentDrafts.set("revision-task-a", CommentDraft(body = "old", employeeId = "stable"))
        TaskCommentDrafts.set("revision-task-b", CommentDraft(body = "other"))
        TaskCommentDrafts.set("revision-task-a", submitted.copy(body = "new"))
        assertEquals("new", TaskCommentDrafts.accept("revision-task-a", submitted).body)
        assertEquals("other", TaskCommentDrafts.get("revision-task-b").body)
        assertEquals("", TaskCommentDrafts.accept("revision-task-a", TaskCommentDrafts.get("revision-task-a")).body)
    }

    @Test fun unknownAndPendingReceiptsRetainRequestIdentityEvenWhenCommentIsSaved() {
        assertTrue(TaskCommentDispatch("stable", "员工", "unknown", "session", "unknown").deliveryUncertain)
        assertTrue(TaskCommentDispatch("stable", "员工", "pending", null, "unknown").deliveryUncertain)
        assertTrue(TaskCommentDispatch("stable", "员工", "accepted", "session", "unknown").deliveryUncertain)
        assertFalse(TaskCommentDispatch("stable", "员工", "accepted", "session", "running").deliveryUncertain)
        assertFalse(TaskCommentDispatch("stable", "员工", "failed", null, "failed").deliveryUncertain)
    }
}
