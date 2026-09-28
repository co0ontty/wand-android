package com.wand.app.ui.screens

import com.wand.app.data.AI_TEAM_DEFAULT_MAX_STEPS
import com.wand.app.data.AI_TEAM_MAX_CANDIDATES
import com.wand.app.data.AI_TEAM_MAX_MEMBERS
import com.wand.app.data.AI_TEAM_MIN_STEPS
import com.wand.app.data.AI_TEAM_NAME_MAX
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDraft
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 团队编辑器纯函数（新建模板 / 成员增删 / 候选增删移 / 草稿校验）。 */
class AiTeamEditorPresentationTest {

    private fun agent(
        provider: String = "claude",
        model: String = "default",
        effort: String = "off",
        mode: String = "default",
        kind: String = "structured",
    ) = BoardTaskAgent(provider, model, effort, mode, kind)

    private fun member(
        name: String,
        leader: Boolean = false,
        duty: String = "",
        agents: List<BoardTaskAgent> = listOf(agent()),
        id: String = "",
    ) = AiTeamMember(id = id, name = name, duty = duty, agents = agents, isLeader = leader)

    private fun draft(
        name: String = "开发三人组",
        members: List<AiTeamMember> = listOf(member("负责人", leader = true), member("实现者")),
        description: String = "",
        instructions: String = "",
        maxSteps: Int = AI_TEAM_DEFAULT_MAX_STEPS,
        requirePlanApproval: Boolean = true,
    ) = AiTeamDraft(
        name = name,
        description = description,
        instructions = instructions,
        requirePlanApproval = requirePlanApproval,
        maxSteps = maxSteps,
        members = members,
    )

    @Test
    fun templatesMirrorWebAndStartFromEditableDraft() {
        // 四个模板与 Web teams-page.tsx 一一对应（id 相同），少一个就会让两端入口不一致。
        assertEquals(listOf("dev", "bugfix", "research", "blank"), AI_TEAM_TEMPLATES.map { it.id })

        val dev = aiTeamTemplateDraft(aiTeamTemplateById("dev")!!, agent("codex"))
        assertEquals("开发三人组", dev.name)
        assertEquals("审查者只读代码，问题写进报告，由负责人决定是否返工。", dev.instructions)
        assertTrue(dev.requirePlanApproval)
        assertEquals(AI_TEAM_DEFAULT_MAX_STEPS, dev.maxSteps)
        assertEquals(listOf("负责人", "实现者", "审查者"), dev.members.map { it.name })
        assertEquals(listOf(true, false, false), dev.members.map { it.isLeader })
        // 新成员的 id 交给服务端生成，执行配置取当时的看板默认。
        assertTrue(dev.members.all { it.id.isEmpty() })
        assertEquals(listOf("codex", "codex", "codex"), dev.members.map { it.agents.single().provider })
        assertTrue(aiTeamDraftCanSave(dev))
        assertEquals(emptyList<String>(), aiTeamDraftErrors(dev))

        // 空白模板：团队名不预填，否则用户得先删掉模板名。
        val blank = aiTeamTemplateDraft(aiTeamTemplateById(AI_TEAM_BLANK_TEMPLATE_ID)!!)
        assertEquals("", blank.name)
        assertEquals("", blank.instructions)
        assertEquals(2, blank.members.size)
        // 没有默认配置时回落 Claude 结构化。
        assertEquals("claude", blank.members.first().agents.single().provider)
    }

    @Test
    fun editableDraftKeepsUneditedMemberFields() {
        val team = AiTeam(
            id = "team_1",
            name = "开发三人组",
            description = "拆解与验收",
            instructions = "审查者只读代码。",
            requirePlanApproval = false,
            maxSteps = 42,
            members = listOf(
                member("负责人", leader = true, id = "m_lead", agents = listOf(agent("claude"), agent("qoder"))),
                AiTeamMember(
                    id = "m_work",
                    name = "实现者",
                    duty = "改代码",
                    agents = listOf(agent("codex")),
                    isLeader = false,
                    avatar = "cat:3",
                    role = "work",
                ),
            ),
        )
        val editable = aiTeamEditableDraft(team)
        assertEquals(42, editable.maxSteps)
        assertFalse(editable.requirePlanApproval)
        assertEquals(listOf("m_lead", "m_work"), editable.members.map { it.id })
        // 头像 / 职责标注 Android 不编辑，但必须原样带回去，PUT 才不会把它们抹掉。
        assertEquals("cat:3", editable.members[1].avatar)
        assertEquals("work", editable.members[1].role)
        assertEquals(2, editable.members[0].agents.size)
    }

