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
    fun activityLabelsMatchTheWebFoldBar() {
        assertEquals("深度思考", activityThinkingLabel("   "))
        assertEquals("先看目录结构", activityThinkingLabel("先看目录结构"))
        assertEquals(
            "运行 ls -la",
            activityToolLabel("Bash", JSONObject().put("command", "ls -la")),
        )
        assertEquals(
            "读取 android/app/src/App.kt",
            activityToolLabel("Read", JSONObject().put("file_path", "android/app/src/App.kt")),
        )
        assertEquals("修改 文件", activityToolLabel("Edit", JSONObject()))
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
}
