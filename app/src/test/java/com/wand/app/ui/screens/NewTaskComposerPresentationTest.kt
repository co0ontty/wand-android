package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.WorkspaceSessionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 侧栏「新建任务」团队分支的纯函数（对齐 Web §5.1 入口矩阵 R2 / §6.2 A4）。 */
class NewTaskComposerPresentationTest {

    private fun member(id: String) = AiTeamMember(
        id = id,
        name = "成员-$id",
        duty = "做 $id",
        agents = listOf(BoardTaskAgent("claude", "default", "off")),
        isLeader = id == "leader",
    )

    private fun team(id: String, name: String, memberCount: Int) = AiTeam(
        id = id,
        name = name,
        description = "",
        members = (1..memberCount).map { member("m$it") },
    )

    @Test
    fun teamOptionLabelMatchesOtherScreens() {
        // 与看板新建、团队页同口径：`团队名（N 人）`。
        assertEquals("三人组（3 人）", newTaskTeamOptionLabel(team("t1", "三人组", 3)))
    }

    @Test
    fun teamPickAllowedOnlyForExistingNonGlobalProject() {
        assertTrue(isTeamPickAllowed("ws_a"))
        assertFalse("目录未命中已有项目时不许选团队", isTeamPickAllowed(null))
        assertFalse(isTeamPickAllowed(""))
        assertFalse("global 暂存区不能跑团队（服务端 400 口径）", isTeamPickAllowed("wand-global"))
    }

    @Test
    fun disabledReasonOnlyWhenTeamsExistAndNotAllowed() {
        assertNull(newTaskTeamDisabledReason(allowed = true, hasTeams = true))
        assertNull("没有团队时不必解释为什么不能选", newTaskTeamDisabledReason(allowed = false, hasTeams = false))
        assertEquals(
            "AI 团队需要先选择一个已有项目：这个目录还不是项目。",
            newTaskTeamDisabledReason(allowed = false, hasTeams = true),
        )
    }

    @Test
    fun teamOptionsEmptyWhenNotAllowed() {
        val teams = listOf(team("t1", "甲", 2), team("t2", "乙", 1))
        assertEquals(
            listOf("t1" to "甲（2 人）", "t2" to "乙（1 人）"),
            newTaskTeamOptions(teams, allowed = true),
        )
        assertEquals("禁用态不落成可点选项", emptyList<Pair<String, String>>(), newTaskTeamOptions(teams, allowed = false))
        // 渲染层遍历的就是这个结果（NewTaskComposerScreen.kt:206），可用性由 isTeamPickAllowed 决定。
        assertEquals(emptyList<Pair<String, String>>(),
            newTaskTeamOptions(teams, isTeamPickAllowed(null)))
        assertEquals(listOf("t1" to "甲（2 人）", "t2" to "乙（1 人）"),
            newTaskTeamOptions(teams, isTeamPickAllowed("ws_a")))
        assertEquals(emptyList<Pair<String, String>>(),
            newTaskTeamOptions(teams, isTeamPickAllowed("wand-global")))
    }

    @Test
    fun teamSubmitRequiresAllowedProjectAndPromptContent() {
        // 团队没有目标（服务端按标题+描述拼 run）就没意义：选中团队必须填内容。
        assertEquals("交给团队需要先填写任务内容。", newTaskTeamSubmitError("   ", teamSelected = true, teamAllowed = true))
        assertNull(newTaskTeamSubmitError("把周报整理成图表", teamSelected = true, teamAllowed = true))
        assertNull("CLI 目标沿用既有校验，不额外要求内容",
            newTaskTeamSubmitError("", teamSelected = false, teamAllowed = true))
        // R2 提交侧兜底：目录改成未登记项目后，即使团队选择还挂着也拒绝走团队分支。
        assertEquals(
            "AI 团队需要先选择一个已有项目，请改回已有项目的目录后再交给团队。",
            newTaskTeamSubmitError("有内容", teamSelected = true, teamAllowed = false),
        )
        // 不允许 + 空内容：先报项目问题（更上游的原因）。
        assertNotNull(newTaskTeamSubmitError("", teamSelected = true, teamAllowed = false))
    }

    @Test
    fun retryStateNeverCreatesAnotherCard() {
        // 阻塞项守护：重试态 needCreate 恒 false。
        assertFalse(newTaskNeedsCardCreation(NewTaskTeamRetry("task_a", "card_a")))
        assertTrue(newTaskNeedsCardCreation(null))
        // 只建出 workspace task、还没找到卡的重试态同样是「不再建卡」。
        assertFalse(newTaskNeedsCardCreation(NewTaskTeamRetry("task_a")))
        // 重试只跟团队分支走：CLI 目标下挂着的重试态不算重试态。
        assertFalse(newTaskTeamRetryActive(teamSelected = false, retry = NewTaskTeamRetry("task_a", "card_a")))
        assertTrue(newTaskTeamRetryActive(teamSelected = true, retry = NewTaskTeamRetry("task_a", "card_a")))
        assertFalse(newTaskTeamRetryActive(teamSelected = true, retry = null))
    }

