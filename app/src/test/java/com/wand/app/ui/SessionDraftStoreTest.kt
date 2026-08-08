package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDraftStoreTest {
    @Test
    fun draftsRemainIsolatedBySession() {
        val store = SessionDraftStore()

        store["chat-a"] = "unfinished chat"
        store["pty-b"] = "terminal command"

        assertEquals("unfinished chat", store["chat-a"])
        assertEquals("terminal command", store["pty-b"])
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
}
