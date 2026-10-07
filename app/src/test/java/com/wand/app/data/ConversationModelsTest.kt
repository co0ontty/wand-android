package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationModelsTest {
    @Test fun emptyGroupIsIndependentOfRunAndAcceptsOneRealEmployee() {
        val raw = JSONObject("""{"id":"group-a","kind":"group","title":"我与员工的群聊","memberVersion":1,
            "team":{"id":"group-a","name":"实例","members":[{"id":"m-a","employeeId":"e-a","name":"员工","isLeader":true}]},
            "tasks":[],"messages":[]}""")
        val group = ConversationInstance.parse(raw)!!
        assertEquals("group-a", group.id)
        assertEquals(1, group.team!!.members.size)
        assertTrue(group.tasks.isEmpty())
        assertNull(group.sessionId)
        assertEquals(1, CONVERSATION_MIN_EMPLOYEES)
        assertEquals(2, AI_TEAM_MIN_MEMBERS) // preset validation remains distinct
    }

    @Test fun conversationIdentityAndExplicitTargetSurviveParse() {
        val target = ConversationTarget("task-a", "run-a")
        assertEquals(target, ConversationTarget.parse(target.toJson()))
        assertNull(ConversationTarget.parse(JSONObject().put("runId", "latest-is-not-a-target")))
        assertEquals("dm_e-a", employeeConversationId("e-a"))
        val turn = ConversationTurn.parse(JSONObject("""{"role":"assistant","content":[{"type":"text","text":"历史回复"}],
            "author":{"id":"m-a","name":"原署名"},"messageId":"message-a","requestId":"request-a",
            "conversationTarget":{"taskId":"task-a","runId":"run-a"}}"""))
        assertEquals("message-a", turn.messageId)
        assertEquals("request-a", turn.requestId)
        assertEquals(target, turn.conversationTarget)
        assertEquals("原署名", turn.author!!.name)
    }

    @Test fun taskPreviewKeepsExactRunAndBoundsItsReadOnlyText() {
        val json = JSONObject().put("role", "assistant").put("content", org.json.JSONArray())
            .put("conversationLink", JSONObject().put("conversationId", "group-a").put("taskId", "task-a").put("title", "任务 A"))
            .put("taskPreview", JSONObject().put("runId", "run-a").put("status", "running").put("text", "进展".repeat(4000) + "最新"))
        val turn = ConversationTurn.parse(json)
        assertEquals("run-a", turn.taskPreview?.runId)
        assertEquals("group-a", turn.conversationLink?.conversationId)
        assertEquals(4000, turn.taskPreview?.text?.length)
        assertTrue(turn.taskPreview!!.text.endsWith("最新"))
        assertNull(ConversationTurn.parse(JSONObject().put("role", "assistant")).taskPreview)
        val unavailable = ConversationTaskPreview.parse(JSONObject().put("status", "unavailable").put("text", "任务已删除"))!!
        assertNull(unavailable.runId)
        assertEquals("unavailable", unavailable.status)
    }

    @Test fun directMessageLinksAnExactSessionAndBoundsLiveUpdatesWithoutTaskIdentity() {
        val json = JSONObject().put("role", "assistant")
            .put("sessionLink", JSONObject().put("sessionId", "session-a").put("title", "消息 A"))
            .put("sessionPreview", JSONObject().put("status", "running").put("text", "回复".repeat(4000) + "最新"))
        val turn = ConversationTurn.parse(json)
        assertEquals("session-a", turn.sessionLink?.sessionId)
        assertNull(turn.conversationLink)
        assertNull(turn.taskPreview)
        assertEquals(4000, turn.sessionPreview?.text?.length)
        assertTrue(turn.sessionPreview!!.text.endsWith("最新"))
        val update = ConversationSessionUpdate.parse(JSONObject().put("conversationId", "dm_a")
            .put("sessionId", "session-a").put("preview", JSONObject().put("status", "done").put("text", "完成")))!!
        assertEquals("dm_a", update.conversationId)
        assertEquals("session-a", update.sessionId)
        assertEquals("done", update.preview.status)
        assertNull(ConversationSessionUpdate.parse(JSONObject().put("preview", JSONObject())))
    }

    @Test fun receiptDoesNotInferSuccessFromMissingFields() {
        assertThrows(Exception::class.java) { ConversationReceipt.parse(JSONObject("{}")) }
        val pending = ConversationReceipt.parse(JSONObject("""{"requestId":"request-1","state":"pending","conversationId":"group-1"}"""))
        assertEquals("pending", pending.state)
        assertNull(pending.runId)
        val accepted = ConversationReceipt.parse(JSONObject("""{"requestId":"request-1","state":"accepted","conversationId":"group-1","taskId":"task-1","runId":"run-1"}"""))
        assertEquals("run-1", accepted.runId)
    }

    @Test fun acceptedTaskStartupFailureIsNotADeliveryRejectionAndKeepsOriginalIds() {
        val receipt = ConversationReceipt.parse(JSONObject("""{"requestId":"request-accepted","state":"accepted","conversationId":"group-original","taskId":"task-original","startup":"failed","error":"relay失败"}"""))
        assertEquals("accepted", receipt.state)
        assertEquals("failed", receipt.startup)
        assertEquals("group-original", receipt.conversationId)
        assertEquals("task-original", receipt.taskId)
        assertNull(receipt.runId)
        val group = ConversationInstance.parse(JSONObject("""{"id":"group-original","kind":"group","title":"原任务",
            "tasks":[{"task":{"id":"task-original","title":"原任务"},"runs":[],"startup":{"state":"failed","error":"relay失败"}}]}"""))!!
        assertEquals("failed", group.tasks.single().startup?.state)
        assertEquals("relay失败", group.tasks.single().startup?.error)
    }

    @Test fun legacyMemberAndFrozenRunVersionAreNotReboundToAnotherEmployee() {
        val member = AiTeamMember.parse(JSONObject("""{"id":"m-old","name":"旧名字","legacyTemplateId":"template-a","legacyMemberId":"original"}"""))!!
        assertNull(member.employeeId)
        assertEquals("template-a", member.legacyTemplateId)
        val run = AiTeamRun.parse(JSONObject("""{"id":"run-a","taskId":"task-a","conversationId":"group-a","memberVersion":2,"roundNumber":3}"""))!!
        assertEquals("group-a", run.conversationId)
        assertEquals(2, run.memberVersion)
        assertEquals(3, run.roundNumber)
    }
}
