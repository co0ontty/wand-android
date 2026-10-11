package com.wand.app.ui.components

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ToolActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AvatarFileActivityTest {
    @Test fun thoughtOrbitUsesOnlyCurrentThinkingAndNeverStreamingReadingOrCompletedHistory() {
        val thought = listOf(user(), assistant(ContentBlock.Thinking("reason", null)))
        assertTrue(companionThinking(thought, true, AvatarFileActivity.Idle))
        assertFalse(companionThinking(thought, false, AvatarFileActivity.Idle))
        assertFalse(companionThinking(thought, true, AvatarFileActivity.Reading))
        assertFalse(companionThinking(thought + assistant(ContentBlock.Text("reply", null)), true, AvatarFileActivity.Idle))
        assertFalse(companionThinking(thought + user(), true, AvatarFileActivity.Idle))
        assertFalse(companionThinking(listOf(user(), thought.last().copy(completedAt = "done")), true, AvatarFileActivity.Idle))
    }
    private fun tool(id: String, kind: String) = ContentBlock.ToolUse(id, "tool", null, JSONObject(), null, activity = ToolActivity(kind, kind))
    private fun result(id: String, error: Boolean = false) = ContentBlock.ToolResult(id, "done", error, false, null)
    private fun assistant(vararg content: ContentBlock) = ConversationTurn("assistant", content.toList())
    private fun user() = ConversationTurn("user", emptyList())
    @Test fun currentRoundOnlyAndExecutionGated() {
        val turns = listOf(assistant(tool("old", "edit_file")), user(), assistant(tool("new", "read_file")))
        assertEquals(AvatarFileActivity.Reading, avatarFileActivity(turns, true))
        assertEquals(AvatarFileActivity.Idle, avatarFileActivity(turns, false))
    }
    @Test fun concurrentResultsIncludingErrorsRetireOnlyTheirInvocation() {
        val turns = listOf(user(), assistant(tool("r", "read_file"), tool("w", "edit_file")))
        assertEquals(AvatarFileActivity.Writing, avatarFileActivity(turns, true))
        assertEquals(AvatarFileActivity.Reading, avatarFileActivity(turns + assistant(result("w", true)), true))
        assertEquals(AvatarFileActivity.Idle, avatarFileActivity(turns + assistant(result("w"), result("r")), true))
    }
    @Test fun completedTurnAndUnclassifiedCommandDoNotAnimate() {
        assertEquals(AvatarFileActivity.Idle, avatarFileActivity(listOf(assistant(tool("r", "read_file")).copy(completedAt = "done")), true))
        assertEquals(AvatarFileActivity.Idle, avatarFileActivity(listOf(assistant(tool("c", "run_command"))), true))
    }
    @Test fun duplicateSnapshotsDoNotReorderCalls() {
        assertEquals(AvatarFileActivity.Writing, avatarFileActivity(listOf(assistant(tool("r", "read_file"), tool("w", "edit_file"), tool("r", "read_file"))), true))
    }
    @Test fun fixedIdsLockCatsAndSceneConfigMatchesWeb() {
        assertEquals("plush_cat_silver", PlushAvatarIdentity.asset("e_wand_default", "改名", "data:image/png;base64,a"))
        assertEquals("plush_cat_orange", PlushAvatarIdentity.asset("e_wand_ops", "改名", "cat:0"))
        assertEquals("silver", plushSceneConfig("plush_cat_silver")!!.getString("coat"))
        assertEquals("heart", plushSceneConfig("plush_heart_coral_gold_beret")!!.getString("shape"))
        // 柴犬走同一个共享场景，config 与 Web `plush-dog:v1:shiba:red-scarf` 同字段。
        val dog = plushSceneConfig(PlushAvatarIdentity.dogAsset)!!
        assertEquals("dog", dog.getString("kind"))
        assertEquals("shiba", dog.getString("breed"))
        assertEquals("red", dog.getString("scarf"))
    }
}
