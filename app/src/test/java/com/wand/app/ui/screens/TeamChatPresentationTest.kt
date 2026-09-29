package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamMember
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ContentBlock
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TurnAuthor
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTeamChat
import com.wand.app.data.WandApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 群聊纯函数测试（契约 docs 无，口径真源是 Web `team-chat-view.tsx`）。
 * 端上一致性靠这些断言守住：同一条服务端回合，两端必须解析成同一角色 / 同一段正文。
 */
class TeamChatPresentationTest {

    @Test
    fun officeShowsActualWorkAndAttentionAndCanOpenMemberSession() {
        val members = listOf(
            AiTeamMember("lead", "负责人", "派工", emptyList(), true),
            AiTeamMember("dev", "开发者", "实现", emptyList(), false),
            AiTeamMember("qa", "审查者", "检查", emptyList(), false),
        )
        val team = AiTeam("team", "开发组", "", members)
        val run = AiTeamRun("r2", "team", team, "task", "目标", "running", "", 2, 20, "chat")
        val detail = AiTeamRunDetail(run, listOf(
            AiTeamStep("s1", 1, "leader", "lead", "拟定计划", "done", "lead-session"),
            AiTeamStep("s2", 2, "work", "dev", "实现接口", "running", "dev-session"),
            AiTeamStep("s3", 3, "work", "qa", "审查接口", "queued"),
        ), memberStates = mapOf("dev-session" to "needs_permission"))
        val office = teamOfficeMembers(detail)
        assertEquals(listOf(TeamOfficeState.Done, TeamOfficeState.Attention, TeamOfficeState.Queued),
            office.map { it.state })
        assertEquals("待授权", office[1].label)
        assertEquals("实现接口", office[1].task)
        assertEquals("dev-session", office[1].sessionId)
        assertEquals("r3", newestRunOnSameChat(run, listOf(run.copy(id = "r3"), run)))
        assertNull(newestRunOnSameChat(run, listOf(run, run.copy(id = "r1"))))
    }

    @Test
    fun chatSendOnlyRestoresDraftForDefiniteRejection() {
        assertTrue(chatSendDefinitelyRejected(WandApiException(400, "拒收")))
        for (status in listOf(null, 408, 409, 500)) {
            assertFalse(chatSendDefinitelyRejected(WandApiException(status, "未知")))
        }
    }

    private fun turn(
        role: String,
        text: String,
        notice: Boolean = false,
        author: TurnAuthor? = null,
        createdAt: String? = null,
    ) = ConversationTurn(
        role = role,
        content = listOf(ContentBlock.Text(text, null)),
        notice = notice,
        author = author,
        createdAt = createdAt,
    )

    private fun author(
        name: String,
        leader: Boolean = false,
        sessionId: String? = null,
    ) = TurnAuthor(id = name, name = name, avatar = null, leader = leader, provider = null, sessionId = sessionId)

    private fun session(teamChat: WorkspaceSessionTeamChat?) = WorkspaceSessionSummary(
        id = "s1",
        provider = "claude",
        sessionKind = "structured",
        runner = "claude-sdk",
        title = "群聊",
        status = "running",
        cwd = "/repo",
        startedAt = null,
        teamChat = teamChat,
    )

    // MARK: - 角色分层

    @Test
    fun turnKindPrefersNoticeThenUserThenLeader() {
        assertEquals(TeamChatTurnKind.Notice, chatTurnKind(turn("assistant", "系统提示", notice = true)))
        assertEquals(TeamChatTurnKind.User, chatTurnKind(turn("user", "批准")))
        assertEquals(
            TeamChatTurnKind.Leader,
            chatTurnKind(turn("assistant", "我来派工", author = author("负责人", leader = true))),
        )
        assertEquals(TeamChatTurnKind.Step, chatTurnKind(turn("assistant", "✅ 完成「T1」正文", author = author("实现者"))))
    }

