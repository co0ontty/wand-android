package com.wand.app.ui.screens

import com.wand.app.data.AiTeamRun
import com.wand.app.data.ConversationInstance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiTeamConversationRouteTest {
    private val run = AiTeamRun("run-1", "team", null, "task-1", "", "done", "", 0, 1, "session-1")
    private fun group(id: String = "group-1", kind: String = "group", session: String = "session-1") =
        ConversationInstance.parse(JSONObject().put("id", id).put("kind", kind).put("sessionId", session))!!

    @Test fun explicitOwnershipWinsAndFallbackRequiresOneExactGroup() {
        assertEquals("explicit", conversationForRun(run.copy(conversationId = "explicit"), listOf(group())))
        assertEquals("group-1", conversationForRun(run, listOf(group())))
        assertNull(conversationForRun(run, listOf(group(kind = "dm"))))
        assertNull(conversationForRun(run, listOf(group(), group("group-2"))))
        assertNull(conversationForRun(run, listOf(group(session = "other"))))
        assertNull(conversationForRun(run.copy(chatSessionId = null), listOf(group())))
    }

    @Test fun taskOwnershipResolvesWithoutAChatSession() {
        val owned = ConversationInstance.parse(JSONObject("""{"id":"group-1","kind":"group","tasks":[{"task":{"id":"task-1","title":"任务"},"runs":[{"id":"run-1"}]}]}"""))!!
        assertEquals("group-1", conversationForRun(run.copy(chatSessionId = null), listOf(owned)))
        assertNull(conversationForRun(run.copy(id = "other", chatSessionId = null), listOf(owned)))
    }
}
