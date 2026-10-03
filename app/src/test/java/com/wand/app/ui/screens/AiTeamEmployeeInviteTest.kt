package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDraft
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SiliconEmployee
import org.junit.Assert.*
import org.junit.Test

class AiTeamEmployeeInviteTest {
    private val agent = BoardTaskAgent.default().copy(kind = "structured")
    private val employee = SiliconEmployee("e_real", "通讯录员工", "基础职责", "不复制人设",
        "cat:3", listOf(agent))
    private val member = AiTeamMember("m_stable", "负责人", "未保存的团队职责", listOf(agent),
        true, "cat:1", "verify")
    private val draft = AiTeamDraft.from(AiTeam("t", "未保存的名字", "草稿说明",
        listOf(member, member.copy(id = "m_2", name = "实现者", isLeader = false))))
        .copy(instructions = "未保存的协作指令", maxSteps = 42, requirePlanApproval = false)

    @Test fun bindingAndReplacementPreserveTeamOwnedDraftFields() {
        val bound = inviteTeamEmployee(draft, employee, 0)
        val expected = member.copy(employeeId = employee.id, name = employee.name,
            avatar = employee.avatar, agents = employee.agents)
        assertEquals(draft.copy(members = listOf(expected, draft.members[1])), bound)
        val replacement = employee.copy(id = "e_other", name = "另一个员工")
        val replaced = inviteTeamEmployee(bound, replacement, 0)
        assertEquals("m_stable", replaced.members[0].id)
        assertEquals("verify", replaced.members[0].role)
        assertEquals(member.duty, replaced.members[0].duty)
        assertTrue(replaced.members[0].isLeader)
    }

    @Test fun invitingNewEmployeeKeepsManualTemplateMembersAndOneLeader() {
        val invited = inviteTeamEmployee(draft, employee)
        assertEquals(draft.members, invited.members.take(2))
        assertEquals(3, invited.members.size)
        assertEquals(1, invited.members.count { it.isLeader })
        assertEquals(employee.id, invited.members.last().employeeId)
        assertEquals("", invited.members.last().duty)
        assertEquals("", invited.members.last().id)
        assertNull(invited.members.last().role)
        assertEquals(draft.name, invited.name)
        assertEquals(draft.maxSteps, invited.maxSteps)
        assertEquals(draft.requirePlanApproval, invited.requirePlanApproval)
    }

    @Test fun duplicateIdentityIsRejectedOnInviteAndReplacement() {
        val bound = inviteTeamEmployee(draft, employee, 0)
        assertSame(bound, inviteTeamEmployee(bound, employee))
        assertSame(bound, inviteTeamEmployee(bound, employee, 1))
        assertNull(teamEmployeeInviteError(employee, bound.members, 0))
        assertTrue(aiTeamEmployeeDraftErrors(bound.copy(members = bound.members +
            bound.members[0].copy(id = "m_duplicate", name = "另一个名字", isLeader = false)))
            .contains("同一员工不能重复加入团队。"))
    }

    @Test fun archivedDeletedInvalidAndFullDirectoryChoicesDoNotChangeDraft() {
        assertSame(draft, inviteTeamEmployee(draft, employee.copy(archivedAt = "2026-10-03")))
        assertSame(draft, inviteTeamEmployee(draft, employee.copy(agents = emptyList())))
        assertSame(draft, inviteTeamEmployee(draft, employee.copy(agents = listOf(agent.copy(kind = "pty")))))
        assertSame(draft, inviteTeamEmployee(draft, employee, 99))
        val full = draft.copy(members = List(8) { member.copy(id = "m_$it", isLeader = it == 0) })
        assertSame(full, inviteTeamEmployee(full, employee))
    }

    @Test fun directoryReadOnlyProjectionNeverOverwritesDraftAndNeverGuessesByName() {
        val bound = inviteTeamEmployee(draft, employee, 0)
        val updated = employee.copy(name = "新名字", avatar = "cat:4",
            duty = "新的基础职责", agents = listOf(agent.copy(provider = "codex")))
        val projected = teamEmployeeProjection(bound.members[0], listOf(updated))
        assertEquals("新名字", projected.name)
        assertEquals("cat:4", projected.avatar)
        assertEquals(updated.agents, projected.agents)
        assertEquals(member.duty, projected.duty)
        assertEquals(member.role, projected.role)
        assertEquals(employee.name, bound.members[0].name)
        val manual = member.copy(name = employee.name)
        assertSame(manual, teamEmployeeProjection(manual, listOf(employee)))
        assertNull(manual.employeeId)
        assertEquals(draft.instructions, bound.instructions)
        assertEquals(draft.description, bound.description)
    }

    @Test fun unavailableBindingIsRetainedUntilExplicitManualConversion() {
        val bound = inviteTeamEmployee(draft, employee, 0).members[0]
        assertSame(bound, teamEmployeeProjection(bound, null))
        assertSame(bound, teamEmployeeProjection(bound, emptyList()))
        assertTrue(teamEmployeeBindingStatus(bound, null)!!.contains("重试"))
        assertTrue(teamEmployeeBindingStatus(bound, emptyList())!!.contains("删除"))
        assertTrue(teamEmployeeBindingStatus(bound, listOf(employee.copy(archivedAt = "date")))!!.contains("归档"))
        assertEquals(employee.id, teamEmployeeProjection(bound, emptyList()).employeeId)
        val manual = unbindTeamEmployee(bound, listOf(employee.copy(name = "当前名字")))
        assertNull(manual.employeeId)
        assertEquals("当前名字", manual.name)
        assertEquals(member.duty, manual.duty)
        assertEquals(member.role, manual.role)
        assertTrue(manual.toJsonForTestIsExplicitNull())
    }

    private fun AiTeamMember.toJsonForTestIsExplicitNull(): Boolean =
        com.wand.app.data.AiTeamDraft("团队", "", "", true, 30, listOf(this))
            .toJson().getJSONArray("members").getJSONObject(0).isNull("employeeId")
}