    @Test
    fun noticeLineKeepsEventBodyAndDoesNotRepeatAnExistingAuthor() {
        assertEquals(
            "负责人 创建了团队群聊「开发四人组」",
            teamNoticeLine(turn("assistant", "创建了团队群聊「开发四人组」", notice = true,
                author = author("负责人"))),
        )
        assertEquals(
            "实现者 开始「T1」",
            teamNoticeLine(turn("assistant", "实现者\n开始「T1」", notice = true,
                author = author("实现者"))),
        )
        assertEquals("", teamNoticeLine(turn("assistant", "  ", notice = true,
            author = author("负责人"))))
    }

    @Test
    fun turnTextJoinsOnlyNonBlankTextBlocks() {
        val merged = ConversationTurn(
            role = "assistant",
            content = listOf(
                ContentBlock.Text(" 第一段 ", null),
                ContentBlock.Text("", null),
                ContentBlock.ToolResult("toolu_1", "工具输出不该出现在正文里", false, truncated = false, subagent = null),
            ),
        )
        assertEquals("第一段", chatTurnText(merged))
    }

    // MARK: - 成员报告前缀（对齐 Web parseStepReport）

    @Test
    fun parsesSuccessAndFailureReportPrefixes() {
        val ok = parseStepReport("✅ 完成「T1 类型与存储迁移」\n按步骤迁移了 3 处。")
        assertEquals(true, ok?.ok)
        assertEquals("T1 类型与存储迁移", ok?.title)
        assertEquals("按步骤迁移了 3 处。", ok?.body)

        val failed = parseStepReport("❌ 没完成「T2 回归」 测试仍红。")
        assertEquals(false, failed?.ok)
        assertEquals("T2 回归", failed?.title)
        assertEquals("测试仍红。", failed?.body)
    }

    @Test
    fun nonReportTextHasNoPrefixMatch() {
        assertNull(parseStepReport("还在跑，先说一句进展。"))
        // 前缀必须在开头，正文里出现「✅ 完成」不算。
        assertNull(parseStepReport("进展：✅ 完成「T1」"))
    }

    @Test
    fun stepStatusFallsBackToPrefixWhenStepMissing() {
        val steps = listOf(AiTeamStep(id = "1", seq = 1, kind = "work", memberId = "m", title = "T1 类型与存储迁移", status = "running"))
        val hit = parseStepReport("✅ 完成「T1 类型与存储迁移」正文")
        // 命中真实步骤时以步骤状态为准（这里正在跑，不能被前缀说成 done）。
        assertEquals("running", teamStepStatus(steps, hit))
        val miss = parseStepReport("✅ 完成「T9 没这条步骤」正文")
        assertEquals("done", teamStepStatus(steps, miss))
        assertEquals("failed", teamStepStatus(steps, parseStepReport("❌ 没完成「T9」正文")))
        assertNull(teamStepStatus(steps, null))
    }

    // MARK: - 负责人派工拆分（对齐 Web splitLeaderMessage）

    @Test
    fun splitsLeaderHeadAndAssignmentItems() {
        val message = splitLeaderMessage(
            """
            这一轮三个人分工如下：
            1. **@实现者** T1 类型与存储迁移（等第 1 项完成后）
            2. **@审查者** T2 只读审计
            """.trimIndent(),
        )
        assertEquals("这一轮三个人分工如下：", message.head)
        assertEquals(2, message.assignments.size)
        assertEquals("实现者", message.assignments[0].member)
        assertEquals("T1 类型与存储迁移", message.assignments[0].title)
        assertEquals("等第 1 项完成后", message.assignments[0].note)
        assertEquals("审查者", message.assignments[1].member)
        assertEquals("", message.assignments[1].note)
        val basis = splitLeaderMessage("1. **@设计师** 规格（依据：第 2 步「草图」的产物）")
        assertEquals("设计师", basis.assignments.single().member)
        assertEquals("依据：第 2 步「草图」的产物", basis.assignments.single().note)
    }

    @Test
    fun leaderWithoutAssignmentsKeepsWholeTextAsHead() {
        val message = splitLeaderMessage("先等实现者一轮结果。")
        assertEquals("先等实现者一轮结果。", message.head)
        assertTrue(message.assignments.isEmpty())
    }

