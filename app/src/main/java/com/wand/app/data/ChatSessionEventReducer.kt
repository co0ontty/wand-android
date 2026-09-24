package com.wand.app.data

import java.time.Instant

/** Compose-independent realtime state. The interface is the test surface for event sequences. */
data class ChatSessionEventState(
    val messages: List<ConversationTurn> = emptyList(),
    val loadedOffset: Int = 0,
    val messageTotal: Int = 0,
    /** 块级窗口游标：messages[0] 被切掉的头部块数 / 这条 turn 的完整块数。 */
    val leadingBlockOffset: Int = 0,
    val leadingBlockTotal: Int = 0,
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
                MessageUpdate.Full(
                    it,
                    snapshot.messageOffset,
                    snapshot.messageTotal,
                    snapshot.leadingBlockOffset,
                    snapshot.leadingBlockTotal,
                ),
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
        val snapOffset = (update.offset ?: 0).coerceAtLeast(0)
        val snapTotal = update.total ?: maxOf(snapOffset + incoming.size, incoming.size)
        val incomingLeadingOffset = (update.leadingOffset ?: 0).coerceAtLeast(0)
        val incomingLeadingTotal = update.leadingTotal ?: (incoming.firstOrNull()?.content?.size ?: 0)
        // 空快照不清屏：终端 ended 事件常带空 messages/0 总数，不能把可见历史干掉。
        if (incoming.isEmpty() && current.messages.isNotEmpty() && snapTotal == 0) return current

        if (current.messages.isEmpty()) {
            return current.copy(
                messages = incoming,
                loadedOffset = snapOffset,
                messageTotal = maxOf(snapTotal, snapOffset + incoming.size),
                leadingBlockOffset = incomingLeadingOffset,
                leadingBlockTotal = incomingLeadingTotal,
            )
        }

        // leadingBlockOffset 只能描述 messages[0]。新窗口从更晚 turn 开始且该 turn 自身
        // 被截断时，保留本地旧前缀会让游标指向错误的 turn，并永久漏掉新窗口的头部块；
        // 此时采用新窗口，用户翻完它的块后仍可继续按 turn 加载旧前缀。
        if (snapOffset > current.loadedOffset && incomingLeadingOffset > 0) {
            return current.copy(
                messages = incoming,
                loadedOffset = snapOffset,
                messageTotal = maxOf(snapTotal, snapOffset + incoming.size),
                leadingBlockOffset = incomingLeadingOffset,
                leadingBlockTotal = incomingLeadingTotal,
            )
        }

        val currentEnd = current.loadedOffset + current.messages.size
        val snapEnd = snapOffset + incoming.size
        if (snapOffset > currentEnd || current.loadedOffset > snapEnd) {
            return current.copy(
                messages = incoming,
                loadedOffset = snapOffset,
                messageTotal = maxOf(snapTotal, snapOffset + incoming.size),
                leadingBlockOffset = incomingLeadingOffset,
                leadingBlockTotal = incomingLeadingTotal,
            )
        }

        val mergedOffset = minOf(current.loadedOffset, snapOffset)
        val mergedEnd = maxOf(currentEnd, snapEnd)
        var resolvedLeadingOffset = if (mergedOffset == current.loadedOffset) {
            current.leadingBlockOffset
        } else {
            incomingLeadingOffset
        }
        var resolvedLeadingTotal = if (mergedOffset == current.loadedOffset) {
            current.leadingBlockTotal
        } else {
            incomingLeadingTotal
        }
        val merged = ArrayList<ConversationTurn>(mergedEnd - mergedOffset)
        for (absoluteIndex in mergedOffset until mergedEnd) {
            val local = current.messages.getOrNull(absoluteIndex - current.loadedOffset)
                ?.takeIf { absoluteIndex in current.loadedOffset until currentEnd }
            val replacement = incoming.getOrNull(absoluteIndex - snapOffset)
                ?.takeIf { absoluteIndex in snapOffset until snapEnd }
            if (local != null && replacement != null) {
                val leadingMerge = if (absoluteIndex == mergedOffset && current.loadedOffset == snapOffset) {
                    mergeLeadingAssistantTurn(
                        local = local,
                        localOffset = current.leadingBlockOffset,
                        localTotal = current.leadingBlockTotal,
                        incoming = replacement,
                        incomingOffset = incomingLeadingOffset,
                        incomingTotal = incomingLeadingTotal,
                    )
                } else {
                    null
                }
                if (leadingMerge != null) {
                    merged += leadingMerge.turn
                    resolvedLeadingOffset = leadingMerge.blockOffset
                    resolvedLeadingTotal = leadingMerge.blockTotal
                } else {
                    val keepLocal = shouldKeepLocalTurn(local, replacement)
                    merged += mergeOverlappingTurns(local, replacement)
                    if (absoluteIndex == mergedOffset && current.loadedOffset == snapOffset) {
                        resolvedLeadingOffset = if (keepLocal) current.leadingBlockOffset else incomingLeadingOffset
                        resolvedLeadingTotal = if (keepLocal) current.leadingBlockTotal else incomingLeadingTotal
                    }
                }
            } else {
                val chosen = replacement ?: local
                if (chosen != null) merged += chosen
            }
        }
        return current.copy(
            messages = merged,
            loadedOffset = mergedOffset,
            messageTotal = maxOf(snapTotal, mergedOffset + merged.size),
            leadingBlockOffset = resolvedLeadingOffset,
            leadingBlockTotal = resolvedLeadingTotal,
        )
    }

    private fun applyIncrementalMessage(
        current: ChatSessionEventState,
        update: MessageUpdate.Incremental,
    ): ChatSessionEventState {
        val expected = update.expectedCount
        val last = current.messages.lastOrNull()
        val incoming = stampLiveTurnTime(
            mergeConversationTurnTimes(last?.takeIf { it.role == update.message.role }, update.message),
        )
        var leadingBlockOffset = current.leadingBlockOffset
        var leadingBlockTotal = current.leadingBlockTotal
        val messages = when {
            last != null && last.role == update.message.role -> {
                val keepLocal = shouldKeepLocalTurn(last, incoming)
                if (current.messages.size == 1 && !keepLocal) {
                    leadingBlockOffset = 0
                    leadingBlockTotal = incoming.content.size
                }
                current.messages.dropLast(1) + mergeOverlappingTurns(last, incoming)
            }
            current.loadedOffset + current.messages.size < expected || expected == 0 ->
                current.messages + incoming
            else -> current.messages
        }
        return current.copy(
            messages = messages,
            leadingBlockOffset = leadingBlockOffset,
            leadingBlockTotal = leadingBlockTotal,
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
            next = next.copy(providerCliActive = active)
        }
        changes.providerCliExitCode?.let { next = next.copy(providerCliExitCode = it) }
        changes.currentTaskTitle?.let { next = next.copy(currentTaskTitle = it) }
        if (changes.title != null || changes.description != null || changes.summary != null
            || changes.titleGenerating != null || changes.providerCliActive != null
            || changes.providerCliExitCode != null || changes.ptyBusy != null
        ) {
            next.snapshot?.let { snapshot ->
                next = next.copy(snapshot = snapshot.copy(
                    title = changes.title ?: snapshot.title,
                    description = changes.description ?: snapshot.description,
                    summary = changes.summary ?: snapshot.summary,
                    titleGenerating = changes.titleGenerating ?: snapshot.titleGenerating,
                    ptyBusy = changes.ptyBusy ?: snapshot.ptyBusy,
                    providerCliActive = changes.providerCliActive ?: snapshot.providerCliActive,
                    providerCliExitCode = changes.providerCliExitCode ?: snapshot.providerCliExitCode,
                ))
            }
        }
        if (changes.ptyBusy != null || changes.providerCliActive != null) {
            next.snapshot?.let { snapshot ->
                next = next.copy(isResponding = snapshot.isResponding)
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

internal fun mergeConversationTurnTimes(
    previous: ConversationTurn?,
    incoming: ConversationTurn,
): ConversationTurn {
    val createdAt = incoming.createdAt ?: previous?.createdAt
    val completedAt = incoming.completedAt ?: previous?.completedAt
    if (createdAt == incoming.createdAt && completedAt == incoming.completedAt) return incoming
    return incoming.copy(createdAt = createdAt, completedAt = completedAt)
}

/** 内容体积：块级合并时用「哪一版更完整」决定重叠块取谁。 */
internal fun contentBlockVolume(block: ContentBlock): Int = when (block) {
    is ContentBlock.Text -> block.text.length
    is ContentBlock.Thinking -> block.thinking.length
    is ContentBlock.ToolUse ->
        (block.description?.length ?: 0) + jsonValueVolume(block.input)
    is ContentBlock.ToolResult -> block.text.length
    is ContentBlock.Unknown -> block.payload.length
}

private fun jsonValueVolume(value: Any?): Int = when (value) {
    null -> 1
    is String -> value.length
    is Number, is Boolean -> 1
    is org.json.JSONArray -> (0 until value.length()).sumOf { jsonValueVolume(value.opt(it)) }
    is org.json.JSONObject -> value.keys().asSequence().sumOf { key ->
        key.length + jsonValueVolume(value.opt(key))
    }
    else -> value.toString().length
}

internal fun turnContentVolume(turn: ConversationTurn): Int =
    turn.content.sumOf(::contentBlockVolume)

/** 本地这一版比服务端快照更完整（服务端窗口可能只带尾部）。 */
internal fun shouldKeepLocalTurn(local: ConversationTurn, incoming: ConversationTurn): Boolean =
    local.role == "assistant" &&
        incoming.role == "assistant" &&
        turnContentVolume(local) > turnContentVolume(incoming)

/**
 * 两版 turn 重叠时，内容更完整的一版胜出；时代戳逐字段互补，不因为换内容丢掉时间。
 */
internal fun mergeOverlappingTurns(
    local: ConversationTurn,
    incoming: ConversationTurn,
): ConversationTurn =
    if (shouldKeepLocalTurn(local, incoming)) {
        mergeConversationTurnTimes(local, incoming)
            .copy(content = local.content, usage = incoming.usage ?: local.usage)
    } else {
        mergeConversationTurnTimes(local, incoming)
    }

internal data class LeadingTurnMerge(
    val turn: ConversationTurn,
    val blockOffset: Int,
    val blockTotal: Int,
)

/**
 * 首 turn 的块窗口按**绝对块下标**合并：本地已翻出的旧前缀和服务端最新尾窗同时保留，
 * 重叠块逐块取内容更完整的一版，避免短快照回退或流式尾块增长丢失。
 * 两个窗口不重叠（不可比）时返回 null，由调用方回退到整 turn 合并。
 */
internal fun mergeLeadingAssistantTurn(
    local: ConversationTurn,
    localOffset: Int,
    localTotal: Int,
    incoming: ConversationTurn,
    incomingOffset: Int,
    incomingTotal: Int,
): LeadingTurnMerge? {
    if (local.role != "assistant" || incoming.role != "assistant") return null
    val localStart = localOffset.coerceAtLeast(0)
    val incomingStart = incomingOffset.coerceAtLeast(0)
    val localEnd = localStart + local.content.size
    val incomingEnd = incomingStart + incoming.content.size
    if (incomingStart > localEnd || localStart > incomingEnd) return null

    val mergedStart = minOf(localStart, incomingStart)
    val mergedEnd = maxOf(localEnd, incomingEnd)
    val blocks = ArrayList<ContentBlock>(mergedEnd - mergedStart)
    for (absoluteIndex in mergedStart until mergedEnd) {
        val localBlock = local.content.getOrNull(absoluteIndex - localStart)
            ?.takeIf { absoluteIndex in localStart until localEnd }
        val incomingBlock = incoming.content.getOrNull(absoluteIndex - incomingStart)
            ?.takeIf { absoluteIndex in incomingStart until incomingEnd }
        when {
            localBlock != null && incomingBlock != null ->
                blocks += if (contentBlockVolume(incomingBlock) >= contentBlockVolume(localBlock)) {
                    incomingBlock
                } else {
                    localBlock
                }
            incomingBlock != null -> blocks += incomingBlock
            localBlock != null -> blocks += localBlock
        }
    }
    return LeadingTurnMerge(
        turn = mergeConversationTurnTimes(local, incoming)
            .copy(content = blocks, usage = incoming.usage ?: local.usage),
        blockOffset = mergedStart,
        blockTotal = maxOf(localTotal, incomingTotal, mergedEnd),
    )
}

internal fun stampLiveTurnTime(turn: ConversationTurn, now: String = Instant.now().toString()): ConversationTurn {
    if (!turn.createdAt.isNullOrBlank()) return turn
    return turn.copy(createdAt = now)
}

/**
 * 服务端旧包可能不下发 createdAt/completedAt。客户端在会话已打开后
 * 为新追加的用户消息、正在回复的助手、以及回复结束补齐本地时钟，
 * 首屏历史不做假时间。
 */
internal fun enrichConversationTimes(
    previous: List<ConversationTurn>,
    incoming: List<ConversationTurn>,
    wasResponding: Boolean,
    nowResponding: Boolean,
    now: String = Instant.now().toString(),
): List<ConversationTurn> {
    if (incoming.isEmpty()) return incoming
    val lastIncoming = incoming.last()
    val lastPrevious = previous.lastOrNull()
    val appendedUser = lastIncoming.role == "user" && previous.isNotEmpty() && (
        incoming.size > previous.size || lastPrevious?.role != "user"
    )
    var changed = false
    val out = incoming.mapIndexed { index, turn ->
        val prev = previous.getOrNull(index)
        var createdAt = turn.createdAt?.takeIf { it.isNotBlank() } ?: prev?.createdAt
        var completedAt = turn.completedAt?.takeIf { it.isNotBlank() } ?: prev?.completedAt
        val isLast = index == incoming.lastIndex
        if (createdAt.isNullOrBlank() && isLast) {
            if (nowResponding || (turn.role == "user" && appendedUser)) {
                createdAt = now
            }
        }
        if (isLast && turn.role == "assistant" && wasResponding && !nowResponding) {
            if (completedAt.isNullOrBlank()) completedAt = now
            if (createdAt.isNullOrBlank()) createdAt = now
        }
        if (createdAt == turn.createdAt && completedAt == turn.completedAt) {
            turn
        } else {
            changed = true
            turn.copy(createdAt = createdAt, completedAt = completedAt)
        }
    }
    return if (changed) out else incoming
}
