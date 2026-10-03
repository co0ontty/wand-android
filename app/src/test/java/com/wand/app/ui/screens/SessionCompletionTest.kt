package com.wand.app.ui.screens

import com.wand.app.data.ChatSessionEventReducer
import com.wand.app.data.ChatSessionEventState
import com.wand.app.data.SessionChanges
import com.wand.app.data.SessionCompletion
import com.wand.app.data.SessionEvent
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WsIncoming
import com.wand.app.data.activityStatus
import com.wand.app.data.toSessionEvent
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.components.WandStatusTone
import com.wand.app.ui.components.wandStatusPresentation
import com.wand.app.ui.withLiveTitle
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCompletionTest {
    @After fun cleanup() { SessionTitleStore.clear() }

    private fun summary(kind: String = "structured", status: String = "idle") =
        WorkspaceSessionSummary.parse(JSONObject()
            .put("id", "finished").put("provider", "pi").put("sessionKind", kind)
            .put("status", status).put("ptyBusy", false).put("inFlight", false)
            .put("completionRevision", 2).put("viewedCompletionRevision", 1),
        )!!

    @Test fun unreadCompletionIsQuietSuccessButRemainsInRunningFilter() {
        val row = summary()
        assertEquals("just-completed", row.activityStatus())
        assertEquals("just-completed", summary("pty", "running").activityStatus())
        assertEquals("just-completed", summary("pty", "exited").activityStatus())
        val badge = wandStatusPresentation(row.activityStatus())
        assertEquals("刚完成", badge.label)
        assertEquals(WandStatusTone.Success, badge.tone)
        assertFalse(badge.breathing)
        assertEquals(HomeSessionPulse.Quiet, sessionPulse(row))
        assertEquals(0, homeSessionCounts(listOf(row)).running)
        assertEquals(listOf(row), foldVisibleSessions(HomeFoldMode.Running, listOf(row)))
        assertTrue(foldVisibleSessions(HomeFoldMode.Running,
            listOf(row.copy(viewedCompletionRevision = 2)),
        ).isEmpty())
    }

    @Test fun activeFailureAndPermissionsTakePrecedenceOverUnreadResult() {
        val row = summary()
        assertEquals("running", row.copy(inFlight = true).activityStatus())
        assertEquals("running", summary("pty", "running").copy(ptyBusy = true).activityStatus())
        assertEquals("failed", row.copy(status = "failed").activityStatus())
        assertEquals("stopped", row.copy(status = "stopped").activityStatus())
        SessionTitleStore.apply(row.id, permissionBlocked = true)
        assertEquals(HomeSessionPulse.NeedsYou, sessionPulse(row))
    }

    @Test fun crossDeviceAcknowledgementAndDelayedProjectionCannotRollBack() {
        val row = summary()
        SessionTitleStore.apply(row.id, completionRevision = 2, viewedCompletionRevision = 2)
        assertEquals("idle", row.withLiveTitle().activityStatus())
        assertFalse(sessionKeptByRunningFold(row))
        SessionTitleStore.apply(row.id, completionRevision = 2, viewedCompletionRevision = 0)
        assertEquals(2, row.withLiveTitle().viewedCompletionRevision)
        SessionTitleStore.apply(row.id, completionRevision = 3, viewedCompletionRevision = 2)
        assertEquals("just-completed", row.withLiveTitle().activityStatus())
        assertTrue(sessionKeptByRunningFold(row))
        assertEquals(SessionCompletion(3, 2), SessionCompletion(3, 2).merge(SessionCompletion(2, 2)))
    }

    @Test fun transportAndReducerCarryGenerationsWithoutOverwritingNewerState() {
        val snap = SessionSnapshot.parse(JSONObject()
            .put("id", "finished").put("sessionKind", "structured").put("status", "idle")
            .put("completionRevision", 3).put("viewedCompletionRevision", 2),
        )
        assertEquals(3, snap.completionRevision)
        val parsed = WsIncoming.parse(JSONObject("""{
            "type":"status","sessionId":"finished",
            "data":{"completionRevision":2,"viewedCompletionRevision":2}
        }""")).toSessionEvent() as SessionEvent.StatusChanged
        assertEquals(2, parsed.changes.completionRevision)
        val state = ChatSessionEventReducer.applySnapshot(ChatSessionEventState(), snap)
        val merged = ChatSessionEventReducer.reduce(state, parsed)
        assertEquals(3, merged.snapshot?.completionRevision)
        assertEquals(2, merged.snapshot?.viewedCompletionRevision)
        val acked = ChatSessionEventReducer.reduce(merged, SessionEvent.StatusChanged(
            sessionId = "finished", permissionRequest = null, responding = null,
            changes = SessionChanges(completionRevision = 3, viewedCompletionRevision = 3),
        ))
        val delayed = ChatSessionEventReducer.applySnapshot(acked, snap)
        assertEquals(3, delayed.snapshot?.viewedCompletionRevision)
    }
}
