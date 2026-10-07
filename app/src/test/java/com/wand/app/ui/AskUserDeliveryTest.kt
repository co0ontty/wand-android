package com.wand.app.ui

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.WandApiException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AskUserDeliveryTest {
    private fun ask(id: String) = ContentBlock.ToolUse(id, "AskUserQuestion", null, JSONObject(), null)

    @Test fun onlyUnansweredQuestionsInCurrentRoundAcceptInput() {
        val old = ConversationTurn("assistant", listOf(ask("old")))
        val user = ConversationTurn("user", listOf(ContentBlock.Text("new request", null)))
        val current = ConversationTurn("assistant", listOf(ask("current")))
        assertEquals(setOf("current"), activeAskQuestionIds(listOf(old, user, current)))
        val result = ContentBlock.ToolResult("current", "answer", false, false, null)
        assertEquals(emptySet<String>(), activeAskQuestionIds(listOf(old, user, current, ConversationTurn("assistant", listOf(result)))))
    }

    @Test fun unknownDeliveryKeepsSelectionAndDuplicateSubmissionLock() {
        val selected = AskUserSelectionState(mapOf(0 to setOf(1)), submitted = true)
        val failures = listOf(408, 409, 500, 503).map { WandApiException(it, "unknown") } +
            listOf(CancellationException("left"), IllegalStateException("ack parse failed"), UnconfirmedComposerInputException(WandApiException(422, "second PTY chunk rejected")))
        failures.forEach { failure ->
            val after = askUserSelectionAfterFailure(selected, failure)
            assertTrue(after.submitted)
            assertTrue(after.submissionUnconfirmed)
            assertEquals(selected.selected, after.selected)
        }
        val rejected = askUserSelectionAfterFailure(selected, WandApiException(422, "not accepted"))
        assertFalse(rejected.submitted)
        assertFalse(rejected.submissionUnconfirmed)
        assertEquals(selected.selected, rejected.selected)
    }
}
