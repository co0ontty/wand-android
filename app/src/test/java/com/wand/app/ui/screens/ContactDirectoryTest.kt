package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SiliconEmployee
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDirectoryTest {
    private fun employee(
        id: String,
        name: String = id,
        archivedAt: String? = null,
        agents: List<BoardTaskAgent> = listOf(BoardTaskAgent.default("codex")),
        systemKey: String? = null,
        createdAt: String = "",
    ) = SiliconEmployee(id, name, "", "", "", agents, systemKey, archivedAt, emptyList(), createdAt)

    private fun team(
        id: String = "team-1",
        name: String = "开发组",
        createdAt: String = "",
    ) = AiTeam(id, name, "", emptyList(), createdAt = createdAt)

    @Test
    fun directoryKeepsEveryActiveEmployeeIncludingBuiltinAndDropsArchived() {
        val visible = contactDirectoryEmployees(listOf(
            employee("e_wand_ops", "勤劳的初二", systemKey = "wand-ops"),
            employee("e_wand_default", "赛博虎妞", systemKey = "wand-default"),
            employee("e_user", "架构师"),
            employee("e_old", "旧人", archivedAt = "2026-09-01"),
        ))
        assertEquals(listOf("e_wand_ops", "e_wand_default", "e_user"), visible.map { it.id })
    }

    @Test
    fun directoryListsTeamNamesWithoutFiltering() {
        val teams = listOf(team("t1", "一组"), team("t2", "二组"))
        assertEquals(teams, contactDirectoryTeams(teams))
    }

    @Test
    fun directorySearchMatchesNamesDutiesAndTagsWithoutRevivingArchivedEmployees() {
        val architect = employee("architect", "架构师").copy(duty = "Android Équipe", tags = listOf("研发"))
        val archived = architect.copy(id = "archived", archivedAt = "2026-10-02")
        val builtin = employee("ops", "勤劳的初二", systemKey = "wand-ops")
        val employees = listOf(architect, archived, builtin)
        assertEquals(listOf(architect), contactDirectoryEmployees(employees, "  ANDROID  研发  "))
        assertEquals(listOf(architect), contactDirectoryEmployees(employees, "équipe"))
        assertEquals(listOf(builtin), contactDirectoryEmployees(employees, "系统用户"))
        assertTrue(contactDirectoryEmployees(employees, "不存在").isEmpty())
        assertEquals(listOf(architect, builtin), contactDirectoryEmployees(employees, "  "))
    }

    @Test
    fun directoryTeamSearchFindsDescriptionAndMemberWithoutChangingOrder() {
        val first = team("one", "客户端组").copy(description = "Android 和 iOS")
        val second = team("two", "审查组").copy(members = listOf(
            com.wand.app.data.AiTeamMember("member", "安全专家", "检查", emptyList(), true),
        ))
        val teams = listOf(first, second)
        assertEquals(listOf(first), contactDirectoryTeams(teams, "android"))
        assertEquals(listOf(second), contactDirectoryTeams(teams, "审查 安全"))
        assertEquals(teams, contactDirectoryTeams(teams, "组"))
        assertTrue(contactDirectoryTeams(teams, "Android 安全").isEmpty())
    }

    @Test
    fun onlyCurrentAvailableEmployeeCanReceiveNewConversation() {
        val current = employee("e-1", "虎妞")
        assertEquals(current, contactAssignableEmployee("e-1", listOf(current)))
        assertNull(contactAssignableEmployee("e-1", emptyList()))
        assertNull(contactAssignableEmployee("e-1", listOf(employee("other"))))
        assertNull(contactAssignableEmployee("e-1", listOf(current.copy(archivedAt = "2026-10-01"))))
        assertNull(contactAssignableEmployee("e-1", listOf(current.copy(agents = emptyList()))))
    }

    @Test
    fun employeesAndTeamsAreOrderedByCreationTimeWithBlanksLast() {
        val employees = listOf(
            employee("late", "后建", createdAt = "2026-10-02T10:00:00Z"),
            employee("early", "先建", createdAt = "2026-09-01T08:00:00Z"),
            employee("legacy", "旧数据无时间"),
        )
        assertEquals(listOf("early", "late", "legacy"), contactOrderedEmployees(employees).map { it.id })
        val teams = listOf(
            team("t2", "后建", createdAt = "2026-10-01T00:00:00Z"),
            team("t1", "先建", createdAt = "2026-09-15T00:00:00Z"),
            team("t3", "旧数据无时间"),
        )
        assertEquals(listOf("t1", "t2", "t3"), contactOrderedTeams(teams).map { it.id })
        assertTrue(contactOrderedEmployees(emptyList()).isEmpty())
    }

    @Test
    fun directoryLayoutKeepsTeamsAboveEmployeesAndSkipsEmptyHintsOnError() {
        val layout = contactDirectoryLayout(
            employeeIds = listOf("e1"), teamIds = listOf("t1"),
            queryBlank = true, hasError = false, loadingEmpty = false,
        )
        assertEquals(
            listOf(
                ContactSlot.CreatePanel, ContactSlot.TeamHeader, ContactSlot.Team("t1"),
                ContactSlot.GroupGap, ContactSlot.EmployeeHeader, ContactSlot.Employee("e1"),
            ),
            layout,
        )
        // 搜索只命中员工：不画团队标题，也不留空提示。
        val employeesOnly = contactDirectoryLayout(
            employeeIds = listOf("e1"), teamIds = emptyList(),
            queryBlank = false, hasError = false, loadingEmpty = false,
        )
        assertEquals(
            listOf(ContactSlot.CreatePanel, ContactSlot.ResultCount, ContactSlot.Employee("e1")),
            employeesOnly,
        )
        // 搜索无命中：只留一条空态。
        val none = contactDirectoryLayout(
            employeeIds = emptyList(), teamIds = emptyList(),
            queryBlank = false, hasError = false, loadingEmpty = false,
        )
        assertEquals(listOf(ContactSlot.CreatePanel, ContactSlot.NoResults), none)
        // 首屏加载失败：不冒充空目录。
        val errored = contactDirectoryLayout(
            employeeIds = emptyList(), teamIds = emptyList(),
            queryBlank = true, hasError = true, loadingEmpty = false,
        )
        assertTrue(errored.any { it is ContactSlot.Error })
        assertFalse(errored.any { it is ContactSlot.TeamEmpty || it is ContactSlot.EmployeeEmpty })
        val loading = contactDirectoryLayout(
            employeeIds = emptyList(), teamIds = emptyList(),
            queryBlank = true, hasError = false, loadingEmpty = true,
        )
        assertEquals(listOf(ContactSlot.CreatePanel, ContactSlot.Loading), loading)
    }

    @Test
    fun contactsLandingIsDirectoryNotManagement() {
        val screen = File("src/main/java/com/wand/app/ui/screens/ContactsScreen.kt").readText()
        assertTrue(screen.contains("点名字开新对话，点头像改资料"))
        assertTrue(screen.contains("搜索名字、职责、标签"))
        assertTrue(screen.contains("contactOrderedEmployees"))
        assertTrue(screen.contains("contactDirectoryLayout"))
        assertFalse(screen.contains("拼音索引"))
        assertFalse(screen.contains("WandCard"))
        assertTrue(screen.contains("管理\${employee.name}的信息"))
        assertTrue(screen.contains("与\${employee.name}聊天"))
        assertTrue(screen.contains("与\${team.name}新建群聊"))
        assertTrue(screen.contains("onOpenConversation(employeeConversationId(assignable.id))"))
        assertTrue(screen.contains("ConversationGroupEditor("))
        assertTrue(screen.contains("draftContext = \"contact:\$presetId\""))
        assertFalse(screen.contains("RecentEmployeeConversation"))
        assertFalse(screen.contains("startDirectTeamRun"))
        assertFalse(screen.contains("taskDefaultCwd"))
        assertTrue(screen.contains("onOpenEmployee(employee.id)"))
        assertFalse(screen.contains("SiliconEmployeeListSection"))
        assertFalse(screen.contains("AiTeamListSection"))
        assertFalse(screen.contains("showArchived"))
        assertTrue(screen.contains("onOpenTeam(team.id)"))
        // 通讯录不再是自带 Scaffold/返回键的独立壳：顶栏与底栏都由根壳提供。
        assertFalse(screen.contains("Scaffold("))
        // 通讯录不再是导航栈里的一页：内容挂在根壳的页签上，接线在 TaskListScreen 的分支里。
        val taskList = File("src/main/java/com/wand/app/ui/screens/TaskListScreen.kt").readText()
        val branch = taskList.substringAfter("projection == HomeListMode.Contacts.storageValue")
            .substringBefore("projection == HomeListMode.Im.storageValue")
        assertTrue(branch.contains("ContactsScreen("))
        assertTrue(branch.contains("onOpenEmployee = onOpenEmployee"))
        assertTrue(branch.contains("onOpenConversation = onOpenConversation"))
        assertTrue(branch.contains("conversations = directoryConversations"))
        assertTrue(branch.contains("bottomClearance = menuClearance"))
        val app = File("src/main/java/com/wand/app/ui/WandApp.kt").readText()
        assertTrue(app.contains("onOpenEmployee = { nav.push(Screen.SiliconEmployeeEditor(it)) }"))
        assertTrue(app.contains("onOpenConversation = { id -> conversationState.select(id); openConversation(id) }"))
        assertTrue(app.contains("onOpenConversation = { id -> conversationState.select(id); nav.setDetail(Screen.Conversation(id)) }"))
        assertTrue(app.contains("onOpenTeam = { nav.push(Screen.AiTeamDetail(it)) }"))
        assertTrue(app.contains("onOpenGroupChat = { runId -> nav.push(Screen.AiTeamChat(runId)) }"))
        assertFalse(branch.contains("onEditTeam"))
    }

}
