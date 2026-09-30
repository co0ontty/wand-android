package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ToolActivity
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

    private fun tool(id: String, name: String, kind: String? = null, fileKey: String? = null): ContentBlock.ToolUse =
        ContentBlock.ToolUse(
            id = id,
            name = name,
            description = null,
            input = JSONObject(),
            subagent = null,
            activity = kind?.let { ToolActivity(it, "", fileKey) },
        )

    @Test
    fun compactToolsFormSummaryWhileThinkingAndLegacyToolsStayInPlace() {
        val blocks = listOf(
            ContentBlock.Thinking("planning", null),
            tool("old", "Read"),
            tool("t1", "Edit", "edit_file", "file-a"),
            tool("t2", "Bash", "run_command"),
            ContentBlock.Text("done", null),
        )
        val segments = collapseActivityItems(pairToolBlocks(blocks), true, true)
        assertEquals(4, segments.size)
        assertTrue(segments[0] is SegmentRenderItem.Item)
        assertTrue(segments[1] is SegmentRenderItem.Item)
        val group = (segments[2] as SegmentRenderItem.Activity).group
        assertEquals(2, group.items.size)
        assertFalse(group.running)
        assertTrue(segments[3] is SegmentRenderItem.Item)
    }

    @Test
    fun onlyTrailingSummaryBreathesWhileReplyRuns() {
        val segments = collapseActivityItems(
            pairToolBlocks(listOf(tool("t1", "Edit", "edit_file", "file-a"))),
            isLastTurn = true,
            isResponding = true,
        )
        assertTrue((segments.single() as SegmentRenderItem.Activity).group.running)
    }

    @Test
    fun fileCountsDeduplicateAnonymousFileKeyButDetailsKeepEachToolId() {
        val calls = listOf(
            tool("e1", "Edit", "edit_file", "same-file"),
            tool("e2", "Write", "edit_file", "same-file"),
            tool("e3", "Edit", "edit_file", "next-file"),
            tool("r1", "Read", "read_file", "same-file"),
            tool("c1", "Bash", "run_command"),
            tool("c2", "Bash", "run_command"),
            tool("x1", "mcp__other", "other"),
        ).map { DisplayItem.Tool(it, null) }
        val categories = toolActivityCategories(calls)
        assertEquals(
            listOf("修改了 2 个文件", "查看了 1 个文件", "运行了 2 条命令", "其他 1 次调用"),
            categories.map { it.title },
        )
        assertEquals(2, categories.first().entries.size)
        assertEquals(listOf("e1", "e2"), categories.first().entries[0].calls.map { it.use.id })
        assertEquals(listOf("e3"), categories.first().entries[1].calls.map { it.use.id })
        assertEquals(2, categories[2].entries.size)
    }

    @Test
    fun repeatedTransportToolIdCountsOnceAndEmptyCategoriesAreHidden() {
        val use = tool("e1", "Edit", "edit_file", "same-file")
        val categories = toolActivityCategories(
            listOf(DisplayItem.Tool(use, null), DisplayItem.Tool(use, null)),
        )
        assertEquals(1, categories.size)
        assertEquals(1, categories.single().count)
        assertEquals(1, categories.single().entries.size)
        assertEquals(1, categories.single().entries.single().calls.size)
    }

    @Test
    fun summaryScopeDoesNotChangeWhenAnotherToolArrives() {
        fun key(blocks: List<ContentBlock>) = collapseActivityItems(
            pairToolBlocks(blocks),
            isLastTurn = true,
            isResponding = true,
        ).filterIsInstance<SegmentRenderItem.Activity>().single().group.key
        val first = tool("t1", "Edit", "edit_file", "file-a")
        assertEquals("tool:t1", key(listOf(first)))
        assertEquals(key(listOf(first)), key(listOf(first, tool("t2", "Bash", "run_command"))))
        assertEquals(key(listOf(first)), key(listOf(ContentBlock.Thinking("planning", null), first)))
    }
}
