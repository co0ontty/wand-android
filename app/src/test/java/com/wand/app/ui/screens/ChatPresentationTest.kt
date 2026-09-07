package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
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

    @Test
    fun firstChatLayoutDoesNotAnimateListItems() {
        assertEquals(false, shouldAnimateChatListItems(listSettled = false))
        assertEquals(true, shouldAnimateChatListItems(listSettled = true))
    }

    @Test
    fun firstChatPaintUsesASingleStickToBottomRetry() {
        assertEquals(listOf(80L), chatStickToBottomRetryDelaysMs(listSettled = false))
        assertEquals(
            listOf(50L, 150L, 350L, 700L),
            chatStickToBottomRetryDelaysMs(listSettled = true),
        )
    }

    @Test
    fun conversationScrubberStaysHiddenForASingleUserTurn() {
        assertEquals(false, shouldShowConversationTurnScrubber(0))
        assertEquals(false, shouldShowConversationTurnScrubber(1))
        assertEquals(true, shouldShowConversationTurnScrubber(2))
        assertEquals(true, shouldShowConversationTurnScrubber(12))
    }

    @Test
    fun conversationScrubberCountsUserTurnsNotAssistantReplies() {
        val items = groupExplorationTurns(
            listOf(
                textTurn("user", "hi"),
                textTurn("assistant", "hello"),
                textTurn("user", "fix scrollbar"),
                textTurn("assistant", "done"),
            ),
        )

        val targets = conversationScrubberTargets(items)

        assertEquals(listOf(0, 2), targets)
        assertEquals(true, shouldShowConversationTurnScrubber(targets.size))
        assertEquals(
            listOf(0),
            conversationScrubberTargets(
                groupExplorationTurns(
                    listOf(
                        textTurn("user", "hi"),
                        textTurn("assistant", "hello"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun conversationScrubberHighlightsTheUserTurnForAVisibleReply() {
        val targets = listOf(0, 2)
        assertEquals(0, conversationScrubberIndexForDisplayItem(targets, 0))
        assertEquals(0, conversationScrubberIndexForDisplayItem(targets, 1))
        assertEquals(1, conversationScrubberIndexForDisplayItem(targets, 2))
        assertEquals(1, conversationScrubberIndexForDisplayItem(targets, 3))
    }

    private fun textTurn(role: String, text: String) = ConversationTurn(
        role = role,
        content = listOf(ContentBlock.Text(text, null)),
    )
}
