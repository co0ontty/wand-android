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
    fun builtinEmployeesAreLockedAndSystemEmployeeIsDistinguished() {
        val system = SiliconEmployee.parse(JSONObject()
            .put("id", "e_wand_ops").put("name", "勤劳的初二")
            .put("systemKey", "wand-ops")
            .put("agents", JSONArray().put(BoardTaskAgent.default("codex").toJson())))
        assertNotNull(system)
        assertTrue(system!!.builtin)
        assertTrue(system.isSystemEmployee)
        assertEquals(listOf("系统用户"), system.displayTags)

        val partner = SiliconEmployee.parse(JSONObject()
            .put("id", "e_wand_default").put("name", "赛博虎妞")
            .put("systemKey", "wand-default")
            .put("agents", JSONArray().put(BoardTaskAgent.default("codex").toJson())))
        assertNotNull(partner)
        assertTrue(partner!!.builtin)
        assertFalse(partner.isSystemEmployee)
        assertEquals(listOf("默认用户"), partner.displayTags)
        assertEquals("默认用户", SiliconEmployeeDraft.from(partner).tagInput)
        assertEquals(null, SiliconEmployeeDraft.from(partner).validationError(validateTags = false))

        val user = SiliconEmployee.parse(JSONObject()
            .put("id", "e_user").put("name", "架构师")
            .put("agents", JSONArray().put(BoardTaskAgent.default("codex").toJson())))
        assertNotNull(user)
        assertFalse(user!!.builtin)
        assertFalse(user.isSystemEmployee)
    }

    @Test
    fun builtinEmployeeUpdateOnlySendsCandidates() {
        val draft = SiliconEmployeeDraft(
            name = "勤劳的初二", duty = "系统运维", prompt = "锁定设定",
            agents = listOf(BoardTaskAgent.default("codex")),
        )
        val body = JSONObject().put("agents", draft.agentsJson())
        assertFalse(body.has("name"))
        assertFalse(body.has("duty"))
        assertFalse(body.has("prompt"))
        assertFalse(body.has("avatar"))
        assertFalse(body.has("tags"))
        assertTrue(body.has("agents"))
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
    fun customEmployeeTagsRoundTripAndValidate() {
        val draft = SiliconEmployeeDraft(name = "架构师", tagInput = " 开发，测试、设计\n开发,Équipe")
        assertEquals(null, draft.validationError())
        assertEquals(listOf("开发", "测试", "设计", "Équipe"), draft.tags())
        val json = draft.toJson().put("id", "e_user")
        val parsed = SiliconEmployee.parse(json)!!
        assertEquals(draft.tags(), parsed.tags)
        assertEquals(draft.tags(), parsed.displayTags)
        assertEquals("开发，测试，设计，Équipe", SiliconEmployeeDraft.from(parsed).tagInput)
        assertEquals(0, draft.copy(tagInput = "").toJson().getJSONArray("tags").length())
        assertEquals("最多 8 个员工标签。",
            draft.copy(tagInput = (1..9).joinToString(",") { "标签$it" }).validationError())
        assertEquals("每个员工标签不能超过 20 个字符。",
            draft.copy(tagInput = "x".repeat(21)).validationError())
        for (tag in listOf("系统用户", "默认用户")) {
            assertEquals("「系统用户」「默认用户」是内置标签，不可自定义。",
                draft.copy(tagInput = " $tag ").validationError())
        }
        assertEquals("员工标签不能包含控制字符。",
            draft.copy(tagInput = "a\tb").validationError())
        val builtin = parsed.copy(systemKey = "wand-ops", tags = listOf("被篡改"))
        assertEquals(listOf("系统用户"), builtin.displayTags)
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
