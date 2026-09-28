package com.wand.app.ui.screens

import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.BoardTaskMilestone
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.BoardTaskWorkspace
import com.wand.app.data.boardTaskStatusLabel
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardPresentationTest {
    @Test
    fun statsCountRemainingAndHighPriority() {
        val stats = boardTaskStats(
            listOf(
                task(id = "1", status = "todo", priority = "urgent"),
                task(id = "2", status = "doing", priority = "high"),
                task(id = "3", status = "done", priority = "low"),
                task(id = "4", status = "todo"),
            ),
        )
        assertEquals(4, stats.total)
        assertEquals(2, stats.todo)
        assertEquals(1, stats.doing)
        assertEquals(1, stats.done)
        assertEquals(3, stats.remaining)
        assertEquals(2, stats.high)
    }

    @Test
    fun filterMatchesQueryWorkspaceAndStatus() {
        val tasks = listOf(
            task(id = "1", title = "Fix login", workspaceId = "alpha", workspaceName = "Alpha"),
            task(id = "2", title = "Write docs", status = "doing", workspaceId = "beta", workspaceName = "Beta"),
            task(id = "3", identifier = "WAND-9", title = "Login polish", workspaceId = "alpha", workspaceName = "Alpha"),
        )
        assertEquals(listOf("1", "3"), filterBoardTasks(tasks, "login", "", "").map { it.id })
        assertEquals(listOf("2"), filterBoardTasks(tasks, "", "beta", "").map { it.id })
        assertEquals(listOf("3"), filterBoardTasks(tasks, "wand-9", "alpha", "todo").map { it.id })
        assertTrue(filterBoardTasks(tasks, "missing", "", "").isEmpty())
    }

    @Test
    fun sortPreservesServerOrder() {
        val sorted = sortBoardTasks(
            listOf(
                task(id = "done-old", status = "done", sortOrder = 0, updatedAt = "2026-01-01"),
                task(id = "todo-b", status = "todo", sortOrder = 2, updatedAt = "2026-02-01"),
                task(id = "todo-a", status = "todo", sortOrder = 1, updatedAt = "2026-01-01"),
                task(id = "doing", status = "doing", sortOrder = 0, updatedAt = "2026-03-01"),
            ),
        ).map { it.id }
        assertEquals(listOf("done-old", "todo-b", "todo-a", "doing"), sorted)
    }

    @Test
    fun groupedBoardKeepsCanonicalStatusOrder() {
        val grouped = groupedBoardTasks(
            listOf(
                task(id = "d", status = "done"),
                task(id = "t", status = "todo"),
            ),
        )
        assertEquals(listOf("todo", "doing", "done"), grouped.map { it.first })
        assertEquals(listOf("t"), grouped[0].second.map { it.id })
        assertTrue(grouped[1].second.isEmpty())
        assertEquals(listOf("d"), grouped[2].second.map { it.id })
    }

    @Test
    fun completedSectionStartsClosedButShowsSearchMatches() {
        assertFalse(boardDoneSectionOpen(collapsed = true, query = ""))
        assertTrue(boardDoneSectionOpen(collapsed = true, query = "登录"))
        assertTrue(boardDoneSectionOpen(collapsed = false, query = ""))
    }

    @Test
    fun completeToggleReopensDoneTasks() {
        assertEquals("done", boardTaskToggledStatus("todo"))
        assertEquals("done", boardTaskToggledStatus("doing"))
        assertEquals("todo", boardTaskToggledStatus("done"))
        assertEquals("todo", boardTaskToggledStatus("archived"))
    }

    @Test
    fun archivedTasksStayOutOfDoneStatsAndMainGroups() {
        val tasks = listOf(
            task(id = "t", status = "todo"),
            task(id = "d", status = "done"),
            task(id = "a", status = "archived"),
        )
        val stats = boardTaskStats(tasks)
        assertEquals(1, stats.done)
        assertEquals(1, stats.remaining)
        assertEquals(listOf("a"), boardArchivedTasks(tasks).map { it.id })
        assertEquals(listOf("d"), groupedBoardTasks(tasks)[2].second.map { it.id })
        assertEquals(listOf("a"), filterBoardTasks(tasks, "", "", "archived").map { it.id })
    }

    @Test
    fun processingLabelFollowsSessions() {
        assertNull(boardTaskProcessingLabel(task(status = "todo")))
        assertEquals("等待派发", boardTaskProcessingLabel(task(status = "doing")))
        assertEquals(
            "正在处理...",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "running")))),
        )
        assertEquals(
            "等待验收",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "idle")))),
        )
        assertEquals(
            "等待验收",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "exited")))),
        )
        assertEquals(
            "暂停处理",
            boardTaskProcessingLabel(task(status = "doing", sessions = listOf(session(status = "stopped")))),
        )
    }

    @Test
    fun unnamedAndEmptyTasksRemainAvailableInTheBoard() {
        val tasks = listOf(task(title = "未命名任务", description = "项目：wand\n目录：/tmp/wand"),
            task(title = "", description = ""))
        assertEquals(tasks, filterBoardTasks(tasks, "", "", ""))
    }

    @Test
    fun cardModelKeepsOnlyUsefulFields() {
        val placeholderTitle = boardTaskCardTitle(
            task(title = "", description = "项目：wand\n把登录页修好\n目录：/tmp"),
        )
        assertEquals("把登录页修好", placeholderTitle)

        val slim = boardTaskCardModel(
            task(
                title = "修登录",
                workspaceName = "wand",
                workspaceId = "ws-1",
            ),
            showWorkspace = false,
        )
        assertEquals("修登录", slim.title)
        assertNull(slim.workspaceName)
        assertNull(slim.milestoneName)
        assertNull(slim.priority)
        assertNull(slim.agentLabel)
        assertTrue(slim.labels.isEmpty())
        assertNull(slim.processingLabel)
        assertTrue(slim.sessions.isEmpty())
        assertFalse(slim.hasChips)

        val rich = boardTaskCardModel(
            task(
                title = "修登录",
                priority = "high",
                workspaceName = "wand",
                workspaceId = "ws-1",
                milestoneName = "  1.2 看板重构  ",
                labels = listOf("bug", "login", "extra"),
                agent = BoardTaskAgent("claude", "default", "off"),
                status = "doing",
                sessions = listOf(session(), session(id = "s2"), session(id = "s3"), session(id = "s4")),
            ),
            showWorkspace = true,
        )
        assertEquals("wand", rich.workspaceName)
        assertEquals("1.2 看板重构", rich.milestoneName)
        assertEquals("high", rich.priority)
        assertEquals("Claude", rich.agentLabel)
        assertEquals(listOf("bug", "login"), rich.labels)
        assertEquals("正在处理...", rich.processingLabel)
        assertEquals(3, rich.sessions.size)
        assertTrue(rich.hasChips)
    }

    @Test
    fun cardCapsLabelsAndSessionsWithExplicitOverflow() {
        val model = boardTaskCardModel(
            task(
                labels = listOf("bug", "login", "extra"),
                sessions = listOf(session(), session(id = "s2"), session(id = "s3"), session(id = "s4")),
            ),
            showWorkspace = false,
        )
        assertEquals(listOf("bug", "login"), model.labels)
        assertEquals(1, model.extraLabelCount)
        assertEquals(BOARD_TASK_CARD_SESSION_LIMIT, model.sessions.size)
        assertEquals(1, model.extraSessionCount)
    }

    @Test
    fun cardCarriesIdentifierDueStampAndRunningFlag() {
        val model = boardTaskCardModel(
            task(
                identifier = "WAND-42",
                status = "doing",
                dueDate = "2026-03-14",
                sessions = listOf(session(status = "running"), session(id = "s2", status = "idle")),
            ),
            showWorkspace = true,
            today = LocalDate.parse("2026-03-20"),
        )
        assertEquals("WAND-42", model.identifier)
        assertEquals("逾期 · 3/14", model.due?.label)
        assertEquals(true, model.due?.overdue)
        assertTrue(model.running)
        assertEquals(listOf(true, false), model.sessions.map { it.running })
    }

    @Test
    fun closedTasksAndMalformedDatesNeverReadAsOverdue() {
        val today = LocalDate.parse("2026-03-20")
        assertFalse(boardTaskIsOverdue("2026-03-14", "done", today))
        assertFalse(boardTaskIsOverdue("2026-03-14", "archived", today))
        assertTrue(boardTaskIsOverdue("2026-03-14", "doing", today))
        assertFalse(boardTaskIsOverdue("2026-03-20", "todo", today))
        assertNull(boardTaskCardDue("下周三", "todo", today))
        assertNull(boardTaskCardDue(null, "todo", today))
        assertEquals("3/25", boardTaskCardDue("2026-03-25", "todo", today)?.label)
        assertEquals(false, boardTaskCardDue("2026-03-25", "todo", today)?.overdue)
    }

    @Test
    fun cardOnlyFallsBackToDescriptionWhenNothingIsRunning() {
        val bare = task(description = "项目：wand\n把登录页修好\n目录：/tmp")
        assertEquals("把登录页修好", boardTaskCardBody(bare))
        // 自动标题就是描述首行生成的，卡片不能再把这行当摘要重复一遍。
        assertEquals(
            "第二行补充",
            boardTaskCardBody(bare.copy(title = "把登录页修好", titleSource = "auto", description = "把登录页修好\n第二行补充")),
        )
        assertNull(boardTaskCardBody(bare.copy(sessions = listOf(session()))))
        assertNull(boardTaskCardBody(bare.copy(agent = BoardTaskAgent("claude", "default", "off"))))
    }

    @Test
    fun sessionRowLabelFallsBackToProviderName() {
        assertEquals("session-1", boardSessionCardLabel(session()))
        assertEquals("Claude", boardSessionCardLabel(session().copy(title = "")))
        assertEquals("Claude", boardSessionCardLabel(session().copy(title = "claude")))
        assertEquals("修自动填充", boardSessionCardLabel(session().copy(title = "修自动填充")))
        assertEquals("终端", boardSessionCardLabel(session(provider = "shell").copy(title = "")))
    }

    @Test
    fun onlyDoingColumnDispatchesOnCreate() {
        // 「进行中」建卡即派 Agent；「待办」只建任务，不能悄悄起会话。
        assertTrue(boardCreateDispatches("doing"))
        assertFalse(boardCreateDispatches("todo"))
        assertFalse(boardCreateDispatches("done"))
        assertFalse(boardCreateDispatches("archived"))
    }

    @Test
    fun dispatchSessionIdOnlyWhenServerReturnedOne() {
        assertEquals("session-9", boardDispatchSessionId("session-9"))
        assertEquals("session-9", boardDispatchSessionId("  session-9  "))
        assertNull(boardDispatchSessionId(null))
        assertNull(boardDispatchSessionId(""))
        assertNull(boardDispatchSessionId("   "))
    }

    @Test
    fun detailTitleFallsBackToDescriptionLikeTheCard() {
        assertEquals("Fix login", boardTaskDetailTitle(task(title = "Fix login")))
        assertEquals(
            "从描述生成",
            boardTaskDetailTitle(task(title = "  ", description = "从描述生成\n项目：wand")),
        )
    }

    /**
     * §2.20 已知标识映射：服务端写进 labels 的 `team_direct` 只换显示文案，
     * 卡片不再出现裸枚举，也不因为映射少掉一枚芯片。
     */
    @Test
    fun knownInternalLabelIsMappedForDisplay() {
        assertEquals("团队直发", boardTaskLabelDisplay("team_direct"))
        val model = boardTaskCardModel(
            task(id = "t_direct", labels = listOf("team_direct", "优化")),
            showWorkspace = false,
        )
        assertEquals(listOf("团队直发", "优化"), model.labels)
        assertFalse("裸枚举不得再出现在卡片上", model.labels.contains("team_direct"))
    }

    /**
     * §2.20 + 负责人裁定 §0.6-3：映射表之外的标签一律原样，
     * 包括**看着像内部标识**的 `snake_case` —— 那是用户自己的数据，不能按格式规则隐藏。
     */
    @Test
    fun unknownLabelsAlwaysRenderVerbatimEvenWhenTheyLookInternal() {
        for (label in listOf("some_unknown_label", "pty_session", "team_review", "auto_archived")) {
            assertEquals(label, boardTaskLabelDisplay(label))
        }
        // 大小写完全一致才映射：`Team_Direct` 是用户标签，不猜。
        assertEquals("Team_Direct", boardTaskLabelDisplay("Team_Direct"))
        val model = boardTaskCardModel(
            task(id = "t_user", labels = listOf("some_unknown_label", "显示优化", "Team_Direct")),
            showWorkspace = false,
        )
        assertEquals(listOf("some_unknown_label", "显示优化"), model.labels)
        assertEquals(1, model.extraLabelCount)
    }

    /** 映射只是换文案：芯片数量、超出折叠计数与「有元信息行」判定都不受影响。 */
    @Test
    fun labelMappingKeepsChipCountAndRowVisibility() {
        val mapped = boardTaskCardModel(task(id = "t_1", labels = listOf("team_direct", "a", "b")), showWorkspace = false)
        assertEquals(2, mapped.labels.size)
        assertEquals(1, mapped.extraLabelCount)
        assertTrue(mapped.hasChips)
        // 全空标签（含空白项）不渲染标签行，与现状一致。
        val bare = boardTaskCardModel(task(id = "t_2", labels = listOf("", "  ")), showWorkspace = false)
        assertTrue(bare.labels.isEmpty())
        assertFalse(bare.hasChips)
    }

    /**
     * §2.16 分组 ＋ 按钮文案：分组名带引号，「进行中」不再读成「在进行中中新建任务」。
     * 归档分组也走同一模板。
     */
    @Test
    fun groupAddButtonDescriptionQuotesStatusName() {
        assertEquals("在「待办」中新建任务", boardGroupAddTaskDescription("todo"))
        assertEquals("在「进行中」中新建任务", boardGroupAddTaskDescription("doing"))
        assertEquals("在「已完成」中新建任务", boardGroupAddTaskDescription("done"))
        for (status in BOARD_TASK_STATUSES) {
            val description = boardGroupAddTaskDescription(status)
            val label = boardTaskStatusLabel(status)
            assertTrue("每条文案都要点名分组：$description", description.contains("「$label」"))
            assertFalse("不得再出现叠字「中中」：$description", description.contains("中中"))
        }
    }

    private fun task(
        id: String = "task-1",
        title: String = "Fix login",
        description: String = "",
        status: String = "todo",
        priority: String = "none",
        identifier: String = "WAND-1",
        workspaceId: String? = null,
        workspaceName: String? = null,
        milestoneName: String? = null,
        sortOrder: Int = 0,
        updatedAt: String = "2026-01-02",
        createdAt: String = "2026-01-01",
        sessions: List<BoardTaskSession> = emptyList(),
        labels: List<String> = emptyList(),
        dueDate: String? = null,
        agent: BoardTaskAgent? = null,
        titleSource: String = "user",
    ): BoardTask = BoardTask(
        id = id,
        workspaceId = workspaceId,
        identifier = identifier,
        title = title,
        titleSource = titleSource,
        description = description,
        status = status,
        priority = priority,
        labels = labels,
        dueDate = dueDate,
        sortOrder = sortOrder,
        agent = agent,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sessionIds = sessions.map { it.id },
        sessions = sessions,
        workspace = workspaceName?.let { BoardTaskWorkspace(workspaceId ?: "workspace", it, "/tmp") },
        milestone = milestoneName?.let { BoardTaskMilestone("milestone-1", it) },
    )

    @Test
    fun createTeamChoiceOnlyVisibleForDispatchingCreateWithTeams() {
        val teams = listOf(
            com.wand.app.data.AiTeam(
                id = "team_1", name = "开发三人组", description = "",
                members = listOf(
                    com.wand.app.data.AiTeamMember(
                        id = "m_a", name = "甲", duty = "",
                        agents = listOf(BoardTaskAgent.default()), isLeader = true,
                    ),
                    com.wand.app.data.AiTeamMember(
                        id = "m_b", name = "乙", duty = "",
                        agents = listOf(BoardTaskAgent.default("codex")), isLeader = false,
                    ),
                ),
            ),
        )
        // 「待办」列建卡 / 没有团队：都不出现团队组，也不给带 teamId 提交。
        assertFalse(boardCreateTeamChoiceVisible(teams, dispatches = false))
        assertFalse(boardCreateTeamChoiceVisible(emptyList(), dispatches = true))
        assertTrue(boardCreateTeamChoiceVisible(teams, dispatches = true))

        val cliOnly = boardCreateTargetOptions(emptyList(), dispatches = true)
        assertEquals(listOf("" to "CLI 工具"), cliOnly)

        val options = boardCreateTargetOptions(teams, dispatches = true)
        assertEquals("", options.first().first)
        assertEquals("CLI 工具", options.first().second)
        // 取值编码 = teamId；文案与 TaskBoardDetailPane 的「名字（N 人）」一致。
        assertEquals("team_1", options[1].first)
        assertEquals("开发三人组（2 人）", options[1].second)

        // 「待办」列即使有团队也只给 CLI 项（不显示条件同时约束选项）。
        assertEquals(1, boardCreateTargetOptions(teams, dispatches = false).size)
    }

    @Test
    fun createActionLabelCoversTeamAndCliBranches() {
        assertEquals("创建任务", boardCreateActionLabel(false, dispatches = false, hasDescription = false, busy = false))
        assertEquals("创建任务", boardCreateActionLabel(false, dispatches = true, hasDescription = false, busy = false))
        assertEquals("创建并指派", boardCreateActionLabel(false, dispatches = true, hasDescription = true, busy = false))
        assertEquals("创建中…", boardCreateActionLabel(false, dispatches = true, hasDescription = true, busy = true))
        // 选中团队：文案换成「交给团队」，与详情页按钮口径一致。
        assertEquals("创建并交给团队", boardCreateActionLabel(true, dispatches = true, hasDescription = true, busy = false))
        assertEquals("正在交给团队…", boardCreateActionLabel(true, dispatches = true, hasDescription = true, busy = true))
        // 选了团队但没写描述：服务端起不了 run（目标 = 标题+描述），文案退回「创建任务」。
        assertEquals("创建任务", boardCreateActionLabel(true, dispatches = true, hasDescription = false, busy = false))
        // 建卡成功但交给团队失败：再点只重发 team-runs，按钮说清「重试」而不是「创建」。
        assertEquals(
            "重试交给团队",
            boardCreateActionLabel(true, dispatches = true, hasDescription = true, busy = false, teamRunRetry = true),
        )
        // 重试态只跟团队分支走：没选团队（或没描述）时仍是老文案，不骗用户「只重试不建卡」。
        assertEquals(
            "创建并指派",
            boardCreateActionLabel(false, dispatches = true, hasDescription = true, busy = false, teamRunRetry = true),
        )
        // 没描述时 handler 根本不会重发 team-runs，所以按钮也不许承诺「重试」。
        assertEquals(
            "创建任务",
            boardCreateActionLabel(true, dispatches = true, hasDescription = false, busy = false, teamRunRetry = true),
        )
        // 失败后把状态改回「待办」：handler 里有 boardCreateDispatches 守卫，按钮必须同步收窄。
        assertEquals(
            "创建任务",
            boardCreateActionLabel(true, dispatches = false, hasDescription = true, busy = false, teamRunRetry = true),
        )
        assertEquals(
            "正在交给团队…",
            boardCreateActionLabel(true, dispatches = true, hasDescription = true, busy = true, teamRunRetry = true),
        )
    }

    @Test
    fun retryLabelAndSkipCardCreationShareOneCondition() {
        // 「复用旧卡 / 重发 team-runs / 按钮写重试」必须是同一份判定（boardDispatchesToTeam），
        // 否则失败后改回「待办」再点「创建任务」会不建新卡直接跳旧卡 —— 语义与行为矛盾。
        val combos = listOf(
            true to listOf(true to true, true to false, false to true, false to false),
            false to listOf(true to true, true to false, false to true, false to false),
        )
        combos.forEach { (teamSelected, rest) ->
            rest.forEach { (dispatches, hasDescription) ->
                val dispatchesToTeam =
                    boardDispatchesToTeam(teamSelected, dispatches, hasDescription)
                val label = boardCreateActionLabel(
                    teamSelected, dispatches = dispatches, hasDescription = hasDescription,
                    busy = false, teamRunRetry = true,
                )
                assertEquals(
                    "dispatchesToTeam=$dispatchesToTeam 时文案=$label",
                    dispatchesToTeam,
                    label == "重试交给团队",
                )
            }
        }
        // 复现口径：doing+团队+描述失败后改回「待办」→ 不再走团队链路 → 真的建新卡。
        assertFalse(boardDispatchesToTeam(true, dispatches = false, hasDescription = true))
        assertTrue(boardDispatchesToTeam(true, dispatches = true, hasDescription = true))
        assertFalse("没描述起不了 run", boardDispatchesToTeam(true, dispatches = true, hasDescription = false))
        assertFalse("CLI 目标永不复用团队旧卡", boardDispatchesToTeam(false, dispatches = true, hasDescription = true))
    }

    private fun session(
        id: String = "session-1",
        status: String = "running",
        provider: String = "claude",
    ): BoardTaskSession = BoardTaskSession(
        id = id,
        provider = provider,
        sessionKind = "structured",
        title = id,
        status = status,
        cwd = "/tmp",
        model = "default",
        thinkingEffort = "off",
    )
}
