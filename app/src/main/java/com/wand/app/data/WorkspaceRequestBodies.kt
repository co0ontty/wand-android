package com.wand.app.data

import org.json.JSONObject

internal fun createWorkspaceTaskRequestBody(
    name: String,
    baseRef: String?,
    worktree: Boolean?,
): JSONObject = JSONObject().put("name", name).apply {
    if (!baseRef.isNullOrBlank()) put("baseRef", baseRef)
    if (worktree != null) put("worktree", worktree)
}

internal fun createWorkspaceTaskWindowRequestBody(
    target: WorkspaceSessionTarget,
    binding: WorkspaceBinding,
): JSONObject = JSONObject()
    .put("cwd", binding.cwd)
    .put("workspaceId", binding.workspaceId)
    .put("workspaceTaskId", binding.workspaceTaskId)
    .apply {
        if (target.isShell) {
            put("shell", true)
        } else {
            val provider = target.raw
            val command = if (provider == "qoder") "qodercli" else provider
            put("command", command)
            put("provider", provider)
        }
    }
