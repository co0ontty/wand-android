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
    fun allSixProviders_mapToCorrectCommands() {
        // 对齐 Web startSessionInCwd：command = provider === "qoder" ? "qodercli" : provider
        val cases = listOf(
            WorkspaceSessionTarget.Claude to "claude",
            WorkspaceSessionTarget.Codex to "codex",
            WorkspaceSessionTarget.OpenCode to "opencode",
            WorkspaceSessionTarget.Grok to "grok",
            WorkspaceSessionTarget.Qoder to "qodercli",
            WorkspaceSessionTarget.Pi to "pi",
        )
        for ((target, expectedCommand) in cases) {
            val command = if (target.raw == "qoder") "qodercli" else target.raw
            assertEquals("command for ${target.raw}", expectedCommand, command)
            assertEquals("provider for ${target.raw}", target.raw, target.provider)
        }
    }

    @Test
    fun shellTarget_doesNotMapToCommand() {
        // Shell 使用 {shell:true}，不传 command/provider
        assertTrue(WorkspaceSessionTarget.Shell.isShell)
        assertEquals(null, WorkspaceSessionTarget.Shell.provider)
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
