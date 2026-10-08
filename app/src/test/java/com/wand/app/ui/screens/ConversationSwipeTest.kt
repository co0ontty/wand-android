package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSwipeTest {
    @Test
    fun swipeActionsReflectPinAndGroupState() {
        assertEquals(listOf(ConversationSwipeAction.Pin, ConversationSwipeAction.Delete),
            conversationSwipeActions(kind = "dm", pinned = false, dissolved = false))
        assertEquals(listOf(ConversationSwipeAction.Unpin, ConversationSwipeAction.Delete),
            conversationSwipeActions(kind = "dm", pinned = true, dissolved = false))
        assertEquals(listOf(ConversationSwipeAction.Pin, ConversationSwipeAction.Dissolve, ConversationSwipeAction.Delete),
            conversationSwipeActions(kind = "group", pinned = false, dissolved = false))
        // 已解散的群聊只剩「恢复」，不再给解散动作。
        assertEquals(listOf(ConversationSwipeAction.Unpin, ConversationSwipeAction.Restore, ConversationSwipeAction.Delete),
            conversationSwipeActions(kind = "group", pinned = true, dissolved = true))
    }

    @Test
    fun swipeActionLabelsAreLocalized() {
        assertEquals("置顶", conversationSwipeActionLabel(ConversationSwipeAction.Pin))
        assertEquals("取消置顶", conversationSwipeActionLabel(ConversationSwipeAction.Unpin))
        assertEquals("解散", conversationSwipeActionLabel(ConversationSwipeAction.Dissolve))
        assertEquals("恢复", conversationSwipeActionLabel(ConversationSwipeAction.Restore))
        assertEquals("删除", conversationSwipeActionLabel(ConversationSwipeAction.Delete))
    }

    @Test
    fun batchPinReversesOnlyWhenEverythingSelectedIsPinned() {
        assertNull(conversationBatchAction(emptyList()))
        assertEquals(ConversationBatchAction.Pin, conversationBatchAction(listOf(false)))
        assertEquals(ConversationBatchAction.Pin, conversationBatchAction(listOf(true, false)))
        assertEquals(ConversationBatchAction.Unpin, conversationBatchAction(listOf(true, true)))
    }
}
