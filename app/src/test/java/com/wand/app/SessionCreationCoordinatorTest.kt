package com.wand.app

import com.wand.app.data.SessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCreationCoordinatorTest {
    @Test
    fun ownsOneCreateUntilItsResultIsClaimed() = runBlocking {
        val releaseCreate = CompletableDeferred<Unit>()
        val snapshot = SessionSnapshot.parse(JSONObject("""{"id":"session-1"}"""))

        assertTrue(
            SessionCreationCoordinator.start(
                hostServerId = "server-a",
                targetServerId = "server-b",
            ) {
                releaseCreate.await()
                snapshot
            },
        )
        assertTrue(SessionCreationCoordinator.isBusy())
        assertEquals("server-a", SessionCreationCoordinator.busyHostServerId())
        assertFalse(
            SessionCreationCoordinator.start("server-a", "server-a") { snapshot },
        )

        releaseCreate.complete(Unit)
        val completed = withTimeout(2_000) {
            SessionCreationCoordinator.state
                .filterIsInstance<SessionCreationCoordinator.State.Completed>()
                .first()
        }
        assertEquals("server-a", completed.hostServerId)
        assertEquals("server-b", completed.targetServerId)
        assertSame(snapshot, (completed.outcome as SessionCreationCoordinator.Outcome.Success).snapshot)
        assertSame(completed, SessionCreationCoordinator.takeCompleted(completed.requestId))
        assertFalse(SessionCreationCoordinator.isBusy())
        assertNull(SessionCreationCoordinator.busyHostServerId())
        assertNull(SessionCreationCoordinator.takeCompleted(completed.requestId))

        assertTrue(
            SessionCreationCoordinator.start("server-a", "server-b") {
                error("offline")
            },
        )
        val failed = withTimeout(2_000) {
            SessionCreationCoordinator.state
                .filterIsInstance<SessionCreationCoordinator.State.Completed>()
                .first()
        }
        assertEquals(
            "offline",
            (failed.outcome as SessionCreationCoordinator.Outcome.Failure).message,
        )
        assertSame(failed, SessionCreationCoordinator.takeCompleted(failed.requestId))
        assertFalse(SessionCreationCoordinator.isBusy())
    }
}
