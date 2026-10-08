package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作窗口目标 → 创建请求体字段映射测试。
 *
 * WandApi.createWorkspaceTaskWindow 的请求体构建逻辑无法在 JVM 单元测试中直接测（依赖
 * OkHttp 网络层），这里验证 WorkspaceSessionTarget 的映射语义，确保：
 * - 六个 Provider 使用 PTY command（qoder→qodercli）
 * - Shell 使用 {shell:true}
 * - Provider 字段正确
 */
class WorkspaceTaskCreationTest {

    @Test
    fun employeeConversationAlwaysUsesStructuredEndpointAndIdentity() {
        val request = createEmployeeWorkspaceTaskWindowRequest(
            "employee-1", WorkspaceBinding("ws-1", "task-1", "/repo"), "  继续工作  ",
        )
        assertEquals("/api/structured-sessions", request.path)
        assertEquals("employee-1", request.body.getString("employeeId"))
        assertEquals("ws-1", request.body.getString("workspaceId"))
        assertEquals("task-1", request.body.getString("workspaceTaskId"))
        assertEquals("继续工作", request.body.getString("prompt"))
        assertTrue(request.body.getBoolean("respondImmediately"))
        assertFalse(request.body.has("provider"))
        assertFalse(request.body.has("command"))
    }

    @Test
    fun blankEmployeeConversationDoesNotSendPromptOrBindOldTask() {
        val request = createEmployeeWorkspaceTaskWindowRequest(
            "employee-1", WorkspaceBinding("ws-1", cwd = "/repo"),
        )
        assertEquals("/api/structured-sessions", request.path)
        assertEquals("employee-1", request.body.getString("employeeId"))
        assertEquals("ws-1", request.body.getString("workspaceId"))
        assertEquals("/repo", request.body.getString("cwd"))
        for (key in listOf("prompt", "respondImmediately", "workspaceTaskId", "provider", "model", "thinkingEffort")) {
            assertFalse(request.body.has(key))
        }
    }

    @Test
    fun taskCreationBodyCarriesExplicitWorktreeChoice() {
        val isolated = createWorkspaceTaskRequestBody("Task", "main", true)
        val shared = createWorkspaceTaskRequestBody("Task", null, false)
        val legacyDefault = createWorkspaceTaskRequestBody("Task", null, null)

        assertEquals("Task", isolated.getString("name"))
        assertEquals("main", isolated.getString("baseRef"))
        assertTrue(isolated.getBoolean("worktree"))
        assertEquals(false, shared.getBoolean("worktree"))
        assertTrue(!legacyDefault.has("worktree"))
    }

    @Test
    fun standaloneTaskBodyUsesOptionalDirectory() {
        val scratch = createStandaloneTaskRequestBody("随口问问", null, false)
        assertEquals("随口问问", scratch.getString("name"))
        assertEquals(false, scratch.getBoolean("worktree"))
        assertTrue(!scratch.has("cwd"))

        val mounted = createStandaloneTaskRequestBody("挂目录", "/tmp/work", true)
        assertEquals("/tmp/work", mounted.getString("cwd"))
        assertTrue(mounted.getBoolean("worktree"))
    }

    @Test
    fun taskCreationIncludesParentOnlyWhenSelected() {
        val projectChild = createWorkspaceTaskRequestBody("子任务", null, false, parentTaskId = "parent-id")
        val globalChild = createStandaloneTaskRequestBody("子任务", parentTaskId = "global-parent")
        val independent = createWorkspaceTaskRequestBody("独立任务", null, false)
        assertEquals("parent-id", projectChild.getString("parentTaskId"))
        assertEquals("global-parent", globalChild.getString("parentTaskId"))
        assertFalse(independent.has("parentTaskId"))
    }

    @Test
    fun allSixProvidersMapToBoundCommandBodies() {
        val cases = listOf(
            WorkspaceSessionTarget.Claude to "claude",
            WorkspaceSessionTarget.Codex to "codex",
            WorkspaceSessionTarget.OpenCode to "opencode",
            WorkspaceSessionTarget.Grok to "grok",
            WorkspaceSessionTarget.Qoder to "qodercli",
            WorkspaceSessionTarget.Pi to "pi",
        )
        val binding = WorkspaceBinding("ws-1", "task-1", "/worktree/path")

        for ((target, expectedCommand) in cases) {
            val pty = createWorkspaceTaskWindowRequest(target, binding, WorkspaceSessionKind.Pty)
            assertEquals("/api/commands", pty.path)
            assertEquals("command for ${target.raw}", expectedCommand, pty.body.getString("command"))
            assertEquals("provider for ${target.raw}", target.provider, pty.body.getString("provider"))
            assertEquals("ws-1", pty.body.getString("workspaceId"))
            assertEquals("task-1", pty.body.getString("workspaceTaskId"))
            assertEquals("/worktree/path", pty.body.getString("cwd"))

            val structured = createWorkspaceTaskWindowRequest(target, binding, WorkspaceSessionKind.Structured)
            assertEquals("/api/structured-sessions", structured.path)
            assertEquals(target.provider, structured.body.getString("provider"))
            assertEquals(structuredRunnerFor(target.provider!!), structured.body.getString("runner"))
            assertTrue(!structured.body.has("command"))
        }
    }

