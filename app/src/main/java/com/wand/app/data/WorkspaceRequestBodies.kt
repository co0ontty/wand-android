package com.wand.app.data

import org.json.JSONObject

internal fun createWorkspaceTaskRequestBody(
    name: String,
    baseRef: String?,
    worktree: Boolean?,
    cwd: String? = null,
    description: String? = null,
    parentTaskId: String? = null,
): JSONObject = JSONObject().put("name", name).apply {
    if (!baseRef.isNullOrBlank()) put("baseRef", baseRef)
    if (!cwd.isNullOrBlank()) put("cwd", cwd)
    if (worktree != null) put("worktree", worktree)
    // 首个会话的提示词：任务留空名时服务端据此总结标题，不再写“未命名任务”。
    if (!description.isNullOrBlank()) put("description", description)
    if (!parentTaskId.isNullOrBlank()) put("parentTaskId", parentTaskId)
}

internal fun createStandaloneTaskRequestBody(
    name: String,
    cwd: String? = null,
    worktree: Boolean? = null,
    description: String? = null,
    parentTaskId: String? = null,
): JSONObject = createWorkspaceTaskRequestBody(name, null, worktree, cwd, description, parentTaskId)

internal data class WorkspaceTaskWindowRequest(
    val path: String,
    val body: JSONObject,
)

internal fun createEmployeeWorkspaceTaskWindowRequest(
    employeeId: String,
    binding: WorkspaceBinding,
    prompt: String? = null,
    overrideCli: Boolean = false,
    target: WorkspaceSessionTarget = WorkspaceSessionTarget.Claude,
    model: String? = null,
    thinkingEffort: String? = null,
    mode: String? = null,
): WorkspaceTaskWindowRequest {
    require(!target.isShell) { "员工对话不能使用空白终端" }
    val body = JSONObject().put("kind", WorkspaceSessionKind.Structured.raw)
        .put("employeeId", employeeId).put("cwd", binding.cwd)
    binding.workspaceId?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("workspaceId", it) }
    binding.workspaceTaskId?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("workspaceTaskId", it) }
    prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("prompt", it) }
    if (overrideCli) {
        body.put("overrideCli", true)
        body.put("provider", target.provider)
        target.engine?.let { body.put("engine", it.raw) }
        model?.trim()?.takeIf { it.isNotEmpty() && it != "default" }?.let { body.put("model", it) }
        thinkingEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("thinkingEffort", it) }
        mode?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("mode", it) }
        body.put("runner", structuredRunnerFor(target.provider.orEmpty()))
    }
    return WorkspaceTaskWindowRequest("/api/sessions", body)
}

internal fun structuredRunnerFor(provider: String): String =
    requireNotNull(WandProvider.fromId(provider)) { "不支持的执行工具：$provider" }.structuredRunner

internal fun createWorkspaceTaskWindowRequest(
    target: WorkspaceSessionTarget,
    binding: WorkspaceBinding,
    kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
    prompt: String? = null,
    model: String? = null,
    thinkingEffort: String? = null,
    mode: String? = null,
    overrideCli: Boolean = false,
): WorkspaceTaskWindowRequest {
    val body = JSONObject().put("cwd", binding.cwd).apply {
        binding.workspaceId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("workspaceId", it) }
        binding.workspaceTaskId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("workspaceTaskId", it) }
    }
    if (target.isShell) {
        body.put("kind", WorkspaceSessionKind.Pty.raw).put("shell", true)
        mode?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("mode", it) }
        return WorkspaceTaskWindowRequest("/api/sessions", body)
    }
    // Wand Agent 与 Pi 共用 pi provider：请求里的 provider 必须是 pi，引擎单独传。
    val provider = target.provider.orEmpty()
    // The in-process Wand Agent harness cannot execute as a PTY CLI.
    val sessionKind = if (target.engine == WandAgentEngine.Sdk) WorkspaceSessionKind.Structured else kind
    body.put("kind", sessionKind.raw).put("provider", provider)
    if (overrideCli) body.put("overrideCli", true)
    // 引擎是第二个维度：Wand Agent 与 Pi 共用 pi provider，必须显式告诉服务端走进程内 SDK。
    target.engine?.let { engine -> body.put("engine", engine.raw) }
    model?.trim()?.takeIf { it.isNotEmpty() && it != "default" }?.let { body.put("model", it) }
    thinkingEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("thinkingEffort", it) }
    mode?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("mode", it) }
    val initialPrompt = prompt?.trim()?.takeIf { it.isNotEmpty() }
    if (sessionKind == WorkspaceSessionKind.Structured) {
        initialPrompt?.let { body.put("prompt", it) }
        body.put("runner", structuredRunnerFor(provider))
        return WorkspaceTaskWindowRequest("/api/sessions", body)
    }
    initialPrompt?.let { body.put("initialInput", it) }
    body.put("command", WandProvider.cliCommandFor(provider))
    return WorkspaceTaskWindowRequest("/api/sessions", body)
}
