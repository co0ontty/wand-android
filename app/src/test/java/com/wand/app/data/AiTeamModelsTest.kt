package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AI 团队 DTO 解析与动作映射（真源 src/ai-team-types.ts / server-ai-team-routes.ts）。 */
class AiTeamModelsTest {

    @Test
    fun runDetailParseToleratesMissingAndMalformedFields() {
        val response = JSONObject()
            .put(
                "run",
                JSONObject()
                    .put("id", "run_1")
                    .put("teamId", "team_1")
                    .put("team", JSONObject().put("id", "team_1").put("name", "开发三人组").put("members", JSONArray().put(
                        JSONObject().put("id", "m_a").put("name", "小明").put("isLeader", true).put(
                            "agent", JSONObject().put("provider", "claude"),
                        ),
                    )))
                    .put("taskId", "t_1"),
            )
            .put(
                "steps",
                JSONArray()
                    .put(JSONObject().put("id", "s1").put("seq", 1).put("kind", "leader").put("status", "done"))
                    // 缺 id 的畸形项整条跳过，不拖垮列表（parseEach 语义）。
                    .put(JSONObject().put("title", "没有 id"))
                    .put(
                        JSONObject()
                            .put("id", "s2").put("kind", "work").put("memberId", "m_a")
                            .put("title", "改代码"),
                    ),
            )

        val detail = AiTeamRunDetail.parse(response)
        assertEquals("run_1", detail!!.run.id)
        // 契约缺省回落：status 默认 running、计数默认 0、chatSessionId 可空。
        assertEquals("running", detail.run.status)
        assertEquals("", detail.run.statusDetail)
        assertEquals(0, detail.run.stepsUsed)
        assertNull(detail.run.chatSessionId)
        assertEquals("开发三人组", detail.run.team!!.name)
        assertEquals(listOf("s1", "s2"), detail.steps.map { it.id })
        assertEquals("queued", detail.steps.last().status)
        // leader 步无成员也显示「负责人」；work 步按快照解出成员名。
        assertEquals("负责人", aiTeamStepOwnerLabel(detail.steps.first(), detail))
        assertEquals("小明", aiTeamStepOwnerLabel(detail.steps.last(), detail))

        // run 缺失 / 无 id 时整条判 null，调用方按「没有运行」处理。
        assertNull(AiTeamRunDetail.parse(JSONObject().put("steps", JSONArray())))
        assertNull(AiTeamRunDetail.parse(JSONObject().put("run", JSONObject())))
    }

    @Test
    fun actOnTeamRunMapsActionToPathAndBody() {
        val (approvePath, approveBody) = teamRunActionRequest("run 1", TeamRunAction.Approve)
        assertEquals("/api/ai-team-runs/run+1/approve", approvePath)
        assertEquals(0, approveBody.length())

        val (rejectPath, rejectBody) = teamRunActionRequest("r", TeamRunAction.Reject("重做计划"))
        assertEquals("/api/ai-team-runs/r/reject", rejectPath)
        assertEquals("重做计划", rejectBody.getString("feedback"))

        val (replyPath, replyBody) = teamRunActionRequest("r", TeamRunAction.Reply("补充要求"))
        assertEquals("/api/ai-team-runs/r/reply", replyPath)
        assertEquals("补充要求", replyBody.getString("text"))

        val (continuePath, continueBody) = teamRunActionRequest("r", TeamRunAction.Continue(10))
        assertEquals("/api/ai-team-runs/r/continue", continuePath)
        assertEquals(10, continueBody.getInt("extraSteps"))

        val (stopPath, stopBody) = teamRunActionRequest("r", TeamRunAction.Stop)
        assertEquals("/api/ai-team-runs/r/stop", stopPath)
        assertEquals(0, stopBody.length())
    }

    @Test
    fun directRunParseKeepsTaskIdAndToleratesOldServers() {
        val response = JSONObject()
            .put("run", JSONObject().put("id", "run_9").put("teamId", "team_1").put("taskId", "t_from_run"))
            .put("steps", JSONArray().put(JSONObject().put("id", "s1").put("kind", "leader")))
            .put("taskId", "t_direct")

        val direct = AiTeamDirectRun.parse(response)
        assertEquals("t_direct", direct!!.taskId)
        assertEquals("run_9", direct.detail.run.id)
        assertEquals(listOf("s1"), direct.detail.steps.map { it.id })

        // 旧服务端没有顶层 taskId：回落空串，由调用方用 run.taskId 兜底。
        val legacy = AiTeamDirectRun.parse(
            JSONObject().put("run", JSONObject().put("id", "run_8").put("taskId", "t_run_only")),
        )
        assertEquals("", legacy!!.taskId)
        assertEquals("t_run_only", legacy.detail.run.taskId)

        // run 缺失时整条判 null，和 AiTeamRunDetail 同口径。
        assertNull(AiTeamDirectRun.parse(JSONObject().put("taskId", "t")))
    }
}
