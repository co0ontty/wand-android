package com.wand.app.ui.screens

import com.wand.app.data.AiTeamLiveStep
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
        assertEquals("等第 1 项完成后", message.assignments[0].wait)
        assertEquals("审查者", message.assignments[1].member)
        assertEquals("", message.assignments[1].wait)
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
        assertEquals(6, (1..7).joinToString("\n") { "第 $it 行" }.let { collapsedPreview(it).lines().size })
    }

    @Test
    fun collapsedPreviewCutsSingleLongLine() {
        val preview = collapsedPreview("长".repeat(500))
        assertEquals(CHAT_COLLAPSE_AFTER_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
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
        val local = listOf(LocalChatTurn("批准", sentAtMillis = 1_000L))
        val turns = listOf(turn("user", "批准", createdAt = "1970-01-01T00:00:02.000Z"))
        assertTrue(settleLocalTurns(local, turns).isEmpty())
    }

    @Test
    fun olderOrAssistantTurnsDoNotConfirmLocalRow() {
        val local = listOf(LocalChatTurn("批准", sentAtMillis = 1_000L))
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
    fun memberAvatarVariantIsStableAndInRange() {
        val first = memberAvatarVariant("m_impl", "实现者", "cat-2")
        assertEquals(first, memberAvatarVariant("m_impl", "实现者", "cat-2"))
        assertTrue(first in 0 until MEMBER_AVATAR_VARIANTS)
        // 不同成员至少不因空 id 全部塌成一处。
        assertTrue(memberAvatarVariant(null, "审查者", null) in 0 until MEMBER_AVATAR_VARIANTS)
    }

    // MARK: - 正在输出的成员（live 卡片，口径逐条对齐 Web）

    private fun live(
        stepId: String,
        seq: Int,
        text: String = "▸ Read · 读 README",
        omittedChars: Int = 0,
        state: String = "working",
        model: String? = null,
        thinkingEffort: String? = null,
    ) = AiTeamLiveStep(
        stepId = stepId,
        seq = seq,
        memberId = "m_impl",
        memberName = "实现者",
        provider = "claude",
        model = model,
        thinkingEffort = thinkingEffort,
        sessionId = "sess_$stepId",
        state = state,
        text = text,
        omittedChars = omittedChars,
        updatedAt = "2026-09-27T10:00:00.000Z",
    )

    @Test
    fun liveStateLabelMatchesWeb() {
        assertEquals("工作中", liveStateLabel("working"))
        assertEquals("等待回答", liveStateLabel("needs_input"))
        assertEquals("等待授权", liveStateLabel("needs_permission"))
        assertEquals("已完成", liveStateLabel("done"))
        assertEquals("失败", liveStateLabel("failed"))
        // 未知状态不给芯片：步骤芯片已经说明它在哪一步。
        assertEquals("", liveStateLabel("queued"))
        assertEquals("", liveStateLabel(null))
    }

    @Test
    fun liveOmittedTextOnlyWhenServerTruncated() {
        assertEquals("", liveOmittedText(0))
        assertEquals("", liveOmittedText(-5))
        assertEquals("已省略前面 431 字", liveOmittedText(431))
        assertEquals("已开始，等待第一段输出…", LIVE_EMPTY_TEXT)
    }

    @Test
    fun shouldFollowTailKeepsUserWhereHeIs() {
        val threshold = liveTailThresholdPx(1f)
        // 与 Web 同参数：scrollHeight 400、视口 200 → 贴底的 scrollTop 是 200。
        assertTrue(shouldFollowTail(200, 400, 200, threshold))
        assertTrue("距底 24dp 以内仍跟随", shouldFollowTail(176, 400, 200, threshold))
        assertFalse("超过阈值就不把他拽回尾部", shouldFollowTail(175, 400, 200, threshold))
        assertFalse(shouldFollowTail(0, 400, 200, threshold))
        // 内容不到一屏时恒为贴尾。
        assertTrue(shouldFollowTail(0, 120, 200, threshold))
        // 同一个几何、density 2.625：阈值随密度放大，判定的物理距离与 Web 那个 24px 是同一个。
        val dense = liveTailThresholdPx(2.625f)
        assertTrue(shouldFollowTail(200, 400, 200, dense))
        assertFalse(shouldFollowTail(200 - dense - 1, 400, 200, dense))
    }

    @Test
    fun tailThresholdIsDpConvertedByDensity() {
        assertEquals("阈值与 Web 的 LIVE_TAIL_PX 同一个数，单位是 dp", 24, LIVE_TAIL_DP)
        assertEquals(24, liveTailThresholdPx(1f))
        assertEquals(48, liveTailThresholdPx(2f))
        // 24 × 2.625 = 63（Pixel 8 那一档密度）；旧实现拿 24 当像素比，实际只有 9dp。
        assertEquals(63, liveTailThresholdPx(2.625f))
    }

    @Test
    fun orderLiveStepsSortsBySeqAndDropsDuplicateStepId() {
        val ordered = orderLiveSteps(listOf(live("s3", 3), live("s1", 1), live("s1dup", 1)))
        assertEquals(listOf("s1", "s1dup", "s3"), ordered.map { it.stepId })
        assertEquals(listOf("s1"), orderLiveSteps(listOf(live("s1", 1), live("s1", 9))).map { it.stepId })
    }

    /**
     * 两端同规则：merge 之后一律按 seq 排，**与这一行是否在退场无关**。
     * 旧实现把 active 行排到全部退场行之前，原本在前的行收工时会被搬到尾部，
     * 列表里就是位置跳一下 + 动画重播一次（同 Web 的 DOM move 重启 CSS 动画）。
     */
    @Test
    fun mergeLiveRowsKeepsRetiringRowInItsOwnSlot() {
        val first = mergeLiveRows(emptyList(), listOf(live("a", 1), live("b", 2)))
        assertEquals(listOf("a", "b"), first.map { it.step.stepId })
        assertTrue(first.none { it.leaving })

        val second = mergeLiveRows(first, listOf(live("b", 2)))
        assertEquals("退场行不被搬到尾部", listOf("a" to true, "b" to false), second.map { it.step.stepId to it.leaving })

        // 同一步又回来时按在场处理，不留退场标记。
        val back = mergeLiveRows(second, listOf(live("a", 1), live("b", 2)))
        assertEquals(listOf("a" to false, "b" to false), back.map { it.step.stepId to it.leaving })
    }

    /**
     * 旧实现会在下一次轮询时把退场行直接摘掉，卡片因此在动画播完前腰斩。
     * 现在摘除只由行自身的 onRetire（退场时长到点）负责，merge 一律留着它，且顺序不交换。
     */
    @Test
    fun mergeLiveRowsKeepsLeavingRowUntilRetireNotNextPush() {
        val second = mergeLiveRows(
            mergeLiveRows(emptyList(), listOf(live("a", 1), live("b", 2))),
            listOf(live("b", 2)),
        )
        val third = mergeLiveRows(second, listOf(live("b", 2), live("c", 3)))
        assertEquals(listOf("a" to true, "b" to false, "c" to false), third.map { it.step.stepId to it.leaving })

        val fourth = mergeLiveRows(third, listOf(live("c", 3)))
        assertEquals(listOf("a" to true, "b" to true, "c" to false), fourth.map { it.step.stepId to it.leaving })

        // 同一批内再来一次推送：顺序一个都不交换（两端同断言）。
        val again = mergeLiveRows(fourth, listOf(live("c", 3)))
        assertEquals(fourth.map { it.step.stepId }, again.map { it.step.stepId })
        assertEquals(fourth.map { it.leaving }, again.map { it.leaving })
    }

    @Test
    fun liveStepChipShowsSeqPlusTitleFromRunSteps() {
        val step = live("s7", 7)
        val runSteps = listOf(
            AiTeamStep(id = "s7", seq = 7, kind = "work", memberId = "m_impl", title = "类型与存储迁移", status = "running"),
        )
        assertEquals("#7 类型与存储迁移", liveStepChip(step, runSteps))
        // 步骤还没进 detail（或 id 对不上）时只给序号，不编标题。
        assertEquals("#7", liveStepChip(step, emptyList()))
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

    @Test
    fun liveStepSignatureSkipsNullSegmentsFromOldServer() {
        // 两个字段都缺（老服务端 /live）→ 只显示 provider。
        val bare = live("s1", 1)
        assertEquals("Claude", agentSignatureLabel(bare.provider, bare.model, bare.thinkingEffort))
        // 只缺一个 → 少一段，不出现空的「 · 」。
        val onlyEffort = live("s2", 2, thinkingEffort = "deep")
        assertEquals("Claude · 深入", agentSignatureLabel(onlyEffort.provider, onlyEffort.model, onlyEffort.thinkingEffort))
        val onlyModel = live("s3", 3, model = "Qwen3.8-Flash")
        assertEquals("Claude · Qwen3.8-Flash", agentSignatureLabel(onlyModel.provider, onlyModel.model, onlyModel.thinkingEffort))
        // 分隔符只出现在段与段之间：首尾不带「 · 」。
        for (label in listOf(
            agentSignatureLabel(bare.provider, bare.model, bare.thinkingEffort),
            agentSignatureLabel(onlyEffort.provider, onlyEffort.model, onlyEffort.thinkingEffort),
            agentSignatureLabel(onlyModel.provider, onlyModel.model, onlyModel.thinkingEffort),
        )) {
            assertFalse(label.startsWith(" · "))
            assertFalse(label.endsWith(" · "))
            assertFalse(label.contains(" ·  · "))
        }
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
