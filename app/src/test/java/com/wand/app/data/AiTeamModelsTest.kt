package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AI 团队 DTO 解析与动作映射（真源 src/ai-team-types.ts / server-ai-team-routes.ts）。 */
class AiTeamModelsTest {

    @Test
    fun employeeIdentitySurvivesDtoDraftAndJsonRoundTrip() {
        val input = JSONObject().put("id", "m_1").put("name", "员工")
            .put("employeeId", "e_real").put("avatar", "cat:4").put("role", "verify")
            .put("agents", JSONArray().put(JSONObject().put("provider", "codex")))
        val member = AiTeamMember.parse(input)!!
        val draft = AiTeamDraft.from(AiTeam("t", "团队", "", listOf(member)))
        val body = draft.copy(description = "未保存的说明").toJson().getJSONArray("members").getJSONObject(0)
        assertEquals("e_real", body.getString("employeeId"))
        assertEquals(member, AiTeamMember.parse(body))
        assertEquals("verify", body.getString("role"))
    }

    @Test
    fun legacyManualAndExplicitUnbindAlwaysWriteJsonNull() {
        for (input in listOf(JSONObject(), JSONObject().put("employeeId", JSONObject.NULL),
            JSONObject().put("employeeId", "  "))) {
            val member = AiTeamMember.parse(input.put("id", "m_old").put("name", "同名员工"))!!
            assertNull(member.employeeId)
            val body = member.toJson()
            assertEquals(true, body.has("employeeId"))
            assertEquals(true, body.isNull("employeeId"))
        }
        val bound = AiTeamMember("m", "员工", "", emptyList(), false, employeeId = "e_real")
        assertEquals(true, bound.copy(employeeId = null).toJson().isNull("employeeId"))
    }

    @Test
    fun teamRunListKeepsChatEntryAndTaskSummary() {
        val runs = AiTeamRun.parseList(
            JSONArray().put(
                JSONObject()
                    .put("id", "run_latest")
                    .put("teamId", "team_1")
                    .put("chatSessionId", "chat_1")
                    .put("taskTitle", "整理登录流程")
                    .put("taskIdentifier", "WAND-42"),
            ).put(JSONObject().put("teamId", "team_1")),
        )
        assertEquals(1, runs.size)
        assertEquals("chat_1", runs.single().chatSessionId)
        assertEquals("整理登录流程", runs.single().taskTitle)
        assertEquals("WAND-42", runs.single().taskIdentifier)
    }

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

