package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠卡状态派生规则（设计规格 §8）：用户显式收放与「是否窗口尾部 / 是否最新一段」
 * 派生默认值分离。这组用例直接覆盖本轮 bug（新卡出现后历史卡不自动折叠）与它的反例。
 *
 * 全部是纯函数，不需要 Compose 运行时，所以能跑 JVM 单测。
 */
class ChatCardFoldStateTest {
    // MARK: - §8.5-3/4 用户 override 优先

    @Test
    fun userOverrideWinsOverDerivedDefault() {
        // 手动展开的卡，即使派生默认值变成「收起」，也不会被抢走。
        assertTrue(resolveCardExpanded(userOverride = true, derivedDefault = false))
        // 手动收起的卡，即使它还是窗口尾部，也不会被自动重新展开。
        assertFalse(resolveCardExpanded(userOverride = false, derivedDefault = true))
        // 从未手动操作过（null）才用派生默认值。
        assertTrue(resolveCardExpanded(userOverride = null, derivedDefault = true))
        assertFalse(resolveCardExpanded(userOverride = null, derivedDefault = false))
    }

    @Test
    fun codeRoundTripKeepsThreeStates() {
        assertNull(foldOverrideFromCode(FOLD_OVERRIDE_NONE))
        assertEquals(true, foldOverrideFromCode(FOLD_OVERRIDE_EXPANDED))
        assertEquals(false, foldOverrideFromCode(FOLD_OVERRIDE_COLLAPSED))
        // 未知编码按「没手动操作过」处理，不崩也不锁死。
        assertNull(foldOverrideFromCode(99))
    }

    // MARK: - C1/C2 新卡到达 / 新段开始：没手动过的历史卡自动收起

    @Test
    fun untouchedOldTailCollapsesWhenTailMoves() {
        // 同一个活动段内又来了一个新工具卡：窗口尾部从 item0 前移到 item1。
        fun item0Expanded(tailIndex: Int) = foldExpanded(
            overrideCode = FOLD_OVERRIDE_NONE,
            derivedDefault = cardExpandDefault(inWindowTail = 0 == tailIndex, configured = false),
        )
        assertTrue(item0Expanded(tailIndex = 0))
        // 新卡到达后，旧的尾部条目自动退化成一行摘要（这就是本轮要修的 bug）。
        assertFalse(item0Expanded(tailIndex = 1))
    }

    @Test
    fun untouchedOldSegmentCollapsesWhenNewestFlips() {
        // 正文把活动切成两段：旧段 newest 由 true 变 false。
        fun oldSegmentExpanded(newest: Boolean) = foldExpanded(
            overrideCode = FOLD_OVERRIDE_NONE,
            derivedDefault = activityFoldExpandDefault(newest),
        )
        assertTrue(oldSegmentExpanded(newest = true))
        assertFalse(oldSegmentExpanded(newest = false))
    }

    @Test
    fun manualExpandSurvivesTailMove() {
        // 用户手动展开过：尾部前移 + 新段开始都不能把它收回。
        assertTrue(
            foldExpanded(
                overrideCode = FOLD_OVERRIDE_EXPANDED,
                derivedDefault = cardExpandDefault(inWindowTail = false, configured = false),
            ),
        )
        assertTrue(foldExpanded(FOLD_OVERRIDE_EXPANDED, activityFoldExpandDefault(false)))
    }

    @Test
    fun manualCollapseIsNotReexpanded() {
        // 用户手动收起当前最新段：它仍是尾部，但不许被自动重新展开。
        assertFalse(
            foldExpanded(
                overrideCode = FOLD_OVERRIDE_COLLAPSED,
                derivedDefault = cardExpandDefault(inWindowTail = true, configured = true),
            ),
        )
        assertFalse(foldExpanded(FOLD_OVERRIDE_COLLAPSED, activityFoldExpandDefault(true)))
    }

    // MARK: - 点击语义

