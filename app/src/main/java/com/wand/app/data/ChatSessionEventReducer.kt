package com.wand.app.data

/** Compose-independent realtime state. The interface is the test surface for event sequences. */
data class ChatSessionEventState(
    val messages: List<ConversationTurn> = emptyList(),
    val loadedOffset: Int = 0,
    val messageTotal: Int = 0,
    val status: String = "running",
    val isResponding: Boolean = false,
    val queuedMessages: List<String> = emptyList(),
    val pendingEscalation: EscalationRequest? = null,
    val legacyPermissionPrompt: PermissionRequestInfo? = null,
    val permissionBlocked: Boolean = false,
    val providerCliActive: Boolean? = null,
    val providerCliExitCode: Int? = null,
    val currentTaskTitle: String? = null,
    val snapshot: SessionSnapshot? = null,
    val selectedModel: String? = null,
    val thinkingEffort: String = "off",
    val mode: String = "default",
    val confirmedModel: String? = null,
    val confirmedThinkingEffort: String = "off",
    val confirmedMode: String = "default",
    val errorMessage: String? = null,
    val initialized: Boolean = false,
)

data class PendingSessionSettings(
    val model: Boolean = false,
    val thinkingEffort: Boolean = false,
    val mode: Boolean = false,
)

object ChatSessionEventReducer {
    fun applySnapshot(
        current: ChatSessionEventState,
        snapshot: SessionSnapshot,
        pending: PendingSessionSettings = PendingSessionSettings(),
    ): ChatSessionEventState {
        val previousError = current.snapshot?.structuredState?.lastError
        var next = current.copy(snapshot = snapshot)
        snapshot.messages?.let {
            next = applyMessages(
                next,
                MessageUpdate.Full(it, snapshot.messageOffset, snapshot.messageTotal),
            )
        }
        next = next.copy(
            status = snapshot.status ?: next.status,
            isResponding = snapshot.isResponding,
            queuedMessages = snapshot.queuedMessages ?: emptyList(),
            pendingEscalation = snapshot.pendingEscalation,
            permissionBlocked = snapshot.permissionBlocked ?: (snapshot.pendingEscalation != null),
            providerCliActive = snapshot.providerCliActive,
            providerCliExitCode = snapshot.providerCliExitCode,
            currentTaskTitle = snapshot.currentTaskTitle,
            confirmedModel = snapshot.selectedModel,
            selectedModel = if (pending.model) next.selectedModel else snapshot.selectedModel,
            confirmedThinkingEffort = snapshot.thinkingEffort ?: "off",
            thinkingEffort = if (pending.thinkingEffort) next.thinkingEffort else snapshot.thinkingEffort ?: "off",
        )
        if (snapshot.mode != null) {
            next = next.copy(
                confirmedMode = snapshot.mode,
                mode = if (pending.mode) next.mode else snapshot.mode,
            )
        }
        if (!next.permissionBlocked || snapshot.pendingEscalation != null) {
            next = next.copy(legacyPermissionPrompt = null)
        }
        val incomingError = snapshot.structuredState?.lastError?.trim()?.takeIf { it.isNotEmpty() }
        if (incomingError != null && incomingError != previousError) {
            next = next.copy(errorMessage = incomingError)
        }
        return next
    }

    fun reduce(
        current: ChatSessionEventState,
        event: SessionEvent,
        pending: PendingSessionSettings = PendingSessionSettings(),
    ): ChatSessionEventState {
        var next = current
        when (event) {
            is SessionEvent.Initialized -> {
                next = applyMessages(next, event.messages)
                next = applyChanges(next, event.changes, pending)
                event.responding?.let { next = next.copy(isResponding = it) }
                if (next.snapshot == null && event.snapshot != null) {
                    next = next.copy(snapshot = event.snapshot)
                }
                next = next.copy(initialized = true)
            }

            is SessionEvent.Output -> {
                next = applyMessages(next, event.messages)
                next = applyChanges(next, event.changes, pending)
                event.responding?.let { next = next.copy(isResponding = it) }
            }

            is SessionEvent.StatusChanged -> {
                next = applyChanges(next, event.changes, pending)
                event.responding?.let { next = next.copy(isResponding = it) }
                if (event.permissionRequest != null && next.pendingEscalation == null) {
                    next = next.copy(
                        legacyPermissionPrompt = event.permissionRequest,
                        permissionBlocked = true,
                    )
                }
            }

            is SessionEvent.TaskChanged -> next = next.copy(currentTaskTitle = event.title)

            is SessionEvent.Ended -> {
                next = applyMessages(next, event.messages)
                next = applyChanges(next, event.changes, pending)
                next = next.copy(status = event.status, isResponding = false)
            }

            is SessionEvent.Error -> next = next.copy(errorMessage = event.message)
            is SessionEvent.Started -> Unit
        }
        return next
    }

