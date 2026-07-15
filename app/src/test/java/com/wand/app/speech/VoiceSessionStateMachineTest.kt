package com.wand.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionStateMachineTest {
    @Test
    fun recordingReleaseWaitsForFinalAndCommitsItOnce() {
        var state = reduce(VoiceSessionState(), VoiceSessionEvent.Begin).state
        state = reduce(state, VoiceSessionEvent.Partial("draft")).state

        val released = reduce(state, VoiceSessionEvent.Release)
        assertEquals(VoiceSessionPhase.AWAITING_FINAL, released.state.phase)
        assertSame(VoiceSessionEffect.FinishEngine, released.effect)

        val completed = reduce(released.state, VoiceSessionEvent.Complete(" final "))
        assertEquals(VoiceSessionState(), completed.state)
        assertEquals("final", (completed.effect as VoiceSessionEffect.Commit).text)

        val duplicate = reduce(completed.state, VoiceSessionEvent.Complete("duplicate"))
        assertSame(VoiceSessionEffect.None, duplicate.effect)
    }

    @Test
    fun emptyFinalAndTimeoutFallBackToLatestPartial() {
        val awaiting = awaitingWithPartial(" partial ")

        val emptyFinal = reduce(awaiting, VoiceSessionEvent.Complete(""))
        val timeout = reduce(awaiting, VoiceSessionEvent.Complete(null))

        assertEquals("partial", (emptyFinal.effect as VoiceSessionEffect.Commit).text)
        assertEquals("partial", (timeout.effect as VoiceSessionEffect.Commit).text)
    }

    @Test
    fun cancelReleaseDiscardsTranscriptAndCancelsEngine() {
        var state = reduce(VoiceSessionState(), VoiceSessionEvent.Begin).state
        state = reduce(state, VoiceSessionEvent.Partial("discard me")).state
        state = reduce(state, VoiceSessionEvent.CancelChanged(true)).state
        assertTrue(state.canceling)

        val released = reduce(state, VoiceSessionEvent.Release)

        assertEquals(VoiceSessionState(), released.state)
        assertSame(VoiceSessionEffect.CancelEngine, released.effect)
    }

    @Test
    fun cancelCanBeReversedBeforeRelease() {
        var state = reduce(VoiceSessionState(), VoiceSessionEvent.Begin).state
        state = reduce(state, VoiceSessionEvent.CancelChanged(true)).state
        state = reduce(state, VoiceSessionEvent.CancelChanged(false)).state

        assertTrue(state.pressed)
        assertFalse(state.canceling)
        assertEquals(VoiceSessionEffect.FinishEngine, reduce(state, VoiceSessionEvent.Release).effect)
    }

    @Test
    fun idleAndAwaitingStatesIgnoreIllegalEvents() {
        val idle = VoiceSessionState()
        assertEquals(idle, reduce(idle, VoiceSessionEvent.Partial("late")).state)
        assertEquals(idle, reduce(idle, VoiceSessionEvent.Release).state)

        val awaiting = awaitingWithPartial("draft")
        assertEquals(awaiting, reduce(awaiting, VoiceSessionEvent.CancelChanged(true)).state)
        assertEquals(awaiting, reduce(awaiting, VoiceSessionEvent.Release).state)
        assertNull((reduce(awaiting.copy(transcript = ""), VoiceSessionEvent.Complete(null)).effect as VoiceSessionEffect.Commit).text)
    }

    private fun awaitingWithPartial(text: String): VoiceSessionState {
        var state = reduce(VoiceSessionState(), VoiceSessionEvent.Begin).state
        state = reduce(state, VoiceSessionEvent.Partial(text)).state
        return reduce(state, VoiceSessionEvent.Release).state
    }

    private fun reduce(state: VoiceSessionState, event: VoiceSessionEvent) =
        VoiceSessionStateMachine.reduce(state, event)
}