    @Test
    fun toggleFromDerivedDefaultWritesExplicitState() {
        // 默认展开 → 点一下变显式收起。
        assertEquals(FOLD_OVERRIDE_COLLAPSED, foldToggleCode(FOLD_OVERRIDE_NONE, derivedDefault = true))
        // 默认收起 → 点一下变显式展开。
        assertEquals(FOLD_OVERRIDE_EXPANDED, foldToggleCode(FOLD_OVERRIDE_NONE, derivedDefault = false))
        // 已经是显式展开 → 点一下变显式收起。
        assertEquals(FOLD_OVERRIDE_COLLAPSED, foldToggleCode(FOLD_OVERRIDE_EXPANDED, derivedDefault = false))
        // 显式收起 → 点开后是显式展开（不回到「未操作」，避免默认值再次翻转时抖回去）。
        assertEquals(FOLD_OVERRIDE_EXPANDED, foldToggleCode(FOLD_OVERRIDE_COLLAPSED, derivedDefault = true))
    }

    // MARK: - C7 切会话不串状态

    @Test
    fun sessionScopedKeysDoNotCollide() {
        assertTrue(
            cardFoldKey("session-a", "activity-0:tool:Bash") !=
                cardFoldKey("session-b", "activity-0:tool:Bash"),
        )
        assertEquals("session-a|activity-0:tool:Bash", cardFoldKey("session-a", "activity-0:tool:Bash"))
    }

    // MARK: - v2 勘误 2：fold key 只允许「结构性 scope + 未过滤下标」

    @Test
    fun thinkingFoldKeyIsStableWhileThinkingStreams() {
        val scope = "turn-3/seg0"
        // 位置 id 只由「类型 + 绝对值下标」构成：思考文本多长都不参与。
        assertEquals("thinking#0", cardPositionId("thinking", "", 0))
        fun keyFor(thinkingText: String): String {
            // 模拟流式：文本变长不影响 key（生产路径同 cardPositionId("thinking", "", index)）。
            assertTrue(thinkingText.isNotEmpty())
            return cardFoldKey("session-a", cardFoldId(scope, cardPositionId("thinking", "", 0)))
        }
        val short = keyFor("p")
        val grown = keyFor("planning")
        val long = keyFor("planning".repeat(30))
        assertEquals(short, grown)
        assertEquals(grown, long)
        // key 不漂移 ⇒ 用户手动展开的思考块在流式期间保持展开（override 不被清）。
        assertTrue(foldExpanded(FOLD_OVERRIDE_EXPANDED, derivedDefault = false))
        assertTrue(foldExpanded(FOLD_OVERRIDE_COLLAPSED, derivedDefault = true) == false)
    }

    @Test
    fun thinkingFoldKeysDifferAcrossContainers() {
        val firstSegment = cardFoldKey("session-a", cardFoldId("turn-3/seg0", cardPositionId("thinking", "", 0)))
        val secondSegment = cardFoldKey("session-a", cardFoldId("turn-3/seg2", cardPositionId("thinking", "", 0)))
        val otherSession = cardFoldKey("session-b", cardFoldId("turn-3/seg0", cardPositionId("thinking", "", 0)))
        assertNotEquals(firstSegment, secondSegment)
        assertNotEquals(firstSegment, otherSession)
        assertNotEquals(secondSegment, otherSession)
    }

    @Test
    fun unknownBlocksOfTheSameTypeDoNotCollide() {
        val scope = "turn-3/seg0"
        assertEquals("unknown:mystery#1", unknownCardPositionId("mystery", 1))
        val first = cardFoldKey("session-a", cardFoldId(scope, unknownCardPositionId("mystery", 1)))
        val second = cardFoldKey("session-a", cardFoldId(scope, unknownCardPositionId("mystery", 2)))
        assertNotEquals(first, second)
    }

    @Test
    fun activityGroupKeyDistinguishesSegments() {
        // 两个段里的活动组即使 startIndex 相同（都是 0），带不同段 scope 后 fold key 也不同。
        val groupKey = "0:thinking"
        val firstSegment = cardFoldKey("session-a", cardFoldId("turn-1/seg0/act$groupKey", "thinking#0"))
        val secondSegment = cardFoldKey("session-a", cardFoldId("turn-1/seg2/act$groupKey", "thinking#0"))
        assertNotEquals(firstSegment, secondSegment)
    }

