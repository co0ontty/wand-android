package com.wand.app.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerInputFieldPresentationTest {
    @Test
    fun composerInputMaxHeightMatchesDesignContract() {
        assertEquals(Dp.Infinity, composerInputMaxHeight(expanded = false))
        assertEquals(132.dp, composerInputMaxHeight(expanded = true))
    }

    @Test
    fun composerExpansionStatesReflectUserActivity() {
        // 未聚焦、无录音、单行草稿、无附件时保持紧凑折叠
        val collapsed = shouldComposerExpand(
            isFocused = false,
            voicePressed = false,
            draftNeedsExpanded = false,
            hasAttachments = false,
        )
        assertFalse(collapsed)

        // 任意激活状态均触发平滑展开
        assertTrue(shouldComposerExpand(isFocused = true, voicePressed = false, draftNeedsExpanded = false, hasAttachments = false))
        assertTrue(shouldComposerExpand(isFocused = false, voicePressed = true, draftNeedsExpanded = false, hasAttachments = false))
        assertTrue(shouldComposerExpand(isFocused = false, voicePressed = false, draftNeedsExpanded = true, hasAttachments = false))
        assertTrue(shouldComposerExpand(isFocused = false, voicePressed = false, draftNeedsExpanded = false, hasAttachments = true))
    }

    @Test
    fun prioritizeSelectedItemPlacesActiveItemAtTop() {
        val items = listOf(
            "default" to "默认",
            "claude-3-5" to "Sonnet 3.5",
            "claude-3-7" to "Sonnet 3.7",
            "opus" to "Opus",
        )

        // 选中中间项：被选中的项移至最顶端，其余项相对顺序保持不变
        val prioritized = prioritizeSelectedItem(items, "claude-3-7") { it.first }
        assertEquals("claude-3-7", prioritized.first().first)
        assertEquals(listOf("claude-3-7", "default", "claude-3-5", "opus"), prioritized.map { it.first })

        // 选中第一项：顺序保持原样
        val alreadyTop = prioritizeSelectedItem(items, "default") { it.first }
        assertEquals(items, alreadyTop)

        // 选中项不存在或为 null：顺序保持原样
        val notFound = prioritizeSelectedItem(items, "unknown") { it.first }
        assertEquals(items, notFound)
        val nullSelected = prioritizeSelectedItem(items, null) { it.first }
        assertEquals(items, nullSelected)

        // 空列表或单项列表：安全处理
        val empty: List<Pair<String, String>> = emptyList()
        assertEquals(empty, prioritizeSelectedItem(empty, "default") { it.first })
        val single = listOf("a" to "A")
        assertEquals(single, prioritizeSelectedItem(single, "a") { it.first })
    }
}
