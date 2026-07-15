package com.wand.app

data class NotificationVisibility(
    val appInForeground: Boolean,
    val activeChatSessionId: String?,
)

data class SessionNotification(
    val title: String,
    val body: String,
    val tag: String,
)

/**
 * 会话提醒的纯策略层：集中管理可见性抑制、文案和节流，不依赖 Android 通知 API。
 * SessionWatcher 只负责维护会话状态，并把本类的决策交给 NotificationHelper 投递。
 */
class SessionNotificationPolicy(private val nowMillis: () -> Long) {
    private val history = HashMap<String, Long>()

    fun taskProgress(
        sessionId: String,
        label: String,
        title: String?,
        visibility: NotificationVisibility,
    ): SessionNotification? {
        if (title.isNullOrEmpty() || visibility.appInForeground) return null
        if (throttled("task:$sessionId:$title", TASK_THROTTLE_MS)) return null
        return SessionNotification(
            title = "任务进行中",
            body = "$label\n$title",
            tag = "task:wand-task-$sessionId",
        )
    }

    fun permissionRequired(
        sessionId: String,
        label: String,
        detail: String,
        target: String?,
        visibility: NotificationVisibility,
    ): SessionNotification? {
        if (!shouldDisturb(sessionId, visibility)) return null
        if (throttled("perm:$sessionId", PERMISSION_THROTTLE_MS)) return null
        return SessionNotification(
            title = "需要你的授权",
            body = "$label\n$detail" + if (target.isNullOrEmpty()) "" else " · $target",
            tag = "permission:wand-perm-$sessionId",
        )
    }

    fun sessionEnded(
        sessionId: String,
        label: String,
        assistantText: String,
        isError: Boolean,
        visibility: NotificationVisibility,
    ): SessionNotification? {
        if (!shouldDisturb(sessionId, visibility)) return null
        if (throttled("ended:$sessionId", ENDED_THROTTLE_MS)) return null
        return SessionNotification(
            title = if (isError) "任务异常结束" else "任务已完成",
            body = label + if (!isError && assistantText.isNotEmpty()) "\n$assistantText" else "",
            tag = "task-ended:wand-ended-$sessionId",
        )
    }

    fun responseCompleted(
        sessionId: String,
        label: String,
        assistantText: String,
        permissionBlocked: Boolean,
        status: String,
        visibility: NotificationVisibility,
    ): SessionNotification? {
        if (permissionBlocked || status in ENDED_STATUSES) return null
        if (!shouldDisturb(sessionId, visibility)) return null
        if (throttled("turn:$sessionId", TURN_THROTTLE_MS)) return null
        return SessionNotification(
            title = "回复完成",
            body = label + if (assistantText.isNotEmpty()) "\n$assistantText" else "",
            tag = "task-ended:wand-turn-$sessionId",
        )
    }

    fun reset() {
        history.clear()
    }

    private fun shouldDisturb(sessionId: String, visibility: NotificationVisibility): Boolean =
        !(visibility.appInForeground && visibility.activeChatSessionId == sessionId)

    private fun throttled(key: String, minIntervalMs: Long): Boolean {
        val now = nowMillis()
        val last = history[key]
        if (last != null && now - last < minIntervalMs) return true
        history[key] = now
        if (history.size > MAX_HISTORY_SIZE) {
            history.entries.removeAll { now - it.value > TASK_THROTTLE_MS }
        }
        return false
    }

    private companion object {
        const val ENDED_THROTTLE_MS = 10_000L
        const val TURN_THROTTLE_MS = 10_000L
        const val TASK_THROTTLE_MS = 90_000L
        const val PERMISSION_THROTTLE_MS = 60_000L
        const val MAX_HISTORY_SIZE = 64
        val ENDED_STATUSES = setOf("exited", "failed", "stopped")
    }
}
