package com.wand.app.ui.screens

import com.wand.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationFiltersTest {
    private fun session(id: String, employee: String? = "e", archived: Boolean = false, binding: String? = null) =
        JSONObject().put("id", id).put("title", "会话 $id").put("employeeId", employee ?: JSONObject.NULL)
            .put("archived", archived).put("workspaceTaskId", binding ?: JSONObject.NULL)

    private fun task(id: String, stage: String?, sessions: List<JSONObject>, status: String = "active") =
        JSONObject().put("id", id).put("workspaceId", "w").put("name", id).put("status", status)
            .put("sessions", JSONArray(sessions)).apply {
                if (stage != null) { put("boardTaskId", "board-$id"); put("boardTaskStatus", stage) }
            }

    private fun group(id: String = "w", tasks: List<JSONObject> = emptyList(), standalone: List<JSONObject> = emptyList(),
        synthetic: Boolean = false, global: Boolean = false, userAdded: Boolean = true): TaskDirectoryGroup = TaskDirectoryGroup.parse(
        JSONObject().put("workspaceId", id).put("workspaceName", "项目 $id").put("workspaceCwd", "/same/path")
            .put("userAdded", userAdded).put("synthetic", synthetic).put("global", global).put("tasks", JSONArray(tasks))
            .put("standaloneSessions", JSONArray(standalone)))!!

    private fun person() = ConversationInstance.parse(JSONObject().put("id", "dm_e").put("kind", "dm")
        .put("title", "员工").put("peerEmployeeId", "e"))!!

    private fun groupTask(id: String, stage: String?, workspace: String?, known: Boolean = true): ConversationTask {
        val json = JSONObject().put("id", id).put("title", "任务 $id")
        if (stage != null) json.put("status", stage)
        if (known) json.put("workspaceId", workspace ?: JSONObject.NULL)
        return ConversationTask.parse(JSONObject().put("task", json))!!
    }

    @Test fun sameSessionIntersectionNeverCombinesSiblingMatches() {
        val groups = listOf(group("a", listOf(task("review-a", "review", listOf(session("a"))))),
            group("b", listOf(task("doing-b", "doing", listOf(session("b"))))))
        val filters = ConversationListFilters(stage = ConversationTaskStage.Review, workspaceId = "b")
        assertTrue(projectConversationList(listOf(person()), groups, filters, "").rows.isEmpty())
        val match = projectConversationList(listOf(person()), groups, filters.copy(workspaceId = "a"), "").rows.single()
        assertEquals(listOf("a"), match.sessions.map { it.session.id })
        assertEquals(1, match.sessions.size)
        assertTrue(projectConversationList(listOf(person()), groups, filters.copy(workspaceId = "a"), "会话 b").rows.isEmpty())
    }

    @Test fun workspaceSummaryDecodesBoardFieldsAndNeverUsesContainerStatus() {
        val parsed = WorkspaceTaskSummary.parse(task("t", "done", listOf(session("s")), "active"))!!
        assertEquals("board-t", parsed.boardTaskId)
        assertEquals("done", parsed.boardTaskStatus)
        assertEquals(ConversationTaskStage.Review, conversationSessionStage(HomeRecentConversation(group(), parsed, parsed.sessions.single())))
        val unknown = WorkspaceTaskSummary.parse(task("old", null, listOf(session("s")), "done"))!!
        assertEquals(ConversationTaskStage.Unknown, conversationSessionStage(HomeRecentConversation(group(), unknown, unknown.sessions.single())))
        assertNull(unknown.boardTaskStatus)
        val explicitNull = WorkspaceTaskSummary.parse(task("null", null, listOf(session("s")))
            .put("boardTaskId", JSONObject.NULL).put("boardTaskStatus", JSONObject.NULL))!!
        assertEquals(ConversationTaskStage.Unknown, conversationSessionStage(HomeRecentConversation(group(), explicitNull, explicitNull.sessions.single())))
    }

    @Test fun onlyExplicitStandaloneWithoutBindingIsNoneAndFailureIsUnknown() {
        val groups = listOf(group(tasks = listOf(task("old", null, listOf(session("container")))),
            standalone = listOf(session("standalone"), session("bound", binding = "missing"))))
        val filters = ConversationListFilters(stage = ConversationTaskStage.None)
        assertEquals(listOf("standalone"), projectConversationList(listOf(person()), groups, filters, "").rows.single().sessions.map { it.session.id })
        assertTrue(projectConversationList(listOf(person()), groups, filters, "", associationsAvailable = false).rows.isEmpty())
        assertTrue(projectConversationList(listOf(person()), emptyList(), filters, "").rows.isEmpty())
        assertEquals(ConversationTaskStage.Unknown, conversationSessionStage(conversationFilterSessions(groups).first(), false))
    }

    @Test fun unassignedUsesStableGroupFlagsNotPathsAndAllIncludesIt() {
        val groups = listOf(group("real", standalone = listOf(session("real"))),
            group("synthetic", standalone = listOf(session("synthetic")), synthetic = true),
            group("global", standalone = listOf(session("global")), global = true),
            group("wand-global", standalone = listOf(session("wand-global"))),
            group("automatic", standalone = listOf(session("automatic")), userAdded = false))
        val all = projectConversationList(listOf(person()), groups, ConversationListFilters(), "")
        assertEquals(5, all.rows.single().sessions.size)
        val filtered = projectConversationList(listOf(person()), groups,
            ConversationListFilters(workspaceId = UnassignedConversationWorkspace), "")
        assertEquals(setOf("synthetic", "global", "wand-global", "automatic"), filtered.rows.single().sessions.map { it.session.id }.toSet())
        assertEquals("未分配", conversationWorkspaceLabel(groups[1]))
        assertEquals("real", conversationWorkspaceId(groups[0]))
    }

    @Test fun taskArchiveAndSessionArchiveAreIndependentAndArchivedChildrenAreNotLost() {
        val groups = listOf(group(tasks = listOf(task("t", "archived", listOf(session("live"), session("archived", archived = true))))))
        val all = ConversationListFilters(stage = ConversationTaskStage.Archived, tier = ConversationListTier.All)
        assertEquals(2, projectConversationList(listOf(person()), groups, all, "").rows.single().sessions.size)
        assertEquals(listOf("live"), projectConversationList(listOf(person()), groups,
            all.copy(tier = ConversationListTier.Active), "").rows.single().sessions.map { it.session.id })
        assertEquals(listOf("archived"), projectConversationList(listOf(person()), groups,
            all.copy(tier = ConversationListTier.Archived), "").rows.single().sessions.map { it.session.id })
        val archived = conversationFilterSessions(groups).single { it.session.id == "archived" }
        assertTrue(conversationSessionMatches(archived, all.copy(tier = ConversationListTier.Archived)))
        assertFalse(conversationSessionMatches(archived, all.copy(activeOnly = true)))
    }

    @Test fun containerArchiveIsInheritedWithoutChangingBusinessStage() {
        val archivedTask = task("t", "doing", listOf(session("s").put("inFlight", true)))
            .put("archived", true)
        val groups = listOf(group(tasks = listOf(archivedTask)))
        val item = conversationFilterSessions(groups).single()
        assertTrue(item.task!!.archived)
        assertTrue(item.task.copy(sessions = emptyList()).archived)
        assertEquals(ConversationTaskStage.Doing, conversationSessionStage(item))
        assertTrue(conversationSessionMatches(item, ConversationListFilters(tier = ConversationListTier.Archived)))
        assertFalse(conversationSessionMatches(item, ConversationListFilters(tier = ConversationListTier.Active)))
        assertFalse(conversationSessionMatches(item, ConversationListFilters(activeOnly = true)))
        assertEquals(listOf("s"), projectConversationList(listOf(person()), groups,
            ConversationListFilters(stage = ConversationTaskStage.Doing, workspaceId = "w", tier = ConversationListTier.Archived), "")
            .rows.single().sessions.map { it.session.id })
        assertTrue(projectConversationList(listOf(person()), groups,
            ConversationListFilters(stage = ConversationTaskStage.Doing, workspaceId = "w", tier = ConversationListTier.Active), "").rows.isEmpty())
    }

    @Test fun mobileGroupFiltersItsRealBoardTasksWithoutCrossTaskMatches() {
        val tasks = listOf(groupTask("review-a", "done", "a"), groupTask("doing-b", "doing", "b"))
        val chat = person().copy(id = "group", kind = "group", peerEmployeeId = null, tasks = tasks)
        val filters = ConversationListFilters(stage = ConversationTaskStage.Review, workspaceId = "b")
        assertTrue(projectConversationList(listOf(chat), emptyList(), filters, "").rows.isEmpty())
        val match = projectConversationList(listOf(chat), emptyList(), filters.copy(workspaceId = "a"), "").rows.single()
        assertEquals(listOf("review-a"), match.tasks.map { it.task.id })
        assertTrue(projectConversationList(listOf(chat), emptyList(), ConversationListFilters(stage = ConversationTaskStage.None), "").rows.isEmpty())
        assertEquals(2, projectConversationList(listOf(chat), emptyList(), ConversationListFilters(), "").rows.single().tasks.size)
    }

    @Test fun missingGroupFieldsAreUnknownNotTodoOrUnassigned() {
        val old = groupTask("old", null, null, known = false)
        assertNull(old.boardTaskStatus)
        assertFalse(conversationGroupTaskMatches(old, ConversationListFilters(stage = ConversationTaskStage.Todo)))
        assertFalse(conversationGroupTaskMatches(old, ConversationListFilters(workspaceId = UnassignedConversationWorkspace)))
        assertTrue(conversationGroupTaskMatches(groupTask("global", "ready", null),
            ConversationListFilters(stage = ConversationTaskStage.Ready, workspaceId = UnassignedConversationWorkspace)))
    }

    @Test fun otherSessionsUseSameSearchAndIntersectionAndDeduplicate() {
        val s = session("other", employee = null)
        val groups = listOf(group(tasks = listOf(task("t", "review", listOf(s, s)))))
        val filters = ConversationListFilters(stage = ConversationTaskStage.Review, workspaceId = "w")
        assertEquals(1, projectConversationList(emptyList(), groups, filters, "other").unbound.size)
        assertTrue(projectConversationList(emptyList(), groups, filters, "missing").unbound.isEmpty())
        assertTrue(projectConversationList(emptyList(), groups, filters.copy(stage = ConversationTaskStage.Ready), "").unbound.isEmpty())
    }

    @Test fun activeFilterIsOrthogonalToBusinessStageAndProjectionDoesNotMutateSources() {
        val running = session("running").put("inFlight", true).put("sessionKind", "structured")
        val idle = session("idle").put("inFlight", false).put("sessionKind", "structured")
        val groups = listOf(group(tasks = listOf(task("t", "review", listOf(running, idle)))))
        val source = person()
        val filters = ConversationListFilters(stage = ConversationTaskStage.Review, workspaceId = "w", activeOnly = true)
        assertEquals(listOf("running"), projectConversationList(listOf(source), groups, filters, "").rows.single().sessions.map { it.session.id })
        assertTrue(projectConversationList(listOf(source), groups, filters.copy(stage = ConversationTaskStage.Doing), "").rows.isEmpty())
        assertEquals(2, groups.single().tasks.single().sessions.size)
        assertEquals("dm_e", source.id)
        assertEquals(listOf("idle"), projectConversationList(listOf(source), groups, ConversationListFilters(), "idle").rows.single().sessions.map { it.session.id })
    }

    @Test fun boardStatusIsTrustedWithoutBoardIdentity() {
        val json = task("t", "review", listOf(session("s"))).apply { remove("boardTaskId") }
        val item = conversationFilterSessions(listOf(group(tasks = listOf(json)))).single()
        assertEquals(ConversationTaskStage.Review, conversationSessionStage(item))
        assertTrue(conversationSessionMatches(item, ConversationListFilters(stage = ConversationTaskStage.Review)))
        assertFalse(conversationSessionMatches(item, ConversationListFilters(stage = ConversationTaskStage.None)))
    }

    @Test fun filterCountAndStaleWorkspaceSummaryRemainClearable() {
        val filters = ConversationListFilters(stage = ConversationTaskStage.Review, workspaceId = "deleted", tier = ConversationListTier.All, activeOnly = true)
        assertEquals(4, filters.count)
        assertTrue(conversationFilterSummary(filters, emptyList()).contains("工作区不可用（deleted）"))
        assertEquals(0, ConversationListFilters().count)
    }

    @Test fun employeeFilterMatchesByEmployeeId() {
        val s1 = session("s1", employee = "emp-1")
        val s2 = session("s2", employee = "emp-2")
        val s3 = session("s3", employee = null)
        val groups = listOf(group("A", standalone = listOf(s1, s2, s3)))
        val sessions = conversationFilterSessions(groups)
        val filter1 = ConversationListFilters(employeeIds = setOf("emp-1"))
        assertEquals(listOf("s1"), sessions.filter { conversationSessionMatches(it, filter1) }.map { it.session.id })
        val filterNone = ConversationListFilters(employeeIds = setOf("none"))
        assertEquals(listOf("s3"), sessions.filter { conversationSessionMatches(it, filterNone) }.map { it.session.id })
        val filterMulti = ConversationListFilters(employeeIds = setOf("emp-1", "emp-2"))
        assertEquals(setOf("s1", "s2"), sessions.filter { conversationSessionMatches(it, filterMulti) }.map { it.session.id }.toSet())
        assertEquals(1, filter1.count)
        val options = conversationEmployeeOptions(sessions)
        assertTrue(options.any { it.first == "none" })
        assertTrue(options.any { it.first == "emp-1" })
        assertTrue(options.any { it.first == "emp-2" })
    }
}