    @Test
    fun runDetailCarriesChatTurnsAndToleratesOldServers() {
        val withTurns = AiTeamRunDetail.parse(
            JSONObject()
                .put("run", JSONObject().put("id", "run_1").put("chatSessionId", "s_chat"))
                .put(
                    "chatTurns",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "assistant")
                                .put("notice", true)
                                .put("author", JSONObject().put("name", "负责人").put("leader", true).put("avatar", "cat-3"))
                                .put(
                                    "content",
                                    JSONArray().put(JSONObject().put("type", "text").put("text", "负责人 接管本轮")),
                                ),
                        )
                        // 畸形 turn 不拖垮整段（ConversationTurn.parse 逐块容错）。
                        .put(JSONObject().put("role", "user")),
                ),
        )!!
        assertEquals(2, withTurns.chatTurns.size)
        assertEquals("负责人", withTurns.chatTurns[0].author?.name)
        assertEquals("cat-3", withTurns.chatTurns[0].author?.avatar)
        assertEquals("s_chat", withTurns.run.chatSessionId)

        // 老服务端完全没有 chatTurns 字段：空列表，页面走「还没有消息」而不是崩。
        val legacy = AiTeamRunDetail.parse(JSONObject().put("run", JSONObject().put("id", "run_1")))!!
        assertEquals(emptyList<Any>(), legacy.chatTurns)
    }

    // MARK: - live 文本（§4.9.1，GET /api/ai-team-runs/:id/live）

    @Test
    fun runLiveParseReadsEveryRunningStep() {
        val response = JSONObject()
            .put("runId", "run_1")
            .put(
                "steps",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("stepId", "s1").put("seq", 1).put("memberId", "m_a").put("memberName", "实现者")
                            .put("provider", "claude").put("sessionId", "sess_1").put("state", "working")
                            .put("model", "Qwen3.8-Flash").put("thinkingEffort", "codex:ultra")
                            .put("text", "▸ Read · 读 README").put("omittedChars", 431)
                            .put("updatedAt", "2026-09-27T10:00:00.000Z"),
                    )
                    .put(JSONObject().put("stepId", "s2").put("seq", 2)),
            )
        val live = AiTeamRunLive.parse(response)!!
        assertEquals("run_1", live.runId)
        assertEquals(2, live.steps.size)
        val first = live.steps[0]
        assertEquals("s1", first.stepId)
        assertEquals(1, first.seq)
        assertEquals("实现者", first.memberName)
        assertEquals("sess_1", first.sessionId)
        assertEquals("▸ Read · 读 README", first.text)
        assertEquals(431, first.omittedChars)
        // 署名两字段：服务端带就解析，供 live 卡头部显示「CLI · 模型 · 思考深度」。
        assertEquals("Qwen3.8-Flash", first.model)
        assertEquals("codex:ultra", first.thinkingEffort)
        // 缺字段一律回落：state 缺省按 working 渲染，文本回落空串 / 0，不丢这一行。
        val fallback = live.steps[1]
        assertEquals("working", fallback.state)
        assertEquals("", fallback.text)
        assertEquals(0, fallback.omittedChars)
        // 老服务端不带 model / thinkingEffort：**null**（不是空串），显示处按 null 跳过，署名只剩 provider。
        assertNull(fallback.model)
        assertNull(fallback.thinkingEffort)
        // 显式 null 与缺字段同口径，不让空串进布局。
        val nulled = AiTeamLiveStep.parse(
            JSONObject()
                .put("stepId", "s3").put("seq", 3).put("provider", "claude")
                .put("model", JSONObject.NULL).put("thinkingEffort", JSONObject.NULL),
        )!!
        assertNull(nulled.model)
        assertNull(nulled.thinkingEffort)
    }

    @Test
    fun runLiveParseRejectsUnusablePayloadsInsteadOfThrowing() {
        // 没有 runId 就不猜归属，整份回 null 由调用方保留上一份文本。
        assertNull(AiTeamRunLive.parse(JSONObject().put("steps", JSONArray())))
        assertNull(AiTeamRunLive.parse(JSONObject().put("runId", "  ")))
        // steps 形状不认识（不是数组）→ 空列表，不抛。
        val weird = AiTeamRunLive.parse(JSONObject().put("runId", "run_1").put("steps", "nope"))!!
        assertEquals(emptyList<AiTeamLiveStep>(), weird.steps)
        // 空回包 / 形状整个不对：只回 null，不抛，由轮询保留上一份文本。
        assertNull(AiTeamRunLive.parse(JSONObject()))
        // 单项坏掉只跳过那一项，其余照常。
        val partial = AiTeamRunLive.parse(
            JSONObject()
                .put("runId", "run_1")
                .put("steps", JSONArray().put(JSONObject().put("seq", 3)).put(JSONObject().put("stepId", "s2").put("seq", 2))),
        )!!
        assertEquals(listOf("s2"), partial.steps.map { it.stepId })
    }

    @Test
    fun runDetailParsesMemberStatesAndToleratesMissing() {
        val response = JSONObject()
            .put("run", JSONObject().put("id", "run_1"))
            .put("memberStates", JSONObject().put("sess_1", "needs_permission").put("", "working"))
        val detail = AiTeamRunDetail.parse(response)!!
        assertEquals(mapOf("sess_1" to "needs_permission"), detail.memberStates)
        // 老服务端没这个字段 = 空表，状态芯片退回 live 自带的 state。
        assertEquals(
            emptyMap<String, String>(),
            AiTeamRunDetail.parse(JSONObject().put("run", JSONObject().put("id", "run_1")))!!.memberStates,
        )
        assertEquals(
            emptyMap<String, String>(),
            AiTeamRunDetail.parse(JSONObject().put("run", JSONObject().put("id", "run_1")).put("memberStates", "nope"))!!
                .memberStates,
        )
    }

    // MARK: - 编辑器读写（POST / PUT /api/ai-teams）

    @Test
    fun teamParseReadsWhatTheEditorEdits() {
        val team = AiTeam.parse(
            JSONObject()
                .put("id", "team_1")
                .put("name", "开发三人组")
                .put("description", "拆解与验收")
                .put("instructions", "审查者只读代码。")
                .put("requirePlanApproval", false)
                .put("maxSteps", 42)
                .put(
                    "members",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "m_lead").put("name", "负责人").put("duty", "拆解").put("isLeader", true)
                            .put("avatar", "cat:3").put("role", "plan")
                            .put("agent", JSONObject().put("provider", "claude"))
                            .put(
                                "agents",
                                JSONArray()
                                    .put(JSONObject().put("provider", "claude").put("model", "default"))
                                    .put(JSONObject().put("provider", "qoder").put("model", "Qwen3.8-Flash")),
                            ),
                    ),
                ),
        )!!
        assertEquals("审查者只读代码。", team.instructions)
        assertEquals(false, team.requirePlanApproval)
        assertEquals(42, team.maxSteps)
        val leader = team.members.single()
        assertEquals("cat:3", leader.avatar)
        assertEquals("plan", leader.role)
        assertEquals(listOf("claude", "qoder"), leader.agents.map { it.provider })

        // 老服务端 / 旧数据：字段全缺时回落默认（批准开、30 步），不因缺字段整条失败。
        val legacy = AiTeam.parse(JSONObject().put("id", "team_2").put("name", "旧团队"))!!
        assertEquals("", legacy.instructions)
        assertEquals(true, legacy.requirePlanApproval)
        assertEquals(AI_TEAM_DEFAULT_MAX_STEPS, legacy.maxSteps)
    }

    @Test
    fun teamDraftRoundTripsFieldsAndroidDoesNotEdit() {
        val team = AiTeam(
            id = "team_1",
            name = " 开发三人组 ",
            description = " 拆解与验收 ",
            instructions = " 审查者只读代码。 ",
            requirePlanApproval = false,
            maxSteps = 12,
            members = listOf(
                AiTeamMember(
                    id = "m_lead",
                    name = " 负责人 ",
                    duty = " 拆解 ",
                    agents = listOf(BoardTaskAgent("claude", "default", "off")),
                    isLeader = true,
                    avatar = "cat:3",
                    role = "plan",
                ),
                AiTeamMember(
                    id = "",
                    name = "实现者",
                    duty = "",
                    agents = listOf(BoardTaskAgent("qoder", "Qwen3.8-Flash", "standard", "default", "pty")),
                    isLeader = false,
                ),
            ),
        )

        val body = AiTeamDraft.from(team).toJson()
        // 与 service boundedText 同口径：trim 后提交，首尾空白不吃配额。
        assertEquals("开发三人组", body.getString("name"))
        assertEquals("拆解与验收", body.getString("description"))
        assertEquals("审查者只读代码。", body.getString("instructions"))
        assertEquals(false, body.getBoolean("requirePlanApproval"))
        assertEquals(12, body.getInt("maxSteps"))
        val members = body.getJSONArray("members")
        assertEquals(2, members.length())
        val leader = members.getJSONObject(0)
        assertEquals("m_lead", leader.getString("id"))
        assertEquals("负责人", leader.getString("name"))
        assertEquals(true, leader.getBoolean("isLeader"))
        // Android 不编辑头像 / 职责标注，但 PUT 是整体替换：必须原样回写，否则一次改名就把它们抹掉。
        assertEquals("cat:3", leader.getString("avatar"))
        assertEquals("plan", leader.getString("role"))
        // id 空串留给服务端生成 m_xxxxxxxx；未填 role 的成员不带这个键。
        val member = members.getJSONObject(1)
        assertEquals("", member.getString("id"))
        assertEquals(false, member.has("role"))
        // 兼容字段 agent 由服务端按 agents[0] 强制重写，客户端不重复塞。
        assertEquals(false, leader.has("agent"))
        assertEquals("qoder", member.getJSONArray("agents").getJSONObject(0).getString("provider"))
        assertEquals("pty", member.getJSONArray("agents").getJSONObject(0).getString("kind"))
        // 五元组身份与 service agentKey 同口径（候选重复检测用它）。
        assertEquals(
            "qoder|Qwen3.8-Flash|standard|default|pty",
            boardTaskAgentKey(BoardTaskAgent("qoder", "Qwen3.8-Flash", "standard", "default", "pty")),
        )
    }
}
