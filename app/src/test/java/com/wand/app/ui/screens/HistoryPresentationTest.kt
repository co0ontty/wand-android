package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.SubagentMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class HistoryPresentationTest {
    @Test
    fun turnKeyStaysStableWhenEarlierMessagesArePrepended() {
        val turn = ConversationTurn(role = "user", content = emptyList())

        val beforePrepend = messageItemKey(MessageDisplayItem.Turn(index = 5, turn = turn), loadedOffset = 100)
        val afterPrepend = messageItemKey(MessageDisplayItem.Turn(index = 15, turn = turn), loadedOffset = 90)

        assertEquals(beforePrepend, afterPrepend)
    }

    @Test
    fun historyExplorationKeyAnchorsTheRightEdgeAcrossPrependMerging() {
        val beforePrepend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("new-tool")),
            lastTurnIndex = 5,
        )
        val afterPrepend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("older-tool"), explorationTool("new-tool")),
            lastTurnIndex = 15,
        )

        assertEquals(
            messageItemKey(beforePrepend, loadedOffset = 100, anchorExplorationAtEnd = true),
            messageItemKey(afterPrepend, loadedOffset = 90, anchorExplorationAtEnd = true),
        )
    }

    @Test
    fun currentExplorationKeyAnchorsTheFirstToolWhileStreaming() {
        val beforeAppend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("first-tool")),
            lastTurnIndex = 5,
        )
        val afterAppend = MessageDisplayItem.Exploration(
            tools = listOf(explorationTool("first-tool"), explorationTool("next-tool")),
            lastTurnIndex = 6,
        )

        assertEquals(
            messageItemKey(beforeAppend, loadedOffset = 100),
            messageItemKey(afterAppend, loadedOffset = 100),
        )
    }

    @Test
    fun historicalRepliesStayExpandedWithTheCurrentTurn() {
        assertFalse(shouldCollapseReply(turnIndex = 5, lastUserTurnIndex = -1))
        assertFalse(shouldCollapseReply(turnIndex = 3, lastUserTurnIndex = 4))
        assertFalse(shouldCollapseReply(turnIndex = 5, lastUserTurnIndex = 4))
    }

    @Test
    fun attachmentOnlyUserTurnGetsAReadablePreview() {
        val turn = textTurn(
            "user",
            "[附件已上传，请查看以下文件:\n/tmp/screenshot.png]\n\n",
        )

        assertEquals("1 个附件", conversationTurnPreview(turn))
    }

    @Test
    fun toolOnlyTurnGetsAReadablePreview() {
        val turn = ConversationTurn(
            role = "assistant",
            content = listOf(
                explorationTool("first").use,
                explorationTool("second").use,
            ),
        )

        assertEquals("2 个工具调用", conversationTurnPreview(turn))
    }

    @Test
    fun upToThreeExplorationCallsStayAsIndividualTurns() {
        val turns = List(3) { index -> explorationTurn("tool-$index") }

        val items = groupExplorationTurns(turns)

        assertEquals(3, items.size)
        assertTrue(items.all { it is MessageDisplayItem.Turn })
    }

    @Test
    fun fourExplorationCallsCollapseIntoOneGroup() {
        val turns = List(4) { index -> explorationTurn("tool-$index") }

        val items = groupExplorationTurns(turns)

        assertEquals(1, items.size)
        assertTrue(items.single() is MessageDisplayItem.Exploration)
    }

    @Test
    fun compactPreviewRemovesCommonMarkdownAndWhitespace() {
        val source = """
            # Heading
            - **First**   item
            > `quoted` value
        """.trimIndent()

        assertEquals("Heading First item quoted value", compactPreviewText(source))
        assertEquals("snake_case link", compactPreviewText("`snake_case` [link](https://example.com)"))

        val splitSurrogate = "a".repeat(239) + "😀" + "tail"
        assertFalse(compactPreviewText(splitSurrogate).last().isHighSurrogate())
    }

    @Test
    fun latestSubagentsMoveIntoThePersistentActivityModel() {
        val first = SubagentMeta("task-1", "Explore", "检查界面")
        val second = SubagentMeta("task-2", "Review", "复核交互")
        val messages = listOf(
            textTurn("user", "优化 Agent 展示"),
            ConversationTurn(
                role = "assistant",
                content = listOf(
                    ContentBlock.ToolUse("task-1", "Task", null, JSONObject(), first),
                    ContentBlock.Text("正在检查", first),
                    ContentBlock.ToolResult("task-1", "已完成", false, false, first),
                    ContentBlock.ToolUse("task-2", "Task", null, JSONObject(), second),
                    ContentBlock.Thinking("继续复核", second),
                ),
            ),
        )

        val activities = collectSubagentActivities(messages, sessionRunning = true)

        assertEquals(listOf("task-1", "task-2"), activities.map { it.id })
        assertFalse(activities[0].running)
        assertTrue(activities[1].running)
        assertEquals(3, activities[0].blocks.size)
    }

    @Test
    fun aNewHumanTurnKeepsPreviousAgentsAvailableButPending() {
        val meta = SubagentMeta("task-old", "Explore", null)
        val messages = listOf(
            textTurn("user", "上一轮"),
            ConversationTurn("assistant", listOf(ContentBlock.Text("旧输出", meta))),
            textTurn("user", "下一轮"),
        )

        val activities = collectSubagentActivities(messages, sessionRunning = true)

        assertEquals(1, activities.size)
        assertEquals("task-old", activities.single().id)
        assertFalse(activities.single().running)
        // 分页窗口截断了父级 tool_result：说「未完成」，不误报中断，也不假绿完成。
        assertTrue(activities.single().pending)
        assertEquals(SubagentStatus.Pending, activities.single().status)
    }

    @Test
    fun subagentReturnedUserTextDoesNotMoveTheLatestWindow() {
        val meta = SubagentMeta("task-1", "Explore", "检查界面")
        val messages = listOf(
            textTurn("user", "优化 Agent 展示"),
            ConversationTurn(
                "assistant",
                listOf(ContentBlock.ToolUse("task-1", "Task", null, JSONObject(), meta)),
            ),
            // 子 Agent 的回传也是 user 角色，但它不是真人轮，不能把窗口推到它之后。
            ConversationTurn("user", listOf(ContentBlock.Text("子 Agent 回传", meta))),
        )

        val activities = collectSubagentActivities(messages, sessionRunning = true)

        assertTrue(activities.single().running)
        assertFalse(activities.single().pending)
    }

    @Test
    fun blankUserTextDoesNotCountAsAHumanTurn() {
        val meta = SubagentMeta("task-1", "Explore", "检查界面")
        val messages = listOf(
            textTurn("user", "优化 Agent 展示"),
            ConversationTurn(
                "assistant",
                listOf(ContentBlock.ToolUse("task-1", "Task", null, JSONObject(), meta)),
            ),
            textTurn("user", "   "),
        )

        val activities = collectSubagentActivities(messages, sessionRunning = true)

        assertTrue(activities.single().running)
    }

    /**
     * 真实样本，逐字取自 271 条 `Pi/subagent` toolResult 的聚类（取证见
     * `.wand-team/run_46c0a39145b3/8-m_ca82f853.md`，形状表见 `docs/subagent-display.md` §6）。
     * 上一版 fixture 是自造的「`Async:` + `Output: …/x.jsonl`」组合，真实语料里一条都不存在，
     * 于是判据一直没被发现是死判据；现在 fixture 一律换成真实形状。
     */
    private val piSingleReceipt =
        "Run fan-out: 1/64 used, 63 remaining\n" +
            "Async: wand-team-sol [72dddd42-dc49-4ba9-8dcb-45a0d07e3e01]\n\n" +
            "The async run is detached and running in the background.\n" +
            "You are in an interactive session. Return control to the user now; Pi will wake you " +
            "through the native completion notification when this subagent completes or needs attention.\n" +
            "Mission: 2d310602-4bab-4c9b-a3d8-19d78003a331 (active)"

    private val piWorkflowReceipt =
        "Run fan-out: 0/64 used, 64 remaining\n" +
            "Async workflow [79c600cb-1714-43e9-ad65-756177c8bb34]\n\n" +
            "The async run is detached and running in the background.\n" +
            "Override the default and call blocking subagent_wait() before ending the turn only when " +
            "the current request is run-to-completion\n" +
            "Mission: 4d524beb-3217-4ce1-9a87-f6b35b011e59 (active)"

    // 状态查询：正文里同时含 `Run fan-out:` 与 `Output:`，但它是查询、不是派发回执。
    private val piStatusText =
        "Status target: run 02559929-4b9b-467e-b930-bc532138515c\n" +
            "State: running\nMode: single\nRun fan-out: 1/64 used, 63 remaining\n" +
            "Dir: /var/folders/tf/.../async-subagent-runs/02559929-4b9b-467e-b930-bc532138515c\n" +
            "Output: /var/folders/tf/.../async-subagent-runs/02559929-4b9b-467e-b930-bc532138515c/output-0.log\n" +
            "Events: /var/folders/tf/.../async-subagent-runs/02559929-4b9b-467e-b930-bc532138515c/events.jsonl"

    // 转录查询：含 `Output:` 且真的带回子 Agent 报告正文，绝不能当回执吞掉。
    private val piTranscriptText =
        "Transcript target: run 881a7591-b9ab-4663-ac64-779cf8092b47\n" +
            "State: completed\nOutput: /Users/u/.pi/.../881a7591_reviewer_0_output.md\n" +
            "Result transcript tail:\n  ## Review\n  1. **中等：Grok 历史会话被错误归类**"

    // 完成事件：真实报告，首行固定 Background task completed。
    private val piNotifyText =
        "Background task completed: **wand-team-sol**\n\n" +
            "wand-team-sol:\n# Sol review\n\n**Verdict: FAIL for this slice.**"

    /**
     * 真实样本（271 条里唯一一条「否决层独立起作用」的）：唤醒后台运行不算新派发，
     * 而它正文照抄了兜底层那句 `detached and running in the background`——
     * 没有第一层否决，第三层会把它误判成回执。
     */
    private val piReviveText =
        "Revived async subagent from 1b8d3789-a115-49e6-a2f7-f0c617e96edb.\n" +
            "The async run is detached and running in the background."

    private val qoderAck =
        "Async agent launched successfully.\n" +
            "agentId: ageneral-purpose-ca16580eee0d40fb (internal ID - do not mention to user.)\n" +
            "The agent is working in the background. You will be notified automatically when it completes.\n" +
            "Do not duplicate this agent's work — avoid working with the same files or topics it is using.\n" +
            "output_file: /var/folders/tf/.../tasks/ab9dd6834d59e0307.output\n" +
            "Do NOT read or tail this file via the shell tool — it is the full subagent JSONL transcript."

    private fun receiptActivity(taskId: String, resultText: String, isError: Boolean = false): SubagentActivity {
        val meta = SubagentMeta(taskId, "worker", "跑后台任务")
        val messages = listOf(
            textTurn("user", "派后台任务"),
            ConversationTurn(
                "assistant",
                listOf(
                    ContentBlock.ToolUse(taskId, "Pi/subagent", null, JSONObject(), meta),
                    ContentBlock.ToolResult(taskId, resultText, isError, false, meta),
                ),
            ),
        )
        return collectSubagentActivities(messages, sessionRunning = true).single()
    }

    @Test
    fun piAsyncReceiptBecomesBackgroundNotCompleted() {
        // 真实 pi 回执没有 `Output:` 行：outputPath 为空是正常情况，不得因此判不回执。
        val activity = receiptActivity("task-pi", piSingleReceipt)

        assertEquals(SubagentStatus.Background, activity.status)
        assertEquals("72dddd42-dc49-4ba9-8dcb-45a0d07e3e01", activity.receipt?.runId)
        assertEquals("", activity.receipt?.outputPath)
        assertEquals("后台运行中", subagentStatusLabel(activity.status, sessionRunning = true))
        assertEquals("后台已结束", subagentStatusLabel(activity.status, sessionRunning = false))
    }

    @Test
    fun qoderAckBecomesBackgroundWithItsAgentId() {
        val activity = receiptActivity("task-qoder", qoderAck)

        assertEquals(SubagentStatus.Background, activity.status)
        // qoder 的 id 不是 uuid（`ageneral-purpose-ca16…`），只能从 `agentId:` 行取。
        assertEquals("ageneral-purpose-ca16580eee0d40fb", activity.receipt?.runId)
        assertEquals("/var/folders/tf/.../tasks/ab9dd6834d59e0307.output", activity.receipt?.outputPath)
    }

    @Test
    fun failedResultIsNeverTreatedAsADispatchReceipt() {
        // 失败正文哪怕长得像 ack，也保持「失败原因」，不能改口成「后台运行中」。
        val failed = receiptActivity("task-err", qoderAck, isError = true)

        assertNull(failed.receipt)
        assertTrue(failed.failed)
        assertEquals(SubagentStatus.Failed, failed.status)
    }

    @Test
    fun receiptPredicateRecognisesRealShapesAndRejectsQueries() {
        // 旧版这条断言的是错误行为：真实单发形状（无 `Output:`）期望 null。
        assertEquals(
            AsyncDispatchReceipt("72dddd42-dc49-4ba9-8dcb-45a0d07e3e01", ""),
            parseAsyncDispatchReceipt(piSingleReceipt),
        )
        assertEquals(
            AsyncDispatchReceipt("79c600cb-1714-43e9-ad65-756177c8bb34", ""),
            parseAsyncDispatchReceipt(piWorkflowReceipt),
        )
        assertEquals(
            AsyncDispatchReceipt("ageneral-purpose-ca16580eee0d40fb", "/var/folders/tf/.../tasks/ab9dd6834d59e0307.output"),
            parseAsyncDispatchReceipt(qoderAck),
        )
        // 否决层：这三条都含 `Output:`，说明「含 Output: 就算回执」的方向天然制造误判。
        assertNull(parseAsyncDispatchReceipt(piStatusText))
        assertNull(parseAsyncDispatchReceipt(piTranscriptText))
        assertNull(parseAsyncDispatchReceipt(piNotifyText))
        // 唤醒运行说的是同一句后台措辞：靠第一层否决挡住，第三层不许捞它。
        assertNull(parseAsyncDispatchReceipt(piReviveText))
        assertNull(parseAsyncDispatchReceipt("已完成，输出在 /tmp/a.jsonl"))
        assertNull(parseAsyncDispatchReceipt(""))
        // 只有预算行、后面没有派发形状 → 不硬判。
        assertNull(parseAsyncDispatchReceipt("Run fan-out: 1/64 used, 63 remaining\nMission: x"))
    }

    @Test
    fun dispatchLineInsideAPreflightTableStillYieldsItsRunId() {
        // 真实语料里派发行最远落到第 15 行（中间是 10 lanes 的 Preflight 计划表）。
        // 窗口写死「第二行」或太窄会让它掉进兜底层：状态还是 background，但 runId 丢成空串。
        val lanes = (0..11).joinToString("\n") { "  lane-$it | mutation | 只改自己名下的文件" }
        val receipt = parseAsyncDispatchReceipt(
            "Run fan-out: 0/64 used, 64 remaining\n" +
                "Preflight: v1 · complete · 12 lanes\n" +
                "  key | mode | decision\n" +
                lanes + "\n" +
                "Async workflow [3a2b1c0d-9f8e-7d6c-5b4a-392817061504]\n\n" +
                "The async run is detached and running in the background.",
        )

        assertEquals("3a2b1c0d-9f8e-7d6c-5b4a-392817061504", receipt?.runId)
    }

    @Test
    fun bracketedTypeNamesDoNotStealTheRunId() {
        val receipt = parseAsyncDispatchReceipt(
            "Run fan-out: 1/64 used, 63 remaining\n" +
                "Async: worker[general] [4f9c1a2b-0000-1111-2222-333344445555]",
        )

        // 类型名自带方括号时取最后一个括号段，不能因为先撞上 `[general]` 就抽不到 id。
        assertEquals("4f9c1a2b-0000-1111-2222-333344445555", receipt?.runId)
    }

    @Test
    fun suspectedBackgroundPhrasesFallBackToNeutralStatus() {
        val suspected =
            "Subagent handed off.\nIt is working in the background and you " +
                "will be notified automatically when it completes."
        assertEquals(AsyncDispatchReceipt("", ""), parseAsyncDispatchReceipt(suspected))

        val activity = receiptActivity("task-unknown", suspected)
        // 认不出形状也不标绿「最终结论」；两字段为空，渲染侧回落到正文。
        assertEquals(SubagentStatus.Background, activity.status)
        assertEquals("", activity.receipt?.runId)
        assertEquals("", activity.receipt?.outputPath)
    }

    @Test
    fun titlePrefersTaskDescriptionOverAgentType() {
        val described = activityWith(SubagentMeta("t-1", "general-purpose", "复核贴底跟随"))
        val typeOnly = activityWith(SubagentMeta("t-2", "Explore", null))
        val bare = activityWith(SubagentMeta("t-3", null, null))

        assertEquals("复核贴底跟随", agentBubbleTitle(described))
        assertEquals("猫猫 Explore", agentBubbleTitle(typeOnly))
        assertEquals("猫猫 子 Agent", agentBubbleTitle(bare))
    }

    @Test
    fun multiAgentCardTitleIsCountPlusPrimaryDescription() {
        val running = activityWith(SubagentMeta("t-1", "Explore", "检查界面")).copy(running = true)
        val done = activityWith(SubagentMeta("t-2", "Review", "复核交互"))

        assertEquals("2 个子 Agent", subagentCardTitle(listOf(done, running)))
        assertEquals("检查界面", subagentCardTopic(listOf(done, running)))
        assertEquals("", subagentCardTopic(listOf(done)))
        // 并行批次类型天然相同且是默认类型（general-purpose）时，chip 不携带信息就不出现。
        assertEquals(
            "",
            subagentTypeChip(listOf(activityWith(SubagentMeta("a", "general-purpose", "甲")))),
        )
        // 整卡单一且非默认类型：chip 说一次就够（rail 行不再重复）。
        assertEquals(
            "Explore",
            subagentTypeChip(listOf(activityWith(SubagentMeta("a", "Explore", "甲")))),
        )
        assertEquals(
            "Review",
            subagentTypeChip(listOf(done, activityWith(SubagentMeta("b", "Review", "乙")))),
        )
        // 类型不止一种时 chip 无法代表整卡，交给各自的行。
        assertEquals(
            "",
            subagentTypeChip(listOf(done, activityWith(SubagentMeta("b", "Explore", "乙")))),
        )
    }

    @Test
    fun collapsedRowShowsStatusWordOnlyWhenItNeedsExplaining() {
        assertFalse(subagentStatusNeedsText(SubagentStatus.Completed))
        assertTrue(subagentStatusNeedsText(SubagentStatus.Running))
        assertTrue(subagentStatusNeedsText(SubagentStatus.Pending))
        // 回执头部不再重复状态词（与 Web 同批），摘要行是它唯一的上屏处：
        // 这条必须为真，否则「去重」会变成「去没」。
        assertTrue(subagentStatusNeedsText(SubagentStatus.Background))
        assertEquals("已完成", subagentStatusLabel(SubagentStatus.Completed, sessionRunning = true))
    }

    @Test
    fun lastActionLineIsTheLatestStepNotTheWholeReport() {
        val meta = SubagentMeta("t-1", "Explore", "检查界面")
        val activity = activityWith(meta).copy(
            blocks = listOf(
                ContentBlock.Text("# 标题\n很长的最终报告", meta),
                ContentBlock.ToolUse("u-1", "Bash", null, JSONObject().put("command", "npm test"), meta),
                ContentBlock.ToolResult("u-1", "结果正文", false, false, meta),
            ),
            result = ContentBlock.ToolResult("t-1", "最终结论正文", false, false, meta),
        )

        assertEquals("Bash npm test", subagentLastAction(activity))
    }

    @Test
    fun receiptSublineYieldsToRealProcessSteps() {
        val meta = SubagentMeta("t-r", "worker", "跑后台任务")
        val withSteps = activityWith(meta).copy(
            blocks = listOf(
                ContentBlock.ToolUse("u-9", "Read", null, JSONObject().put("file_path", "a.ts"), meta),
            ),
            result = ContentBlock.ToolResult("t-r", piSingleReceipt, false, false, meta),
            receipt = parseAsyncDispatchReceipt(piSingleReceipt),
        )
        val empty = activityWith(meta).copy(
            result = ContentBlock.ToolResult("t-r", piSingleReceipt, false, false, meta),
            receipt = parseAsyncDispatchReceipt(piSingleReceipt),
        )

        // 与 Web agentRunLastActionText 同序：有过程块就说最近一步，扫不到才说「已交给后台执行」。
        assertEquals("Read a.ts", subagentLastAction(withSteps))
        assertEquals("已交给后台执行", subagentLastAction(empty))
    }

    @Test
    fun parallelAgentsGetDistinctIdentityColorsWithinOneCard() {
        // 5 色盘：前 5 个并行 Agent 必须互不撞色（同 Web agentRunAccent 的顺延算法）。
        val ids = (1..6).map { index -> "task-$index" }
        val variants = dedupeAgentLogoVariants(ids)

        assertEquals(ids.size, variants.size)
        assertEquals(
            "色位要互不相同: ${variants.map { it.paletteIndex }}",
            5,
            variants.take(5).map { it.paletteIndex }.distinct().size,
        )
        assertTrue(variants.all { it.paletteIndex in 0..4 && it.facetIndex in 0..2 })
        assertEquals(variants, dedupeAgentLogoVariants(ids))
    }

    @Test
    fun processStepCountSkipsResultsAndDispatch() {
        val meta = SubagentMeta("task-1", "Explore", "检查界面")
        val blocks = listOf(
            ContentBlock.ToolUse("task-1", "Task", null, JSONObject(), meta),
            ContentBlock.Thinking("   ", meta),
            ContentBlock.Text("", meta),
            ContentBlock.ToolUse("u-1", "Read", null, JSONObject(), meta),
            ContentBlock.ToolResult("u-1", "文件内容", false, false, meta),
            ContentBlock.Text("小结", meta),
            ContentBlock.ToolResult("task-1", "最终结论", false, false, meta),
        )

        // 派遣块、空思考/空正文、结果块都不占「过程 · N 步」的位。
        assertEquals(2, subagentStepCount(blocks))
    }

    @Test
    fun agentLogoIsStableAndAlwaysUsesAValidVariant() {
        val first = agentLogoVariant("task-alpha")
        val same = agentLogoVariant("task-alpha")
        val second = agentLogoVariant("task-beta")

        assertEquals(first, same)
        assertTrue(first.paletteIndex in 0..4)
        assertTrue(first.facetIndex in 0..2)
        assertTrue(second.paletteIndex in 0..4)
        assertTrue(second.facetIndex in 0..2)
    }

    private fun activityWith(meta: SubagentMeta) = SubagentActivity(
        id = meta.taskId ?: "agent",
        meta = meta,
        blocks = emptyList(),
        running = false,
        failed = false,
        interrupted = false,
    )

    private fun textTurn(role: String, text: String) = ConversationTurn(
        role = role,
        content = listOf(ContentBlock.Text(text = text, subagent = null)),
    )

    private fun explorationTurn(id: String) = ConversationTurn(
        role = "assistant",
        content = listOf(explorationTool(id).use),
    )

    private fun explorationTool(id: String) = ExplorationToolItem(
        use = ContentBlock.ToolUse(
            id = id,
            name = "Grep",
            description = null,
            input = JSONObject(),
            subagent = null,
        ),
        result = null,
    )
}
