package com.wand.app.ui.screens

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.WandApiException
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskDetail
import com.wand.app.ui.SessionTitleStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTerminalConversationTest {
    @After
    fun tearDown() = SessionTitleStore.clear()

    @Test
    fun emptyListKeepsOnlyBlankTerminalHeaderInEveryFold() {
        val groups = recentHomeGroups(emptyList())
        assertEquals(listOf("blank-terminal"), groups.map { it.key })
        assertEquals(listOf("空白终端"), groups.map { it.title })
        groups.forEach { group ->
            assertTrue(homeGroupShowsHeader(group))
            HomeFoldMode.entries.forEach { fold ->
                val visible = foldVisibleConversations(fold, group.conversations)
                assertTrue(visible.isEmpty())
                assertFalse(foldExpandsContent(fold, visible.size))
            }
        }
    }

    @Test
    fun loneBlankTerminalAlwaysUsesSecondaryRowAndHonorsAllThreeFolds() {
        listOf(null, "shell").forEach { provider ->
            val group = recentHomeGroups(listOf(conversation("blank", provider, "idle")))
                .last()
            assertTrue(homeGroupShowsHeader(group))
            assertEquals(1, group.conversations.size)
            assertTrue(foldExpandsContent(HomeFoldMode.Expand,
                foldVisibleConversations(HomeFoldMode.Expand, group.conversations).size))
            assertFalse(foldExpandsContent(HomeFoldMode.Collapse,
                foldVisibleConversations(HomeFoldMode.Collapse, group.conversations).size))
            assertFalse(foldExpandsContent(HomeFoldMode.Running,
                foldVisibleConversations(HomeFoldMode.Running, group.conversations).size))
            val running = group.copy(conversations = listOf(conversation("blank", provider, "running")))
            assertTrue(homeGroupShowsHeader(running))
            assertTrue(foldExpandsContent(HomeFoldMode.Running,
                foldVisibleConversations(HomeFoldMode.Running, running.conversations).size))
        }
    }

    @Test
    fun loneCliTerminalKeepsHeaderAndDoesNotTreatIdleCliAsRunning() {
        val active = conversation("pty", "pi", "running")
        val group = recentHomeGroups(listOf(active)).first()
        assertTrue(homeGroupShowsHeader(group))
        assertTrue(foldExpandsContent(HomeFoldMode.Running,
            foldVisibleConversations(HomeFoldMode.Running, group.conversations).size))
        val idle = group.copy(conversations = listOf(active.copy(session = active.session.copy(ptyBusy = false))))
        assertTrue(homeGroupShowsHeader(idle))
        assertTrue(foldVisibleConversations(HomeFoldMode.Running, idle.conversations).isEmpty())
        assertFalse(foldExpandsContent(HomeFoldMode.Collapse, 1))
    }

    @Test
    fun olderTerminalsAreNotLostBehindRecentEightEmployeeConversations() {
        val employees = (1..10).map { index ->
            conversation("employee-$index", "pi", "idle", "2026-10-02T12:${index.toString().padStart(2, '0')}:00Z")
                .let { it.copy(session = it.session.copy(employeeId = "e-1", employeeName = "虎妞")) }
        }
        val terminals = (1..10).map { index -> conversation("shell-$index", null, "running") } +
            conversation("old-pty", "codex", "running")
        val groups = recentHomeGroups(employees + terminals)
        assertEquals(listOf("employee:e-1", "pty", "blank-terminal"), groups.map { it.key })
        assertEquals(8, groups.first().conversations.size)
        assertEquals("employee-10", groups.first().conversations.first().session.id)
        assertEquals(listOf("old-pty"), groups[1].conversations.map { it.session.id })
        assertEquals(10, groups.last().conversations.size)
        assertEquals(10, groups.last().counts.running)
    }

    @Test
    fun terminalGroupsStayInFixedOrderAcrossCreationAndDeduplicateSessions() {
        val blank = conversation("blank", null, "running", "2026-10-02T13:00:00Z")
        val pty = conversation("pty", "pi", "running", "2026-10-02T11:00:00Z")
        val groups = recentHomeGroups(listOf(blank, pty, blank))
        assertEquals(listOf("pty", "blank-terminal"), groups.map { it.key })
        assertEquals(1, groups.last().conversations.size)
        assertEquals(groups.map { it.key }, recentHomeGroups(listOf(pty.copy(
            session = pty.session.copy(startedAt = "2026-10-02T14:00:00Z")), blank)).map { it.key })
        assertEquals(listOf("blank-terminal"),
            recentHomeGroups(emptyList(), limit = 0).map { it.key })
    }

    @Test
    fun runningFoldKeepsFailuresWaitingAndLivePermissionsButNeverChangesHeader() {
        val conversations = listOf(
            conversation("running", null, "running"),
            conversation("failed", null, "failed"),
            conversation("waiting", null, "waiting_input"),
            conversation("permission", null, "idle"),
            conversation("idle", null, "idle"),
            conversation("exited", null, "exited"),
        )
        SessionTitleStore.apply("permission", permissionBlocked = true)
        val group = recentHomeGroups(conversations).last()
        assertEquals(setOf("running", "failed", "waiting", "permission"),
            foldVisibleConversations(HomeFoldMode.Running, group.conversations).map { it.session.id }.toSet())
        assertTrue(homeGroupShowsHeader(group))
        assertEquals(1, group.counts.running)
        assertEquals(3, group.counts.needsYou)
    }

    @Test
    fun ptyEmployeeIdentityStillBelongsToEmployeeNotTerminalGroup() {
        val conversation = conversation("employee-pty", "pi", "running").let {
            it.copy(session = it.session.copy(employeeId = "e-1", employeeName = "虎妞"))
        }
        val groups = recentHomeGroups(listOf(conversation))
        assertEquals(listOf("employee:e-1", "blank-terminal"), groups.map { it.key })
        assertEquals("employee-pty", groups.first().conversations.single().session.id)
        assertTrue(groups.drop(1).all { it.conversations.isEmpty() })
    }

    @Test
    fun screenshotPiAndBlankTerminalDoNotInventAThirdEmptyPtyGroup() {
        val pi = conversation("pi-chat", "pi", "idle").let {
            it.copy(session = it.session.copy(sessionKind = "structured", runner = "pi-cli", inFlight = false))
        }
        val blank = conversation("blank", null, "idle")
        val groups = recentHomeGroups(listOf(pi, blank))
        assertEquals(listOf("cli:pi", "blank-terminal"), groups.map { it.key })
        assertEquals(HomeGroupKind.Cli, groups.first().kind)
        assertFalse(groups.first().kind.isTerminal)
        assertEquals("Pi", groups.first().title)
        assertEquals(1, groups.first().conversations.size)
        assertEquals(1, groups.last().conversations.size)
        assertEquals(setOf("pi-chat", "blank"),
            groups.flatMap { it.conversations }.map { it.session.id }.toSet())
        HomeFoldMode.entries.forEach { fold ->
            assertTrue(homeGroupShowsHeader(groups.last()))
            val visible = foldVisibleConversations(fold, groups.last().conversations)
            assertEquals(fold == HomeFoldMode.Expand, foldExpandsContent(fold, visible.size))
        }
    }

    @Test
    fun ptyGroupRequiresRealPtySessionButIsNotRemovedByRunningFilter() {
        val blank = conversation("blank", null, "idle")
        val pty = conversation("pi-pty", "pi", "idle")
        assertEquals(listOf("blank-terminal"), recentHomeGroups(listOf(blank)).map { it.key })
        val groups = recentHomeGroups(listOf(pty, blank))
        assertEquals(listOf("pty", "blank-terminal"), groups.map { it.key })
        val realPty = groups.first()
        assertEquals(1, realPty.conversations.size)
        assertTrue(homeGroupShowsHeader(realPty))
        assertTrue(foldVisibleConversations(HomeFoldMode.Running, realPty.conversations).isEmpty())
        assertEquals(1, realPty.conversations.size)
        assertEquals(listOf("blank-terminal"), recentHomeGroups(listOf(blank)).map { it.key })
    }

    @Test
    fun blankPlusCreatesOnlyShellWithoutTaskPromptOrGlobalPreferenceChange() = runBlocking {
        val api = FakePort()
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
        assertEquals("created", request.create(api)?.id)
        assertEquals(1, api.defaultLoads)
        assertEquals(Create(WorkspaceSessionTarget.Shell, WorkspaceBinding(cwd = "/default"),
            WorkspaceSessionKind.Pty, null, null, null), api.creates.single())
        assertFalse(request.busy)
        assertFalse(request.creationUnconfirmed)
        assertNull(request.error)
        assertNull(request.create(api))
        assertEquals(1, api.creates.size)
    }

    @Test
    fun emptyDefaultDirectoryUsesServerFallbackRatherThanInventingClientPath() = runBlocking {
        val api = FakePort().apply { defaultCwd = null }
        RecentTerminalConversation(WorkspaceSessionTarget.Shell, null).create(api)
        assertEquals("", api.creates.single().binding.cwd)
    }

    @Test
    fun terminalPlusUsesProjectDirectoryNotOldTaskOrWorktree() = runBlocking {
        val recent = conversation("old", null, "idle").let {
            it.copy(session = it.session.copy(cwd = "/repo/.wand-worktrees/old"))
        }
        val group = recentHomeGroups(listOf(recent)).last()
        val binding = homeGroupConversationBinding(group)
        assertEquals(WorkspaceBinding(workspaceId = "workspace-1", cwd = "/repo"), binding)
        val api = FakePort()
        RecentTerminalConversation(WorkspaceSessionTarget.Shell, binding).create(api)
        assertEquals(binding, api.creates.single().binding)
        assertEquals(0, api.defaultLoads)
    }

    @Test
    fun everyCliSelectionAlwaysCreatesPtyWithNoInitialInput() = runBlocking {
        WorkspaceSessionTarget.OPTIONS.filterNot { it.isShell }.forEach { target ->
            val api = FakePort()
            assertEquals("created", RecentTerminalConversation(target, null).create(api)?.id)
            val sent = api.creates.single()
            assertEquals(target, sent.target)
            assertEquals(WorkspaceSessionKind.Pty, sent.kind)
            assertNull(sent.prompt)
            assertNull(sent.model)
            assertNull(sent.thinkingEffort)
        }
    }

    @Test
    fun rejectsOldTaskBindingBeforeSendingAndDoesNotLockRetry() = runBlocking {
        val api = FakePort()
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell,
            WorkspaceBinding(workspaceTaskId = "old-task", cwd = "/repo"))
        assertNull(request.create(api))
        assertTrue(api.creates.isEmpty())
        assertFalse(request.creationUnconfirmed)
        assertFalse(request.busy)
    }

    @Test
    fun defaultDirectoryFailureCanRetryBecauseNothingWasSent() = runBlocking {
        val api = FakePort().apply { defaultFailure = IllegalStateException("读取失败") }
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
        assertNull(request.create(api))
        assertFalse(request.creationUnconfirmed)
        assertTrue(api.creates.isEmpty())
        api.defaultFailure = null
        assertEquals("created", request.create(api)?.id)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun explicitHttpRejectionAllowsRetry() = runBlocking {
        val api = FakePort().apply { createFailure = WandApiException(400, "目录无效") }
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
        assertNull(request.create(api))
        assertFalse(request.creationUnconfirmed)
        assertEquals("目录无效", request.error)
        api.createFailure = null
        assertEquals("created", request.create(api)?.id)
        assertEquals(2, api.creates.size)
    }

    @Test
    fun unknownHttpNetworkAndMalformedAcknowledgementsNeverCreateTwice() = runBlocking {
        val failures = listOf<Exception>(WandApiException(500, "失败"), WandApiException(408, "超时"),
            WandApiException(409, "冲突"), IllegalStateException("连接中断"))
        failures.forEach { failure ->
            val api = FakePort().apply { createFailure = failure }
            val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
            assertNull(request.create(api))
            assertTrue(request.creationUnconfirmed)
            api.createFailure = null
            assertNull(request.create(api))
            assertEquals(1, api.creates.size)
            assertFalse(request.busy)
        }
        listOf(
            snapshot("", null),
            snapshot("created", "pi"),
            snapshot("created", null).copy(sessionKind = "structured"),
        ).forEach { result ->
            val api = FakePort().apply { createResult = result }
            val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
            assertNull(request.create(api))
            assertTrue(request.creationUnconfirmed)
            assertNull(request.create(api))
            assertEquals(1, api.creates.size)
        }
    }

    @Test
    fun concurrentClicksCannotDuplicateCreationIncludingDuringDefaultLoad() = runBlocking {
        val api = FakePort().apply { defaultGate = CompletableDeferred() }
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
        val first = async(start = CoroutineStart.UNDISPATCHED) { request.create(api) }
        assertTrue(request.busy)
        assertNull(request.create(api))
        api.defaultGate?.complete(Unit)
        assertEquals("created", first.await()?.id)
        assertEquals(1, api.defaultLoads)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun cancellationBeforeCreateDoesNotInventUnknownDelivery() {
        val api = FakePort().apply { defaultFailure = CancellationException("已离开") }
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
        assertThrows(CancellationException::class.java) { runBlocking { request.create(api) } }
        assertFalse(request.creationUnconfirmed)
        assertFalse(request.busy)
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun cancellationAfterCreateStartedKeepsUnknownDeliveryProtection() {
        val api = FakePort().apply { createFailure = CancellationException("已离开") }
        val request = RecentTerminalConversation(WorkspaceSessionTarget.Shell, null)
        assertThrows(CancellationException::class.java) { runBlocking { request.create(api) } }
        assertTrue(request.creationUnconfirmed)
        assertFalse(request.busy)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun screenRetainsEmptyGroupActionsClosesMenusAndIgnoresLateNavigation() {
        val screen = File("src/main/java/com/wand/app/ui/screens/TaskListScreen.kt").readText()
        assertTrue(screen.contains("recentHomeConversations(visibleGroups, limit = Int.MAX_VALUE)"))
        assertFalse(screen.contains("!hasAnyContent -> EmptyState("))
        assertFalse(screen.contains("!hasVisibleContent -> EmptyState("))
        assertFalse(screen.contains("if (recentGroups.isNotEmpty())"))
        assertTrue(screen.contains("BackHandler(enabled = terminalMenuKey != null)"))
        val invalidate = screen.substringAfter("fun invalidateRecentConversationOpening()")
            .substringBefore("androidx.activity.compose.BackHandler")
        assertTrue(invalidate.contains("terminalMenuKey = null"))
        val create = screen.substringAfter("fun createRecentTerminal(").substringBefore("val overview")
        assertTrue(create.contains("creationUnconfirmed == true"))
        assertTrue(create.contains("openingId == recentConversationOpeningId"))
        assertTrue(create.contains("structured = false"))
        assertTrue(create.contains("CoroutineStart.UNDISPATCHED"))
        assertTrue(screen.contains("menuResetKey = recentConversationOpeningId"))
        assertFalse(create.contains("createTask("))
        assertFalse(create.contains("rememberCreationChoice("))
    }

    @Test
    fun headersKeepFixedAddSlotAndSecondaryRowsKeepTheirActionMenus() {
        val chrome = File("src/main/java/com/wand/app/ui/screens/HomeChrome.kt").readText()
        val card = chrome.substringAfter("internal fun HomeGroupCard(").substringBefore("/** 一级行")
        assertTrue(card.contains("homeGroupShowsHeader(group)"))
        assertTrue(card.contains("foldExpandsContent(fold, visibleConversations.size)"))
        assertTrue(card.contains("secondary = true"))
        assertTrue(card.contains("key(conversation.session.id)"))
        assertTrue(card.contains("Modifier.size(44.dp)"))
        assertTrue(card.contains("WandStatusIconSlot("))
        assertTrue(card.contains("WandMorphingIcon("))
        assertTrue(card.contains("onDismissTerminalMenu()"))
        assertTrue(card.contains("WandMotion.respectMotion(!reduceMotion"))
        val header = chrome.substringAfter("private fun HomeGroupHeader(").substringBefore("/** 分组卡里")
        assertTrue(header.contains("group.kind.isTerminal"))
        assertTrue(header.contains("Modifier.height(64.dp)"))
        assertTrue(header.contains("trailingAction()"))
        val cliIcon = header.substringAfter("HomeGroupKind.Cli ->")
            .substringBefore("HomeGroupKind.Pty")
        assertTrue(cliIcon.contains("WandProviderMark("))
        assertTrue(cliIcon.contains("group.conversations.firstOrNull()?.session?.provider"))
        assertFalse(header.contains("HomeGroupKind.BlankTerminal, HomeGroupKind.Cli -> Icon"))
        val sessionRow = chrome.substringAfter("internal fun HomeSessionRow(")
            .substringBefore("/** 团队派发来源")
        assertTrue(sessionRow.contains("remember(session.id)"))
        assertTrue(sessionRow.contains("LaunchedEffect(selecting, menuResetKey)"))
        listOf("打开", "移动到任务", "删除终端").forEach { assertTrue(sessionRow.contains(it)) }
    }

    private data class Create(
        val target: WorkspaceSessionTarget,
        val binding: WorkspaceBinding,
        val kind: WorkspaceSessionKind,
        val prompt: String?,
        val model: String?,
        val thinkingEffort: String?,
    )

    private class FakePort : WorkspacePort {
        val creates = mutableListOf<Create>()
        var defaultLoads = 0
        var defaultCwd: String? = "/default"
        var defaultGate: CompletableDeferred<Unit>? = null
        var defaultFailure: Exception? = null
        var createFailure: Exception? = null
        var createResult: SessionSnapshot? = null
        override suspend fun taskDefaultCwd(): String? {
            defaultLoads += 1
            defaultGate?.await()
            defaultFailure?.let { throw it }
            return defaultCwd
        }
        override suspend fun createWorkspaceTaskWindow(
            target: WorkspaceSessionTarget, binding: WorkspaceBinding, kind: WorkspaceSessionKind,
            prompt: String?, model: String?, thinkingEffort: String?,
        ): SessionSnapshot {
            creates += Create(target, binding, kind, prompt, model, thinkingEffort)
            createFailure?.let { throw it }
            return createResult ?: snapshot("created", target.provider)
        }
        override suspend fun listWorkspaces(): List<Workspace> = error("不得新建或修改工作区")
        override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> = error("unused")
        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask = error("unused")
        override suspend fun deleteWorkspaceTask(taskId: String) = error("unused")
        override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail = error("不得读取旧任务")
        override suspend fun saveWorkspaceTaskLayout(taskId: String, layout: TaskWindowLayout?): TaskWindowLayout? =
            error("不得改旧任务布局")
    }

    companion object {
        private fun snapshot(id: String, provider: String?) = SessionSnapshot.parse(
            JSONObject().put("id", id).put("sessionKind", "pty").put("runner", "pty")
                .put("provider", provider).put("cwd", "/repo"),
        )

        private fun conversation(
            id: String, provider: String?, status: String, startedAt: String = "2026-10-01T12:00:00Z",
        ) = HomeRecentConversation(
            group = TaskDirectoryGroup("workspace-1", "Repo", "/repo", false, emptyList(), emptyList()),
            task = null,
            session = WorkspaceSessionSummary(id, provider, "pty", "pty", id, status, "/repo", startedAt,
                ptyBusy = status == "running"),
        )
    }
}
