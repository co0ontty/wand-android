package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class ChatPresentationTest {
    @Test
    fun scrollingTowardHistoryImmediatelyPausesBottomFollow() {
        assertEquals(true, shouldPauseBottomFollow(0.01f))
        assertEquals(false, shouldPauseBottomFollow(0f))
        assertEquals(false, shouldPauseBottomFollow(-12f))
    }

    @Test
    fun scrollingToTheTopQuietlyLoadsOneEarlierPage() {
        fun shouldLoad(
            canLoadEarlier: Boolean = true,
            userPulledToTop: Boolean = true,
            loadedRowCount: Int = 20,
            measuredRowHeightsPx: List<Int> = List(6) { 900 },
            viewportHeightPx: Int = 1000,
        ) = shouldLoadEarlierPage(
            canLoadEarlier = canLoadEarlier,
            userPulledToTop = userPulledToTop,
            loadedRowCount = loadedRowCount,
            measuredRowHeightsPx = measuredRowHeightsPx,
            viewportHeightPx = viewportHeightPx,
        )

        // 用户上拉到列表绝对顶部才翻页；还在一条长回复中间（没到顶）不翻。
        assertEquals(true, shouldLoad())
        assertEquals(false, shouldLoad(canLoadEarlier = false))
        assertEquals(false, shouldLoad(userPulledToTop = false))
    }

    @Test
    fun openingASessionFillsTwoScreensWithoutAnyUserScroll() {
        fun fills(
            loadedRowCount: Int,
            measuredRowHeightsPx: List<Int>,
            viewportHeightPx: Int = 1000,
        ) = shouldLoadEarlierPage(
            canLoadEarlier = true,
            userPulledToTop = false,
            loadedRowCount = loadedRowCount,
            measuredRowHeightsPx = measuredRowHeightsPx,
            viewportHeightPx = viewportHeightPx,
        )

        // 6 行 × 200px = 1200px < 2 × 1000px：打开会话时静默补上一页。
        assertEquals(true, fills(loadedRowCount = 6, measuredRowHeightsPx = List(6) { 200 }))
        // 10 行 × 200px = 两屏：不再往下翻，等用户自己上拉。
        assertEquals(false, fills(loadedRowCount = 10, measuredRowHeightsPx = List(6) { 200 }))
        // 还没量到任何行、视口未知时什么都不做。
        assertEquals(false, fills(loadedRowCount = 6, measuredRowHeightsPx = emptyList()))
        assertEquals(false, fills(loadedRowCount = 0, measuredRowHeightsPx = List(6) { 200 }))
        assertEquals(
            false,
            fills(loadedRowCount = 6, measuredRowHeightsPx = List(6) { 200 }, viewportHeightPx = 0),
        )
    }

    @Test
    fun earlierAnchorKeepsReadingPositionWhenTheSameItemGrowsFromTheHead() {
        val anchor = EarlierLoadAnchor("turn-1", scrollOffset = 40, turnOffset = 1, blockOffset = 80, itemSize = 900)

        assertEquals(40, earlierAnchorScrollOffset(anchor, laidOutSize = null))
        assertEquals(40, earlierAnchorScrollOffset(anchor, laidOutSize = 800))
        assertEquals(340, earlierAnchorScrollOffset(anchor, laidOutSize = 1200))
    }

    @Test
    fun earlierPageOnlyRestoresAnchorAfterBlockOrTurnCursorMovesBack() {
        val anchor = EarlierLoadAnchor("turn-80", 12, turnOffset = 80, blockOffset = 50)

        assertEquals(false, earlierLoadAdvanced(anchor, turnOffset = 80, blockOffset = 50))
        assertEquals(false, earlierLoadAdvanced(anchor, turnOffset = 81, blockOffset = 0))
        assertEquals(true, earlierLoadAdvanced(anchor, turnOffset = 80, blockOffset = 10))
        assertEquals(true, earlierLoadAdvanced(anchor, turnOffset = 40, blockOffset = 0))
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

    @Test
    fun structuredActivityDockDoesNotStayOpenJustToRepeatCompletionTime() {
        assertEquals(
            false,
            shouldShowStructuredActivityDock(
                isStructured = true,
                isResponding = false,
                hasSubagentActivities = false,
            ),
        )
        assertEquals(
            true,
            shouldShowStructuredActivityDock(
                isStructured = true,
                isResponding = true,
                hasSubagentActivities = false,
            ),
        )
        assertEquals(
            true,
            shouldShowStructuredActivityDock(
                isStructured = true,
                isResponding = false,
                hasSubagentActivities = true,
            ),
        )
        assertEquals(
            false,
            shouldShowStructuredActivityDock(
                isStructured = false,
                isResponding = true,
                hasSubagentActivities = true,
            ),
        )
    }

    @Test
    fun chatClockUsesTimeOfDayForSameDay() {
        val zone = ZoneOffset.UTC
        val today = java.time.ZonedDateTime.now(zone).toLocalDate().atTime(9, 8, 7).atZone(zone)
        assertEquals("09:08:07", formatChatClock(today.toInstant().toString(), zone))
    }

    @Test
    fun chatClockIncludesDateWhenNotToday() {
        assertEquals(
            "1/2 03:04:05",
            formatChatClock("2020-01-02T03:04:05Z", ZoneOffset.UTC),
        )
        assertEquals("", formatChatClock(null))
        assertEquals("", formatChatClock(""))
    }

    @Test
    fun collapsedComposerKeepsOnlyMinHeightSoScaledFontIsNotClipped() {
        // §2.15 R1：折叠态不得再有固定高度上限（原来是 34dp 上下限夹住，1.45 字体占位被裁）；
        // 展开态保留原有的内容滚动上限，两态切换不引入新数值。
        assertEquals(androidx.compose.ui.unit.Dp.Infinity, composerInputMaxHeight(expanded = false))
        assertEquals(androidx.compose.ui.unit.Dp(132f), composerInputMaxHeight(expanded = true))
        assertEquals(androidx.compose.ui.unit.Dp(132f), ComposerExpandedInputMaxHeight)
    }

    private fun textTurn(role: String, text: String) = ConversationTurn(
        role = role,
        content = listOf(ContentBlock.Text(text, null)),
    )
}
