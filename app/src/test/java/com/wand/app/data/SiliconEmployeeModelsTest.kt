package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiliconEmployeeModelsTest {
    @Test
    fun employeeDraftKeepsCandidateOrderAndRejectsPty() {
        val first = BoardTaskAgent("codex", "gpt", "deep", "full-access", "structured")
        val second = BoardTaskAgent("claude", "sonnet", "off", "managed", "structured")
        val draft = SiliconEmployeeDraft(
            name = "  架构师  ", duty = "审查设计", prompt = "保持简单", agents = listOf(first, second),
        )
        assertEquals(null, draft.validationError())
        val json = draft.toJson()
        assertEquals("架构师", json.getString("name"))
        assertEquals("codex", json.getJSONArray("agents").getJSONObject(0).getString("provider"))
        assertEquals("claude", json.getJSONArray("agents").getJSONObject(1).getString("provider"))
        assertEquals("硅基员工只能使用结构化会话。",
            draft.copy(agents = listOf(first.copy(kind = "pty"))).validationError())
    }

    @Test
    fun employeeParsingRetainsArchiveAndDefinition() {
        val employee = SiliconEmployee.parse(JSONObject()
            .put("id", "emp-1").put("name", "架构师").put("duty", "设计")
            .put("prompt", "规则").put("avatar", "cat:3")
            .put("archivedAt", "2026-09-30T00:00:00Z")
            .put("agents", JSONArray().put(BoardTaskAgent.default("codex").toJson())))
        assertNotNull(employee)
        assertTrue(employee!!.archived)
        assertEquals("设计", employee.duty)
        assertEquals("codex", employee.agents.single().provider)
    }

    @Test
    fun taskBodiesNeverMixEmployeeOrTeamWithOldPtyAgent() {
        val oldPty = BoardTaskAgent.default("codex").copy(kind = "pty")
        for (subject in listOf(ExecutionSubject.employee("emp-1"), ExecutionSubject.team("team-1"))) {
            val create = createBoardTaskBody("任务", "目标", "doing", "none", null,
                oldPty, executionSubject = subject)
            val dispatch = boardDispatchSubjectBody(subject, oldPty, "目标", null)
            assertFalse(create.has("agent"))
            assertFalse(dispatch.has("agent"))
            assertEquals(subject.type, create.getJSONObject("executionSubject").getString("type"))
            assertEquals(subject.id, dispatch.getJSONObject("subject").getString("id"))
            assertTrue(dispatch.isNull("workspaceId"))
        }
        val cli = boardDispatchSubjectBody(ExecutionSubject.cli("codex"), oldPty, "目标")
        assertEquals("pty", cli.getJSONObject("agent").getString("kind"))
        assertFalse(cli.has("workspaceId"))
    }

    @Test
    fun boardTaskReadsPersistedExecutionSubject() {
        val task = BoardTask.parse(JSONObject().put("id", "task-1")
            .put("executionSubject", ExecutionSubject.employee("emp-1").toJson()))
        assertEquals(ExecutionSubject.employee("emp-1"), task?.executionSubject)
    }
}
