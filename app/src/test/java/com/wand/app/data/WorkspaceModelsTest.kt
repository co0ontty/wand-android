package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workspace / Task / Layout JSON 模型解析测试。
 * 覆盖：Workspace/Task 实体、空 session、未知 tab kind、layout 序列化往返、
 * qoder→qodercli 命令映射、SessionSnapshot 的 workspaceId/workspaceTaskId 字段。
 */
class WorkspaceModelsTest {

    // MARK: - WorkspaceSessionTarget

    @Test
    fun target_fromRaw_resolvesAllProviders() {
        assertEquals(WorkspaceSessionTarget.Claude, WorkspaceSessionTarget.fromRaw("claude"))
        assertEquals(WorkspaceSessionTarget.Codex, WorkspaceSessionTarget.fromRaw("codex"))
        assertEquals(WorkspaceSessionTarget.OpenCode, WorkspaceSessionTarget.fromRaw("opencode"))
        assertEquals(WorkspaceSessionTarget.Grok, WorkspaceSessionTarget.fromRaw("grok"))
        assertEquals(WorkspaceSessionTarget.Qoder, WorkspaceSessionTarget.fromRaw("qoder"))
        assertEquals(WorkspaceSessionTarget.Pi, WorkspaceSessionTarget.fromRaw("pi"))
        assertEquals(WorkspaceSessionTarget.Shell, WorkspaceSessionTarget.fromRaw("shell"))
        assertNull(WorkspaceSessionTarget.fromRaw("unknown"))
        assertNull(WorkspaceSessionTarget.fromRaw(null))
    }

    @Test
    fun target_shell_isShellTrue() {
        assertTrue(WorkspaceSessionTarget.Shell.isShell)
        WorkspaceSessionTarget.OPTIONS.filter { it != WorkspaceSessionTarget.Shell }.forEach {
            assertFalse("$it should not be shell", it.isShell)
        }
    }

    @Test
    fun target_shell_providerNull_othersReturnRaw() {
        assertNull(WorkspaceSessionTarget.Shell.provider)
        assertEquals("claude", WorkspaceSessionTarget.Claude.provider)
    }

    // MARK: - Layout 解析

    @Test
    fun parseLayout_null_returnsNull() {
        assertNull(LayoutNode.parse(null))
        assertNull(LayoutNode.parse(JSONObject()))
    }

    @Test
    fun parseLayout_paneWithSessionTab() {
        val o = JSONObject().put("type", "pane")
            .put("tabs", JSONArray().put(
                JSONObject().put("id", "tab-1").put("kind", "session").put("sessionId", "s1"),
            ))
            .put("active", 0)
        val node = LayoutNode.parse(o)
        assertNotNull(node)
        assertTrue(node is LayoutNode.Pane)
        val pane = node as LayoutNode.Pane
        assertEquals(1, pane.tabs.size)
        assertEquals("tab-1", pane.tabs[0].id)
        assertTrue(pane.tabs[0] is PaneTab.Session)
        assertEquals("s1", (pane.tabs[0] as PaneTab.Session).sessionId)
    }

    @Test
    fun parseLayout_unknownTabKind_preservedAsUnknown() {
        // 未来类型（如 image）不能丢弃 —— 保留为 Unknown
        val o = JSONObject().put("type", "pane")
            .put("tabs", JSONArray().put(
                JSONObject().put("id", "tab-x").put("kind", "image").put("src", "/a.png"),
            ))
            .put("active", 0)
        val node = LayoutNode.parse(o) as LayoutNode.Pane
        assertEquals(1, node.tabs.size)
        val tab = node.tabs[0]
        assertTrue(tab is PaneTab.Unknown)
        assertEquals("image", (tab as PaneTab.Unknown).rawKind)
    }

