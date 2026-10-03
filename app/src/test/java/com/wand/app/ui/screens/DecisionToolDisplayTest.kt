package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ToolActivity
import com.wand.app.data.ToolUseSemantic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionToolDisplayTest {
    private fun decision(): ContentBlock.ToolUse = ContentBlock.parse(JSONObject(
        """{"type":"tool_use","id":"d1","name":"Bash","input":{"command":"wand decide --stdin"},"semantic":{"kind":"decision"}}""",
    )) as ContentBlock.ToolUse

    @Test
    fun projectionKeepsRealInvocationAndResultFields() {
        val use = decision()
        assertEquals("Bash", use.name)
        assertEquals("wand decide --stdin", use.input.getString("command"))
        assertEquals(ToolUseSemantic.Decision(), use.semantic)
        val result = ContentBlock.parse(JSONObject(
            """{"type":"tool_result","tool_use_id":"d1","content":"actual result","semantic":{"kind":"decision"}}""",
        )) as ContentBlock.ToolResult
        assertTrue(result.decision)
        assertEquals("actual result", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun decisionIsIndependentWhilePendingCompletedOrFailedEvenWithOldActivityMetadata() {
        val use = decision().copy(activity = ToolActivity("run_command", "运行命令"))
        val complete = ContentBlock.ToolResult("d1", "actual result", false, false, null, semantic = ToolUseSemantic.Decision())
        for (result in listOf(null, complete, complete.copy(isError = true))) {
            val items = listOf(DisplayItem.Tool(use, result))
            val render = collapseActivityItems(items, isLastTurn = true, isResponding = result == null)
            assertTrue(render.single() is SegmentRenderItem.Item)
            assertFalse(isCollapsibleActivityTool(use, result))
            val blocks = listOf(use) + listOfNotNull(result)
            assertFalse(isToolActivityOnly(blocks))
            for (default in listOf(false, true)) {
                assertFalse(toolCardExpanded(use, result, FOLD_OVERRIDE_NONE, derivedDefault = default))
                assertFalse(toolCardExpanded(use, result, FOLD_OVERRIDE_COLLAPSED, derivedDefault = default))
                assertTrue(toolCardExpanded(use, result, FOLD_OVERRIDE_EXPANDED, derivedDefault = default))
            }
        }
    }

    @Test
    fun lateResultStaysIndependentWithoutResettingTheExplicitDetailState() {
        val cached = decision().copy(semantic = null, input = JSONObject(), activity = ToolActivity("run_command", "运行命令"))
        val result = ContentBlock.ToolResult("d1", "actual result", false, false, null, semantic = ToolUseSemantic.Decision())
        val results = mapOf("d1" to result)
        assertTrue(isDecisionToolCall(cached, result))
        assertFalse(isToolActivityOnly(listOf(cached), results))
        assertTrue(collapseActivityItems(pairToolBlocks(listOf(cached), results), false, false).single() is SegmentRenderItem.Item)
        assertFalse(toolCardExpanded(cached, result, FOLD_OVERRIDE_NONE, derivedDefault = true))
        assertFalse(toolCardExpanded(cached, result, FOLD_OVERRIDE_COLLAPSED, derivedDefault = false))
        assertTrue(toolCardExpanded(cached, result, FOLD_OVERRIDE_EXPANDED, derivedDefault = false))
        assertTrue(collapseActivityItems(pairToolBlocks(listOf(result)), false, false).single() is SegmentRenderItem.Item)
    }

    @Test
    fun decisionCardSummaryRendersTheServerProjectionOnBothTheInvocationAndTheLateResult() {
        val summary = """{"kind":"decision","summary":{"mode":"mixed","questions":3,
            |"preview":"订单被重复扣款","outcome":"category=billing 84%","label":"category=billing 84% · 3 题"}}""".trimMargin()
        val use = ContentBlock.parse(JSONObject(
            """{"type":"tool_use","id":"d9","name":"Bash","input":{"command":"wand decide --stdin"},"semantic":$summary}""",
        )) as ContentBlock.ToolUse
        val semantic = use.semantic as ToolUseSemantic.Decision
        assertEquals("category=billing 84% · 3 题", semantic.summary?.label)
        assertEquals(3, semantic.summary?.questions)
        assertEquals("mixed", semantic.summary?.mode)
        assertEquals("category=billing 84% · 3 题 · 实验性", "${decisionSummaryLabel(use, null)} · 实验性")
        // 迟到的结果页只有结果块，摘要必须从它自己的投影里读出来。
        val late = ContentBlock.parse(JSONObject(
            """{"type":"tool_result","tool_use_id":"d9","content":"{}","semantic":$summary}""",
        )) as ContentBlock.ToolResult
        assertTrue(late.decision)
        assertEquals("category=billing 84% · 3 题", decisionSummaryLabel(ContentBlock.ToolUse("d9", "本地决策", null, JSONObject(), null, null), late))
        // 旧服务端没有投影时退回通用描述，不编造结论。
        assertEquals("选择 / 评分 / 是非判断", decisionSummaryLabel(decision(), null))
        assertEquals("选择 / 评分 / 是非判断", decisionSummaryLabel(decision(), ContentBlock.ToolResult("d1", "", false, false, null, semantic = ToolUseSemantic.Decision())))
    }

    @Test
    fun decisionDetailsOpenAndCloseFromTheirOwnCollapsedDefault() {
        val use = decision()
        val opened = foldToggleCode(FOLD_OVERRIDE_NONE, derivedDefault = false)
        assertTrue(toolCardExpanded(use, null, opened, derivedDefault = true))
        val closed = foldToggleCode(opened, derivedDefault = false)
        assertFalse(toolCardExpanded(use, null, closed, derivedDefault = true))
    }

    @Test
    fun decisionsDoNotBecomeExplorationGroupsAndStatusQueriesRemainOrdinary() {
        val asRead = decision().copy(name = "Read")
        val turns = List(4) { ConversationTurn("assistant", listOf(asRead.copy(id = "d$it"))) }
        assertTrue(groupExplorationTurns(turns).all { it is MessageDisplayItem.Turn })
        val status = decision().copy(semantic = null, input = JSONObject().put("command", "wand decide --status"))
        assertTrue(isCollapsibleActivityTool(status))
        assertFalse(toolCardExpanded(status, null, FOLD_OVERRIDE_COLLAPSED, derivedDefault = true))
    }
}
