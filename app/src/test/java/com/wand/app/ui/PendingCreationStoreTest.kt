package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCreationStoreTest {
    private class FakePersistence : PendingCreationPersistence {
        private val values = mutableMapOf<String, PendingCreationState>()
        var failWrites = false
        var failRemoves = false

        override fun read(key: String): PendingCreationState = values[key] ?: PendingCreationState()
        override fun write(key: String, state: PendingCreationState): Boolean {
            if (failWrites) return false
            values[key] = state
            return true
        }
        override fun remove(key: String): Boolean {
            if (failRemoves) return false
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
        assertEquals(PendingCreationOperation.TASK, reopenedStore.state(key).operation)
        assertNull(reopenedStore.state(key).taskId)
    }

    @Test
    fun acceptedTaskContinuesToSessionWithoutRecreatingTaskAfterStateRecreation() {
        val persistence = FakePersistence()
        val firstStore = PendingCreationStore(persistence)
        val key = PendingCreationStore.stableKey("server-1", "global")
        firstStore.markUnconfirmed(key, PendingCreationOperation.TASK)
        firstStore.recordTaskAccepted(key, "task-accepted")
        firstStore.markUnconfirmed(key, PendingCreationOperation.SESSION)

        val reopenedStore = PendingCreationStore(persistence)
        val state = reopenedStore.state(key)
        assertEquals("task-accepted", state.taskId)
        assertTrue(state.unconfirmed)
        assertEquals(PendingCreationOperation.SESSION, state.operation)
    }

    @Test
    fun sessionAcceptanceIsResumableAndDefiniteSessionRejectionRetainsTask() {
        val persistence = FakePersistence()
        val store = PendingCreationStore(persistence)
        val key = PendingCreationStore.stableKey("server-1", "task:task-1")
        store.markUnconfirmed(key, PendingCreationOperation.TASK)
        store.recordTaskAccepted(key, "task-1")
        store.markUnconfirmed(key, PendingCreationOperation.SESSION)
        store.sessionAccepted(key, "session-1")

        val reopened = PendingCreationStore(persistence).state(key)
        assertEquals("task-1", reopened.taskId)
        assertEquals("session-1", reopened.sessionId)
        assertFalse(reopened.unconfirmed)

        store.markUnconfirmed(key, PendingCreationOperation.SESSION)
        store.definiteRejection(key)
        val rejected = store.state(key)
        assertEquals("task-1", rejected.taskId)
        assertEquals("session-1", rejected.sessionId)
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
    fun acceptedTaskBindingSurvivesStoreRecreation() {
        val persistence = FakePersistence()
        val key = PendingCreationStore.stableKey("server-1", "workspace:ws-1")
        PendingCreationStore(persistence).recordTaskAccepted(
            key, "task-1", taskCwd = "/repo/.worktrees/task-1", taskWorkspaceId = "ws-1",
        )

        val restored = PendingCreationStore(persistence).state(key)
        assertEquals("task-1", restored.taskId)
        assertEquals("/repo/.worktrees/task-1", restored.taskCwd)
        assertEquals("ws-1", restored.taskWorkspaceId)
    }

    @Test
    fun unknownSessionWithAcceptedTaskBlocksCreationAfterReopening() {
        val persistence = FakePersistence()
        val key = PendingCreationStore.stableKey("server-1", "global")
        val store = PendingCreationStore(persistence)
        store.recordTaskAccepted(key, "task-1")
        store.markUnconfirmed(key, PendingCreationOperation.SESSION)

        val reopened = PendingCreationStore(persistence).state(key)
        assertTrue(reopened.blocksCreation(volatileUnconfirmed = false, hasKnownSession = false))
        assertFalse(reopened.blocksCreation(volatileUnconfirmed = false, hasKnownSession = true))
        assertTrue(PendingCreationState().blocksCreation(volatileUnconfirmed = true, hasKnownSession = false))
        store.definiteRejection(key)
        assertEquals("task-1", store.state(key).taskId)
        assertFalse(store.state(key).blocksCreation(volatileUnconfirmed = false, hasKnownSession = false))
    }

    @Test
    fun failedAcceptanceWriteRetainsDurableUnknownLock() {
        for (operation in PendingCreationOperation.entries) {
            val persistence = FakePersistence()
            val store = PendingCreationStore(persistence)
            store.markUnconfirmed("scope", operation)
            persistence.failWrites = true
            try {
                if (operation == PendingCreationOperation.TASK) store.recordTaskAccepted("scope", "task-1")
                else store.sessionAccepted("scope", "session-1")
                throw AssertionError("Expected persistence failure")
            } catch (_: PendingCreationPersistenceException) {
                val reopened = PendingCreationStore(persistence).state("scope")
                assertTrue(reopened.unconfirmed)
                assertEquals(operation, reopened.operation)
                assertTrue(reopened.blocksCreation(volatileUnconfirmed = false, hasKnownSession = false))
            }
        }
    }

    @Test
    fun failedClearRetainsAcceptedSessionForContinuation() {
        val persistence = FakePersistence()
        val store = PendingCreationStore(persistence)
        store.sessionAccepted("scope", "session-1")
        persistence.failRemoves = true
        try {
            store.clear("scope")
            throw AssertionError("Expected persistence failure")
        } catch (_: PendingCreationPersistenceException) {
            assertEquals("session-1", PendingCreationStore(persistence).state("scope").sessionId)
        }
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
            store.markUnconfirmed("scope", PendingCreationOperation.TASK)
            throw AssertionError("Expected persistence failure")
        } catch (_: IllegalStateException) {
            assertFalse(store.state("scope").unconfirmed)
        }
    }
}
