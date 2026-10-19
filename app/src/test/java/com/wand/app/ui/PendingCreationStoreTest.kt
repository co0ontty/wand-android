package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCreationStoreTest {
    private class FakePersistence : PendingCreationPersistence {
        private val values = mutableMapOf<String, PendingCreationState>()

        override fun read(key: String): PendingCreationState = values[key] ?: PendingCreationState()
        override fun write(key: String, state: PendingCreationState): Boolean {
            values[key] = state
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }

    @Test
    fun unknownTaskCreationSurvivesPendingEntryAndStateRecreation() {
        val persistence = FakePersistence()
        val firstStore = PendingCreationStore(persistence)
        val key = PendingCreationStore.stableKey("server-1", "workspace:/repo")
        firstStore.markUnconfirmed(key, PendingCreationOperation.TASK)

        val reopenedStore = PendingCreationStore(persistence)
        assertTrue(reopenedStore.state(key).unconfirmed)
        assertFalse(reopenedStore.state(key).unconfirmed.not())
        assertNull(reopenedStore.state(key).taskId)
    }

    @Test
    fun acceptedTaskContinuesToSessionWithoutRecreatingTaskAfterStateRecreation() {
        val persistence = FakePersistence()
        val firstStore = PendingCreationStore(persistence)
        val key = PendingCreationStore.stableKey("server-1", "global")
        firstStore.markUnconfirmed(key)
        firstStore.markUnconfirmedWithTask(key, "task-accepted")

        val reopenedStore = PendingCreationStore(persistence)
        val state = reopenedStore.state(key)
        assertEquals("task-accepted", state.taskId)
        assertTrue(state.unconfirmed)
    }

    @Test
    fun sessionAcceptanceIsResumableAndDefiniteSessionRejectionRetainsTask() {
        val persistence = FakePersistence()
        val store = PendingCreationStore(persistence)
        val key = PendingCreationStore.stableKey("server-1", "task:task-1")
        store.markUnconfirmed(key)
        store.markUnconfirmedWithTask(key, "task-1")
        store.sessionAccepted(key, "session-1")

        val reopened = PendingCreationStore(persistence).state(key)
        assertEquals("task-1", reopened.taskId)
        assertEquals("session-1", reopened.sessionId)
        assertFalse(reopened.unconfirmed)

        store.markUnconfirmedWithTask(key, "task-1")
        store.definiteRejection(key)
        val rejected = store.state(key)
        assertEquals("task-1", rejected.taskId)
        assertNull(rejected.sessionId)
        assertFalse(rejected.unconfirmed)
    }

    @Test
    fun stableScopeSeparatesServersAndEntryKinds() {
        val workspace = PendingCreationStore.stableKey("server-1", "workspace:/repo")
        assertEquals(workspace, PendingCreationStore.stableKey("server-1", "workspace:/repo"))
        assertTrue(workspace != PendingCreationStore.stableKey("server-2", "workspace:/repo"))
        assertTrue(workspace != PendingCreationStore.stableKey("server-1", "task:task-1"))
    }

    @Test
    fun cannotMarkCreationUnknownWhenDurableWriteFails() {
        val persistence = object : PendingCreationPersistence {
            override fun read(key: String) = PendingCreationState()
            override fun write(key: String, state: PendingCreationState) = false
            override fun remove(key: String) = true
        }
        val store = PendingCreationStore(persistence)
        try {
            store.markUnconfirmed("scope")
            throw AssertionError("Expected persistence failure")
        } catch (_: IllegalStateException) {
            assertFalse(store.state("scope").unconfirmed)
        }
    }
}
