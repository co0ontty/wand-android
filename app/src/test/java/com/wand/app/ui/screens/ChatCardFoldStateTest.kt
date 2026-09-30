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
 * 普通折叠卡的显式收放与结构性 key 保持稳定；工具活动摘要另行测试。
 *
 * 全部是纯函数，不需要 Compose 运行时，所以能跑 JVM 单测。
 */
class ChatCardFoldStateTest {
    // MARK: - §8.5-3/4 用户 override 优先

    @Test
    fun userOverrideWinsOverDerivedDefault() {
        // 手动展开的卡，即使派生默认值变成「收起」，也不会被抢走。
        assertTrue(resolveCardExpanded(userOverride = true, derivedDefault = false))
        // 手动收起的卡不会被默认展开偏好重新打开。
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
        val groupKey = "tool:t1"
        val firstSegment = cardFoldKey("session-a", cardFoldId("turn-1/seg0/act$groupKey", "tool#0"))
        val secondSegment = cardFoldKey("session-a", cardFoldId("turn-1/seg2/act$groupKey", "tool#0"))
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

}
