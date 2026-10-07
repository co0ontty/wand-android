package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ToolActivity
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityGroupingTest {
    @Test
    fun timelineModeAllowsAllNetworksOrOnlyWifi() {
        assertTrue(activityTimelineEnabled("all", wifiConnected = false))
        assertTrue(activityTimelineEnabled("all", wifiConnected = true))
        assertFalse(activityTimelineEnabled("wifi", wifiConnected = false))
        assertTrue(activityTimelineEnabled("wifi", wifiConnected = true))
    }

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
    fun completedFileActivityStaysStillOnceItsGroupLeavesTheTail() {
        // 正文跟在后面时这一段不再是末尾运行段：已完成的活动不点灯、也没有运行中的调用。
        val past = collapseActivityItems(
            pairToolBlocks(listOf(tool("t1", "Edit", "edit_file", "file-a"), ContentBlock.Text("done", null))),
            isLastTurn = true,
            isResponding = true,
        ).filterIsInstance<SegmentRenderItem.Activity>().single().group
        assertFalse(past.running)
        assertNull(toolActivityRunningCallId(past.items, past.running))
        // 正在生成的末尾段才是活的：思考、命令、普通工具在跑期间都点灯，面板不一闪一灭。
        val trailing = collapseActivityItems(
            pairToolBlocks(listOf(tool("t1", "Edit", "edit_file", "file-a"))),
            isLastTurn = true,
            isResponding = true,
        ).single() as SegmentRenderItem.Activity
        assertTrue(trailing.group.running)
        assertEquals("t1", toolActivityRunningCallId(trailing.group.items, trailing.group.running))
        // 历史（非末尾 turn）永远不算运行中。
        val history = collapseActivityItems(
            pairToolBlocks(listOf(tool("t1", "Edit", "edit_file", "file-a"))),
            isLastTurn = false,
            isResponding = true,
        ).single() as SegmentRenderItem.Activity
        assertFalse(history.group.running)
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
    fun runningCallIsTheLastCallWithoutAResultAndKeepsItsStatusInSemantics() {
        val edit = DisplayItem.Tool(tool("edit", "Edit", "edit_file", "file-a"), null)
        val command = DisplayItem.Tool(tool("run", "Bash", "run_command"), null)
        val done = DisplayItem.Tool(
            tool("read", "Read", "read_file", "file-a"),
            ContentBlock.ToolResult("read", "ok", false, false, null),
        )
        // 段不在运行时（历史段）：缺回执只是「未返回」，没有运行中的调用。
        assertNull(toolActivityRunningCallId(listOf(edit, command), groupRunning = false))
        assertEquals("未返回", toolActivityEntryStatus(ToolActivityEntry(listOf(edit)), null))
        // 运行中的段：最后一条还没有回执的普通调用就是正在执行的那一条。
        val runningId = toolActivityRunningCallId(listOf(done, edit, command), groupRunning = true)
        assertEquals("run", runningId)
        assertFalse(toolActivityCallRunning(edit, runningId))
        assertEquals("运行中", toolActivityEntryStatus(ToolActivityEntry(listOf(command)), runningId))
        assertEquals("完成", toolActivityEntryStatus(ToolActivityEntry(listOf(done)), runningId))
        // 普通工具（不只是命令）在跑时同样点灯与动效。
        val editRunning = toolActivityRunningCallId(listOf(done, edit), groupRunning = true)
        assertEquals("edit", editRunning)
        assertTrue(toolActivityCallRunning(edit, editRunning))
        assertEquals("运行中", toolActivityEntryStatus(ToolActivityEntry(listOf(edit)), editRunning))
        // 待办更新本就不回结果，不算运行中。
        val todo = DisplayItem.Tool(tool("todo", "TodoWrite"), null)
        assertNull(toolActivityRunningCallId(listOf(todo), groupRunning = true))
        assertEquals("完成", toolActivityEntryStatus(ToolActivityEntry(listOf(todo)), null))
        // 右侧不再写字状态字样：结果状态留在语义里。
        assertEquals("已收起，运行中", activityEntryStateDescription(open = false, status = "运行中", running = true))
        assertEquals("已展开，失败", activityEntryStateDescription(open = true, status = "失败", running = false))
        assertEquals("已收起", activityEntryStateDescription(open = false, status = null, running = false))
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
    fun latestActivityTimeIsAnyKindAndNeverInventsMissingHistory() {
        val command = tool("run", "Bash", "run_command").copy(
            activity = ToolActivity("run_command", "运行命令", occurredAt = "2026-09-30T12:36:02Z"),
        )
        val edit = tool("edit", "Edit", "edit_file", "file-a").copy(
            activity = ToolActivity("edit_file", "修改 a", "file-a", occurredAt = "2026-09-30T12:42:40Z"),
        )
        val items = listOf(DisplayItem.Tool(command, null), DisplayItem.Tool(edit, null))
        // 缩略栏的时间跟着最新一次真实活动走，不再只看最后一条命令。
        assertEquals(Instant.parse("2026-09-30T12:42:40Z"), latestActivityOccurredAt(items))
        assertEquals(Instant.parse("2026-09-30T12:36:02Z"), latestCommandOccurredAt(items))
        val thinking = pairToolBlocks(listOf(ContentBlock.Thinking("planning", null))).single()
        assertNull(latestActivityOccurredAt(listOf(thinking)))
        assertNull(latestActivityOccurredAt(listOf(DisplayItem.Tool(tool("old", "Edit", "edit_file", "file-a"), null))))
        val parts = toolActivitySummaryParts(
            categories = toolActivityCategories(items),
            roundCount = 2,
            thinkingRunning = false,
            thinkingPlaceholder = false,
            leadClock = latestActivityOccurredAt(items)
                ?.let { commandEventClock(it, ZoneId.of("Asia/Shanghai")) },
            pendingCommand = false,
            waitLabel = null,
        )
        assertEquals("20:42:40", parts.first().text)
        assertEquals(ToolActivitySummaryTone.Clock, parts.first().tone)
        assertEquals(
            listOf("思考 2 次", "修改了 1 个文件", "运行了 1 条命令"),
            parts.drop(1).map { it.text },
        )
    }

    @Test
    fun timelineRowsKeepTimesMonotonicAndStayUntimedWithoutARealEventTime() {
        fun edit(id: String, at: String?) = DisplayItem.Tool(
            tool(id, "Edit", "edit_file", "file-$id").copy(
                activity = ToolActivity("edit_file", "修改 $id", "file-$id", occurredAt = at),
            ),
            null,
        )
        val thinking = pairToolBlocks(listOf(ContentBlock.Thinking("planning", null))).single()
        // 真实时间与到达顺序矛盾时按时间排：屏幕上从上到下时间不往回跳。
        val reordered = toolActivityTimelineRows(
            listOf(edit("late", "2026-10-05T12:42:40Z"), edit("early", "2026-10-05T12:36:02Z")),
        )
        assertEquals(listOf("early", "late"), reordered.map { (it.item as DisplayItem.Tool).use.id })
        // 缺时间的条目只补位：位置跟着相邻条目，自己仍然不显示时钟。
        val rows = toolActivityTimelineRows(
            listOf(edit("early", "2026-10-05T12:36:02Z"), thinking, edit("late", "2026-10-05T12:42:40Z")),
        )
        assertEquals(listOf("early", null, "late"), rows.map { (it.item as? DisplayItem.Tool)?.use?.id })
        assertEquals(Instant.parse("2026-10-05T12:36:02Z"), rows[0].occurredAt)
        assertNull(rows[1].occurredAt)
        assertEquals(Instant.parse("2026-10-05T12:42:40Z"), rows[2].occurredAt)
        // 旧历史完全没有真实时间：一个时钟都不补造。
        val legacy = toolActivityTimelineRows(listOf(edit("a", null), thinking))
        assertEquals(listOf("a", null), legacy.map { (it.item as? DisplayItem.Tool)?.use?.id })
        assertTrue(legacy.all { it.occurredAt == null })
    }

    @Test
    fun everyThinkingBlockIsARoundAndOnlyMultiRoundRunsCarryAPosition() {
        val items = pairToolBlocks(listOf(
            ContentBlock.Thinking("先想想", null, "2026-10-05T12:00:00Z", "2026-10-05T12:00:20Z"),
            tool("todo", "Pi/todo"),
            ContentBlock.Thinking("再想想", null, "2026-10-05T12:01:00Z", "2026-10-05T12:01:10Z"),
            ContentBlock.Thinking("继续想", null),
        ))
        val rounds = thinkingRounds(items)
        assertEquals(listOf("思考过程 1/3", "思考过程 2/3", "思考过程 3/3"), rounds.map { it.label })
        assertEquals(listOf(1, 2, 3), rounds.map { it.ordinal })
        assertEquals(
            listOf(Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T12:01:00Z"), null),
            rounds.map { it.occurredAt },
        )
        assertEquals(Instant.parse("2026-10-05T12:01:10Z"), rounds[1].lastActivityAt)
        // 轮次按服务端真实时间进时间线，位置与行身份不随重排变化。
        val rows = toolActivityTimelineRows(items)
        assertEquals(4, rows.size)
        assertEquals(listOf("thinking-0", "todo", "thinking-2", "thinking-3"), rows.map { it.key })
        assertEquals(
            listOf(Instant.parse("2026-10-05T12:00:00Z"), null, Instant.parse("2026-10-05T12:01:00Z"), null),
            rows.map { it.occurredAt },
        )
        // 单轮不带 k/N：没有对照的数字只是噪音。
        val single = thinkingRounds(pairToolBlocks(listOf(ContentBlock.Thinking("一轮", null))))
        assertEquals(listOf("思考过程"), single.map { it.label })
        assertEquals(Instant.parse("2026-10-05T12:01:00Z"), latestActivityOccurredAt(items))
    }

    @Test
    fun aReasoningRoundWithoutTextIsNotARowNorARound() {
        val empty = ContentBlock.Thinking("", null)
        val blank = ContentBlock.Thinking("   ", null)
        val real = ContentBlock.Thinking("有正文", null)
        val history = listOf(
            DisplayItem.Plain(empty),
            DisplayItem.Tool(tool("cmd", "Bash", "run_command"), null),
            DisplayItem.Plain(blank),
        )
        // 历史态里没有产出过正文的轮次：不占时间线，也不进轮次计数。
        assertEquals(listOf("cmd"), toolActivityTimelineRows(history, running = false).map { it.key })
        assertEquals(emptyList<Any>(), thinkingRounds(history, running = false))
        val mixed = listOf(DisplayItem.Plain(empty), DisplayItem.Plain(real))
        assertEquals(listOf("thinking-1"), toolActivityTimelineRows(mixed, running = false).map { it.key })
        assertEquals(listOf(1), thinkingRounds(mixed, running = false).map { it.ordinal })
        // 段尾那一块在这一段还在跑时是正在进行的一轮：保留占位，它就是唯一活跃条目。
        val streaming = listOf(DisplayItem.Plain(real), DisplayItem.Plain(empty))
        val live = activityLiveRow(streaming, groupRunning = true) as ActivityLiveRow.Thinking
        assertTrue(thinkingBlock(live.round.item)?.thinking.isNullOrBlank())
        assertTrue(activityNeedsThinkingPlaceholder(ActivityGroup("g", streaming, true)))
        assertEquals(listOf("thinking-0", "thinking-1"), toolActivityTimelineRows(streaming).map { it.key })
        // 同一段跑完之后，那块占位就不再是轮次。
        assertEquals(listOf("thinking-0"), toolActivityTimelineRows(streaming, running = false).map { it.key })
    }

    @Test
    fun atMostOneRowIsLiveSoTheLoadingEffectNeverRepeats() {
        val todo = tool("todo", "Pi/todo")
        val command = tool("run", "Bash", "run_command")
        fun group(blocks: List<ContentBlock>, running: Boolean = true) = ActivityGroup(
            "g", pairToolBlocks(blocks), running,
        )
        val runningRound = group(listOf(ContentBlock.Thinking("一", null), todo, ContentBlock.Thinking("二", null)))
        // 待办永不回执也不能冒充活跃：现在轮到的是最后一轮思考。
        val live = activityLiveRow(runningRound.items, runningRound.running) as ActivityLiveRow.Thinking
        assertEquals(2, live.round.ordinal)
        assertTrue(activityRowIsLive(live, toolActivityTimelineRows(runningRound.items).last()))
        assertEquals(1, toolActivityTimelineRows(runningRound.items).count { activityRowIsLive(live, it) })
        assertTrue(activityLiveRow(runningRound.items, runningRound.running) is ActivityLiveRow.Thinking)
        // 命令还没回执时活跃的是命令，思考轮次不再同时转。
        val pending = group(listOf(ContentBlock.Thinking("一", null), command, ContentBlock.Thinking("二", null)))
        val pendingLive = activityLiveRow(pending.items, pending.running) as ActivityLiveRow.Call
        assertEquals("run", pendingLive.toolId)
        assertEquals(1, toolActivityTimelineRows(pending.items).count { activityRowIsLive(pendingLive, it) })
        assertFalse(activityLiveRow(pending.items, pending.running) is ActivityLiveRow.Thinking)
        // 历史段没有活跃条目，位置猜测也不该点亮最后一轮。
        val history = group(listOf(ContentBlock.Thinking("一", null)), running = false)
        assertNull(activityLiveRow(history.items, history.running))
        assertFalse(activityNeedsThinkingPlaceholder(history))
    }

    @Test
    fun liveRoundReportsItsOwnElapsedAndSilenceWithoutInventingTime() {
        val started = Instant.parse("2026-10-05T12:00:00Z")
        val items = pairToolBlocks(listOf(ContentBlock.Thinking("一", null, started.toString())))
        val now = started.toEpochMilli() + 125_000
        assertEquals("已思考 2 分 5 秒", thinkingElapsedLabel(started, now))
        assertNull(thinkingSilentLabel(started, started.toEpochMilli() + 30_000))
        assertEquals("无新进展 1 分 0 秒", thinkingSilentLabel(started, now - 65_000))
        // 旧历史没有真实轮次时间：摘要里一个字都不加。
        val legacy = pairToolBlocks(listOf(ContentBlock.Thinking("一", null)))
        assertNull(thinkingRounds(legacy).single().occurredAt)
        assertEquals(
            listOf("思考 1 次", "思考中"),
            toolActivitySummaryParts(
                categories = toolActivityCategories(legacy),
                roundCount = 1,
                thinkingRunning = true,
                thinkingPlaceholder = false,
                leadClock = null,
                pendingCommand = false,
                waitLabel = null,
                thinkingElapsed = null,
                thinkingSilent = null,
            ).map { it.text },
        )
        // 有真实时间才把这一轮的耗时与静默挂在摘要上。
        assertEquals(
            listOf("思考 1 次", "思考中", "已思考 2 分 5 秒", "无新进展 1 分 0 秒"),
            toolActivitySummaryParts(
                categories = toolActivityCategories(items),
                roundCount = 1,
                thinkingRunning = true,
                thinkingPlaceholder = false,
                leadClock = null,
                pendingCommand = false,
                waitLabel = null,
                thinkingElapsed = thinkingElapsedLabel(started, now),
                thinkingSilent = thinkingSilentLabel(started, now - 65_000),
            ).map { it.text },
        )
    }

    @Test
    fun activityPanelNeverTakesMoreThanAThirdOfTheViewport() {
        // 手机：视口的三分之一小于 240dp 上限，按三分之一收口。
        assertEquals(200.dp, activityPanelMaxHeight(600.dp))
        // 宽屏/平板：不超过 240dp 上限。
        assertEquals(TOOL_ACTIVITY_TIMELINE_HEIGHT, activityPanelMaxHeight(1200.dp))
        // 极矮视口（键盘弹起）兜底最小可用高度。
        assertEquals(TOOL_ACTIVITY_PANEL_MIN_HEIGHT, activityPanelMaxHeight(300.dp))
        assertEquals(240f, TOOL_ACTIVITY_TIMELINE_HEIGHT.value)
        assertEquals(120f, TOOL_ACTIVITY_PANEL_MIN_HEIGHT.value)
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
            roundCount = 0,
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
        assertFalse(shouldFollowActivityTail(menuOpen = true, pinnedToLatest = true, itemCount = 3, inspectingDetail = true))
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
            listOf("思考 1 次", "思考中"),
            toolActivitySummaryParts(
                categories = toolActivityCategories(thinking),
                roundCount = 1,
                thinkingRunning = true,
                thinkingPlaceholder = false,
                leadClock = null,
                pendingCommand = false,
                waitLabel = null,
            ).map { it.text },
        )
        assertEquals(
            listOf("思考 2 次"),
            toolActivitySummaryParts(
                categories = emptyList(),
                roundCount = 2,
                thinkingRunning = false,
                thinkingPlaceholder = false,
                leadClock = null,
                pendingCommand = false,
                waitLabel = null,
            ).map { it.text },
        )
        val placeholder = pairToolBlocks(listOf(ContentBlock.Thinking("  ", null)))
        val placeholderParts = toolActivitySummaryParts(
            categories = toolActivityCategories(placeholder),
            roundCount = 0,
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
                roundCount = 0,
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
        val timeline = toolActivityTimelineRows(listOf(read, thinking, command, edit, nextEdit, edit)).map { it.item }
        assertEquals(listOf(read, thinking, command, edit, nextEdit), timeline)
        assertEquals(1, toolActivityCategories(timeline).first { it.kind == "edit_file" }.count)
        assertEquals(240f, TOOL_ACTIVITY_TIMELINE_HEIGHT.value)
    }

    @Test
    fun timelineRailConnectsDotCentersAndDoesNotExtendPastTheFirstOrLastEntry() {
        assertEquals(17f to 64f, activityTimelineRailBounds(64f, 17f, isFirst = true, isLast = false))
        assertEquals(0f to 64f, activityTimelineRailBounds(64f, 17f, isFirst = false, isLast = false))
        assertEquals(0f to 17f, activityTimelineRailBounds(64f, 17f, isFirst = false, isLast = true))
        // 单条及展开很长的最后一条都不会把竖线延长到详情底部。
        assertEquals(17f to 17f, activityTimelineRailBounds(400f, 17f, isFirst = true, isLast = true))
        assertEquals(0f to 17f, activityTimelineRailBounds(400f, 17f, isFirst = false, isLast = true))
        assertEquals(0f to 0f, activityTimelineRailBounds(0f, 17f, isFirst = true, isLast = true))
        assertEquals(5, THINKING_VISIBLE_LINES)
    }

    @Test
    fun timelineRevisionChangesWhenLatestResultStreamsWithoutAddingAnItem() {
        val use = tool("run", "Bash", "run_command")
        val pending = listOf(DisplayItem.Tool(use, null))
        val finished = listOf(DisplayItem.Tool(use, ContentBlock.ToolResult("run", "latest output", false, true, null)))
        assertTrue(toolActivityTimelineRevision(pending) != toolActivityTimelineRevision(finished))
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
