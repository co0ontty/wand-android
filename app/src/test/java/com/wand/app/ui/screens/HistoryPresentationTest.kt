package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.SubagentMeta
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

    @Test
    fun latestSubagentsMoveIntoThePersistentActivityModel() {
        val first = SubagentMeta("task-1", "Explore", "检查界面")
        val second = SubagentMeta("task-2", "Review", "复核交互")
        val messages = listOf(
            textTurn("user", "优化 Agent 展示"),
            ConversationTurn(
                role = "assistant",
                content = listOf(
                    ContentBlock.ToolUse("task-1", "Task", null, JSONObject(), first),
                    ContentBlock.Text("正在检查", first),
                    ContentBlock.ToolResult("task-1", "已完成", false, false, first),
                    ContentBlock.ToolUse("task-2", "Task", null, JSONObject(), second),
                    ContentBlock.Thinking("继续复核", second),
                ),
            ),
        )

        val activities = collectSubagentActivities(messages, sessionRunning = true)

        assertEquals(listOf("task-1", "task-2"), activities.map { it.id })
        assertFalse(activities[0].running)
        assertTrue(activities[1].running)
        assertEquals(3, activities[0].blocks.size)
    }

    @Test
    fun aNewHumanTurnKeepsPreviousAgentsAvailableButCompleted() {
        val meta = SubagentMeta("task-old", "Explore", null)
        val messages = listOf(
            textTurn("user", "上一轮"),
            ConversationTurn("assistant", listOf(ContentBlock.Text("旧输出", meta))),
            textTurn("user", "下一轮"),
        )

        val activities = collectSubagentActivities(messages, sessionRunning = true)

        assertEquals(1, activities.size)
        assertEquals("task-old", activities.single().id)
        assertFalse(activities.single().running)
    }

    @Test
    fun agentLogoIsStableAndAlwaysUsesAValidVariant() {
        val first = agentLogoVariant("task-alpha")
        val same = agentLogoVariant("task-alpha")
        val second = agentLogoVariant("task-beta")

        assertEquals(first, same)
        assertTrue(first.paletteIndex in 0..4)
        assertTrue(first.facetIndex in 0..2)
        assertTrue(second.paletteIndex in 0..4)
        assertTrue(second.facetIndex in 0..2)
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
