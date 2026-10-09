package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** First guard: lifecycle callbacks must actually dispatch to the page's arrival owner. */
class TeamChatArrivalLifecycleTest {
    @Test fun pauseAndStopMustConsumePageArrivalState() {
        val screen = File("src/main/java/com/wand/app/ui/screens/ConversationScreens.kt").readText()
        assertTrue("ON_PAUSE/ON_STOP must reach page-owned state", screen.contains("Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) arrivals.clear()"))
        assertTrue("new arrival must be gated by foreground", screen.contains("foreground && !reducedMotion"))
        assertTrue("old row callback must be scoped", screen.contains("remember(displayedId, displayedFilter) { mutableStateMapOf<String, Boolean>() }"))
    }

    @Test fun pauseThenStopThenResumeConsumesOldBatchButNotLaterOne() {
        var page = TeamArrivalState(scope = "run-A/chat-A")
        page = teamArrivalAdmitted(page, page.scope, setOf("turn-1"), pinned = true, motionEnabled = true)
        assertEquals(setOf("turn-1"), page.activeIds)
        page = teamArrivalPaused(page) // ON_PAUSE
        assertFalse(page.foreground)
        assertTrue("playing=false makes row draw alpha1/translation0", page.activeIds.isEmpty())
        assertSame("repeated ON_STOP has no new side effects", page, teamArrivalPaused(page))
        assertSame("paused batches cannot enter", page,
            teamArrivalAdmitted(page, page.scope, setOf("late"), pinned = true, motionEnabled = true))
        page = teamArrivalResumed(page)
        assertTrue(page.foreground)
        assertTrue("no deferred animation when resumed", page.activeIds.isEmpty())
        page = teamArrivalAdmitted(page, page.scope, setOf("turn-2"), pinned = true, motionEnabled = true)
        assertEquals(setOf("turn-2"), page.activeIds)
        assertSame("duplicate ON_RESUME must not clear the new batch", page, teamArrivalResumed(page))
        assertSame("old row callback cannot consume the new batch", page,
            teamArrivalConsumed(page, "run-B/chat-B", "turn-2"))
        page = teamArrivalConsumed(page, page.scope, "turn-2")
        assertTrue(page.activeIds.isEmpty())
    }

    @Test fun scopeReduceAndScrollOnlyAllowProvenForegroundFirstVisibility() {
        var state = TeamArrivalState(scope = "run-A/chat-A")
        state = teamArrivalAdmitted(state, state.scope, setOf("tail"), pinned = false, motionEnabled = true)
        assertTrue("user upscroll is static", state.activeIds.isEmpty())
        state = teamArrivalAdmitted(state, state.scope, setOf("tail"), pinned = true, motionEnabled = false)
        assertTrue("reduce-motion is static", state.activeIds.isEmpty())
        state = teamArrivalAdmitted(state, state.scope, setOf("tail"), pinned = true, motionEnabled = true)
        assertEquals(setOf("tail"), state.activeIds)
        state = teamArrivalReduced(state)
        assertTrue("dynamic reduce consumes the current batch", state.activeIds.isEmpty())
        state = teamArrivalForScope(state, "run-B/chat-B")
        assertEquals("run-B/chat-B", state.scope)
        state = teamArrivalAdmitted(state, state.scope, setOf("turn-1"), pinned = true, motionEnabled = true)
        assertEquals(setOf("turn-1"), state.activeIds)
        assertSame("old scope disposal must not eat a same-numbered new handle", state,
            teamArrivalConsumed(state, "run-A/chat-A", "turn-1"))
    }

    @Test fun drawableFrameSnapsAtPauseReduceAndKeepsFullLayoutHeight() {
        assertEquals(TeamArrivalFrame(0f, 4f), teamArrivalFrame(0f, true, 4f))
        assertEquals(TeamArrivalFrame(0.5f, 2f), teamArrivalFrame(0.5f, true, 4f))
        assertEquals(TeamArrivalFrame(1f, 0f), teamArrivalFrame(1f, true, 4f))
        assertEquals(TeamArrivalFrame(1f, 0f), teamArrivalFrame(0f, false, 4f))
        assertEquals(TeamArrivalFrame(1f, 0f), teamArrivalFrame(0.5f, false, 4f))
    }
}
