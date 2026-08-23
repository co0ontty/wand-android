package com.wand.app.data

import org.junit.Assert.assertEquals
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
            assertEquals("provider for ${target.raw}", target.raw, pty.body.getString("provider"))
            assertEquals("ws-1", pty.body.getString("workspaceId"))
            assertEquals("task-1", pty.body.getString("workspaceTaskId"))
            assertEquals("/worktree/path", pty.body.getString("cwd"))

            val structured = createWorkspaceTaskWindowRequest(target, binding, WorkspaceSessionKind.Structured)
            assertEquals("/api/structured-sessions", structured.path)
            assertEquals(target.raw, structured.body.getString("provider"))
            assertEquals(structuredRunnerFor(target.raw), structured.body.getString("runner"))
            assertTrue(!structured.body.has("command"))
        }
    }

    @Test
    fun shellTargetUsesBoundShellBodyWithoutProviderCommand() {
        val body = createWorkspaceTaskWindowRequestBody(
            WorkspaceSessionTarget.Shell,
            WorkspaceBinding("ws-1", "task-1", "/worktree/path"),
        )

        assertTrue(body.getBoolean("shell"))
        assertTrue(!body.has("command"))
        assertTrue(!body.has("provider"))
        assertEquals("ws-1", body.getString("workspaceId"))
        assertEquals("task-1", body.getString("workspaceTaskId"))
        assertEquals("/worktree/path", body.getString("cwd"))
    }

    @Test
    fun binding_carriesThreeFields() {
        val binding = WorkspaceBinding("ws-1", "task-1", "/worktree/path")
        assertEquals("ws-1", binding.workspaceId)
        assertEquals("task-1", binding.workspaceTaskId)
        assertEquals("/worktree/path", binding.cwd)
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
        assertTrue("shell" in raws)
        assertEquals(7, raws.size)
    }
}
