package com.wand.app.ui

import com.wand.app.data.ConversationReceipt
import com.wand.app.data.ConversationTask
import com.wand.app.data.ConversationUnconfirmedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFeedbackTest {
    private fun receipt(error: String? = null) = ConversationReceipt("request", "accepted", "group-original", "task-original", null, null, error, "failed")

    @Test fun acceptedStartupFailureStillSelectsOriginalGroupButNeverOverwritesNewTextOrAttachments() {
        assertTrue(acceptedConversationTask(receipt("relay失败"), hasDraft = false))
        assertFalse(acceptedConversationTask(receipt("relay失败"), hasDraft = true))
        assertFalse(acceptedConversationTask(receipt().copy(state = "rejected"), hasDraft = false))
        assertFalse(acceptedConversationTask(receipt().copy(conversationId = ""), hasDraft = false))
        assertFalse(acceptedConversationTask(receipt().copy(taskId = null), hasDraft = false))
    }

    @Test fun persistedStartupFactHasAnHonestOriginalTaskLabel() {
        val task = ConversationTask.parse(JSONObject("""{"task":{"id":"task-original","title":"原任务"},"runs":[],"startup":{"state":"failed","error":"relay失败"}}"""))!!
        assertEquals("启动失败 · 已接受的原任务", conversationStartupLabel(task))
        assertEquals("待开工 · 已接受的原任务", conversationStartupLabel(task.copy(startup = null)))
    }

    @Test fun approvalUsesExistingOwnerForSendingApprovedResultAndDwellWithoutSendingTwice() = runTest {
        val operation = ConversationOperation(backgroundScope, retainAccepted = false)
        val ack = CompletableDeferred<ConversationReceipt>()
        var calls = 0
        operation.attach()
        assertTrue(operation.submit(request = { calls++; ack.await() }, purpose = "approve"))
        runCurrent()
        assertEquals("批准中", conversationApprovalLabel(operation.phase))
        assertTrue(operation.approvalFeedback); assertFalse(operation.canSubmit)
        assertFalse(operation.submit(request = { calls++; receipt() }, purpose = "approve"))
        ack.complete(receipt()); runCurrent()
        assertEquals("已批准", conversationApprovalLabel(operation.phase)); assertFalse(operation.canSubmit)
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent()
        assertEquals("批准已接受", conversationApprovalLabel(operation.phase)); assertFalse(operation.canSubmit)
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent()
        assertEquals("idle", operation.phase); assertTrue(operation.canSubmit); assertFalse(operation.approvalFeedback)
        assertEquals(1, calls)
    }

    @Test fun approvalFailureStaysLongerAndDoesNotBecomeAnotherTargetsFeedback() = runTest {
        val original = ConversationOperation(backgroundScope, retainAccepted = false)
        val other = ConversationOperation(backgroundScope, retainAccepted = false)
        original.attach()
        original.submit(request = { throw IllegalArgumentException("明确拒收") }, purpose = "approve")
        runCurrent(); original.detach(); other.attach()
        assertEquals("批准失败", conversationApprovalLabel(original.phase)); assertFalse(original.canSubmit)
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent()
        assertEquals("failed", original.phase); assertEquals("idle", other.phase)
        advanceTimeBy(SEND_FAILED_DWELL_MS - SEND_SENT_DWELL_MS); runCurrent()
        assertEquals("idle", original.phase); assertTrue(original.canSubmit)
        assertEquals("", other.feedback)
    }

    @Test fun unknownApprovalReconcilesOnlyItsOriginalRequestAndRetainsAllButtonStages() = runTest {
        val operation = ConversationOperation(backgroundScope, retainAccepted = false)
        var reads = 0
        operation.submit(request = { throw ConversationUnconfirmedException("request-original") }, purpose = "approve")
        runCurrent(); operation.detach(); operation.attach()
        assertEquals("批准未确认", conversationApprovalLabel(operation.phase)); assertFalse(operation.canSubmit)
        operation.reconcile { id -> assertEquals("request-original", id); reads++; receipt() }
        runCurrent(); assertEquals("sent", operation.phase)
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent(); assertEquals("result", operation.phase)
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent(); assertTrue(operation.canSubmit)
        assertEquals(1, reads)
    }

    @Test fun explicitlyRejectedApprovalReadReleasesTheOwnerOnlyAfterFailureDwell() = runTest {
        val operation = ConversationOperation(backgroundScope, retainAccepted = false)
        operation.submit(request = { throw ConversationUnconfirmedException("request-original") }, purpose = "approve")
        runCurrent()
        operation.reconcile { receipt("未接受").copy(state = "rejected") }
        runCurrent(); assertFalse(operation.canSubmit)
        advanceTimeBy(SEND_FAILED_DWELL_MS); runCurrent(); assertTrue(operation.canSubmit)
    }
}
