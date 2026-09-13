package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardSwipeTest {
    @Test
    fun swipeActionsFollowTaskStatus() {
        assertEquals(listOf(BoardTaskSwipeAction.Start), boardTaskSwipeActions("todo"))
        assertEquals(listOf(BoardTaskSwipeAction.Complete), boardTaskSwipeActions("doing"))
        assertEquals(listOf(BoardTaskSwipeAction.Archive), boardTaskSwipeActions("done"))
        assertTrue(boardTaskSwipeActions("archived").isEmpty())
        assertTrue(boardTaskSwipeActions("").isEmpty())
    }

    @Test
    fun swipeActionLabelsAreLocalized() {
        assertEquals("开始", boardTaskSwipeActionLabel(BoardTaskSwipeAction.Start))
        assertEquals("完成", boardTaskSwipeActionLabel(BoardTaskSwipeAction.Complete))
        assertEquals("归档", boardTaskSwipeActionLabel(BoardTaskSwipeAction.Archive))
    }

    @Test
    fun advanceActionsMapToNextStatusOnly() {
        assertEquals("doing", boardTaskSwipeTargetStatus(BoardTaskSwipeAction.Start))
        assertEquals("done", boardTaskSwipeTargetStatus(BoardTaskSwipeAction.Complete))
        // 归档走 DELETE /api/wand-tasks/:id，不通过状态补丁。
        assertNull(boardTaskSwipeTargetStatus(BoardTaskSwipeAction.Archive))
    }

    @Test
    fun revealDecidesByVelocityThenDistance() {
        // 按钮露在右侧，划开方向是从右往左：位移与速度都为负。
        assertTrue(boardTaskSwipeShouldReveal(offsetPx = -50f, revealWidthPx = 100f, velocity = 0f))
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = -49f, revealWidthPx = 100f, velocity = 0f))
        assertTrue(boardTaskSwipeShouldReveal(offsetPx = -10f, revealWidthPx = 100f, velocity = -900f))
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = -90f, revealWidthPx = 100f, velocity = 900f))
        // 从左往右滑不划开。
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = 0f, revealWidthPx = 100f, velocity = 900f))
    }

    @Test
    fun revealStaysClosedWithoutRevealWidth() {
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = -120f, revealWidthPx = 0f, velocity = -900f))
    }

    @Test
    fun confirmCopyStatesThePendingChange() {
        assertEquals("开始任务？", boardTaskSwipeActionTitle(BoardTaskSwipeAction.Start))
        assertEquals("确认完成？", boardTaskSwipeActionTitle(BoardTaskSwipeAction.Complete))
        assertEquals("归档任务？", boardTaskSwipeActionTitle(BoardTaskSwipeAction.Archive))
        assertEquals("任务将标记为进行中。", boardTaskSwipeConfirmMessage(BoardTaskSwipeAction.Start))
        assertEquals("任务将标记为已完成。", boardTaskSwipeConfirmMessage(BoardTaskSwipeAction.Complete))
        assertTrue(boardTaskSwipeConfirmMessage(BoardTaskSwipeAction.Archive).contains("归档"))
    }
}