    @Test
    fun cardMissingErrorStaysInDialogChannel() {
        // 拿不到看板卡：走对话框原位错误位，且要把「已建好 workspace task」这件事说清楚。
        val withDetail = newTaskTeamCardMissingMessage("网络超时")
        assertTrue(withDetail.startsWith("任务已创建，但暂时找不到对应的看板任务"))
        assertTrue(withDetail.contains("网络超时"))
        assertTrue(newTaskTeamCardMissingMessage(null).contains("重试交给团队"))
    }

    @Test
    fun teamActionLabelCoversSubmitRetryAndBusy() {
        assertEquals("创建并交给团队", newTaskTeamActionLabel(submitting = false, retry = false, hasPrompt = true))
        assertEquals("正在交给团队…", newTaskTeamActionLabel(submitting = true, retry = false, hasPrompt = true))
        // 建卡成功但交给团队失败：再点只重发 team-runs，不重复建卡。
        assertEquals("重试交给团队", newTaskTeamActionLabel(submitting = false, retry = true, hasPrompt = true))
        // 与 handler 行为严格一致：没内容时不会重发 team-run，按钮也不许承诺「重试」。
        assertEquals("创建并交给团队", newTaskTeamActionLabel(submitting = false, retry = true, hasPrompt = false))
        assertEquals("正在交给团队…", newTaskTeamActionLabel(submitting = true, retry = true, hasPrompt = false))
    }

    @Test
    fun panelActionsDropSessionToggleForTeam() {
        assertEquals(
            listOf(NewTaskComposerPanelAction.StartSessionToggle, NewTaskComposerPanelAction.WorktreeToggle),
            newTaskComposerPanelActions(teamSelected = false),
        )
        // 团队建卡不读「创建后启动会话」，只留真的作用到 createTask(worktree=…) 的那一项。
        assertEquals(
            listOf(NewTaskComposerPanelAction.WorktreeToggle),
            newTaskComposerPanelActions(teamSelected = true),
        )
    }

    @Test
    fun statusLineDropsSessionSemanticsForTeamAndKeepsWorktreeState() {
        assertEquals(
            "建卡后立即交给团队开工 · 独立工作树",
            newTaskComposerStatusLine(teamSelected = true, startFirstSession = true, worktree = true),
        )
        assertEquals(
            "建卡后立即交给团队开工 · 共用工作区",
            newTaskComposerStatusLine(teamSelected = true, startFirstSession = false, worktree = false),
        )
        val teamLine = newTaskComposerStatusLine(true, true, true)
        assertFalse("团队态说明行不得出现会话语义", teamLine.contains("会话"))
        // CLI 态原文案保持不变。
        assertEquals("创建后启动会话 · 独立工作树",
            newTaskComposerStatusLine(teamSelected = false, startFirstSession = true, worktree = true))
        assertEquals("仅创建任务分组 · 共用工作区",
            newTaskComposerStatusLine(teamSelected = false, startFirstSession = false, worktree = false))
    }

    @Test
    fun feedbackLineOnlyCarriesTeamHint() {
        assertEquals("交给团队需要先填写任务内容。",
            newTaskComposerFeedbackLine("交给团队需要先填写任务内容。"))
        assertEquals("团队态无提示时也要占住同一行", "", newTaskComposerFeedbackLine(null))
        // 预留行只说团队自己的事：`error` 由它自己的整行错误位渲染，这里不再优先一次，
        // 否则同一句话会在团队态出现两遍。签名里没有 error，重复渲染不可能发生。
    }

    @Test
    fun controlChipsAreTheSingleSourceForTeamAndCliStates() {
        assertEquals(
            listOf(
                NewTaskComposerControlChip.SessionKind,
                NewTaskComposerControlChip.Model,
                NewTaskComposerControlChip.ThinkingEffort,
            ),
            newTaskComposerControlChips(teamSelected = false, startFirstSession = true, shellTarget = false),
        )
        // 团队分支不读这三个参数：chip 与对应面板一起收掉（删掉团队分支这条必须变红）。
        assertEquals(emptyList<NewTaskComposerControlChip>(),
            newTaskComposerControlChips(teamSelected = true, startFirstSession = true, shellTarget = false))
        // 仅建分组没有会话可配；空白终端只配会话类型。
        assertEquals(emptyList<NewTaskComposerControlChip>(),
            newTaskComposerControlChips(teamSelected = false, startFirstSession = false, shellTarget = false))
        assertEquals(listOf(NewTaskComposerControlChip.SessionKind),
            newTaskComposerControlChips(teamSelected = false, startFirstSession = true, shellTarget = true))
        // 模型 chip 是控制行里唯一的 weight(1f) 项：它缺席时由 Spacer 补位，二者互斥。
        assertTrue(NewTaskComposerControlChip.Model in newTaskComposerControlChips(false, true, false))
        assertFalse(NewTaskComposerControlChip.Model in newTaskComposerControlChips(true, true, false))
    }

    @Test
    fun sameTargetDoesNotResetCliParams() {
        assertFalse("团队 ⇄ 同一个 CLI 目标往返：手选的模型/思考深度要保留",
            newTaskTargetChangeResetsCliParams(WorkspaceSessionTarget.Codex, WorkspaceSessionTarget.Codex))
        assertTrue("真的换工具才重置",
            newTaskTargetChangeResetsCliParams(WorkspaceSessionTarget.Codex, WorkspaceSessionTarget.Qoder))
        assertTrue(newTaskTargetChangeResetsCliParams(null, WorkspaceSessionTarget.Claude))
    }
}
