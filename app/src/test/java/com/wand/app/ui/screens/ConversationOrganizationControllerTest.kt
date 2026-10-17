package com.wand.app.ui.screens

import com.wand.app.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationOrganizationControllerTest {
    private class Port : ConversationOrganizationPort {
        var previews = 0
        var starts = 0
        var tokenReads = 0
        var reads = 0
        var failStart = false
        var failRead = false
        var activeJobId: String? = null
        var previewSessionCount = 2
        var nextJob = job("completed")
        var capturedTarget: ConversationOrganizationTarget? = null
        var capturedMode: ConversationOrganizationMode? = null
        override suspend fun previewConversationOrganization(target: ConversationOrganizationTarget, mode: ConversationOrganizationMode): ConversationOrganizationPreview {
            previews++
            capturedTarget = target
            capturedMode = mode
            return ConversationOrganizationPreview("original-token", previewSessionCount, 1, 0, 0, 120, 3, false, activeJobId)
        }
        override suspend fun startConversationOrganization(token: String, force: Boolean): ConversationOrganizationJob {
            starts++
            assertEquals("original-token", token)
            if (failStart) error("unknown acceptance")
            return nextJob
        }
        override suspend fun conversationOrganizationJobByToken(token: String): ConversationOrganizationJob {
            tokenReads++
            assertEquals("original-token", token)
            return nextJob
        }
        override suspend fun conversationOrganizationJob(id: String): ConversationOrganizationJob {
            reads++
            assertEquals("original-job", id)
            if (failRead) error("offline")
            return nextJob
        }
        override suspend fun cancelConversationOrganization(id: String) = job("cancelled")
    }

    @Test fun previewUsesCapturedDirectoryAndNeverStartsBeforeConfirmation() = runTest {
        val port = Port()
        val target = ConversationOrganizationTarget.Directory("/work/repo")
        val controller = ConversationOrganizationController(port, target, this)
        controller.previewScope()
        runCurrent()
        assertEquals(ConversationOrganizationController.Phase.Prepared, controller.phase)
        assertEquals(target, port.capturedTarget)
        assertEquals(ConversationOrganizationMode.Auto, port.capturedMode)
        assertEquals(0, port.starts)
        controller.confirm()
        controller.confirm()
        runCurrent()
        assertEquals(1, port.starts)
        assertEquals(ConversationOrganizationController.Phase.Done, controller.phase)
    }

    @Test fun unknownStartKeepsOriginalTokenAndOnlyAllowsReceiptLookup() = runTest {
        val port = Port().apply { failStart = true }
        var refreshes = 0
        val controller = ConversationOrganizationController(port, ConversationOrganizationTarget.Workspace("selected"), this) { refreshes++ }
        controller.previewScope(); runCurrent()
        controller.confirm(); runCurrent()
        assertEquals(ConversationOrganizationController.Phase.Error, controller.phase)
        assertTrue(controller.needsRecheck)
        controller.selectMode(ConversationOrganizationMode.All)
        controller.reset()
        assertTrue(controller.needsRecheck)
        controller.previewScope(); controller.confirm(); runCurrent()
        assertEquals(1, port.previews)
        assertEquals(1, port.starts)
        assertEquals(ConversationOrganizationMode.Auto, controller.mode)
        controller.recheck(); runCurrent()
        assertEquals(1, port.tokenReads)
        assertEquals(1, port.starts)
        assertEquals(1, refreshes)
        assertEquals(ConversationOrganizationController.Phase.Done, controller.phase)
    }

    @Test fun runningJobCanBeRecheckedAfterNetworkFailureAndPollingStopsOnClose() = runTest {
        val port = Port().apply { nextJob = job("running"); failRead = true }
        val controller = ConversationOrganizationController(port, ConversationOrganizationTarget.Workspace("selected"), this)
        controller.previewScope(); runCurrent()
        controller.confirm(); runCurrent()
        advanceTimeBy(801); runCurrent()
        assertEquals(ConversationOrganizationController.Phase.Error, controller.phase)
        assertTrue(controller.job!!.isRunning)
        port.failRead = false
        port.nextJob = job("completed")
        controller.recheck(); runCurrent()
        assertEquals(ConversationOrganizationController.Phase.Done, controller.phase)
        assertEquals(1, port.starts)

        val second = ConversationOrganizationController(port.apply { nextJob = job("running") }, ConversationOrganizationTarget.Workspace("other"), this)
        second.previewScope(); runCurrent(); second.confirm(); runCurrent()
        second.stopPolling()
        val reads = port.reads
        advanceTimeBy(2000); runCurrent()
        assertEquals(reads, port.reads)
        assertEquals(ConversationOrganizationController.Phase.Error, second.phase)
    }

    @Test fun emptyDefaultRangeCanAdjustToAllWithoutStartingAnyModel() = runTest {
        val port = Port().apply { previewSessionCount = 0 }
        val controller = ConversationOrganizationController(port, ConversationOrganizationTarget.Workspace("selected"), this)
        controller.previewScope(); runCurrent()
        assertEquals(ConversationOrganizationController.Phase.Done, controller.phase)
        assertTrue(controller.canAdjustScope)
        assertTrue(controller.feedback.contains("跳过 1 个人工任务"))
        controller.reset()
        controller.selectMode(ConversationOrganizationMode.All)
        port.previewSessionCount = 1
        controller.previewScope(); runCurrent()
        assertEquals(ConversationOrganizationMode.All, port.capturedMode)
        assertEquals(ConversationOrganizationController.Phase.Prepared, controller.phase)
        assertEquals(0, port.starts)
    }

    @Test fun activeJobIsReusedWithoutStartingAnotherRequest() = runTest {
        val port = Port().apply { activeJobId = "original-job" }
        val controller = ConversationOrganizationController(port, ConversationOrganizationTarget.Workspace("selected"), this)
        controller.previewScope(); runCurrent()
        assertEquals(1, port.reads)
        assertEquals(0, port.starts)
        assertEquals(ConversationOrganizationController.Phase.Done, controller.phase)
    }

    companion object {
        private fun job(status: String) = ConversationOrganizationJob.parse(JSONObject()
            .put("id", "original-job").put("status", status)
            .put("result", JSONObject().put("eligibleSessions", 2)))!!
    }
}
