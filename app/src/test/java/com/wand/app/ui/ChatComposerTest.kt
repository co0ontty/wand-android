package com.wand.app.ui

import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposerTest {
    private fun TestScope.composer(
        drafts: SessionDraftStore,
        id: String = "chat-a",
        ready: () -> Boolean = { true },
        send: suspend (String) -> Unit = {},
        notices: MutableList<String> = mutableListOf(),
    ) = ChatComposer(id, drafts, backgroundScope, ready, send, notices::add)

    private fun file(path: String) = UploadedFile(path.substringAfterLast('/'), path, 3, "text/plain")

    @Test
    fun explicitReceiptClearsOnlyUnknownCaptureAndNeverNewInput() = runTest {
        val drafts = SessionDraftStore(mapOf("conversation-a" to "first"))
        val composer = composer(drafts, id = "conversation-a", send = { throw com.wand.app.data.ConversationUnconfirmedException("request-a") })
        assertTrue(composer.submit())
        runCurrent()
        assertFalse(composer.canSubmit)
        assertTrue(drafts.savedDrafts().isEmpty())
        composer.editDraft("new input")
        composer.reconcileSubmission(accepted = true)
        assertEquals("new input", composer.draft)
        assertEquals(mapOf("conversation-a" to "new input"), drafts.savedDrafts())
    }

    @Test
    fun uploadStartedBeforeRevisionChangeDoesNotAttachToNewDraft() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "old"))
        val composer = composer(drafts)
        val upload = CompletableDeferred<List<UploadedFile>>()
        assertTrue(composer.upload { upload.await() })
        runCurrent()
        composer.editDraft("new")
        upload.complete(listOf(file("/uploads/old.txt")))
        runCurrent()
        assertEquals("new", composer.draft)
        assertTrue(composer.attachments.isEmpty())
    }

    @Test
    fun explicitDispatchUsesSameOwnerAndFeedbackWithoutSendingTwice() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "task"))
        var ordinary = 0
        var dispatch = 0
        var navigations = 0
        val composer = composer(drafts, send = { ordinary++ })
        assertTrue(composer.submit(deliver = { dispatch++ }, afterAccepted = { navigations++ }))
        assertFalse(composer.submit())
        runCurrent()
        assertEquals(0, ordinary)
        assertEquals(1, dispatch)
        assertEquals(SendPhase.Sent, composer.sendPhase)
        assertEquals(0, navigations)
        advanceTimeBy(SEND_SENT_DWELL_MS)
        runCurrent()
        assertEquals(1, navigations)
    }

    @Test
    fun keepsTextAndAttachmentUntilAckThenClearsOnlyTheSubmittedContent() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "first"))
        val ack = CompletableDeferred<Unit>()
        val prompts = mutableListOf<String>()
        val composer = composer(drafts, send = { prompts += it; ack.await() })
        composer.upload { listOf(file("/uploads/first.txt")) }
        runCurrent()
        assertTrue(composer.submit())
        assertFalse(composer.submit())
        assertEquals("first", composer.draft)
        assertEquals(1, composer.attachments.size)
        assertEquals(SendPhase.Sending, composer.sendPhase)
        assertEquals(emptyMap<String, String>(), drafts.savedDrafts())
        assertEquals(emptyMap<String, List<UploadedFile>>(), drafts.savedAttachments())
        runCurrent()
        assertTrue(prompts.single().contains("/uploads/first.txt"))
        ack.complete(Unit)
        runCurrent()
        assertEquals("", composer.draft)
        assertTrue(composer.attachments.isEmpty())
        assertEquals(SendPhase.Sent, composer.sendPhase)
        advanceTimeBy(SEND_SENT_DWELL_MS)
        runCurrent()
        assertEquals(SendPhase.Idle, composer.sendPhase)
    }

    @Test
    fun aDefiniteRejectionPreservesContentAndMakesItRestorable() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "keep me"))
        val composer = composer(drafts, send = { throw WandApiException(422, "invalid input") })
        composer.upload { listOf(file("/uploads/retry.txt")) }
        runCurrent()
        composer.submit()
        runCurrent()
        assertEquals("keep me", composer.draft)
        assertEquals(1, composer.attachments.size)
        assertEquals(mapOf("chat-a" to "keep me"), drafts.savedDrafts())
        assertEquals(composer.attachments, drafts.savedAttachments()["chat-a"])
        assertEquals(SendPhase.Failed, composer.sendPhase)
    }

    @Test
    fun uncertainDeliveryRemainsInMemoryButNeverBecomesARestoredDraft() = runTest {
        for (status in listOf(null, 408, 409, 500)) {
            val drafts = SessionDraftStore(mapOf("chat-a" to "uncertain"))
            val composer = composer(drafts, send = { throw WandApiException(status, "delivery unknown") })
            composer.upload { listOf(file("/uploads/uncertain.txt")) }
            runCurrent()
            composer.submit()
            runCurrent()
            assertEquals("uncertain", composer.draft)
            assertEquals(1, composer.attachments.size)
            assertTrue(drafts.savedDrafts().isEmpty())
            assertTrue(drafts.savedAttachments().isEmpty())
            composer.shutdown()
        }
    }

    @Test
    fun aPartiallyAcceptedPtyInputIsUncertainEvenWhenTheCarriageReturnIsRejected() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "terminal input"))
        val composer = composer(drafts, send = {
            throw UnconfirmedComposerInputException(WandApiException(422, "carriage return rejected"))
        })
        composer.submit()
        runCurrent()
        assertEquals("terminal input", composer.draft)
        assertTrue(drafts.savedDrafts().isEmpty())
        assertEquals(SendPhase.Failed, composer.sendPhase)
    }

    @Test
    fun reopeningComposerAndEditingBackCannotResendUnknownInput() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "uncertain"))
        var calls = 0
        val first = composer(drafts, send = { calls++; throw WandApiException(500, "unknown") })
        first.submit()
        runCurrent()
        first.shutdown()
        val reopened = composer(drafts, send = { calls++ })
        assertFalse(reopened.canSubmit)
        assertFalse(reopened.submit())
        reopened.editDraft("new input")
        assertTrue(reopened.canSubmit)
        reopened.editDraft("uncertain")
        assertFalse(reopened.canSubmit)
        assertFalse(reopened.submit())
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun successDoesNotClearNewTextVoiceOrAttachmentsEnteredDuringSend() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "first"))
        val ack = CompletableDeferred<Unit>()
        val composer = composer(drafts, send = { ack.await() })
        composer.upload { listOf(file("/uploads/first.txt")) }
        runCurrent()
        composer.submit()
        runCurrent()
        composer.editDraft("next")
        composer.appendVoice(" voice ")
        composer.upload { listOf(file("/uploads/next.txt")) }
        runCurrent()
        ack.complete(Unit)
        runCurrent()
        assertEquals("next voice", composer.draft)
        assertEquals(listOf("/uploads/next.txt"), composer.attachments.map { it.savedPath })
        assertEquals(mapOf("chat-a" to "next voice"), drafts.savedDrafts())
    }

    @Test
    fun delayedVoiceResultCannotAppendToARevisedDraftOrDisposedSession() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "first"))
        val composer = composer(drafts)
        val oldRecording = composer.voiceCommitForCurrentDraft()
        composer.editDraft("new edit")
        oldRecording("stale voice")
        assertEquals("new edit", composer.draft)

        val currentRecording = composer.voiceCommitForCurrentDraft()
        currentRecording("fresh voice")
        assertEquals("new edit fresh voice", composer.draft)

        val disposedRecording = composer.voiceCommitForCurrentDraft()
        composer.shutdown()
        disposedRecording("late voice")
        assertEquals("new edit fresh voice", drafts["chat-a"])
    }

    @Test
    fun lateFailureDoesNotReplaceNewEditsOrNewAttachments() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "first"))
        val response = CompletableDeferred<Unit>()
        val composer = composer(drafts, send = { response.await() })
        composer.submit()
        runCurrent()
        composer.editDraft("new draft")
        composer.upload { listOf(file("/uploads/new.txt")) }
        runCurrent()
        response.completeExceptionally(WandApiException(422, "rejected"))
        runCurrent()
        assertEquals("new draft", composer.draft)
        assertEquals(listOf("/uploads/new.txt"), composer.attachments.map { it.savedPath })
        assertEquals(mapOf("chat-a" to "new draft"), drafts.savedDrafts())
    }

    @Test
    fun switchingSessionsCancelsOperationsAndIgnoresLateUploadAndSendResults() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "a", "chat-b" to "b"))
        val response = CompletableDeferred<Unit>()
        val upload = CompletableDeferred<List<UploadedFile>>()
        val composerA = composer(drafts, send = { withContext(NonCancellable) { response.await() } })
        composerA.submit()
        composerA.upload { withContext(NonCancellable) { upload.await() } }
        runCurrent()
        composerA.shutdown()
        val composerB = composer(drafts, id = "chat-b")
        composerB.appendVoice("voice b")
        response.complete(Unit)
        upload.complete(listOf(file("/uploads/a.txt")))
        runCurrent()
        assertEquals("a", drafts["chat-a"])
        assertEquals("b voice b", composerB.draft)
        assertTrue(composerB.attachments.isEmpty())
        assertTrue(drafts.attachments("chat-a").isEmpty())
        assertFalse(composerA.submit())
        composerA.appendVoice("late a")
        assertEquals("a", drafts["chat-a"])
        assertEquals(mapOf("chat-b" to "b voice b"), drafts.savedDrafts())
    }

    @Test
    fun uploadsAreSingleFlightAndBlockSubmitUntilTheFilesAreKnown() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "input"))
        val upload = CompletableDeferred<List<UploadedFile>>()
        val composer = composer(drafts)
        assertTrue(composer.upload { upload.await() })
        assertFalse(composer.upload { emptyList() })
        assertFalse(composer.submit())
        runCurrent()
        upload.completeExceptionally(WandApiException(413, "file too large"))
        runCurrent()
        assertFalse(composer.uploading)
        assertTrue(composer.canSubmit)
        assertEquals("input", composer.draft)
    }

    @Test
    fun unloadedSessionCannotSubmitThroughTheWrongProtocol() = runTest {
        val drafts = SessionDraftStore(mapOf("chat-a" to "do not send yet"))
        var ready = false
        val composer = composer(drafts, ready = { ready })
        assertFalse(composer.submit())
        assertEquals(mapOf("chat-a" to "do not send yet"), drafts.savedDrafts())
        ready = true
        assertTrue(composer.submit())
        runCurrent()
        assertEquals("", composer.draft)
    }

    @Test
    fun attachmentsRemainIsolatedAndUnsentContentSurvivesScreenReplacement() = runTest {
        val drafts = SessionDraftStore()
        val firstA = composer(drafts)
        firstA.editDraft("a text")
        firstA.upload { listOf(file("/uploads/a.txt")) }
        runCurrent()
        firstA.shutdown()
        val composerB = composer(drafts, id = "chat-b")
        composerB.editDraft("b text")
        composerB.upload { listOf(file("/uploads/b.txt")) }
        runCurrent()
        val secondA = composer(drafts)
        assertEquals("a text", secondA.draft)
        assertEquals(listOf("/uploads/a.txt"), secondA.attachments.map { it.savedPath })
        assertEquals(listOf("/uploads/b.txt"), composerB.attachments.map { it.savedPath })
        secondA.removeAttachment(secondA.attachments.single())
        assertTrue(secondA.attachments.isEmpty())
        assertEquals(1, composerB.attachments.size)
    }
}