    @Test
    fun wandAgentSendsPiProviderWithSdkEngine() {
        // Wand Agent 与 Pi 共用 pi provider：provider 必须还是 pi，引擎单独告诉服务端。
        val request = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.WandAgent,
            WorkspaceBinding("ws-1", "task-1", "/worktree/path"),
            WorkspaceSessionKind.Structured,
        )
        assertEquals("/api/structured-sessions", request.path)
        assertEquals("pi", request.body.getString("provider"))
        assertEquals("sdk", request.body.getString("engine"))
        assertEquals("pi-cli-json", request.body.getString("runner"))

        // Pi CLI 不发明引擎字段：不传就是 CLI，老服务端也照旧。
        val cli = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Pi,
            WorkspaceBinding("ws-1", "task-1", "/worktree/path"),
            WorkspaceSessionKind.Structured,
        )
        assertEquals("pi", cli.body.getString("provider"))
        assertFalse(cli.body.has("engine"))
    }

    @Test
    fun wandAgentIsStructuredOnlyAndReportsPiAsItsProvider() {
        assertEquals("pi", WorkspaceSessionTarget.WandAgent.provider)
        assertTrue(WorkspaceSessionTarget.WandAgent.isSdk)
        assertFalse(WorkspaceSessionTarget.Pi.isSdk)
        assertEquals("Wand Agent", WorkspaceSessionTarget.WandAgent.label)
    }

    @Test
    fun shellTargetUsesBoundShellBodyWithoutProviderCommand() {
        val body = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Shell,
            WorkspaceBinding("ws-1", "task-1", "/worktree/path"),
        ).body

        assertTrue(body.getBoolean("shell"))
        assertTrue(!body.has("command"))
        assertTrue(!body.has("provider"))
        assertEquals("ws-1", body.getString("workspaceId"))
        assertEquals("task-1", body.getString("workspaceTaskId"))
        assertEquals("/worktree/path", body.getString("cwd"))
    }

    @Test
    fun chosenModelAndEffortApplyBeforeFirstPrompt() {
        val binding = WorkspaceBinding("ws-1", "task-1", "/repo")
        val structured = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Pi, binding, WorkspaceSessionKind.Structured,
            prompt = "完成这项任务", model = "pi-model", thinkingEffort = "max",
        ).body
        assertEquals("pi-model", structured.getString("model"))
        assertEquals("max", structured.getString("thinkingEffort"))
        assertEquals("完成这项任务", structured.getString("prompt"))
        assertTrue(structured.getBoolean("respondImmediately"))

        val pty = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Codex, binding, WorkspaceSessionKind.Pty,
            prompt = "先检查仓库", model = "codex-model", thinkingEffort = "deep",
        ).body
        assertEquals("codex-model", pty.getString("model"))
        assertEquals("deep", pty.getString("thinkingEffort"))
        assertEquals("先检查仓库", pty.getString("initialInput"))
        assertFalse(pty.has("respondImmediately"))

        val defaults = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Pi, binding, model = "default",
        ).body
        assertFalse(defaults.has("model"))
        assertFalse(defaults.has("thinkingEffort"))
        assertFalse(defaults.has("respondImmediately"))
    }

    @Test
    fun binding_carriesThreeFields() {
        val binding = WorkspaceBinding("ws-1", "task-1", "/worktree/path")
        assertEquals("ws-1", binding.workspaceId)
        assertEquals("task-1", binding.workspaceTaskId)
        assertEquals("/worktree/path", binding.cwd)
    }

    @Test
    fun ungroupedWindowOmitsTaskBinding() {
        val ungrouped = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Claude,
            WorkspaceBinding(cwd = "/repo"),
            WorkspaceSessionKind.Structured,
        )
        assertEquals("/repo", ungrouped.body.getString("cwd"))
        assertFalse(ungrouped.body.has("workspaceId"))
        assertFalse(ungrouped.body.has("workspaceTaskId"))

        val underProject = createWorkspaceTaskWindowRequest(
            WorkspaceSessionTarget.Shell,
            WorkspaceBinding(workspaceId = "ws-1", cwd = "/repo"),
        )
        assertTrue(underProject.body.getBoolean("shell"))
        assertEquals("ws-1", underProject.body.getString("workspaceId"))
        assertFalse(underProject.body.has("workspaceTaskId"))
    }

    @Test
    fun allTargets_covered() {
        // 确保没有遗漏新增的 provider
        val raws = WorkspaceSessionTarget.OPTIONS.map { it.raw }.toSet()
        assertTrue("claude" in raws)
        assertTrue("codex" in raws)
        assertTrue("opencode" in raws)
        assertTrue("grok" in raws)
        assertTrue("qoder" in raws)
        assertTrue("pi" in raws)
        assertTrue("gemini" in raws)
        assertTrue("shell" in raws)
        // Wand Agent（进程内 SDK）与 Pi CLI 是两条独立选项。
        assertTrue("wand-agent" in raws)
        assertEquals(9, raws.size)
    }
}
