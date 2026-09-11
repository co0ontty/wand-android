package com.wand.app.data

import org.json.JSONObject

internal fun createWorkspaceTaskRequestBody(
    name: String,
    baseRef: String?,
    worktree: Boolean?,
    cwd: String? = null,
): JSONObject = JSONObject().put("name", name).apply {
    if (!baseRef.isNullOrBlank()) put("baseRef", baseRef)
    if (!cwd.isNullOrBlank()) put("cwd", cwd)
    if (worktree != null) put("worktree", worktree)
}

internal fun createStandaloneTaskRequestBody(
    name: String,
    cwd: String? = null,
    worktree: Boolean? = null,
): JSONObject = createWorkspaceTaskRequestBody(name, null, worktree, cwd)

internal data class WorkspaceTaskWindowRequest(
    val path: String,
    val body: JSONObject,
)

internal fun structuredRunnerFor(provider: String): String = when (provider) {
    "codex" -> "codex-cli-exec"
    "opencode" -> "opencode-cli-run"
    "grok" -> "grok-cli-headless"
    "qoder" -> "qoder-cli-print"
    "pi" -> "pi-cli-json"
    else -> "claude-cli-print"
}

internal fun createWorkspaceTaskWindowRequest(
    target: WorkspaceSessionTarget,
    binding: WorkspaceBinding,
    kind: WorkspaceSessionKind = WorkspaceSessionKind.Structured,
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
    if (kind == WorkspaceSessionKind.Structured) {
        body.put("runner", structuredRunnerFor(provider))
        return WorkspaceTaskWindowRequest("/api/structured-sessions", body)
    }
    body.put("command", if (provider == "qoder") "qodercli" else provider)
    return WorkspaceTaskWindowRequest("/api/commands", body)
}

internal fun createWorkspaceTaskWindowRequestBody(
    target: WorkspaceSessionTarget,
    binding: WorkspaceBinding,
    kind: WorkspaceSessionKind = WorkspaceSessionKind.Pty,
): JSONObject = createWorkspaceTaskWindowRequest(target, binding, kind).body
