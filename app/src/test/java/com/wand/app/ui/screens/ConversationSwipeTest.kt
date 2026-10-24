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
    fun detailManagementMatchesGroupStateAndNeverDissolvesExternalGroups() {
        assertEquals(listOf(ConversationManagementAction.Dissolve, ConversationManagementAction.Delete),
            conversationManagementActions("group", dissolved = false, external = false, deleting = false))
        assertEquals(listOf(ConversationManagementAction.Restore, ConversationManagementAction.Delete),
            conversationManagementActions("group", dissolved = true, external = false, deleting = false))
        assertEquals(listOf(ConversationManagementAction.Delete),
            conversationManagementActions("group", dissolved = false, external = true, deleting = false))
        assertEquals(emptyList<ConversationManagementAction>(),
            conversationManagementActions("group", dissolved = false, external = false, deleting = true))
        assertEquals(emptyList<ConversationManagementAction>(),
            conversationManagementActions("dm", dissolved = false, external = false, deleting = false))
        assertEquals("删除本地群镜像", ConversationManagementAction.Delete.title(external = true))
        org.junit.Assert.assertTrue(ConversationManagementAction.Delete.explanation(true).contains("不退出或解散钉钉群"))
        org.junit.Assert.assertTrue(ConversationManagementAction.Dissolve.explanation(false).contains("不会因此停止"))
    }

    @Test
    fun batchPinReversesOnlyWhenEverythingSelectedIsPinned() {
        assertNull(conversationBatchAction(emptyList()))
        assertEquals(ConversationBatchAction.Pin, conversationBatchAction(listOf(false)))
        assertEquals(ConversationBatchAction.Pin, conversationBatchAction(listOf(true, false)))
        assertEquals(ConversationBatchAction.Unpin, conversationBatchAction(listOf(true, true)))
    }
}