    @Test
    fun messageScopePrefersCallerItemKeyAndFallsBackStructurally() {
        assertEquals(
            "turn-7",
            cardMessageScope("session-a", "assistant", "2026-01-01T00:00:00Z", foldScope = "turn-7"),
        )
        // 调用方没传 scope 时退化成结构 token（不含内容）。
        assertEquals(
            "session-a#msg:assistant:2026-01-01T00:00:00Z",
            cardMessageScope("session-a", "assistant", "2026-01-01T00:00:00Z", foldScope = ""),
        )
    }

    // MARK: - v2 勘误 1：chip 净空恒等式

    @Test
    fun chipInsetKeepsEightDpClearance() {
        val clearance = (ACTIVITY_WINDOW_TOP_PAD + ACTIVITY_TAIL_CHIP_INSET) -
            (ACTIVITY_TAIL_CHIP_BOTTOM + ACTIVITY_TAIL_CHIP_HEIGHT)
        assertTrue(
            "chip 净空 $clearance 低于承诺的 $ACTIVITY_TAIL_CHIP_CLEARANCE",
            clearance >= ACTIVITY_TAIL_CHIP_CLEARANCE,
        )
        // inset 必须是派生式：chip 高度/位置/净空任一改动，下面这两边都会同步变（写成字面量就失配）。
        assertEquals(
            ACTIVITY_TAIL_CHIP_HEIGHT + ACTIVITY_TAIL_CHIP_BOTTOM + ACTIVITY_TAIL_CHIP_CLEARANCE -
                ACTIVITY_WINDOW_TOP_PAD,
            ACTIVITY_TAIL_CHIP_INSET,
        )
        assertTrue(ACTIVITY_TAIL_CHIP_HEIGHT.value >= 28f)
    }

    // MARK: - §8.3 窗口内条目的派生默认值

    @Test
    fun windowItemDefaultIsTailOnly() {
        // 窗口里只有最新一条默认展开。
        assertTrue(cardExpandDefault(inWindowTail = true, configured = false))
        assertFalse(cardExpandDefault(inWindowTail = false, configured = true))
        // 不在窗口里（null）时沿用用户的卡片偏好，语义不变。
        assertTrue(cardExpandDefault(inWindowTail = null, configured = true))
        assertFalse(cardExpandDefault(inWindowTail = null, configured = false))
    }

    // MARK: - §8.6 空展开门控

    @Test
    fun emptyInputObjectHasNoBody() {
        val result = ContentBlock.ToolResult("t1", "ok", false, false, null)
        assertFalse(toolInputHasEntries(JSONObject()))
        assertTrue(toolInputHasEntries(JSONObject().put("command", "ls")))
        // 空输入且没有结果：不画箭头、不可点，也不再出现只有分隔线的空白展开区。
        assertFalse(toolCardHasBody(JSONObject(), null))
        // 有参数或拿到结果都能展开。
        assertTrue(toolCardHasBody(JSONObject().put("command", "ls"), null))
        assertTrue(toolCardHasBody(JSONObject(), result))
    }

    // MARK: - 端到端形状：真段转移时旧段真的会收起来

    @Test
    fun olderActivitySegmentCollapsesThroughTheRealGroupingPath() {
        val thinking = ContentBlock.Thinking("planning", null)
        val first = ContentBlock.ToolUse("t1", "Bash", null, JSONObject().put("command", "ls"), null)
        val prose = ContentBlock.Text("先看结果", null)
        val second = ContentBlock.ToolUse("t2", "Read", null, JSONObject().put("file_path", "a.kt"), null)
        val segments = collapseActivityItems(pairToolBlocks(listOf(thinking, first, prose, second)), true, true)
        val groups = segments.filterIsInstance<SegmentRenderItem.Activity>().map { it.group }
        assertEquals(2, groups.size)

        val firstSegment = groups[0]
        val lastSegment = groups[1]
        // 只有最后一段是「最新」，旧段因此默认收起。
        assertFalse(firstSegment.newest)
        assertTrue(lastSegment.newest)
        assertTrue(
            foldExpanded(FOLD_OVERRIDE_NONE, activityFoldExpandDefault(firstSegment.newest)) == false,
        )
        assertTrue(foldExpanded(FOLD_OVERRIDE_NONE, activityFoldExpandDefault(lastSegment.newest)))
    }
}
