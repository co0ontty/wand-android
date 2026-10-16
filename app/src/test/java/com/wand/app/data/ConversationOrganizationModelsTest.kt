package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationOrganizationModelsTest {
    @Test
    fun globalWorkspaceUsesWorkspaceTargetAndSyntheticUsesDirectoryTarget() {
        val global = TaskDirectoryGroup(GLOBAL_WORKSPACE_ID, "未归属", "", false, emptyList(), emptyList(), global = true)
        val synthetic = TaskDirectoryGroup("cwd:/repo", "Repo", "/repo/", true, emptyList(), emptyList())
        assertEquals("workspace", global.conversationOrganizationTarget()!!.toJson().getString("kind"))
        assertEquals(GLOBAL_WORKSPACE_ID, global.conversationOrganizationTarget()!!.toJson().getString("workspaceId"))
        assertEquals("directory", synthetic.conversationOrganizationTarget()!!.toJson().getString("kind"))
        assertEquals("/repo", synthetic.conversationOrganizationTarget()!!.toJson().getString("cwd"))
    }

    @Test
    fun previewAndJobParseOptionalFieldsWithoutDroppingResult() {
        val preview = ConversationOrganizationPreview.parse(JSONObject()
            .put("token", "token-1")
            .put("sessionCount", 3)
            .put("contentCharacters", 1200)
            .put("estimatedModelRequests", 2))
        assertEquals(3, preview!!.sessionCount)
        assertEquals(0, preview.manualSessionCount)
        assertNull(preview.activeJobId)

        val job = ConversationOrganizationJob.parse(JSONObject()
            .put("id", "job-1")
            .put("status", "completed")
            .put("result", JSONObject()
                .put("ok", true)
                .put("movedSessions", org.json.JSONArray().put(JSONObject()
                    .put("sessionId", "session-1").put("taskId", "task-1").put("taskName", "整理")))))
        assertTrue(job!!.result.ok)
        assertEquals(1, job.result.movedSessions.size)
        assertEquals("session-1", job.result.movedSessions.single().sessionId)
    }
}
