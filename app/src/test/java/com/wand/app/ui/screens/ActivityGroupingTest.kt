package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityGroupingTest {
    @Test
    fun cardsOnlyExpandWhenConfigured() {
        assertFalse(shouldExpandChatCard(isLastTurn = true, configured = false))
        assertTrue(shouldExpandChatCard(isLastTurn = true, configured = true))
        assertFalse(shouldExpandChatCard(isLastTurn = false, configured = false))
        assertTrue(shouldExpandChatCard(isLastTurn = false, configured = true))
    }

    @Test
    fun thinkingAndToolsFoldIntoTheActivityBar() {
        assertTrue(shouldCollapseToolInActivity("TodoWrite"))
        assertTrue(shouldCollapseToolInActivity("mcp__codex__update_plan"))
        assertTrue(shouldCollapseToolInActivity("Edit"))
        assertTrue(shouldCollapseToolInActivity("Write"))
        assertTrue(shouldCollapseToolInActivity("Bash"))
        assertTrue(shouldCollapseToolInActivity("Read"))
    }

    @Test
    fun askUserQuestionsStayVisibleOutsideTheActivityBar() {
        assertFalse(shouldCollapseToolInActivity("AskUserQuestion"))
    }

    @Test
    fun todoReadsRemainEligibleForExplorationGrouping() {
        assertFalse(isTodoUpdateToolName("TodoRead"))
        assertTrue(shouldCollapseToolInActivity("TodoRead"))
    }

    @Test
    fun compactTodoSummaryOnlyShowsTheItemCount() {
        assertEquals("2 项", todoUpdateSummary(2))
        assertEquals("", todoUpdateSummary(null))
    }

    @Test
    fun todoUpdatesAreCompletedEvenWhileTheReplyStreamContinues() {
        assertFalse(isToolCardRunning("TodoWrite", sessionReportsRunning = true))
        assertFalse(isToolCardRunning("TaskUpdate", sessionReportsRunning = true))
        assertTrue(isToolCardRunning("Bash", sessionReportsRunning = true))
    }

    @Test
    fun activityKindsMatchTheWebFoldBar() {
        assertEquals("command", activityKindOf("Bash"))
        assertEquals("read", activityKindOf("Read"))
        assertEquals("edit", activityKindOf("TodoWrite"))
        assertEquals("search", activityKindOf("Grep"))
    }

    /** 折叠条的分段规则，直接测生产路径 collapseActivityItems（不再有只给测试用的包装函数）。 */
    private fun foldSegments(
        blocks: List<ContentBlock>,
        isLastTurn: Boolean = false,
        isResponding: Boolean = false,
    ): List<SegmentRenderItem> =
        collapseActivityItems(pairToolBlocks(blocks), isLastTurn, isResponding)

    private fun foldShape(
        blocks: List<ContentBlock>,
        isLastTurn: Boolean = false,
        isResponding: Boolean = false,
    ): List<Triple<Boolean, Int, Boolean>> = foldSegments(blocks, isLastTurn, isResponding).map { item ->
        when (item) {
            is SegmentRenderItem.Item -> Triple(false, 1, false)
            is SegmentRenderItem.Activity -> Triple(true, item.group.count, item.group.running)
        }
    }

    @Test
    fun consecutiveActivitiesFoldUntilProseSplitsThem() {
        val thinking = ContentBlock.Thinking("planning", null)
        val first = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val firstResult = ContentBlock.ToolResult("t1", "ok", false, false, null)
        val prose = ContentBlock.Text("先看结果", null)
        val second = ContentBlock.ToolUse("t2", "Read", null, JSONObject().put("file_path", "a.kt"), null)
        val moreProse = ContentBlock.Text("继续", null)

        val segments = foldShape(
            listOf(thinking, first, firstResult, prose, second, moreProse),
        )

        assertEquals(4, segments.size)
        assertEquals(Triple(true, 2, false), segments[0])
        assertEquals(false, segments[1].first)
        assertEquals(Triple(true, 1, false), segments[2])
        assertEquals(false, segments[3].first)
    }

    @Test
    fun trailingActivityBarIsRunningWhileTheTurnIsStillOpen() {
        val thinking = ContentBlock.Thinking("planning", null)
        val prose = ContentBlock.Text("中间结论", null)
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)

        val segments = foldShape(
            listOf(thinking, prose, use),
            isLastTurn = true,
            isResponding = true,
        )

        assertEquals(3, segments.size)
        assertEquals(Triple(true, 1, false), segments[0])
        assertEquals(false, segments[1].first)
        assertEquals(Triple(true, 1, true), segments[2])
    }

    @Test
    fun trailingFinishedToolsStayLiveUntilProseOrTheTurnEnds() {
        val thinking = ContentBlock.Thinking("planning", null)
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val result = ContentBlock.ToolResult("t1", "ok", false, false, null)

        val live = foldShape(
            listOf(thinking, use, result),
            isLastTurn = true,
            isResponding = true,
        )
        assertEquals(1, live.size)
        assertEquals(Triple(true, 2, true), live[0])

        val settled = foldShape(
            listOf(thinking, use, result),
            isLastTurn = true,
            isResponding = false,
        )
        assertEquals(Triple(true, 2, false), settled[0])
    }

    @Test
    fun activityBarCompletesOnceProseClosesTheTurn() {
        val thinking = ContentBlock.Thinking("planning", null)
        val prose = ContentBlock.Text("最终回答", null)
        val segments = foldShape(
            listOf(thinking, prose),
            isLastTurn = true,
            isResponding = true,
        )

        assertEquals(2, segments.size)
        assertEquals(Triple(true, 1, false), segments[0])
        assertEquals(false, segments[1].first)
    }

    private fun activityGroups(
        blocks: List<ContentBlock>,
        isLastTurn: Boolean = false,
        isResponding: Boolean = false,
    ): List<ActivityGroup> = collapseActivityItems(pairToolBlocks(blocks), isLastTurn, isResponding)
        .filterIsInstance<SegmentRenderItem.Activity>()
        .map { it.group }

    /** 正文把活动切成两段：只有最后一段是「最新」（默认展开），旧段自动收回状态条。 */
    @Test
    fun onlyTheTrailingActivitySegmentIsNewest() {
        val thinking = ContentBlock.Thinking("planning", null)
        val first = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val prose = ContentBlock.Text("先看结果", null)
        val second = ContentBlock.ToolUse("t2", "Read", null, JSONObject().put("file_path", "a.kt"), null)

        val groups = activityGroups(listOf(thinking, first, prose, second), isLastTurn = true)

        assertEquals(2, groups.size)
        assertFalse(groups[0].newest)
        assertTrue(groups[1].newest)
    }

    /** 同一段 blocks 只因为「不再是最新一轮」就应该丢掉 newest（历史卡自动折叠）。 */
    @Test
    fun newestClearsWhenTheTurnIsNoLongerLast() {
        val thinking = ContentBlock.Thinking("planning", null)
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)

        val live = activityGroups(listOf(thinking, use), isLastTurn = true)
        val settled = activityGroups(listOf(thinking, use), isLastTurn = false)

        assertTrue(live.last().newest)
        assertFalse(settled.last().newest)
    }

    /** 窗口尾部条目的派生默认值才是展开；推导必须由 group.items 的最后一个下标决定。 */
    @Test
    fun trailingItemIsTheWindowTail() {        val thinking = ContentBlock.Thinking("planning", null)
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val result = ContentBlock.ToolResult("t1", "ok", false, false, null)

        val group = activityGroups(listOf(thinking, use, result), isLastTurn = true).single()
        val tailIndex = group.items.lastIndex

        // 思考 + 配对后的工具卡 = 2 条；工具卡与结果配成同一条。
        assertEquals(1, tailIndex)
        assertTrue(cardExpandDefault(inWindowTail = tailIndex == group.items.lastIndex, configured = false))
        assertFalse(cardExpandDefault(inWindowTail = 0 == group.items.lastIndex, configured = false))
    }

    /**
     * v2 勘误 2：分组键（会进活动窗口的 fold cardId）不得包含内容片段。
     * 思考文本从 `"p"` 长到 200 字，`group.key` 必须完全不变。
     */
    @Test
    fun activityGroupKeyIgnoresStreamingContent() {
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)

        fun keys(thinkingText: String): List<String> =
            activityGroups(listOf(ContentBlock.Thinking(thinkingText, null), use)).map { it.key }

        val short = keys("p")
        val grown = keys("planning")
        val long = keys("planning".repeat(30))
        assertEquals(1, short.size)
        assertEquals(short, grown)
        assertEquals(grown, long)
        assertEquals("0:thinking", short.single())
    }
}
