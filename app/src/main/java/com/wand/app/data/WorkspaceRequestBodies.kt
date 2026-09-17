package com.wand.app.data

import org.json.JSONObject

internal fun createWorkspaceTaskRequestBody(
    name: String,
    baseRef: String?,
    worktree: Boolean?,
    cwd: String? = null,
    description: String? = null,
): JSONObject = JSONObject().put("name", name).apply {
    if (!baseRef.isNullOrBlank()) put("baseRef", baseRef)
    if (!cwd.isNullOrBlank()) put("cwd", cwd)
    if (worktree != null) put("worktree", worktree)
    // 首个会话的提示词：任务留空名时服务端据此总结标题，不再写“未命名任务”。
    if (!description.isNullOrBlank()) put("description", description)
}

internal fun createStandaloneTaskRequestBody(
    name: String,
    cwd: String? = null,
    worktree: Boolean? = null,
    description: String? = null,
): JSONObject = createWorkspaceTaskRequestBody(name, null, worktree, cwd, description)

internal data class WorkspaceTaskWindowRequest(
    val path: String,
    val body: JSONObject,
)

internal fun structuredRunnerFor(provider: String): String =
    WandProvider.fromId(provider)?.structuredRunner ?: WandProvider.Claude.structuredRunner

internal fun createWorkspaceTaskWindowRequest(
    target: WorkspaceSessionTarget,
    binding: WorkspaceBinding,
    kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
    prompt: String? = null,
): WorkspaceTaskWindowRequest {
    val body = JSONObject().put("cwd", binding.cwd).apply {
        binding.workspaceId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("workspaceId", it) }
        binding.workspaceTaskId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("workspaceTaskId", it) }
    }
    if (target.isShell) {
        body.put("shell", true)
        return WorkspaceTaskWindowRequest("/api/commands", body)
    }
    val provider = target.raw
    body.put("provider", provider)
    val initialPrompt = prompt?.trim()?.takeIf { it.isNotEmpty() }
    if (kind == WorkspaceSessionKind.Structured) {
        initialPrompt?.let { body.put("prompt", it) }
        body.put("runner", structuredRunnerFor(provider))
        return WorkspaceTaskWindowRequest("/api/structured-sessions", body)
    }
    initialPrompt?.let { body.put("initialInput", it) }
    body.put("command", WandProvider.cliCommandFor(provider))
    return WorkspaceTaskWindowRequest("/api/commands", body)
}
