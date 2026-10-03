package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ToolActivity
import java.time.Instant
import java.time.ZoneId
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
    fun thinkingLegacyAndCompactToolsShareInlineGroups() {
        val blocks = listOf(
            ContentBlock.Thinking("planning", null),
            tool("old", "Read"),
            tool("t1", "Edit", "edit_file", "file-a"),
            tool("t2", "Bash", "run_command"),
            ContentBlock.Text("done", null),
        )
        val segments = collapseActivityItems(pairToolBlocks(blocks), true, true)
        assertEquals(2, segments.size)
        val group = (segments[0] as SegmentRenderItem.Activity).group
        assertEquals(4, group.items.size)
        assertTrue(group.running)
        assertTrue(segments[1] is SegmentRenderItem.Item)
    }

    @Test
    fun thinkingAndToolStayOneSegmentAsExecutionMovesForward() {
        val thinking = ContentBlock.Thinking("planning", null)
        val command = tool("run", "Bash", "run_command")
        val first = collapseActivityItems(pairToolBlocks(listOf(thinking)), true, true)
        val later = collapseActivityItems(pairToolBlocks(listOf(thinking, command)), true, true)
        val firstGroup = (first.single() as SegmentRenderItem.Activity).group
        val laterGroup = (later.single() as SegmentRenderItem.Activity).group
        assertEquals("thinking-position:0", firstGroup.key)
        assertEquals(firstGroup.key, laterGroup.key)
        assertEquals(2, laterGroup.items.size)
        assertTrue(laterGroup.running)
        val blankFirst = collapseActivityItems(
            pairToolBlocks(listOf(ContentBlock.Thinking("", null), command)), true, true,
        )
        assertEquals(firstGroup.key, (blankFirst.single() as SegmentRenderItem.Activity).group.key)
    }

    @Test
    fun blankStreamingThinkingHasVisiblePlaceholderAndStableKeyWhenTextArrives() {
        fun group(text: String) = (collapseActivityItems(
            pairToolBlocks(listOf(ContentBlock.Thinking(text, null))),
            isLastTurn = true,
            isResponding = true,
        ).single() as SegmentRenderItem.Activity).group
        val empty = group("")
        val populated = group("planning")
        assertEquals("thinking-position:0", empty.key)
        assertEquals(empty.key, populated.key)
        assertTrue(activityNeedsThinkingPlaceholder(empty))
        assertFalse(activityNeedsThinkingPlaceholder(empty.copy(running = false)))
    }

    @Test
    fun completedFileActivityStaysStillWhileReplyRuns() {
        val segments = collapseActivityItems(
            pairToolBlocks(listOf(tool("t1", "Edit", "edit_file", "file-a"))),
            isLastTurn = true,
            isResponding = true,
        )
        assertFalse((segments.single() as SegmentRenderItem.Activity).group.running)
    }

    @Test
    fun onlyPendingCommandOrLatestThinkingSegmentRuns() {
        val completedEdit = tool("edit", "Edit", "edit_file", "file-a")
        val pendingCommand = tool("run", "Bash", "run_command")
        val segments = collapseActivityItems(
            pairToolBlocks(listOf(
                ContentBlock.Thinking("earlier", null),
                completedEdit,
                ContentBlock.ToolResult("edit", "ok", false, false, null),
                ContentBlock.Text("interlude", null),
                pendingCommand,
                ContentBlock.Text("still working", null),
                ContentBlock.Thinking("latest", null),
            )),
            isLastTurn = true,
            isResponding = true,
        ).filterIsInstance<SegmentRenderItem.Activity>()
        assertEquals(3, segments.size)
        assertFalse(segments[0].group.running)
        assertTrue(segments[1].group.running)
        assertTrue(segments[2].group.running)
        val historical = collapseActivityItems(
            pairToolBlocks(listOf(pendingCommand)),
            isLastTurn = false,
            isResponding = true,
        ).single() as SegmentRenderItem.Activity
        assertFalse(historical.group.running)
    }

    @Test
    fun pendingCommandRemainsRunningEvenAfterProseFollows() {
        val command = tool("run", "Bash", "run_command")
        val segments = collapseActivityItems(
            pairToolBlocks(listOf(command, ContentBlock.Text("still working", null))),
            isLastTurn = true,
            isResponding = true,
        )
        assertTrue(activityHasPendingCommand((segments.first() as SegmentRenderItem.Activity).group.items))
        assertTrue((segments.first() as SegmentRenderItem.Activity).group.running)
        val finished = collapseActivityItems(
            pairToolBlocks(listOf(command, ContentBlock.ToolResult("run", "ok", false, false, null))),
            isLastTurn = true,
            isResponding = true,
        )
        assertFalse(activityHasPendingCommand((finished.single() as SegmentRenderItem.Activity).group.items))
    }

    @Test
    fun pendingCommandDoesNotMarkOtherEntriesAsRunning() {
        val edit = DisplayItem.Tool(tool("edit", "Edit", "edit_file", "file-a"), null)
        val command = DisplayItem.Tool(tool("run", "Bash", "run_command"), null)
        assertEquals("未返回", toolActivityEntryStatus("edit_file", ToolActivityEntry(listOf(edit)), true))
        assertFalse(toolActivityCallRunning("edit_file", edit, true))
        assertEquals("运行中", toolActivityEntryStatus("run_command", ToolActivityEntry(listOf(command)), true))
        assertTrue(toolActivityCallRunning("run_command", command, true))
        assertEquals("未返回", toolActivityEntryStatus("run_command", ToolActivityEntry(listOf(command)), false))
    }

    @Test
    fun latestPendingCommandCanBelongToAnEarlierTurnAndResolvesAcrossTurns() {
        val command = tool("run", "Bash", "run_command")
        val turns = listOf(
            ConversationTurn("user", listOf(ContentBlock.Text("go", null))),
            ConversationTurn("assistant", listOf(command)),
            ConversationTurn("assistant", listOf(ContentBlock.Thinking("still working", null))),
        )
        val pendingResults = conversationToolResults(turns)
        assertEquals("run", latestPendingCommandToolId(turns, 0, pendingResults))
        val oldTurnGroup = collapseActivityItems(
            pairToolBlocks(turns[1].content, pendingResults),
            isLastTurn = false,
            isResponding = true,
            activeCommandIds = setOf("run"),
        ).single() as SegmentRenderItem.Activity
        assertTrue(oldTurnGroup.group.running)

        val resolved = turns + ConversationTurn(
            "assistant",
            listOf(ContentBlock.ToolResult("run", "", false, true, null)),
        )
        val resolvedResults = conversationToolResults(resolved)
        assertEquals(null, latestPendingCommandToolId(resolved, 0, resolvedResults))
        val resolvedItem = pairToolBlocks(turns[1].content, resolvedResults).single() as DisplayItem.Tool
        assertEquals("run", resolvedItem.result?.toolUseId)
        val resolvedGroup = collapseActivityItems(
            listOf(resolvedItem),
            isLastTurn = false,
            isResponding = true,
            activeCommandIds = emptySet(),
        ).single() as SegmentRenderItem.Activity
        assertFalse(resolvedGroup.group.running)

        val twoPending = listOf(
            turns.first(),
            ConversationTurn("assistant", listOf(tool("first", "Bash", "run_command"))),
            ConversationTurn("assistant", listOf(tool("second", "Bash", "run_command"))),
        )
        assertEquals("second", latestPendingCommandToolId(twoPending, 0, conversationToolResults(twoPending)))
        val newestResolved = twoPending + ConversationTurn(
            "assistant", listOf(ContentBlock.ToolResult("second", "", false, true, null)),
        )
        assertEquals(
            "first",
            latestPendingCommandToolId(newestResolved, 0, conversationToolResults(newestResolved)),
        )
    }

    @Test
    fun latestCommandTimeUsesUtcEventTimeAndNeverInventsMissingHistory() {
        val parsed = ContentBlock.parse(JSONObject(
            """{"type":"tool_use","id":"wire","name":"Bash","activity":{"kind":"run_command","label":"运行命令","occurredAt":"2026-09-30T12:03:04Z"}}""",
        )) as ContentBlock.ToolUse
        assertEquals("2026-09-30T12:03:04Z", parsed.activity?.occurredAt)
        val first = tool("c1", "Bash", "run_command").copy(
            activity = ToolActivity("run_command", "运行命令", occurredAt = "2026-09-30T12:01:02Z"),
        )
        val second = tool("c2", "Bash", "run_command").copy(
            activity = ToolActivity("run_command", "运行命令", occurredAt = "2026-09-30T12:03:04Z"),
        )
        val items = listOf(DisplayItem.Tool(first, null), DisplayItem.Tool(second, null))
        val latest = latestCommandOccurredAt(items)
        assertEquals(Instant.parse("2026-09-30T12:03:04Z"), latest)
        assertEquals("20:03:04", commandEventClock(latest!!, ZoneId.of("Asia/Shanghai")))
        assertEquals("已等待 1 分 5 秒", commandWaitLabel(latest, latest.toEpochMilli() + 65_000))
        assertEquals(null, latestCommandOccurredAt(listOf(DisplayItem.Tool(tool("old", "Bash", "run_command"), null))))
    }

    @Test
    fun collapsedSummaryLeadsWithTheLatestCommandTime() {
        val running = tool("run", "Bash", "run_command").copy(
            activity = ToolActivity("run_command", "运行命令", occurredAt = "2026-09-30T12:03:04Z"),
        )
        val items = listOf(
            DisplayItem.Tool(tool("read", "Read", "read_file", "file-a"), null),
            DisplayItem.Tool(running, null),
        )
        val parts = toolActivitySummaryParts(
            categories = toolActivityCategories(items),
            hasThinking = false,
            thinkingRunning = true,
            thinkingPlaceholder = false,
            leadClock = commandEventClock(Instant.parse("2026-09-30T12:03:04Z"), ZoneId.of("Asia/Shanghai")),
            pendingCommand = true,
            waitLabel = "已等待 5 秒",
        )
        assertEquals("20:03:04", parts.first().text)
        assertEquals(ToolActivitySummaryTone.Clock, parts.first().tone)
        assertEquals("  ", parts.first().joiner)
        assertEquals(listOf("查看了 1 个文件", "运行了 1 条命令", "运行中", "已等待 5 秒"), parts.drop(1).map { it.text })
        assertEquals(ToolActivitySummaryTone.AccentPulse, parts[3].tone)
    }

    @Test
    fun activityTimelineFollowsTheNewestCallUntilTheUserScrollsAway() {
        // 空时间线没有可跟随的目标。
        assertFalse(shouldFollowActivityTail(menuOpen = true, pinnedToLatest = true, itemCount = 0))
        // 收起的面板不滚动。
        assertFalse(shouldFollowActivityTail(menuOpen = false, pinnedToLatest = true, itemCount = 3))
        // 用户自己往上翻过（贴尾状态被翻掉）就不再抢视线。
        assertFalse(shouldFollowActivityTail(menuOpen = true, pinnedToLatest = false, itemCount = 3))
        // 展开且贴尾：追加新调用就继续跟到最新。
        assertTrue(shouldFollowActivityTail(menuOpen = true, pinnedToLatest = true, itemCount = 3))
    }

    @Test
    fun activityTimelineTailIndexPointsAtTheNewestItem() {
        assertEquals(2, activityTimelineTailIndex(3))
        assertEquals(0, activityTimelineTailIndex(1))
        assertEquals(-1, activityTimelineTailIndex(0))
    }

    @Test
    fun collapsedSummaryKeepsThinkingFirstWithoutAnInventedTime() {
        val thinking = pairToolBlocks(listOf(ContentBlock.Thinking("working", null)))
        assertEquals(
            listOf("深度思考", "中"),
            toolActivitySummaryParts(
                categories = toolActivityCategories(thinking),
                hasThinking = true,
                thinkingRunning = true,
                thinkingPlaceholder = false,
                leadClock = null,
                pendingCommand = false,
                waitLabel = null,
            ).map { it.text },
        )
        val placeholder = pairToolBlocks(listOf(ContentBlock.Thinking("  ", null)))
        val placeholderParts = toolActivitySummaryParts(
            categories = toolActivityCategories(placeholder),
            hasThinking = false,
            thinkingRunning = true,
            thinkingPlaceholder = true,
            leadClock = null,
            pendingCommand = false,
            waitLabel = null,
        )
        assertEquals(listOf("思考中"), placeholderParts.map { it.text })
        assertEquals(ToolActivitySummaryTone.AccentPulse, placeholderParts.single().tone)
        // 旧历史没有真实时间：正文自己起头，不插占位、不补造。
        val finished = listOf(DisplayItem.Tool(tool("old", "Bash", "run_command"), null))
        assertEquals(
            listOf("运行了 1 条命令"),
            toolActivitySummaryParts(
                categories = toolActivityCategories(finished),
                hasThinking = false,
                thinkingRunning = false,
                thinkingPlaceholder = false,
                leadClock = null,
                pendingCommand = false,
                waitLabel = null,
            ).map { it.text },
        )
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
    fun legacyFallbackNeedsAConcreteFilePathAndNeverCountsSearchAsAFile() {
        val calls = listOf(
            tool("read", "Read").copy(input = JSONObject().put("file_path", "src/main.kt")),
            tool("edit", "Edit").copy(input = JSONObject().put("file_path", "src/main.kt")),
            tool("grep", "Grep").copy(input = JSONObject().put("path", "src/main.kt")),
            tool("glob", "Glob").copy(input = JSONObject().put("path", "src")),
            tool("search", "Search"),
            tool("fetch", "WebFetch"),
            tool("missing", "Read"),
            tool("blank", "Edit").copy(input = JSONObject().put("file_path", "  ")),
        ).map { DisplayItem.Tool(it, null) }
        val categories = toolActivityCategories(calls)
        assertEquals(listOf("修改了 1 个文件", "查看了 1 个文件", "其他 6 次调用"), categories.map { it.title })
    }

    @Test
    fun timelineKeepsMixedInvocationOrderAndRepeatedFileCallsSeparate() {
        val read = DisplayItem.Tool(tool("read", "Read", "read_file", "same-file"), null)
        val edit = DisplayItem.Tool(tool("edit", "Edit", "edit_file", "same-file"), null)
        val nextEdit = DisplayItem.Tool(tool("next-edit", "Edit", "edit_file", "same-file"), null)
        val command = DisplayItem.Tool(tool("run", "Bash", "run_command"), null)
        val thinking = pairToolBlocks(listOf(ContentBlock.Thinking("planning", null))).single()
        val timeline = toolActivityTimeline(listOf(read, thinking, command, edit, nextEdit, edit))
        assertEquals(listOf(read, thinking, command, edit, nextEdit), timeline)
        assertEquals(1, toolActivityCategories(timeline).first { it.kind == "edit_file" }.count)
        assertEquals(240f, TOOL_ACTIVITY_TIMELINE_HEIGHT.value)
    }

    @Test
    fun activityOnlyTurnsBypassOuterHeaderWithoutHidingProseOrInteractiveCards() {
        val read = tool("read", "Read", "read_file", "file-a")
        assertTrue(isToolActivityOnly(listOf(read, ContentBlock.ToolResult("read", "", false, true, null))))
        assertTrue(isToolActivityOnly(listOf(ContentBlock.Thinking("plan", null), read)))
        assertFalse(isToolActivityOnly(listOf(ContentBlock.Text("正文", null), read)))
        assertFalse(isToolActivityOnly(listOf(tool("ask", "AskUserQuestion"))))
        assertFalse(isToolActivityOnly(emptyList()))
    }

    @Test
    fun timelineUsesOnlyCompactIdentityAndNeverInputOrResultText() {
        val use = tool("edit", "Edit", "edit_file", "file-a").copy(
            activity = ToolActivity("edit_file", "修改 src/main.kt", "file-a"),
            input = JSONObject().put("old_string", "secret"),
        )
        assertEquals("修改 src/main.kt", toolActivityItemLabel(use))
        assertEquals("调用 Grep", toolActivityItemLabel(tool("grep", "Grep", "other")))
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
        assertEquals("thinking-position:0", key(listOf(ContentBlock.Thinking("planning", null), first)))
    }

    @Test
    fun thinkingLeadingGroupKeepsItsKeyWhenToolsAppend() {
        val thinking = ContentBlock.Thinking("planning", null)
        val command = tool("run", "Bash", "run_command")
        fun key(blocks: List<ContentBlock>) = collapseActivityItems(
            pairToolBlocks(blocks),
            isLastTurn = true,
            isResponding = true,
        ).filterIsInstance<SegmentRenderItem.Activity>().last().group.key
        assertEquals("thinking-position:0", key(listOf(thinking)))
        assertEquals("thinking-position:0", key(listOf(thinking, command)))
        assertEquals("thinking-position:1", key(listOf(ContentBlock.Text("earlier", null), thinking, command)))
        assertEquals("thinking-position:0", key(listOf(ContentBlock.Thinking("", null), thinking, command)))
        assertEquals("thinking-position:1", key(listOf(ContentBlock.Text("earlier", null), thinking)))
        assertEquals("tool:run", key(listOf(command)))
        assertEquals("tool:run", key(listOf(ContentBlock.Text("earlier", null), command)))
    }
}