    // MARK: - 输入引导语

    @Test
    fun inputHintFollowsRunStatus() {
        assertEquals("回复『批准』即开工，其他内容会作为修改意见转给负责人", chatInputHint("awaiting_approval"))
        assertEquals("回复负责人", chatInputHint("waiting_user"))
        assertEquals("将作为插话，负责人下一轮看到", chatInputHint("running"))
        assertEquals("发消息会接着这一轮的进度开新一轮", chatInputHint("done"))
        assertEquals("发消息会接着这一轮的进度开新一轮", chatInputHint("stopped"))
        assertEquals("发消息会接着这一轮的进度开新一轮", chatInputHint("failed"))
        assertEquals("", chatInputHint("unknown-status"))
    }

    @Test
    fun composerModeMatchesWebSendStopSlots() {
        assertEquals(TeamChatComposerMode.Stop, teamChatComposerMode("running", false))
        assertEquals(TeamChatComposerMode.Stop, teamChatComposerMode("awaiting_approval", false))
        assertEquals(TeamChatComposerMode.Stop, teamChatComposerMode("waiting_user", false))
        assertEquals(TeamChatComposerMode.SendAndStop, teamChatComposerMode("running", true))
        assertEquals(TeamChatComposerMode.Send, teamChatComposerMode("done", true))
        assertEquals(TeamChatComposerMode.Blocked, teamChatComposerMode("stopped", false))
        assertEquals(TeamChatComposerMode.Blocked, teamChatComposerMode("failed", false))
        assertEquals(TeamChatComposerMode.Blocked, teamChatComposerMode("done", false))
        assertEquals(TeamChatComposerMode.SendAndStop, teamChatComposerMode("running", false, sending = true))
        assertEquals(TeamChatComposerMode.Send, teamChatComposerMode("done", false, sending = true))
    }

    // MARK: - 收起 / 展开

    @Test
    fun collapseThresholdsMatchWeb() {
        assertFalse(needsCollapse("短报告\n两行"))
        assertTrue(needsCollapse("一行".repeat(421)))
        assertTrue(needsCollapse((1..7).joinToString("\n") { "第 $it 行" }))
        val byLines = collapsedPreview((1..7).joinToString("\n") { "第 $it 行" })
        assertEquals(
            "只显示上半部分（前 6 行）",
            6,
            byLines.lines().size,
        )
        assertTrue(
            "被截断一定以省略号结尾（与 Web 同字符）",
            byLines.endsWith("…"),
        )
        // 没超阈值时预览就是全文，不高出「点击展开」（展开按钮不当噪音）。
        assertEquals("短报告", collapsedPreview("短报告"))
        assertFalse(needsCollapse(collapsedPreview("短报告")))
    }

