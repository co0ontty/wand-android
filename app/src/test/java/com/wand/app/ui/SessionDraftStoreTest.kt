package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.wand.app.data.UploadedFile
import org.junit.Assert.assertEquals
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
