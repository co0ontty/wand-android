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
        assertTrue(boardTaskSwipeShouldReveal(offsetPx = 50f, revealWidthPx = 100f, velocity = 0f))
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = 49f, revealWidthPx = 100f, velocity = 0f))
        assertTrue(boardTaskSwipeShouldReveal(offsetPx = 10f, revealWidthPx = 100f, velocity = 900f))
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = 90f, revealWidthPx = 100f, velocity = -900f))
    }

    @Test
    fun revealStaysClosedWithoutRevealWidth() {
        assertFalse(boardTaskSwipeShouldReveal(offsetPx = 120f, revealWidthPx = 0f, velocity = 900f))
    }
}
