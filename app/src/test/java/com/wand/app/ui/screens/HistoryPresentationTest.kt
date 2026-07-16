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
    fun onlyRepliesBeforeTheLatestUserInputStartCollapsed() {
        assertFalse(shouldCollapseReply(turnIndex = 5, lastUserTurnIndex = -1))
        assertTrue(shouldCollapseReply(turnIndex = 3, lastUserTurnIndex = 4))
        assertFalse(shouldCollapseReply(turnIndex = 5, lastUserTurnIndex = 4))
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
    fun upToThreeExplorationCallsStayAsIndividualTurns() {
        val turns = List(3) { index -> explorationTurn("tool-$index") }

        val items = groupExplorationTurns(turns)

        assertEquals(3, items.size)
        assertTrue(items.all { it is MessageDisplayItem.Turn })
    }

    @Test
    fun fourExplorationCallsCollapseIntoOneGroup() {
        val turns = List(4) { index -> explorationTurn("tool-$index") }

        val items = groupExplorationTurns(turns)

        assertEquals(1, items.size)
        assertTrue(items.single() is MessageDisplayItem.Exploration)
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

    private fun explorationTurn(id: String) = ConversationTurn(
        role = "assistant",
        content = listOf(explorationTool(id).use),
    )

    private fun explorationTool(id: String) = ExplorationToolItem(
        use = ContentBlock.ToolUse(
            id = id,
            name = "Grep",
            description = null,
            input = JSONObject(),
            subagent = null,
        ),
        result = null,
    )
}
