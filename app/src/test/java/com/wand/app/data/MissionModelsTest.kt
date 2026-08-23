package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionModelsTest {
    @Test
    fun missionParserPreservesLinkedTaskId() {
        val mission = MissionInfo.parse(
            JSONObject()
                .put("id", "mission-1")
                .put("title", "Parallel")
                .put("prompt", "Fix it")
                .put("cwd", "/repo/.wand-worktrees/task-1")
                .put("status", "running")
                .put("taskId", "task-1"),
        )

        assertEquals("task-1", mission.taskId)
    }

    @Test
    fun missionParserTreatsMissingTaskAsUnlinked() {
        assertNull(MissionInfo.parse(JSONObject().put("id", "mission-1")).taskId)
    }

    @Test
    fun linkedMissionBodyCarriesTaskIdAndAllInputs() {
        val body = createMissionRequestBody(
            title = "Parallel",
            prompt = "Fix it",
            cwd = "/repo/.wand-worktrees/task-1",
            providers = listOf("claude", "codex"),
            taskId = "task-1",
            baseRef = "main",
            sharedDirectories = listOf("node_modules"),
            copyPaths = listOf(".env.local"),
        )

        assertEquals("task-1", body.getString("taskId"))
        assertEquals("/repo/.wand-worktrees/task-1", body.getString("cwd"))
        assertEquals(2, body.getJSONArray("providers").length())
        assertEquals("main", body.getString("baseRef"))
        assertEquals("node_modules", body.getJSONArray("sharedDirectories").getString(0))
        assertEquals(".env.local", body.getJSONArray("copyPaths").getString(0))
    }

    @Test
    fun unlinkedMissionBodyOmitsTaskId() {
        val body = createMissionRequestBody(
            title = null,
            prompt = "Fix it",
            cwd = "/repo",
            providers = listOf("claude"),
            taskId = null,
            baseRef = null,
            sharedDirectories = emptyList(),
            copyPaths = emptyList(),
        )

        assertFalse(body.has("taskId"))
        assertFalse(body.has("title"))
        assertTrue(body.getJSONArray("providers").length() == 1)
    }
}
