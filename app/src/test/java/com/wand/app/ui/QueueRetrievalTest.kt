package com.wand.app.ui

import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueRetrievalTest {
    @Test fun acknowledgementIsRequiredAndSingleFlightPreservesExactOriginal() = runTest {
        val drafts = SessionDraftStore()
        val composer = ChatComposer("a", drafts, backgroundScope, { true }, {}, {})
        val ack = CompletableDeferred<Unit>()
        var calls = 0
        val original = "  原文\n下一行  "
        assertTrue(composer.retrieveQueued(2, original) { index, text ->
            calls++
            assertEquals(2, index)
            assertEquals(original, text)
            ack.await()
        })
        assertFalse(composer.retrieveQueued(2, original) { _, _ -> calls++ })
        runCurrent()
        assertEquals("", composer.draft)
        assertEquals(QueueRecoveryPhase.Retrieving, composer.queueRecovery?.phase)
        ack.complete(Unit)
        runCurrent()
        assertEquals(original, composer.draft)
        assertNull(composer.queueRecovery)
        assertEquals(1, calls)
    }

    @Test fun lateAckKeepsNewestDraftAndAttachmentsUntilExplicitAppend() = runTest {
        val drafts = SessionDraftStore(mapOf("a" to "old", "b" to "other session"))
        val composer = ChatComposer("a", drafts, backgroundScope, { true }, {}, {})
        val ack = CompletableDeferred<Unit>()
        composer.retrieveQueued(0, "queued") { _, _ -> ack.await() }
        runCurrent()
        composer.editDraft("latest")
        composer.upload { listOf(UploadedFile("a.txt", "/uploads/a.txt", 1, "text/plain")) }
        runCurrent()
        ack.complete(Unit)
        runCurrent()
        assertEquals("latest", composer.draft)
        assertEquals(QueueRecoveryPhase.Ready, composer.queueRecovery?.phase)
        assertTrue(composer.restoreQueuedOriginal())
        assertEquals("latest\n\nqueued", composer.draft)
        assertEquals("/uploads/a.txt", composer.attachments.single().savedPath)
        assertEquals("other session", drafts["b"])
    }

    @Test fun unknownFailureRetainsOriginalWithoutRetryOrImplicitRestoration() = runTest {
        for (status in listOf(null, 408, 409, 500)) {
            val drafts = SessionDraftStore(mapOf("a" to "current"))
            val composer = ChatComposer("a", drafts, backgroundScope, { true }, {}, {})
            var calls = 0
            composer.retrieveQueued(0, "queued") { _, _ -> calls++; throw WandApiException(status, "unknown") }
            runCurrent()
            assertEquals(QueueRecoveryPhase.Unconfirmed, composer.queueRecovery?.phase)
            assertEquals("queued", composer.queueRecovery?.text)
            assertEquals("current", composer.draft)
            assertFalse(composer.restoreQueuedOriginal())
            assertFalse(composer.retrieveQueued(0, "queued") { _, _ -> calls++ })
            assertEquals(1, calls)
            assertTrue(composer.restoreQueuedOriginal(confirmedNotExecuted = true))
            assertEquals("current\n\nqueued", composer.draft)
            composer.shutdown()
        }
    }

    @Test fun leavingSessionRetainsRecoveryForItsOwnerAndNeverWritesNewSession() = runTest {
        val drafts = SessionDraftStore(mapOf("b" to "new session"))
        val first = ChatComposer("a", drafts, backgroundScope, { true }, {}, {})
        val ack = CompletableDeferred<Unit>()
        first.retrieveQueued(0, "original") { _, _ -> withContext(NonCancellable) { ack.await() } }
        runCurrent()
        first.shutdown()
        val next = ChatComposer("b", drafts, backgroundScope, { true }, {}, {})
        ack.complete(Unit)
        runCurrent()
        assertEquals("new session", next.draft)
        assertNull(next.queueRecovery)
        val reopened = ChatComposer("a", drafts, backgroundScope, { true }, {}, {})
        assertEquals(QueueRecoveryPhase.Unconfirmed, reopened.queueRecovery?.phase)
        assertEquals("original", reopened.queueRecovery?.text)
        assertEquals("", reopened.draft)
    }

    @Test fun definiteRejectionCanBeDismissedWithoutRestoringAnEnqueuedCopy() = runTest {
        val drafts = SessionDraftStore()
        val composer = ChatComposer("a", drafts, backgroundScope, { true }, {}, {})
        composer.retrieveQueued(0, "original") { _, _ -> throw WandApiException(422, "rejected") }
        runCurrent()
        assertEquals(QueueRecoveryPhase.Rejected, composer.queueRecovery?.phase)
        assertFalse(composer.restoreQueuedOriginal())
        assertEquals("", composer.draft)
        composer.dismissRejectedQueueRecovery()
        assertNull(composer.queueRecovery)
    }
}
