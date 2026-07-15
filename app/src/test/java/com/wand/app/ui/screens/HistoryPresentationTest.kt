package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class HistoryPresentationTest {
    @Test
    fun turnKeyStaysStableWhenEarlierMessagesArePrepended() {
        val turn = ConversationTurn(role = "user", content = emptyList())

        val beforePrepend = messageItemKey(MessageDisplayItem.Turn(index = 5, turn = turn), loadedOffset = 100)
        val afterPrepend = messageItemKey(MessageDisplayItem.Turn(index = 15, turn = turn), loadedOffset = 90)

        assertEquals(beforePrepend, afterPrepend)
    }

    @Test
    fun historyExplorationKeyAnchorsTheRightEdgeAcrossPrependMerging() {
        val beforePrepend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("new-tool")),
            lastTurnIndex = 5,
        )
        val afterPrepend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("older-tool"), explorationTool("new-tool")),
            lastTurnIndex = 15,
        )

        assertEquals(
            messageItemKey(beforePrepend, loadedOffset = 100, anchorExplorationAtEnd = true),
            messageItemKey(afterPrepend, loadedOffset = 90, anchorExplorationAtEnd = true),
        )
    }

    @Test
    fun currentExplorationKeyAnchorsTheFirstToolWhileStreaming() {
        val beforeAppend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("first-tool")),
            lastTurnIndex = 5,
        )
        val afterAppend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("first-tool"), explorationTool("next-tool")),
            lastTurnIndex = 6,
        )

        assertEquals(
            messageItemKey(beforeAppend, loadedOffset = 100),
            messageItemKey(afterAppend, loadedOffset = 100),
        )
    }

    @Test
    fun historyAnchorStillTargetsTheSameAbsoluteTurnAfterPrepend() {
        val turn = ConversationTurn(role = "assistant", content = emptyList())
        val refreshedHistory = (0..15).map { index -> MessageDisplayItem.Turn(index, turn) }

        val target = historyAnchorListIndex(
            historyItems = refreshedHistory,
            loadedOffset = 90,
            anchorAbsoluteIndex = 105,
            hasLoadEarlierSentinel = true,
        )

        assertEquals(16, target)
    }

    @Test
    fun missingHistoryAnchorFallsBackToTheDisclosureRow() {
        val turn = ConversationTurn(role = "assistant", content = emptyList())
        val history = (0..2).map { index -> MessageDisplayItem.Turn(index, turn) }

        assertEquals(
            4,
            historyAnchorListIndex(
                historyItems = history,
                loadedOffset = 10,
                anchorAbsoluteIndex = 999,
                hasLoadEarlierSentinel = true,
            ),
        )
    }

    @Test
    fun emptyHistoryDisclosureAccountsForTheLoadEarlierControl() {
        assertEquals(1, historyDisclosureListIndex(0, hasLoadEarlierSentinel = true))
        assertEquals(0, historyDisclosureListIndex(0, hasLoadEarlierSentinel = false))
    }

    @Test
    fun loadEarlierRemainsReachableWhenTheTailWindowHasNoUserTurn() {
        assertTrue(
            shouldShowLoadEarlierControl(
                historyExpanded = false,
                hasCollapsedHistory = false,
                canLoadEarlier = true,
            ),
        )
        assertFalse(
            shouldShowLoadEarlierControl(
                historyExpanded = false,
                hasCollapsedHistory = true,
                canLoadEarlier = true,
            ),
        )
        assertTrue(
            shouldShowLoadEarlierControl(
                historyExpanded = true,
                hasCollapsedHistory = true,
                canLoadEarlier = true,
            ),
        )
        assertFalse(
            shouldShowLoadEarlierControl(
                historyExpanded = true,
                hasCollapsedHistory = false,
                canLoadEarlier = false,
            ),
        )
    }

    @Test
    fun attachmentOnlyUserTurnGetsAReadablePreview() {
        val turn = textTurn(
            "user",
            "[附件已上传，请查看以下文件:\n/tmp/screenshot.png]\n\n",
        )

        assertEquals("1 个附件", conversationTurnPreview(turn))
    }

    @Test
    fun toolOnlyTurnGetsAReadablePreview() {
        val turn = ConversationTurn(
            role = "assistant",
            content = listOf(
                explorationTool("first").use,
                explorationTool("second").use,
            ),
        )

        assertEquals("2 个工具调用", conversationTurnPreview(turn))
    }

    @Test
    fun compactPreviewRemovesCommonMarkdownAndWhitespace() {
        val source = """
            # Heading
            - **First**   item
            > `quoted` value
        """.trimIndent()

        assertEquals("Heading First item quoted value", compactPreviewText(source))
        assertEquals("snake_case link", compactPreviewText("`snake_case` [link](https://example.com)"))

        val splitSurrogate = "a".repeat(239) + "😀" + "tail"
        assertFalse(compactPreviewText(splitSurrogate).last().isHighSurrogate())
    }

    private fun textTurn(role: String, text: String) = ConversationTurn(
        role = role,
        content = listOf(ContentBlock.Text(text = text, subagent = null)),
    )

    private fun explorationTool(id: String) = ExplorationToolItem(
        use = ContentBlock.ToolUse(
            id = id,
            name = "Read",
            description = null,
            input = JSONObject(),
            subagent = null,
        ),
        result = null,
    )
}