    @Test
    fun memberAddRemoveKeepsExactlyOneLeaderAndTheFloor() {
        val two = listOf(member("负责人", leader = true), member("实现者"))
        // 到上限后不再加（界面按钮同步禁用）。
        val eight = (1..AI_TEAM_MAX_MEMBERS).fold(two) { acc, _ -> addTeamMember(acc) }
        assertEquals(AI_TEAM_MAX_MEMBERS, eight.size)
        assertEquals(AI_TEAM_MAX_MEMBERS, addTeamMember(eight).size)

        val three = addTeamMember(two, agent("qoder"))
        assertEquals(3, three.size)
        assertEquals("", three.last().name)
        assertEquals(listOf(true, false, false), three.map { it.isLeader })

        // 删到下限就停手；删掉负责人时由第一位顶上，保证「恰好一位」。
        assertEquals(2, removeTeamMember(two, 1).size)
        assertEquals(two, removeTeamMember(two, 7))
        val promoted = removeTeamMember(three.mapIndexed { i, m -> if (i == 0) m.copy(isLeader = true) else m }, 0)
        assertEquals(listOf(true, false), promoted.map { it.isLeader })
        assertEquals("实现者", promoted.first().name)
    }

    @Test
    fun leaderSwitchIsExclusive() {
        val members = listOf(member("甲", leader = true), member("乙"), member("丙"))
        val switched = setTeamLeader(members, 2)
        assertEquals(listOf(false, false, true), switched.map { it.isLeader })
        // 越界不改任何东西，也不抛。
        assertEquals(switched, setTeamLeader(switched, 9))
        assertEquals(members, replaceTeamMember(members, 9, member("丁")))
    }

    @Test
    fun candidatesAddDuplicateMoveRemove() {
        val first = agent("claude")
        val single = listOf(first)
        assertEquals(single, removeTeamCandidate(single, 0))

        val two = addTeamCandidate(single)
        assertEquals(listOf("claude", "claude"), two.map { it.provider })
        // 复制出来的候选与首选重复，校验必须拦住（服务端 400 的前置检查）。
        assertEquals(listOf(1), duplicateTeamCandidates(two))
        assertEquals("候选「备用 1」与更靠前的配置相同，改一项即可。", teamCandidateListError(two))

        val fixed = setTeamCandidate(two, 1, agent("qoder"))
        assertEquals(emptyList<Int>(), duplicateTeamCandidates(fixed))
        assertEquals("", teamCandidateListError(fixed))

        // 降级顺序 = 列表顺序，上移 / 下移越界原样返回。
        assertEquals(listOf("claude", "qoder"), fixed.map { it.provider })
        assertEquals(listOf("qoder", "claude"), moveTeamCandidate(fixed, 1, -1).map { it.provider })
        assertEquals(listOf("qoder", "claude"), moveTeamCandidate(fixed, 0, 1).map { it.provider })
        assertEquals(fixed, moveTeamCandidate(fixed, 0, -1))
        assertEquals(fixed, moveTeamCandidate(fixed, 1, 1))

        // 三个候选时删除中间那个；上限 4 时再加不动。
        val three = addTeamCandidate(fixed)
        assertEquals(listOf("claude", "qoder", "qoder"), three.map { it.provider })
        assertEquals(listOf("claude", "qoder"), removeTeamCandidate(three, 2).map { it.provider })
        assertEquals(listOf("qoder", "qoder"), removeTeamCandidate(three, 0).map { it.provider })
        val four = addTeamCandidate(setTeamCandidate(three, 2, agent("grok")))
        assertEquals(AI_TEAM_MAX_CANDIDATES, four.size)
        assertEquals(AI_TEAM_MAX_CANDIDATES, addTeamCandidate(four).size)
        assertEquals("执行候选最多 $AI_TEAM_MAX_CANDIDATES 个。", teamCandidateListError(four + agent("pi")))
        assertEquals("至少保留 1 个执行候选。", teamCandidateListError(emptyList()))
        assertEquals("首选", aiTeamCandidateLabel(0))
        assertEquals("备用 2", aiTeamCandidateLabel(2))
        assertFalse(aiTeamDraftCanSave(draft(members = listOf(member("负责人", leader = true), member("乙", agents = emptyList())))))
    }

