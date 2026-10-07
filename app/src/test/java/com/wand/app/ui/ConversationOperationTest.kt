package com.wand.app.ui

import com.wand.app.data.ConversationReceipt
import com.wand.app.data.ConversationUnconfirmedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationOperationTest {
    private fun receipt(state: String = "accepted") = ConversationReceipt("request", state, "group", null, null, null, null)

    @Test fun lateAcceptanceAfterClosingOrSwitchingNeverNavigatesAndNeverPostsAgain() = runTest {
        val owner = ConversationOperation(backgroundScope)
        val response = CompletableDeferred<ConversationReceipt>()
        var posts = 0; var navigations = 0
        owner.attach()
        assertTrue(owner.submit({ posts++; response.await() }, { navigations++ }))
        runCurrent()
        owner.detach()
        owner.attach()
        assertFalse(owner.submit({ posts++; receipt() }))
        response.complete(receipt())
        runCurrent()
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent()
        assertEquals(1, posts)
        assertEquals(0, navigations)
        assertEquals("group", owner.accepted?.conversationId)
        assertFalse(owner.canSubmit)
    }

    @Test fun unknownSurvivesCloseAndReopenAndOnlyReadsReceipt() = runTest {
        val owner = ConversationOperation(backgroundScope)
        var posts = 0; var reads = 0
        owner.attach()
        owner.submit({ posts++; throw ConversationUnconfirmedException("request") })
        runCurrent()
        owner.detach(); owner.attach()
        assertEquals("request", owner.unknown)
        assertFalse(owner.submit({ posts++; receipt() }))
        owner.reconcile { reads++; receipt("pending") }
        runCurrent()
        assertFalse(owner.canSubmit)
        owner.reconcile { reads++; receipt() }
        runCurrent()
        assertEquals(1, posts); assertEquals(2, reads)
        assertNotNull(owner.accepted)
        assertFalse(owner.canSubmit)
    }

    @Test fun currentViewAcceptsOnceAndExplicitRejectionReleasesTheLock() = runTest {
        val owner = ConversationOperation(backgroundScope)
        var navigations = 0
        owner.attach()
        owner.submit({ receipt() }, { navigations++ })
        runCurrent(); advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent()
        assertEquals(1, navigations)
        val rejected = ConversationOperation(backgroundScope)
        rejected.submit(request = { throw ConversationUnconfirmedException("request") })
        runCurrent()
        rejected.reconcile { receipt("rejected") }
        runCurrent()
        assertTrue(rejected.canSubmit)
    }

    @Test fun successDwellAndAcceptedFailureBothRetainTheSameGroupOperationIdentity() = runTest {
        for (failure in listOf(null, "已接受但启动失败")) {
            val owner = ConversationOperation(backgroundScope)
            var posts = 0
            owner.attach()
            assertTrue(owner.submit({ posts++; receipt().copy(error = failure) }))
            runCurrent()
            assertFalse(owner.canSubmit)
            assertFalse(owner.submit({ posts++; receipt() }))
            advanceTimeBy(if (failure == null) SEND_SENT_DWELL_MS else SEND_FAILED_DWELL_MS); runCurrent()
            owner.detach(); owner.attach()
            assertFalse(owner.canSubmit)
            assertNotNull(owner.accepted)
            assertEquals(1, posts)
        }
    }

    @Test fun actionLockAndUnknownReceiptStayInOwnerAfterViewDetaches() = runTest {
        val owner = ConversationOperation(backgroundScope, retainAccepted = false)
        val result = CompletableDeferred<ConversationReceipt>()
        owner.submit(request = { result.await() }); runCurrent()
        owner.detach()
        assertFalse(owner.canSubmit)
        result.complete(receipt()); runCurrent()
        advanceTimeBy(SEND_SENT_DWELL_MS); runCurrent()
        assertTrue(owner.canSubmit)
        owner.submit(request = { throw ConversationUnconfirmedException("request") }); runCurrent()
        owner.detach(); owner.attach()
        assertFalse(owner.canSubmit)
        owner.reconcile { receipt() }; runCurrent()
        assertTrue(owner.canSubmit)
    }
}
