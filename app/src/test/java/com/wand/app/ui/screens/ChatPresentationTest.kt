package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPresentationTest {
    @Test
    fun scrollingTowardHistoryImmediatelyPausesBottomFollow() {
        assertEquals(true, shouldPauseBottomFollow(0.01f))
        assertEquals(false, shouldPauseBottomFollow(0f))
        assertEquals(false, shouldPauseBottomFollow(-12f))
    }

    @Test
    fun quickCommitStatusRefreshWaitsForIdleLoadedSession() {
        assertEquals(false, shouldRefreshQuickCommitStatus(isLoading = true, isResponding = false))
        assertEquals(false, shouldRefreshQuickCommitStatus(isLoading = false, isResponding = true))
        assertEquals(true, shouldRefreshQuickCommitStatus(isLoading = false, isResponding = false))
    }

    @Test
    fun launchModelLabelRemovesDuplicatedCaseInsensitiveId() {
        assertEquals(
            "GPT-5.6-Sol",
            compactModelDisplayLabel("GPT-5.6-Sol · gpt-5.6-sol", "gpt-5.6-sol"),
        )
    }

    @Test
    fun launchModelLabelKeepsUsefulQualifier() {
        assertEquals(
            "GPT-5.6-Sol · 最新稳定版",
            compactModelDisplayLabel("GPT-5.6-Sol · 最新稳定版", "gpt-5.6-sol"),
        )
    }
}
