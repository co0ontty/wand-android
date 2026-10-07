package com.wand.app.ui.screens

import com.wand.app.data.ConversationTurn
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingPresentationTest {
    @Test
    fun clockUsesRealReplyStartInLocalTimezoneRatherThanReplyCompletion() {
        val turn = ConversationTurn("assistant", emptyList(),
            createdAt = "2026-10-04T18:34:30.395Z", completedAt = "2026-10-04T18:57:54.999Z")
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("02:34:30", thinkingEventClock(turn.createdAt, zone))
        assertEquals(commandEventClock(Instant.parse(turn.createdAt), zone), thinkingEventClock(turn.createdAt, zone))
        assertEquals("18:34:30", thinkingEventClock(turn.createdAt, ZoneOffset.UTC))
        assertEquals("18:34:30", thinkingEventClock(" ${turn.createdAt} ", ZoneOffset.UTC))
    }

    @Test
    fun missingOrInvalidStartTimeDoesNotInventAClock() {
        assertNull(thinkingEventClock(null))
        assertNull(thinkingEventClock(""))
        assertNull(thinkingEventClock("  "))
        assertNull(thinkingEventClock("unknown"))
        assertNull(thinkingEventClock("02:34:30"))
        assertNull(thinkingEventClock("2026-10-04"))
    }

    @Test
    fun fiveLinePreviewExpandsToFullTextAndCollapsesWithoutChangingItsIdentity() {
        assertEquals(5, thinkingTextMaxLines(expanded = false))
        assertEquals(Int.MAX_VALUE, thinkingTextMaxLines(expanded = true))
        val sessionKey = cardFoldKey("session-a", "turn-4/activity:thinking-0")
        var override = FOLD_OVERRIDE_NONE
        assertFalse(foldExpanded(override, derivedDefault = false))
        override = foldToggleCode(override, derivedDefault = false)
        assertTrue(foldExpanded(override, derivedDefault = false))
        assertEquals(Int.MAX_VALUE, thinkingTextMaxLines(foldExpanded(override, derivedDefault = false)))
        // 流式追加或补上时间不会进入 fold key，也不会回到五行。
        assertEquals(sessionKey, cardFoldKey("session-a", "turn-4/activity:thinking-0"))
        override = foldToggleCode(override, derivedDefault = false)
        assertFalse(foldExpanded(override, derivedDefault = false))
        assertEquals(5, thinkingTextMaxLines(foldExpanded(override, derivedDefault = false)))
    }
}
