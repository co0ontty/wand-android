package com.wand.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** One API instance owns one server's task invalidations; never leak across server switches. */
interface TaskChangeSource {
    val taskChanges: Flow<Unit> get() = emptyFlow()
}

internal fun changesTaskHierarchy(method: String, path: String): Boolean {
    if (method == "GET" || path.substringBefore('?').endsWith("/layout")) return false
    return path == "/api/tasks" || path.startsWith("/api/workspace-tasks/") ||
        path == "/api/workspaces" || path.startsWith("/api/workspaces/") ||
        path == "/api/wand-tasks" || path.startsWith("/api/wand-tasks/") ||
        path == "/api/commands" || path == "/api/structured-sessions" ||
        path == "/api/sessions/batch-delete"
}