    @Test
    fun collapsedPreviewCutsSingleLongLine() {
        val preview = collapsedPreview("长".repeat(500))
        assertEquals(CHAT_COLLAPSE_AFTER_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    // MARK: - 消息形态分流（对齐 Web teamChatMessageShape）

    @Test
    fun messageShapeSplitsBubbleAndDocumentLikeWeb() {
        assertEquals(
            TeamChatMessageShape.Notice,
            teamChatMessageShape(TeamChatTurnKind.Notice, "团队已停止"),
        )
        assertEquals(
            "自己的长消息仍是气泡（预览 + 点击展开）",
            TeamChatMessageShape.Bubble,
            teamChatMessageShape(TeamChatTurnKind.User, "啊".repeat(500)),
        )
        assertEquals(TeamChatMessageShape.Bubble, teamChatMessageShape(TeamChatTurnKind.Step, "T1 改完了"))
        assertEquals(
            "超行数阈值走文档卡",
            TeamChatMessageShape.Document,
            teamChatMessageShape(TeamChatTurnKind.Step, (1..9).joinToString("\n") { "第 $it 行" }),
        )
        assertEquals(
            "超字符阈值走文档卡",
            TeamChatMessageShape.Document,
            teamChatMessageShape(TeamChatTurnKind.Step, "啊".repeat(421)),
        )
        assertEquals(
            "含派工清单就是文档性质",
            TeamChatMessageShape.Document,
            teamChatMessageShape(TeamChatTurnKind.Leader, "先做后端", 2),
        )
        assertEquals(
            "负责人发短消息也还是气泡",
            TeamChatMessageShape.Bubble,
            teamChatMessageShape(TeamChatTurnKind.Leader, "要不要改目录？", 0),
        )
    }

    // MARK: - 头像（对齐 Web chatAvatarSpec / memberCoatIndex，设计 §5）

    @Test
    fun memberCoatIndexMatchesWebHashSamples() {
        // 固定样本（设计 §5.2）：用来抓跨端口径漂移。m_impl 正是旧 memberAvatarVariant 会算成 2 的那个反例。
        assertEquals(6, memberCoatIndex("m_impl", "实现者", ""))
        assertEquals(1, memberCoatIndex("m_reviewer", "审查者", ""))
        assertEquals(5, memberCoatIndex("m_designer", "设计者", ""))
        assertEquals(5, memberCoatIndex("m_leader", "负责人", ""))
        assertEquals(3, memberCoatIndex(null, "实现者", null))
        assertEquals(0, memberCoatIndex(null, "runner", null))
        // 显式毛色：cat:<n> 取模 8，与 Web Number(n) % 8 同值。
        assertEquals(5, memberCoatIndex(null, "任意", "cat:5"))
        assertEquals(3, memberCoatIndex(null, "任意", "cat:11"))
        // 非法 avatar 按身份派生，不崩；定位不到身份的才回落。
        assertEquals(
            memberCoatIndex("m_impl", "实现者", null),
            memberCoatIndex("m_impl", "实现者", "说不清的取值"),
        )
        assertTrue(memberCoatIndex(null, "", null) in 0 until CAT_COATS.size)
    }

    @Test
    fun chatAvatarSpecFollowsWebRuleOrder() {
        assertEquals(
            "没选毛色的成员用派生毛色（与团队页同一张脸）",
            ChatAvatarSpec.Cat(6),
            chatAvatarSpec(TurnAuthor(id = "m_impl", name = "实现者")),
        )
        assertEquals(
            ChatAvatarSpec.Cat(3),
            chatAvatarSpec(TurnAuthor(id = "m_impl", name = "实现者", avatar = "cat:3")),
        )
        assertEquals(
            ChatAvatarSpec.Upload("data:image/png;base64,AAA"),
            chatAvatarSpec(TurnAuthor(id = "m", name = "成员", avatar = "data:image/png;base64,AAA")),
        )
        assertEquals(
            "没有署名的发言（我）用默认 APP logo",
            ChatAvatarSpec.Brand,
            chatAvatarSpec(null),
        )
        assertEquals(
            "非法 avatar 且定位不到身份也不渲染空图",
            ChatAvatarSpec.Brand,
            chatAvatarSpec(TurnAuthor(id = "", name = "", avatar = "说不清的取值")),
        )
        assertEquals(
            "非法 avatar 但能定位身份 → 派生毛色",
            ChatAvatarSpec.Cat(memberCoatIndex("", "实现者", "说不清的取值")),
            chatAvatarSpec(TurnAuthor(id = "", name = "实现者", avatar = "说不清的取值")),
        )
    }

    @Test
    fun pixelCatGridIsPixelForPixelTheSameAsWeb() {
        // 逐格照抄 Web catCoatGrid（T 透明 / b 底 / d 深 / l 亮 / w 白 / k 瞳 / p 鼻）。
        assertEquals(
            listOf(
                "TdTTTTTTdT", "dbdTTTTdbd", "dbbbbbbbbd", "bbwkbbwkbb", "bbwwbbwwbb",
                "bbbbppbbbb", "bdblbblbdb", "TbbbbbbbbT", "TTbdbbdbTT", "TTTbTTbTTT",
            ),
            CAT_COAT_GRID_ROWS,
        )
        assertEquals(8, CAT_COATS.size)
        val rows = catCoatGrid(1)
        assertEquals(10, rows.size)
        assertTrue(rows.all { it.size == 10 })
        assertNull(
            "行 0 列 0 是透明（耳朵之间的缝）",
            rows[0][0],
        )
        assertEquals(CAT_COATS[1].dark, rows[0][1])
        assertEquals(
            "银渐层的瞳色就是毛色表里那颗绿",
            0xFF3F8F55.toInt(),
            rows[3][3],
        )
        // 越界毛色落回 0..7，不崩。
        assertEquals(catCoatGrid(0), catCoatGrid(8))
        assertEquals(catCoatGrid(7), catCoatGrid(-1))
    }

    // MARK: - 全文弹层标签与完整身份（对齐 Web，同一投影供列表与 owner）

    @Test
    fun docLayerLabelsAndFullFingerprintMatchWeb() {
        assertEquals(
            "我",
            CHAT_SELF_NAME,
        )
        assertEquals(
            "点击展开",
            CHAT_EXPAND_LABEL,
        )
        assertEquals(
            "（这条消息没有正文）",
            CHAT_EMPTY_BODY,
        )
        assertEquals("我的消息", teamChatDocTypeLabel(TeamChatTurnKind.User))
        assertEquals("负责人派工", teamChatDocTypeLabel(TeamChatTurnKind.Leader))
        assertEquals("成员发言", teamChatDocTypeLabel(TeamChatTurnKind.Step))
        assertEquals(
            "成员报告 · T1 类型与存储迁移",
            teamChatDocTypeLabel(TeamChatTurnKind.Step, "T1 类型与存储迁移"),
        )
        val first = turn("assistant", "报告正文", createdAt = "2026-09-27T10:00:00.000Z")
        assertEquals(teamTurnFingerprint(first), teamTurnFingerprint(first.copy(usage = null)))
        assertNotEquals(teamTurnFingerprint(first), teamTurnFingerprint(first.copy(
            createdAt = "2026-09-27T10:00:01.000Z")))
        val projection = projectTeamTurns(null, "run/chat", listOf(first, first))
        assertEquals(2, projection.rows.size)
        assertNotEquals(projection.rows[0].presentationId, projection.rows[1].presentationId)
    }

    // MARK: - 乐观行收敛（对齐 Web settleLocalTurns）

    @Test
    fun refetchFailureKeepsLocalRowAndMarksUnconfirmed() {
        val local = listOf(LocalChatTurn("批准", sentAtMillis = 1_000L))
        val settled = settleLocalTurns(local, null)
        assertEquals(1, settled.size)
        assertTrue(settled[0].unconfirmed)
    }

    @Test
    fun confirmedUserTurnDropsLocalRow() {
        val turns = listOf(turn("user", "批准", createdAt = "1970-01-01T00:00:02.000Z"))
        val local = listOf(LocalChatTurn("批准", sentAtMillis = 1_000L,
            accepted = true, ackFingerprint = teamTurnFingerprint(turns.single())))
        assertTrue(settleLocalTurns(local, turns).isEmpty())
    }

    @Test
    fun olderOrAssistantTurnsDoNotConfirmLocalRow() {
        val local = listOf(LocalChatTurn("批准", sentAtMillis = 1_000L,
            accepted = true, ackFingerprint = teamTurnFingerprint(
                turn("user", "批准", createdAt = "1970-01-01T00:00:02.000Z"))))
        // 服务端回显时刻早于本地发送时刻 = 还没刷到这一条。
        val stale = listOf(turn("user", "批准", createdAt = "1970-01-01T00:00:00.500Z"))
        assertEquals(local, settleLocalTurns(local, stale))
        // 助手回合不能确认用户行。
        val assistant = listOf(turn("assistant", "收到", createdAt = "1970-01-01T00:00:05.000Z"))
        assertEquals(local, settleLocalTurns(local, assistant))
    }

    @Test
    fun turnEpochPrefersCompletedAtThenMissingTimestampIsUnusable() {
        val both = ConversationTurn(
            role = "user",
            content = emptyList(),
            createdAt = "1970-01-01T00:00:01.000Z",
            completedAt = "1970-01-01T00:00:03.000Z",
        )
        assertEquals(3_000L, turnEpochMillis(both))
        assertNull(turnEpochMillis(turn("user", "无时间戳")))
    }

    // MARK: - 群聊标记

    @Test
    fun groupChatRunIdReadsMarkAndToleratesMissingFields() {
        assertEquals(
            "run_1",
            groupChatRunId(session(WorkspaceSessionTeamChat("run_1", "开发三人组", 3))),
        )
        // 老服务端没有 teamChat、或 teamChat 里 runId 为空：按普通会话打开，不猜。
        assertNull(groupChatRunId(session(null)))
        assertNull(groupChatRunId(session(WorkspaceSessionTeamChat("", "开发三人组", 3))))
    }

    @Test
    fun memberIdentityKeepsTheSameFaceAcrossMessages() {
        // 同一成员在任意一条消息上都是同一张脸（设计 §5.2 第 3 条）：id 相同 → 毛色相同。
        assertEquals(memberCoatIndex("m_impl", "实现者", null), memberCoatIndex("m_impl", "实现者", ""))
        // 不同成员不塌成同一张脸。
        assertNotEquals(memberCoatIndex("m_impl", "实现者", null), memberCoatIndex("m_reviewer", "审查者", null))
    }

    // MARK: - 正在输出的成员（live 卡片，口径逐条对齐 Web）

    @Test
    fun tailThresholdIsDpConvertedByDensity() {
        assertEquals("阈值与 Web 的 LIVE_TAIL_PX 同一个数，单位是 dp", 24, LIVE_TAIL_DP)
        assertEquals(24, liveTailThresholdPx(1f))
        assertEquals(48, liveTailThresholdPx(2f))
        // 24 × 2.625 = 63（Pixel 8 那一档密度）；旧实现拿 24 当像素比，实际只有 9dp。
        assertEquals(63, liveTailThresholdPx(2.625f))
    }

    // MARK: - 署名「CLI · 模型 · 思考深度」（口径逐字对齐 Web agentSignatureLabel）

    @Test
    fun signatureLabelsMatchWebTables() {
        assertEquals("Claude · Qwen3.8-Flash · 深入", agentSignatureLabel("claude", "Qwen3.8-Flash", "deep"))
        // `default` 是「跟随服务端默认」的哨兵值，整段省略，思考深度照旧。
        assertEquals("Qoder · 最大", agentSignatureLabel("qoder", "default", "max"))
        assertEquals("标准", agentSignatureLabel("", "", "standard"))
        // 旧四档查不到时剥掉 `provider:` 前缀按 CLI 原生档位读（同 Web compactThinkingLabel）。
        assertEquals("Codex · 低", agentSignatureLabel("codex", "", "codex:low"))
        assertEquals("Codex · 极限代码", agentSignatureLabel("codex", null, "ultracode"))
        // 表里没有的档位原样显示，不谎报成「关闭」。
        assertEquals("Codex · tomorrow", agentSignatureLabel("codex", null, "codex:tomorrow"))
        assertEquals("终端 · 自动", agentSignatureLabel("session", null, "auto"))
    }

    @Test
    fun signatureResolvesDefaultSentinelToConcreteModel() {
        val catalog = ModelsResponse.parse(
            JSONObject(
                """
                {
                  "models": [{"id":"default","label":"跟随 Claude Code 默认"},{"id":"opus","label":"opus（最新 Opus）"}],
                  "codexModels": [{"id":"default","label":"GPT-6-Astra · gpt-6-astra（Codex 默认）"}],
                  "defaultModels": {"claude": "opus"}
                }
                """.trimIndent(),
            ),
        )
        assertEquals("Claude · opus · 关闭", agentSignatureLabel("claude", "default", "off", catalog))
        assertEquals("Codex · GPT-6-Astra · gpt-6-astra", agentSignatureLabel("codex", "default", null, catalog))
        // 目录还没到时只省略模型段，不冒出「默认」这种占位。
        assertEquals("Claude · 关闭", agentSignatureLabel("claude", "default", "off"))
        assertEquals("显式选的模型原样显示", "Claude · sonnet", agentSignatureLabel("claude", "sonnet", null, catalog))
    }

    @Test
    fun signatureSkipsMissingSegmentsWithoutStraySeparator() {
        // 老服务端只给 provider：不能出现「 · 」或 null。
        assertEquals("Claude", agentSignatureLabel("claude", null, null))
        assertEquals("Claude", agentSignatureLabel("claude", "  ", ""))
        assertEquals("", agentSignatureLabel(null, null, null))
        assertEquals("", agentSignatureLabel("", "", ""))
        // 只有模型 / 只有思考深度也各自成段。
        assertEquals("gpt-5 · 高", agentSignatureLabel(null, "gpt-5", "high"))
        assertEquals("Pi · 关闭", agentSignatureLabel("pi", "", "off"))
    }

    // MARK: - 外层列表贴底口径（同 Web isFollowingTail）

    @Test
    fun listFollowsTailUsesTheGivenThresholdPx() {
        val threshold = liveTailThresholdPx(1f)
        assertTrue("首屏还没测量到条目：停在最新一条上", listFollowsTail(null, threshold))
        assertTrue(listFollowsTail(0, threshold))
        assertTrue(listFollowsTail(LIVE_TAIL_DP, threshold))
        assertFalse("上滚超过阈值就不再拉底", listFollowsTail(LIVE_TAIL_DP + 1, threshold))
        assertFalse(listFollowsTail(320, threshold))
        // 最后一条已经滚过视口底（负数）仍算贴底。
        assertTrue(listFollowsTail(-40, threshold))
    }

    @Test
    fun listTailDistanceMeasuresLastItemBottomMinusContentPadding() {
        // 视口底在 1200，最后一条（第 8 条）底在 1163，扣掉 20px 的 contentPadding.bottom → 距底 17。
        assertEquals(
            17,
            listTailDistancePx(
                lastVisibleIndex = 8, totalItemsCount = 9, lastVisibleOffset = 1000,
                lastVisibleHeight = 163, viewportEndOffset = 1200, bottomPaddingPx = 20,
            ),
        )
        // 最后一条没进视口：视为远离尾部，不会因为「offset 恰好接近」被误判成贴底。
        assertTrue(
            listTailDistancePx(7, 9, 1190, 180, 1200, bottomPaddingPx = 0) > liveTailThresholdPx(1f),
        )
        assertEquals(0, listTailDistancePx(-1, 0, 0, 0, 1200, bottomPaddingPx = 37))
    }

    /**
     * 调用点口径（Pixel 8：density 2.625，列表 contentPadding.bottom 14dp ≈ 37px）。
     * 旧实现不扣 padding、阈值又按 24 设备像素算：**手动拖到底那一次被判成不跟随**
     * （距底 37 > 24），于是「滚到底反而不跟新增的 live 行」。
     */
    @Test
    fun draggingToBottomCountsAsFollowingTailOnADenseScreen() {
        val density = 2.625f
        val viewportEnd = 1_869      // Compose 的 viewportEndOffset（1900 视口高 - 12dp 顶部 padding）
        val bottomPadding = (14f * density).roundToInt()       // 37
        val lastItemTop = viewportEnd - bottomPadding - 400    // 条目底正好贴在 padding 上沿
        assertTrue(
            "拖到底 → 判定为跟随",
            listPinnedFromLayout(
                lastVisibleIndex = 12, totalItemsCount = 13, lastVisibleOffset = lastItemTop,
                lastVisibleHeight = 400, viewportEndOffset = viewportEnd,
                contentPaddingBottomDp = 14f, density = density,
            ),
        )
        // 同一个几何在旧口径下会判成不跟随：距底 37px > 24px，这就是清单 2 的破口。
        assertFalse(
            listFollowsTail(listTailDistancePx(12, 13, lastItemTop, 400, viewportEnd, 0), LIVE_TAIL_DP),
        )
        // 上滚一屏 → 判定不跟随。
        assertFalse(
            "上滚后 → 判定不跟随",
            listPinnedFromLayout(
                lastVisibleIndex = 6, totalItemsCount = 13, lastVisibleOffset = 10,
                lastVisibleHeight = 240, viewportEndOffset = viewportEnd,
                contentPaddingBottomDp = 14f, density = density,
            ),
        )
        // 只上滚阈值以内（24dp ≈ 63px）仍算贴尾。
        assertTrue(
            listPinnedFromLayout(
                lastVisibleIndex = 12, totalItemsCount = 13,
                lastVisibleOffset = lastItemTop - (liveTailThresholdPx(density) - 1),
                lastVisibleHeight = 400, viewportEndOffset = viewportEnd,
                contentPaddingBottomDp = 14f, density = density,
            ),
        )
        // 阈值以外 1px：不跟随（两端同一判据）。
        assertFalse(
            listPinnedFromLayout(
                lastVisibleIndex = 12, totalItemsCount = 13,
                lastVisibleOffset = lastItemTop - (liveTailThresholdPx(density) + 1),
                lastVisibleHeight = 400, viewportEndOffset = viewportEnd,
                contentPaddingBottomDp = 14f, density = density,
            ),
        )
    }

    /**
     * 反例锁：把 `viewportEndOffset - viewportStartOffset` 自算成「视口高度」再算距底，
     * 会把一整个顶部 padding 混进距离里。Compose 里条目 offset 与 `viewportEndOffset` 同原点
     * （content 顶边），`viewportStartOffset` 就是 `-paddingTop`（12dp 在 density 2.625 上是 32px），
     * 于是距底恒偏大 32px —— 24dp（63px）的跟随窗口实际只剩 12dp（31px）。
     */
    @Test
    fun topPaddingMustNotLeakIntoTheTailDistance() {
        val density = 2.625f
        val thresholdPx = liveTailThresholdPx(density)   // 24dp = 63px
        val topPadding = (12f * density).roundToInt()   // 32
        val bottomPadding = (14f * density).roundToInt() // 37
        val measuredHeight = 1_900                       // LazyColumn 自身量到的高度
        val viewportEnd = measuredHeight - topPadding    // Compose 的 viewportEndOffset = 1868
        val viewportStart = -topPadding                  // Compose 的 viewportStartOffset
        val itemHeight = 400

        // 真贴底（末条底边 = viewportEndOffset - 底部 padding）：距底 0，判定跟随。
        val pinnedOffset = viewportEnd - bottomPadding - itemHeight
        assertEquals(
            0,
            listTailDistancePx(12, 13, pinnedOffset, itemHeight, viewportEnd, bottomPadding),
        )
        assertTrue(
            "真贴底 → 跟随",
            listPinnedFromLayout(
                lastVisibleIndex = 12, totalItemsCount = 13, lastVisibleOffset = pinnedOffset,
                lastVisibleHeight = itemHeight, viewportEndOffset = viewportEnd,
                contentPaddingBottomDp = 14f, density = density,
            ),
        )

        // 上滚 40px（窗口内）：新口径距底 40 ≤ 63 仍跟随。
        val scrolledOffset = pinnedOffset - 40
        assertEquals(
            40,
            listTailDistancePx(12, 13, scrolledOffset, itemHeight, viewportEnd, bottomPadding),
        )
        assertTrue(
            "上滚阈值以内 → 仍跟随",
            listPinnedFromLayout(
                lastVisibleIndex = 12, totalItemsCount = 13, lastVisibleOffset = scrolledOffset,
                lastVisibleHeight = itemHeight, viewportEndOffset = viewportEnd,
                contentPaddingBottomDp = 14f, density = density,
            ),
        )

        // 同一几何在「自算高度」的旧口径下距底 72 > 63：窗口被顶部 padding 吃掉一半，判定翻成不跟随。
        val legacyDistance = (viewportEnd - viewportStart) - (scrolledOffset + itemHeight) - bottomPadding
        assertEquals(thresholdPx + 9, legacyDistance)
        assertTrue(legacyDistance > thresholdPx)
        assertFalse(listFollowsTail(legacyDistance, thresholdPx))
    }
}
