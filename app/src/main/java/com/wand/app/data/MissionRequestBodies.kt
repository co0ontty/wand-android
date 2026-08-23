package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

internal fun createMissionRequestBody(
    title: String?,
    prompt: String,
    cwd: String,
    providers: List<String>,
    taskId: String?,
    baseRef: String?,
    sharedDirectories: List<String>,
    copyPaths: List<String>,
): JSONObject = JSONObject()
    .put("prompt", prompt)
    .put("cwd", cwd)
    .put("providers", JSONArray(providers))
    .put("sharedDirectories", JSONArray(sharedDirectories))
    .put("copyPaths", JSONArray(copyPaths))
    .apply {
        if (!title.isNullOrBlank()) put("title", title)
        if (!taskId.isNullOrBlank()) put("taskId", taskId)
        if (!baseRef.isNullOrBlank()) put("baseRef", baseRef)
    }
