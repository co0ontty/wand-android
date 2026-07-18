package com.wand.app.data

/**
 * 原生客户端消费的类型化会话事件。
 *
 * WebSocket 的 type 字符串与 WsData nullable 超集只属于 transport implementation；
 * ChatStore、SessionWatcher 等调用方只依赖这里的 domain interface。
 */
sealed interface SessionEvent {
    val sessionId: String?

    data class Initialized(
        override val sessionId: String?,
        val snapshot: SessionSnapshot?,
        val messages: MessageUpdate.Full?,
        val responding: Boolean?,
        val changes: SessionChanges,
    ) : SessionEvent

    data class Output(
        override val sessionId: String?,
        val messages: MessageUpdate,
        val responding: Boolean?,
        val changes: SessionChanges,
    ) : SessionEvent

    data class StatusChanged(
        override val sessionId: String?,
        val permissionRequest: PermissionRequestInfo?,
        val responding: Boolean?,
        val changes: SessionChanges,
    ) : SessionEvent

    data class TaskChanged(
        override val sessionId: String?,
        val title: String?,
    ) : SessionEvent

    data class Ended(
        override val sessionId: String?,
        val messages: MessageUpdate.Full?,
        val status: String,
        val exitCode: Int?,
        val changes: SessionChanges,
    ) : SessionEvent

    data class Started(override val sessionId: String?) : SessionEvent

    data class Error(
        override val sessionId: String?,
        val message: String,
    ) : SessionEvent
}

/** 消息变化只有三种合法形状；调用方不再解释 incremental/messageCount 的组合。 */
sealed interface MessageUpdate {
    data object None : MessageUpdate

    data class Full(
        val messages: List<ConversationTurn>,
        val offset: Int?,
        val total: Int?,
    ) : MessageUpdate

    data class Incremental(
        val message: ConversationTurn,
        val expectedCount: Int,
    ) : MessageUpdate
}

/** 多种事件都可能携带的会话字段变化。null 表示服务端没有更新该字段。 */
data class SessionChanges(
    val status: String? = null,
    val archived: Boolean? = null,
    val summary: String? = null,
    val title: String? = null,
    val description: String? = null,
    val titleGenerating: Boolean? = null,
    val queuedMessages: List<String>? = null,
    val pendingEscalation: EscalationRequest? = null,
    val permissionBlocked: Boolean? = null,
    val currentTaskTitle: String? = null,
    val selectedModel: String? = null,
    val thinkingEffort: String? = null,
    val mode: String? = null,
)

/** transport packet → domain event。保持 internal，防止 WsData 超集重新泄漏给调用方。 */
internal fun WsIncoming.toSessionEvent(): SessionEvent? {
    val payload = data
    return when (type) {
        "init" -> payload?.let {
            SessionEvent.Initialized(
                sessionId = sessionId,
                snapshot = it.toSnapshot(),
                messages = it.messages?.let { turns ->
                    MessageUpdate.Full(turns, it.messageOffset, it.messageTotal)
                },
                responding = it.structuredState?.let { state -> state.inFlight ?: false },
                changes = it.toChanges(),
            )
        }

        "output" -> payload?.let {
            SessionEvent.Output(
                sessionId = sessionId,
                messages = it.toMessageUpdate(),
                responding = it.structuredState?.inFlight ?: it.isResponding,
                changes = it.toChanges(),
            )
        }

        "status" -> payload?.let {
            SessionEvent.StatusChanged(
                sessionId = sessionId,
                permissionRequest = it.permissionRequest,
                responding = it.structuredState?.inFlight,
                changes = it.toChanges(),
            )
        }

        "task" -> SessionEvent.TaskChanged(sessionId, payload?.taskTitle)

        "ended" -> SessionEvent.Ended(
            sessionId = sessionId,
            messages = payload?.messages?.let {
                MessageUpdate.Full(it, payload.messageOffset, payload.messageTotal)
            },
            status = payload?.status ?: "exited",
            exitCode = payload?.exitCode,
            changes = payload?.toChanges() ?: SessionChanges(),
        )

        "started" -> SessionEvent.Started(sessionId)
        "error" -> error?.takeIf(String::isNotEmpty)?.let { SessionEvent.Error(sessionId, it) }
        else -> null
    }
}

private fun WsData.toMessageUpdate(): MessageUpdate = when {
    messages != null -> MessageUpdate.Full(messages, messageOffset, messageTotal)
    incremental == true && lastMessage != null -> MessageUpdate.Incremental(lastMessage, messageCount ?: 0)
    else -> MessageUpdate.None
}

private fun WsData.toChanges() = SessionChanges(
    status = status,
    archived = archived,
    summary = summary,
    title = title,
    description = description,
    titleGenerating = titleGenerating,
    queuedMessages = queuedMessages,
    pendingEscalation = pendingEscalation,
    permissionBlocked = permissionBlocked,
    currentTaskTitle = currentTaskTitle,
    selectedModel = selectedModel,
    thinkingEffort = thinkingEffort,
    mode = mode,
)