    private fun applyMessages(
        current: ChatSessionEventState,
        update: MessageUpdate?,
    ): ChatSessionEventState = when (update) {
        null, MessageUpdate.None -> current
        is MessageUpdate.Full -> applyFullMessages(current, update)
        is MessageUpdate.Incremental -> applyIncrementalMessage(current, update)
    }

    private fun applyFullMessages(
        current: ChatSessionEventState,
        update: MessageUpdate.Full,
    ): ChatSessionEventState {
        val incoming = update.messages
        val snapOffset = update.offset ?: 0
        val snapTotal = update.total ?: maxOf(snapOffset + incoming.size, incoming.size)
        if (incoming.isEmpty() && current.messages.isNotEmpty() && snapTotal == 0) return current

        val (messages, offset) = when {
            current.messages.isEmpty() -> incoming to snapOffset
            current.loadedOffset <= snapOffset -> {
                val keep = (snapOffset - current.loadedOffset).coerceIn(0, current.messages.size)
                (current.messages.subList(0, keep) + incoming) to current.loadedOffset
            }
            else -> incoming to snapOffset
        }
        return current.copy(
            messages = messages,
            loadedOffset = offset,
            messageTotal = maxOf(snapTotal, offset + messages.size),
        )
    }

    private fun applyIncrementalMessage(
        current: ChatSessionEventState,
        update: MessageUpdate.Incremental,
    ): ChatSessionEventState {
        val expected = update.expectedCount
        val last = current.messages.lastOrNull()
        val messages = when {
            last != null && last.role == update.message.role -> current.messages.dropLast(1) + update.message
            current.loadedOffset + current.messages.size < expected || expected == 0 -> current.messages + update.message
            else -> current.messages
        }
        return current.copy(
            messages = messages,
            messageTotal = if (expected > 0) maxOf(current.messageTotal, expected) else current.messageTotal,
        )
    }

    private fun applyChanges(
        current: ChatSessionEventState,
        changes: SessionChanges,
        pending: PendingSessionSettings,
    ): ChatSessionEventState {
        var next = current
        changes.status?.let { next = next.copy(status = it) }
        changes.queuedMessages?.let { next = next.copy(queuedMessages = it) }
        changes.pendingEscalation?.let {
            next = next.copy(pendingEscalation = it, legacyPermissionPrompt = null)
        }
        changes.permissionBlocked?.let { blocked ->
            next = if (blocked) {
                next.copy(permissionBlocked = true)
            } else {
                next.copy(
                    permissionBlocked = false,
                    pendingEscalation = null,
                    legacyPermissionPrompt = null,
                )
            }
        }
        changes.providerCliActive?.let { active ->
            next = next.copy(
                providerCliActive = active,
                isResponding = if (active) next.isResponding else false,
            )
        }
        changes.providerCliExitCode?.let { next = next.copy(providerCliExitCode = it) }
        changes.currentTaskTitle?.let { next = next.copy(currentTaskTitle = it) }
        if (changes.title != null || changes.description != null || changes.summary != null
            || changes.titleGenerating != null || changes.providerCliActive != null
            || changes.providerCliExitCode != null
        ) {
            next.snapshot?.let { snapshot ->
                next = next.copy(snapshot = snapshot.copy(
                    title = changes.title ?: snapshot.title,
                    description = changes.description ?: snapshot.description,
                    summary = changes.summary ?: snapshot.summary,
                    titleGenerating = changes.titleGenerating ?: snapshot.titleGenerating,
                    providerCliActive = changes.providerCliActive ?: snapshot.providerCliActive,
                    providerCliExitCode = changes.providerCliExitCode ?: snapshot.providerCliExitCode,
                ))
            }
        }
        changes.selectedModel?.let {
            next = next.copy(
                confirmedModel = it,
                selectedModel = if (pending.model) next.selectedModel else it,
            )
        }
        changes.thinkingEffort?.let {
            next = next.copy(
                confirmedThinkingEffort = it,
                thinkingEffort = if (pending.thinkingEffort) next.thinkingEffort else it,
            )
        }
        changes.mode?.let {
            next = next.copy(
                confirmedMode = it,
                mode = if (pending.mode) next.mode else it,
            )
        }
        return next
    }
}