    @Test
    fun parseLayout_splitWithRatioClamped() {
        val o = JSONObject().put("type", "split").put("dir", "v").put("ratio", 2.0)
            .put("children", JSONArray().put(
                JSONObject().put("type", "pane").put("tabs", JSONArray().put(
                    JSONObject().put("id", "t1").put("kind", "session").put("sessionId", "s1"),
                )),
            ).put(
                JSONObject().put("type", "pane").put("tabs", JSONArray().put(
                    JSONObject().put("id", "t2").put("kind", "session").put("sessionId", "s2"),
                )),
            ))
        val split = LayoutNode.parse(o) as LayoutNode.Split
        assertEquals(LayoutSplitDir.Vertical, split.dir)
        // ratio 2.0 应被 clamp 到 0.95
        assertEquals(0.95, split.ratio, 0.001)
    }

    // MARK: - TaskWindowLayout 解析

    @Test
    fun parseTaskLayout_legacySingleNode_upgradesToWindow() {
        val legacy = JSONObject().put("type", "pane").put("tabs", JSONArray().put(
            JSONObject().put("id", "tab-1").put("kind", "session").put("sessionId", "s1"),
        ))
        val tw = TaskWindowLayout.parse(legacy)!!
        assertEquals(1, tw.windows.size)
        assertEquals("window-legacy", tw.windows[0].id)
        assertEquals("window-legacy", tw.activeWindowId)
    }

    @Test
    fun parseTaskLayout_null_returnsNull() {
        assertNull(TaskWindowLayout.parse(null))
        assertNull(TaskWindowLayout.parse(JSONObject().put("type", "unknown")))
    }

    @Test
    fun parseTaskLayout_emptyWindowsSet() {
        val o = JSONObject().put("type", "windows").put("windows", JSONArray())
        val tw = TaskWindowLayout.parse(o)!!
        assertTrue(tw.windows.isEmpty())
        assertNull(tw.activeWindowId)
    }

    // MARK: - 布局辅助函数

    @Test
    fun layoutSessionIds_collectsAllSessions() {
        val node = LayoutNode.Split(
            dir = LayoutSplitDir.Horizontal,
            ratio = 0.5,
            children = LayoutNode.Pane(
                listOf(PaneTab.Session("t1", "s1")),
                0,
            ) to LayoutNode.Pane(
                listOf(
                    PaneTab.Session("t2", "s2"),
                    PaneTab.Editor("t3", "/foo"),
                ),
                0,
            ),
        )
        assertEquals(listOf("s1", "s2"), layoutSessionIds(node))
    }

