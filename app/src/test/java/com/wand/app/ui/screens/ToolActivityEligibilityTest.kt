package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.SemanticQuestion
import com.wand.app.data.SemanticQuestionOption
import com.wand.app.data.SemanticTaskItem
import com.wand.app.data.SubagentMeta
import com.wand.app.data.ToolActivity
import com.wand.app.data.ToolUseSemantic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActivityEligibilityTest {
    private fun tool(name: String, id: String = name): ContentBlock.ToolUse =
        ContentBlock.ToolUse(id, name, null, JSONObject(), null)

    private fun segments(blocks: List<ContentBlock>): List<SegmentRenderItem> =
        collapseActivityItems(pairToolBlocks(blocks), isLastTurn = true, isResponding = true)

    @Test
    fun piTodoAndLegacyCallsFoldWithoutCompactTransportMetadata() {
        // The installed Pi session contains these ordinary calls without activity.
        val blocks = listOf(
            tool("Pi/todo", "create").copy(input = JSONObject().put("action", "create")),
            tool("Pi/todo", "update").copy(input = JSONObject().put("action", "update")),
            ContentBlock.Thinking("planning", null),
            tool("Read").copy(input = JSONObject().put("path", "/repo/src/main.kt")),
            tool("Bash"),
            tool("Edit"),
        )
        val group = (segments(blocks).single() as SegmentRenderItem.Activity).group
        assertEquals(6, group.items.size)
        assertEquals("tool:create", group.key)
        assertTrue(isToolActivityOnly(blocks))
        assertEquals(listOf("修改了 1 个文件", "查看了 1 个文件", "运行了 1 条命令", "其他 3 次调用"),
            toolActivityCategories(group.items + DisplayItem.Tool(
                tool("Write").copy(input = JSONObject().put("path", "/repo/src/main.kt")), null,
            )).map { it.title })
    }

    @Test
    fun addingCompactMetadataDoesNotChangeGroupOrFoldIdentity() {
        val legacy = tool("Bash", "run")
        val compact = legacy.copy(activity = ToolActivity("run_command", "运行命令 · Bash"))
        val first = (segments(listOf(legacy)).single() as SegmentRenderItem.Activity).group
        val later = (segments(listOf(compact, tool("Pi/todo"))).single() as SegmentRenderItem.Activity).group
        assertEquals(first.key, later.key)
        assertEquals(cardFoldKey("session", first.key), cardFoldKey("session", later.key))
        assertTrue(isToolActivityOnly(listOf(legacy)))
        assertTrue(isToolActivityOnly(listOf(compact)))
        assertFalse(foldExpanded(FOLD_OVERRIDE_COLLAPSED, derivedDefault = true))
        assertTrue(foldExpanded(FOLD_OVERRIDE_EXPANDED, derivedDefault = false))
    }

    @Test
    fun taskProgressRemainsAvailableWhenItsToolTraceIsFolded() {
        val todo = tool("Pi/todo").copy(semantic = ToolUseSemantic.TaskList(listOf(
            SemanticTaskItem("task-1", "Fix folding", "in_progress", "Fixing folding"),
        )))
        val turns = listOf(ConversationTurn("assistant", listOf(todo)))
        assertEquals("Fix folding", currentTodos(turns).single().content)
        assertTrue(segments(turns.single().content).single() is SegmentRenderItem.Activity)
        assertTrue(isToolActivityOnly(turns.single().content))
        assertEquals("Fixing folding", currentTodos(turns).single().activeForm)
    }

    @Test
    fun questionSemanticsStayInteractiveEvenWhenNameAndMetadataLookOrdinary() {
        val ask = tool("Read").copy(
            semantic = ToolUseSemantic.QuestionRequest(listOf(SemanticQuestion(
                "Choose", null, false, listOf(SemanticQuestionOption("A", null)),
            ))),
            activity = ToolActivity("other", "调用 Read"),
        )
        assertTrue(segments(listOf(ask)).single() is SegmentRenderItem.Item)
        assertFalse(isToolActivityOnly(listOf(ask)))
        val turns = List(4) { ConversationTurn("assistant", listOf(ask.copy(id = "ask-$it"))) }
        assertTrue(groupExplorationTurns(turns).all { it is MessageDisplayItem.Turn })
        for (name in listOf("AskUserQuestion", "mcp__bridge__AskUserQuestion", "Pi/AskUserQuestion")) {
            assertFalse(shouldCollapseToolInActivity(name))
        }
    }

    @Test
    fun dispatchStaysIndependentButItsInnerOrdinaryCallsStillFold() {
        for (name in listOf("Task", "Agent", "Pi/subagent")) {
            assertFalse(shouldCollapseToolInActivity(name))
            assertTrue(segments(listOf(tool(name))).single() is SegmentRenderItem.Item)
            assertFalse(isToolActivityOnly(listOf(tool(name))))
        }
        val meta = SubagentMeta("agent", "worker", null)
        // 派发调用本身（块 id == taskId）：面板头已承接该语义，正文不再渲染、也不折叠进普通活动轨迹。
        val dispatch = tool("Pi/subagent", "agent").copy(subagent = meta)
        assertTrue(isSubagentDispatchBlock(id = dispatch.id, subagent = dispatch.subagent))
        assertFalse(isCollapsibleActivityTool(dispatch))
        assertTrue(segments(listOf(dispatch)).isEmpty())
        // 子 Agent 内部普通调用共享同一份 meta、id 与 taskId 不同，仍按普通活动轨迹折叠。
        val childCall = tool("Read", "read").copy(subagent = meta)
        assertFalse(isSubagentDispatchBlock(id = childCall.id, subagent = childCall.subagent))
        assertTrue(segments(listOf(childCall)).single() is SegmentRenderItem.Activity)
    }

    @Test
    fun imagePathsResultsAndFlagsNeverDisappearInsideTheDefaultFold() {
        val image = tool("Read").copy(input = JSONObject().put("path", "/tmp/shot.png"))
        val flagged = tool("Read").copy(activity = ToolActivity("read_file", "查看 shot.png", hasImage = true))
        val result = ContentBlock.ToolResult("Read", "", false, false, null,
            images = listOf("/api/sessions/fixture/tool-images/Read/0"))
        for (blocks in listOf(listOf(image), listOf(flagged), listOf(tool("Read"), result))) {
            assertTrue(segments(blocks).single() is SegmentRenderItem.Item)
            assertFalse(isToolActivityOnly(blocks))
        }
        val url = tool("WebFetch").copy(input = JSONObject().put("url", "https://example.com/shot.png"))
        assertTrue(segments(listOf(url)).single() is SegmentRenderItem.Item)
    }

    @Test
    fun imageFromAnotherTurnKeepsTheSourceCardVisible() {
        val use = tool("Read").copy(activity = ToolActivity("read_file", "查看 image"))
        val result = ContentBlock.ToolResult("Read", "", false, false, null,
            images = listOf("/api/sessions/fixture/tool-images/Read/0"))
        val results = mapOf("Read" to result)
        val items = pairToolBlocks(listOf(use), results)
        assertTrue(collapseActivityItems(items, true, false).single() is SegmentRenderItem.Item)
        assertFalse(isToolActivityOnly(listOf(use), results))
    }

    @Test
    fun rawEventTimesParseWithoutInventingOrChangingFoldIdentity() {
        val todo = ContentBlock.parse(JSONObject(
            """{"type":"tool_use","id":"todo","name":"Pi/todo","occurredAt":"2026-10-01T01:02:03Z"}""",
        )) as ContentBlock.ToolUse
        assertEquals("2026-10-01T01:02:03Z", todo.occurredAt)
        val group = (segments(listOf(todo)).single() as SegmentRenderItem.Activity).group
        assertEquals("tool:todo", group.key)
        assertEquals("完成", toolActivityEntryStatus(ToolActivityEntry(
            group.items.filterIsInstance<DisplayItem.Tool>(),
        ), null))
        val command = tool("Bash").copy(occurredAt = todo.occurredAt)
        assertEquals(java.time.Instant.parse(todo.occurredAt), latestCommandOccurredAt(
            listOf(DisplayItem.Tool(command, null)),
        ))
        assertEquals(null, tool("Pi/todo").occurredAt)
    }

    @Test
    fun installedPiSessionOnlyLeavesItsThreeImageCallsOutsideTheTimeline() {
        // A read-only capture of this reported session, not a fabricated transport fixture.
        val source = checkNotNull(javaClass.getResourceAsStream("/tool-activity-pi-service-sample.json"))
            .bufferedReader().use { it.readText() }
        val sample = JSONObject(source)
        val turns = checkNotNull(ConversationTurn.parseList(sample.getJSONArray("messages")))
        val calls = turns.flatMap { it.content }.filterIsInstance<ContentBlock.ToolUse>()
        assertEquals(sample.getInt("expectedCalls"), calls.size)
        val results = conversationToolResults(turns)
        val rendered = turns.filter { it.role == "assistant" }.flatMap { turn ->
            collapseActivityItems(pairToolBlocks(turn.content, results), false, false)
        }
        val folded = rendered.filterIsInstance<SegmentRenderItem.Activity>()
            .flatMap { it.group.items }.filterIsInstance<DisplayItem.Tool>()
        val independent = rendered.filterIsInstance<SegmentRenderItem.Item>()
            .mapNotNull { it.item as? DisplayItem.Tool }
        assertEquals(sample.getInt("expectedIndependentImages"), independent.size)
        assertTrue(independent.all { !it.result?.images.isNullOrEmpty() })
        assertEquals(calls.size - independent.size, folded.size)
        assertEquals(sample.getInt("expectedMissingActivityTodos"),
            folded.count { it.use.name == "Pi/todo" && it.use.activity == null })
        assertTrue(currentTodos(turns).isNotEmpty())
        assertFalse(foldExpanded(FOLD_OVERRIDE_NONE, derivedDefault = false))
    }

    @Test
    fun legacyLabelsShowOnlyBoundedFileOrToolIdentity() {
        val read = tool("Read").copy(input = JSONObject().put("path", "/private/repo/src/main.kt"))
        val edit = tool("Edit").copy(input = JSONObject().put("file_path", "C:\\repo\\src\\main.kt")
            .put("old_string", "secret"))
        assertEquals("查看 src/main.kt", toolActivityItemLabel(read))
        assertEquals("修改 src/main.kt", toolActivityItemLabel(edit))
        assertEquals("运行命令 · Bash", toolActivityItemLabel(tool("Bash")
            .copy(input = JSONObject().put("command", "secret command"))))
        assertTrue(toolActivityItemLabel(read.copy(input = JSONObject().put("path", "a/" + "x".repeat(500)))).length <= 120)
        assertFalse(toolActivityItemLabel(edit).contains("secret"))
    }
}
