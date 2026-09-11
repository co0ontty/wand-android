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

    @Test
    fun messageActivityStaysOpenUntilProseArrives() {
        val thinking = ContentBlock.Thinking("planning", null)
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val result = ContentBlock.ToolResult("t1", "ok", false, false, null)
        val text = ContentBlock.Text("done", null)

        assertTrue(isMessageActivityOpen(listOf(thinking)))
        assertTrue(isMessageActivityOpen(listOf(thinking, use)))
        assertFalse(isMessageActivityOpen(listOf(thinking, use, result)))
        assertFalse(isMessageActivityOpen(listOf(thinking, use, result, text)))
        assertTrue(isMessageActivityOpen(listOf(text, thinking)))
    }

    @Test
    fun consecutiveActivitiesFoldUntilProseSplitsThem() {
        val thinking = ContentBlock.Thinking("planning", null)
        val first = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val firstResult = ContentBlock.ToolResult("t1", "ok", false, false, null)
        val prose = ContentBlock.Text("先看结果", null)
        val second = ContentBlock.ToolUse("t2", "Read", null, JSONObject().put("file_path", "a.kt"), null)
        val moreProse = ContentBlock.Text("继续", null)

        val segments = activityFoldSegments(
            listOf(thinking, first, firstResult, prose, second, moreProse),
        )

        assertEquals(4, segments.size)
        assertTrue(segments[0].activity)
        assertEquals(2, segments[0].count)
        assertFalse(segments[0].running)
        assertFalse(segments[1].activity)
        assertTrue(segments[2].activity)
        assertEquals(1, segments[2].count)
        assertFalse(segments[3].activity)
    }

    @Test
    fun trailingActivityBarIsRunningWhileTheTurnIsStillOpen() {
        val thinking = ContentBlock.Thinking("planning", null)
        val prose = ContentBlock.Text("中间结论", null)
        val use = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)

        val segments = activityFoldSegments(
            listOf(thinking, prose, use),
            isLastTurn = true,
            isResponding = true,
        )

        assertEquals(3, segments.size)
        assertTrue(segments[0].activity)
        assertFalse(segments[0].running)
        assertFalse(segments[1].activity)
        assertTrue(segments[2].activity)
        assertTrue(segments[2].running)
    }

    @Test
    fun activityBarCompletesOnceProseClosesTheTurn() {
        val thinking = ContentBlock.Thinking("planning", null)
        val prose = ContentBlock.Text("最终回答", null)
        val segments = activityFoldSegments(
            listOf(thinking, prose),
            isLastTurn = true,
            isResponding = true,
        )

        assertEquals(2, segments.size)
        assertTrue(segments[0].activity)
        assertFalse(segments[0].running)
        assertFalse(segments[1].activity)
    }
}