    @Test
    fun activeWorkWindowTab_returnsActiveSession() {
        val layout = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout("w1", LayoutNode.Pane(listOf(PaneTab.Session("t1", "s1")), 0), "t1"),
                WorkWindowLayout("w2", LayoutNode.Pane(listOf(PaneTab.Session("t2", "s2")), 0), "t2"),
            ),
            activeWindowId = "w2",
        )
        val tab = activeWorkWindowTab(layout)
        assertNotNull(tab)
        assertEquals("s2", (tab as PaneTab.Session).sessionId)
    }

    // MARK: - 序列化往返

    @Test
    fun layoutSerialization_roundTrip() {
        val original = TaskWindowLayout(
            windows = listOf(
                WorkWindowLayout(
                    "window-1",
                    LayoutNode.Split(
                        dir = LayoutSplitDir.Horizontal,
                        ratio = 0.6,
                        children = LayoutNode.Pane(listOf(PaneTab.Session("tab-s1", "s1")), 0) to
                            LayoutNode.Pane(listOf(PaneTab.Editor("tab-e1", "/path")), 0),
                    ),
                    "tab-s1",
                ),
            ),
            activeWindowId = "window-1",
        )
        val json = original.toJsonObject()
        val reparsed = TaskWindowLayout.parse(json)!!
        assertEquals(1, reparsed.windows.size)
        assertEquals("window-1", reparsed.activeWindowId)
        // split 内部也应保留
        val root = reparsed.windows[0].layout as LayoutNode.Split
        assertEquals(0.6, root.ratio, 0.001)
        assertEquals(2, layoutTabs(root).size)
    }

    @Test
    fun unknownTabSerialization_preservesRawPayload() {
        val unknown = PaneTab.Unknown("tab-u", "image", JSONObject().put("kind", "image").put("id", "tab-u").put("src", "/x.png"))
        val json = unknown.toJsonObject()
        assertEquals("image", json.str("kind"))
        assertEquals("/x.png", json.str("src"))
    }

    // MARK: - Workspace/Task 实体

    @Test
    fun parseWorkspace_fullFields() {
        val o = JSONObject()
            .put("id", "ws-1")
            .put("name", "My Project")
            .put("cwd", "/home/user/project")
            .put("defaultProvider", "codex")
            .put("createdAt", "2026-01-01T00:00:00Z")
            .put("lastOpenedAt", "2026-02-01T00:00:00Z")
            .put("worktreeCount", 3)
        val ws = Workspace.parse(o)!!
        assertEquals("ws-1", ws.id)
        assertEquals("My Project", ws.name)
        assertEquals("/home/user/project", ws.cwd)
        assertEquals("codex", ws.defaultProvider)
        assertEquals(3, ws.worktreeCount)
    }

    @Test
    fun parseWorkspace_missingId_returnsNull() {
        val o = JSONObject().put("name", "No ID")
        assertNull(Workspace.parse(o))
    }

    @Test
    fun parseTaskDetail_withSessions() {
        val o = JSONObject()
            .put("id", "task-1")
            .put("workspaceId", "ws-1")
            .put("name", "Fix bug")
            .put("status", "active")
            .put("cwd", "/worktree/task-1")
            .put("isolated", true)
            .put("sessions", JSONArray().put(
                JSONObject().put("id", "s1").put("provider", "claude").put("sessionKind", "pty"),
            ))
        val detail = WorkspaceTaskDetail.parse(o)!!
        assertEquals("task-1", detail.id)
        assertEquals("ws-1", detail.workspaceId)
        assertTrue(detail.isolated)
        assertEquals(1, detail.sessions.size)
        assertEquals("s1", detail.sessions[0].id)
    }

    @Test
    fun parseTaskDetail_emptySessions() {
        val o = JSONObject()
            .put("id", "task-empty")
            .put("workspaceId", "ws-1")
            .put("name", "Empty")
            .put("cwd", "/dir")
        val detail = WorkspaceTaskDetail.parse(o)!!
        assertTrue(detail.sessions.isEmpty())
        assertEquals("/dir", detail.cwd)
    }

    @Test
    fun parseTaskGroupsPreservesSyntheticStandaloneAndTotalSessions() {
        val response = JSONArray().put(
            JSONObject()
                .put("workspaceId", "cwd:/repo")
                .put("workspaceName", "repo")
                .put("workspaceCwd", "/repo")
                .put("synthetic", true)
                .put(
                    "tasks",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "task-1")
                            .put("workspaceId", "ws-1")
                            .put("name", "Task")
                            .put("cwd", "/repo/.wand-worktrees/task-1")
                            .put("isolated", true)
                            .put("totalSessions", 7)
                            .put(
                                "sessions",
                                JSONArray().put(
                                    JSONObject().put("id", "session-1").put("sessionKind", "pty"),
                                ),
                            ),
                    ),
                )
                .put(
                    "standaloneSessions",
                    JSONArray().put(JSONObject().put("id", "legacy-1").put("provider", "codex")),
                ),
        )

        val group = TaskDirectoryGroup.parseList(response).single()

        assertTrue(group.synthetic)
        assertEquals("legacy-1", group.standaloneSessions.single().id)
        assertEquals(7, group.tasks.single().totalSessions)
        assertEquals(1, group.tasks.single().sessions.size)
        assertTrue(group.tasks.single().isIsolated)
    }

    @Test
    fun parseTaskSummaryFallsBackToEmbeddedSessionCount() {
        val summary = WorkspaceTaskSummary.parse(
            JSONObject()
                .put("id", "task-1")
                .put("workspaceId", "ws-1")
                .put("name", "Task")
                .put(
                    "sessions",
                    JSONArray()
                        .put(JSONObject().put("id", "session-1"))
                        .put(JSONObject().put("id", "session-2")),
                ),
        )!!

        assertEquals(2, summary.totalSessions)
    }

    // MARK: - SessionSnapshot workspace 字段

    @Test
    fun parseSessionSnapshot_workspaceFieldsParsed() {
        val o = JSONObject()
            .put("id", "s1")
            .put("workspaceId", "ws-1")
            .put("workspaceTaskId", "task-1")
        val snap = SessionSnapshot.parse(o)
        assertEquals("ws-1", snap.workspaceId)
        assertEquals("task-1", snap.workspaceTaskId)
    }

    @Test
    fun parseSessionSnapshot_workspaceFieldsNullWhenAbsent() {
        val o = JSONObject().put("id", "s1")
        val snap = SessionSnapshot.parse(o)
        assertNull(snap.workspaceId)
        assertNull(snap.workspaceTaskId)
    }

    @Test
    fun parseSessionSnapshot_emptyWorkspaceFieldsTreatedAsNull() {
        val o = JSONObject()
            .put("id", "s1")
            .put("workspaceId", "")
            .put("workspaceTaskId", "")
        val snap = SessionSnapshot.parse(o)
        assertNull(snap.workspaceId)
        assertNull(snap.workspaceTaskId)
    }

    @Test
    fun parseWorktreeOverview_andBuildMergePrompt() {
        val overview = WorkspaceWorktreeOverview.parse(
            JSONObject()
                .put("workspaceId", "ws-1")
                .put("repoRoot", "/repo")
                .put("targetBranch", "main")
                .put(
                    "worktrees",
                    JSONArray().put(
                        JSONObject()
                            .put("taskId", "task-1")
                            .put("taskName", "登录流程")
                            .put("taskStatus", "active")
                            .put("branch", "wand/task-1")
                            .put("path", "/repo/.wand-worktrees/task-1")
                            .put("state", "ready")
                            .put("actionable", true)
                            .put("aheadCount", 1)
                            .put("hasUncommittedChanges", false)
                            .put("hasConflicts", false)
                            .put(
                                "commits",
                                JSONArray().put(
                                    JSONObject()
                                        .put("hash", "abc")
                                        .put("shortHash", "abc")
                                        .put("subject", "feat: add login"),
                                ),
                            ),
                    ),
                ),
        )!!
        assertEquals("main", overview.targetBranch)
        assertEquals("登录流程 · feat: add login", overview.worktrees.single().summary)
        val workspace = Workspace("ws-1", "Wand", "/repo", "claude", null, null, null, 1)
        val prompt = buildWorkspaceMergeAgentPrompt(workspace, overview, setOf("task-1"))
        assertTrue(prompt.contains("唯一目标分支：main"))
        assertTrue(prompt.contains("wand/task-1"))
        assertTrue(prompt.contains("feat: add login"))
    }

    @Test(expected = IllegalStateException::class)
    fun buildMergePrompt_emptySelectionThrows() {
        val workspace = Workspace("ws-1", "Wand", "/repo", null, null, null, null)
        val overview = WorkspaceWorktreeOverview("ws-1", "/repo", "main", emptyList())
        buildWorkspaceMergeAgentPrompt(workspace, overview, emptySet())
    }

    // MARK: - 会话排序

    @Test
    fun orderWorkspaceSessions_byStartedAtAscending() {
        val sessions = listOf(
            sessionSummary("s3", "2026-03-01T00:00:00Z"),
            sessionSummary("s1", "2026-01-01T00:00:00Z"),
            sessionSummary("s2", "2026-02-01T00:00:00Z"),
        )
        val ordered = orderWorkspaceSessions(sessions)
        assertEquals(listOf("s1", "s2", "s3"), ordered.map { it.id })
    }

    @Test
    fun orderWorkspaceSessions_missingTimePreservesOrder() {
        val sessions = listOf(
            sessionSummary("s1", null),
            sessionSummary("s2", "2026-01-01T00:00:00Z"),
            sessionSummary("s3", null),
        )
        val ordered = orderWorkspaceSessions(sessions)
        // 有时间的排前面，无时间的保留相对顺序
        assertEquals("s2", ordered[0].id)
        assertEquals("s1", ordered[1].id)
        assertEquals("s3", ordered[2].id)
    }

    private fun sessionSummary(id: String, startedAt: String?): WorkspaceSessionSummary =
        WorkspaceSessionSummary(
            id = id,
            provider = "claude",
            sessionKind = "pty",
            runner = null,
            title = null,
            status = "idle",
            cwd = "/dir",
            startedAt = startedAt,
        )
}
