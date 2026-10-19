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
        assertEquals("已停止", comment.dispatch?.label)
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