    @Test
    fun maxStepsClampsToServerRange() {
        assertEquals(AI_TEAM_DEFAULT_MAX_STEPS, clampTeamMaxSteps(null))
        assertEquals(AI_TEAM_MIN_STEPS, clampTeamMaxSteps(1))
        assertEquals(200, clampTeamMaxSteps(999))
        assertEquals(30, clampTeamMaxSteps(30))
        // 步进一次 5 步，撞上下限停住（按钮同步禁用）。
        assertEquals(35, stepTeamMaxSteps(30, 5))
        assertEquals(AI_TEAM_MIN_STEPS, stepTeamMaxSteps(AI_TEAM_MIN_STEPS, -5))
        assertEquals(200, stepTeamMaxSteps(200, 5))
        assertFalse(canDecreaseTeamMaxSteps(AI_TEAM_MIN_STEPS))
        assertTrue(canDecreaseTeamMaxSteps(10))
        assertFalse(canIncreaseTeamMaxSteps(200))
        assertTrue(canIncreaseTeamMaxSteps(150))
    }

    @Test
    fun draftErrorsMirrorServerValidation() {
        assertEquals(emptyList<String>(), aiTeamDraftErrors(draft()))

        assertEquals(listOf("请填写团队名。"), aiTeamDraftErrors(draft(name = "   ")))
        assertEquals(
            listOf("团队名不能超过 $AI_TEAM_NAME_MAX 个字符。"),
            aiTeamDraftErrors(draft(name = "名".repeat(AI_TEAM_NAME_MAX + 1))),
        )
        // 名字 trim 后判长度：首尾空白不吃配额。
        assertEquals(emptyList<String>(), aiTeamDraftErrors(draft(name = " " + "名".repeat(AI_TEAM_NAME_MAX) + " ")))

        val tooFew = aiTeamDraftErrors(draft(members = listOf(member("负责人", leader = true))))
        assertTrue(tooFew.contains("至少要有 2 位成员。"))
        assertTrue(tooFew.contains("负责人要恰好 1 位，当前 1 位。") == false)
        assertTrue(aiTeamDraftErrors(draft(members = listOf(member("甲"), member("乙")))).contains("负责人要恰好 1 位，当前 0 位。"))
        assertTrue(
            aiTeamDraftErrors(draft(members = listOf(member("甲", leader = true), member("乙", leader = true))))
                .contains("负责人要恰好 1 位，当前 2 位。"),
        )

        // 名字重复忽略大小写和首尾空格；没填名字报「第 N 位」。
        val duplicated = aiTeamDraftErrors(
            draft(members = listOf(member(" Lead ", leader = true), member("lead"))),
        )
        assertTrue(duplicated.contains("成员名字「lead」重复。"))
        val unnamed = aiTeamDraftErrors(draft(members = listOf(member("负责人", leader = true), member("  "))))
        assertTrue(unnamed.contains("成员 2的名字不能为空。"))

        // 步数上限越界（正常路径被 clamp 挡住，这里防的是别处直接改草稿）。
        assertTrue(
            aiTeamDraftErrors(draft(maxSteps = 1)).first { it.startsWith("步数上限") }
                .startsWith("步数上限需要是 $AI_TEAM_MIN_STEPS–200"),
        )
        assertTrue(
            aiTeamDraftErrors(draft(instructions = "字".repeat(4001)))
                .contains("协作指令不能超过 4000 个字符。"),
        )
        assertTrue(
            aiTeamDraftErrors(draft(description = "字".repeat(501))).contains("说明不能超过 500 个字符。"),
        )
    }

    @Test
    fun editorLabelsSwitchBetweenNewAndEdit() {
        assertEquals("新建团队", teamEditorTitle(isNew = true))
        assertEquals("编辑团队", teamEditorTitle(isNew = false))
        assertEquals("创建团队", teamEditorSubmitLabel(isNew = true, saving = false))
        assertEquals("保存", teamEditorSubmitLabel(isNew = false, saving = false))
        assertEquals("保存中…", teamEditorSubmitLabel(isNew = true, saving = true))
        assertEquals("成员 3", teamMemberDisplayName(member(" "), 2))
        assertEquals("负责人", teamMemberDisplayName(member("负责人"), 0))
    }
}
