package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoProgressStateTest {
    @Test
    fun explicitInProgressWinsOverEarlierPendingTask() {
        val todos = listOf(
            todo("pending"),
            todo("in_progress"),
            todo("pending"),
        )

        assertEquals(1, activeTodoIndex(todos))
    }

    @Test
    fun firstPendingIsInferredAsActiveForBinaryStatusProtocol() {
        val todos = listOf(
            todo("completed"),
            todo("completed"),
            todo("pending"),
        )

        assertEquals(2, activeTodoIndex(todos))
    }

    @Test
    fun noActiveTaskWhenEveryTaskIsCompleted() {
        assertNull(activeTodoIndex(listOf(todo("completed"), todo("completed"))))
    }

    private fun todo(status: String) = TodoEntry(
        content = "Task",
        status = status,
        activeForm = null,
    )
}
