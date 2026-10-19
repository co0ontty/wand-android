package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.wand.app.data.UploadedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDraftStoreTest {
    @Test
    fun conversationDeletionClearsAllTargetsAndInvalidatesLateEdits() {
        val store = SessionDraftStore(mapOf("conversation:a:talk" to "old", "conversation:a:run:1" to "task", "conversation:b:talk" to "keep"))
        val oldRevision = store.revision("conversation:a:talk")
        store.setAttachments("conversation:a:run:1", listOf(UploadedFile("x.txt", "/uploads/x.txt", 1, "text/plain")))
        store.discardScope("conversation:a:")
        assertEquals("", store["conversation:a:talk"])
        assertEquals("", store["conversation:a:run:1"])
        assertEquals(true, store.attachments("conversation:a:run:1").isEmpty())
        assertEquals(true, store.revision("conversation:a:talk") > oldRevision)
        assertEquals(mapOf("conversation:b:talk" to "keep"), store.savedDrafts())
    }

    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun savedAndRestored(store: SessionDraftStore): SessionDraftStore {
        val saved = with(SessionDraftStore.Saver) { saverScope.save(store) }
        return SessionDraftStore.Saver.restore(checkNotNull(saved))!!
    }

    @Test
    fun pendingCreationIdsAndUnknownOutcomeSurviveSaverRestore() {
        val store = SessionDraftStore(mapOf("pending-1" to "first message"))
        store.setPendingCreatedTask("pending-1", "task-1")
        store.setPendingTaskWindow("pending-1", "session-1")
        store.setPendingCreatedSession("pending-1", "session-1")
        store.setPendingCreationUnconfirmed("pending-2", true)

        val restored = savedAndRestored(store)

        assertEquals("task-1", restored.pendingCreatedTask("pending-1"))
        assertEquals("session-1", restored.pendingTaskWindow("pending-1"))
        assertEquals("session-1", restored.pendingCreatedSession("pending-1"))
        assertEquals(true, restored.pendingCreationUnconfirmed("pending-2"))
        restored.clearPendingCreation("pending-1")
        assertEquals(null, restored.pendingCreatedSession("pending-1"))
        assertEquals(null, restored.pendingCreatedTask("pending-1"))
        assertEquals(null, restored.pendingTaskWindow("pending-1"))
    }

    @Test
    fun pendingUploadUnknownSurvivesRestoreAndBlocksBlindRetryUntilExplicitRemoval() {
        val store = SessionDraftStore()
        val staged = StagedComposerAttachment("content://picked/unknown", "unknown.txt", "text/plain")
        store.setStagedAttachments("pending-a", listOf(staged))
        store.markPendingUploadUnconfirmed("pending-a", listOf(staged))

        val restored = savedAndRestored(store)
        val restoredStaged = restored.stagedAttachments("pending-a")
        assertEquals(listOf(staged), restoredStaged)
        assertEquals(true, restored.pendingUploadUnconfirmed("pending-a", restoredStaged))

        restored.releasePendingUploadAfterDefiniteRejection("pending-a", restoredStaged)
        assertEquals(false, restored.pendingUploadUnconfirmed("pending-a", restoredStaged))
        restored.setStagedAttachments("pending-a", emptyList())
        assertEquals(false, restored.pendingUploadUnconfirmed("pending-a", restoredStaged))
    }

    @Test
    fun definiteUploadRejectionCanBeRetriedAndAcceptedUploadClearsStagedFile() {
        val store = SessionDraftStore()
        val staged = StagedComposerAttachment("content://picked/retry", "retry.txt", "text/plain")
        store.setStagedAttachments("pending-a", listOf(staged))
        store.markPendingUploadUnconfirmed("pending-a", listOf(staged))
        store.releasePendingUploadAfterDefiniteRejection("pending-a", listOf(staged))
        assertEquals(false, store.pendingUploadUnconfirmed("pending-a", listOf(staged)))

        val composer = ChatComposer("pending-a", store, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { true }, { error("unused") }, {})
        assertTrue(composer.acceptUploadedStagedAttachments(listOf(staged), listOf(UploadedFile("retry.txt", "/uploads/retry.txt", 1, "text/plain"))))
        assertEquals(emptyList<StagedComposerAttachment>(), store.stagedAttachments("pending-a"))
        assertEquals(1, store.attachments("pending-a").size)
        composer.shutdown()
    }

    @Test
    fun draftsRemainIsolatedBySession() {
        val store = SessionDraftStore()

        store["chat-a"] = "unfinished chat"
        store["pty-b"] = "terminal command"

        assertEquals("unfinished chat", store["chat-a"])
        assertEquals("terminal command", store["pty-b"])
    }

    @Test
    fun unknownInputCannotBecomeRestorableByEditingAwayAndBack() {
        val store = SessionDraftStore(mapOf("chat-a" to "uncertain"))
        val sent = store.submission("chat-a")
        store["chat-a"] = "new text"
        assertEquals(mapOf("chat-a" to "new text"), store.savedDrafts())
        store["chat-a"] = "uncertain"
        assertEquals(true, store.isUnconfirmed("chat-a", sent.prompt))
        assertEquals(emptyMap<String, String>(), store.savedDrafts())
        assertEquals(false, store.isUnconfirmed("chat-b", sent.prompt))
        store.rejected("chat-a", sent)
        assertEquals(false, store.isUnconfirmed("chat-a", sent.prompt))
        assertEquals(mapOf("chat-a" to "uncertain"), store.savedDrafts())
    }

    @Test
    fun clearingOneDraftDoesNotAffectAnotherSession() {
        val store = SessionDraftStore(
            mapOf(
                "chat-a" to "send this",
                "chat-b" to "keep this",
            ),
        )

        store["chat-a"] = ""

        assertEquals("", store["chat-a"])
        assertEquals("keep this", store["chat-b"])
        assertEquals(mapOf("chat-b" to "keep this"), store.savedDrafts())
    }

    @Test
    fun saverRestoresUnsentTextAndAttachmentsWithoutRetainingRuntimeObjects() {
        val store = SessionDraftStore(mapOf("chat-a" to "draft"))
        val file = UploadedFile("file.txt", "/uploads/file.txt", 12, "text/plain")
        store.setAttachments("chat-a", listOf(file))
        val restored = savedAndRestored(store)
        assertEquals("draft", restored["chat-a"])
        assertEquals(listOf(file), restored.attachments("chat-a"))
    }

    @Test
    fun acceptingSubmissionClearsCapturedStagedAttachmentsButPreservesNewerDraft() {
        val store = SessionDraftStore(mapOf("chat-a" to "send this"))
        val staged = StagedComposerAttachment("content://picked/1", "one.txt", "text/plain")
        store.setStagedAttachments("chat-a", listOf(staged))
        val submission = store.submission("chat-a")

        store.accepted("chat-a", submission)

        assertEquals("", store["chat-a"])
        assertEquals(emptyList<StagedComposerAttachment>(), store.stagedAttachments("chat-a"))
    }

    @Test
    fun acceptingSubmissionPreservesDraftAndStagedAttachmentsAddedAfterCapture() {
        val store = SessionDraftStore(mapOf("chat-a" to "send this"))
        val original = StagedComposerAttachment("content://picked/1", "one.txt", "text/plain")
        val later = StagedComposerAttachment("content://picked/2", "two.txt", "text/plain")
        store.setStagedAttachments("chat-a", listOf(original))
        val submission = store.submission("chat-a")
        store["chat-a"] = "typed while sending"
        store.setStagedAttachments("chat-a", listOf(original, later))

        store.accepted("chat-a", submission)

        assertEquals("typed while sending", store["chat-a"])
        assertEquals(listOf(later), store.stagedAttachments("chat-a"))
    }

    @Test
    fun saverOmitsUnconfirmedContentButKeepsNewEditsAndAttachments() {
        val store = SessionDraftStore(mapOf("chat-a" to "submitted"))
        val submittedFile = UploadedFile("old.txt", "/uploads/old.txt", 3, "text/plain")
        val newFile = UploadedFile("new.txt", "/uploads/new.txt", 3, "text/plain")
        store.setAttachments("chat-a", listOf(submittedFile))
        store.submission("chat-a")
        assertEquals("", savedAndRestored(store)["chat-a"])
        assertEquals(emptyList<UploadedFile>(), savedAndRestored(store).attachments("chat-a"))
        store["chat-a"] = "new draft"
        store.setAttachments("chat-a", listOf(submittedFile, newFile))
        val restored = savedAndRestored(store)
        assertEquals("new draft", restored["chat-a"])
        assertEquals(listOf(newFile), restored.attachments("chat-a"))
    }
}
