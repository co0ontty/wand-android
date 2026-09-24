package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionEventReducerTest {
    @Test
    fun initCreatesWindowedStateThroughOneInterface() {
        val turns = listOf(textTurn("user", "hello"), textTurn("assistant", "world"))
        val event = SessionEvent.Initialized(
            sessionId = "s1",
            snapshot = snapshot("s1"),
            messages = MessageUpdate.Full(turns, offset = 10, total = 12),
            responding = true,
            changes = SessionChanges(status = "running", queuedMessages = listOf("next")),
        )

        val state = ChatSessionEventReducer.reduce(ChatSessionEventState(), event)

        assertTrue(state.initialized)
        assertEquals("s1", state.snapshot?.id)
        assertEquals(listOf("user", "assistant"), state.messages.map { it.role })
        assertEquals(10, state.loadedOffset)
        assertEquals(12, state.messageTotal)
        assertTrue(state.isResponding)
        assertEquals(listOf("next"), state.queuedMessages)
    }

    @Test
    fun fullRefreshKeepsPreviouslyLoadedPrefix() {
        val older = textTurn("user", "older")
        val priorTail = textTurn("assistant", "old tail")
        val refreshedTail = textTurn("assistant", "new tail")
        val current = ChatSessionEventState(
            messages = listOf(older, priorTail),
            loadedOffset = 8,
            messageTotal = 12,
        )

        val next = ChatSessionEventReducer.reduce(
            current,
            output(MessageUpdate.Full(listOf(refreshedTail), offset = 9, total = 10)),
        )

        assertEquals(listOf("older", "new tail"), next.messages.map(::turnText))
        assertEquals(8, next.loadedOffset)
        assertEquals(10, next.messageTotal)
    }

    @Test
    fun emptyTerminalSnapshotNeverErasesVisibleMessages() {
        val current = ChatSessionEventState(
            messages = listOf(textTurn("assistant", "keep")),
            messageTotal = 1,
            isResponding = true,
        )

        val next = ChatSessionEventReducer.reduce(
            current,
            SessionEvent.Ended(
                sessionId = "s1",
                messages = MessageUpdate.Full(emptyList(), offset = 0, total = 0),
                status = "exited",
                exitCode = 0,
                changes = SessionChanges(),
            ),
        )

        assertEquals("keep", turnText(next.messages.single()))
        assertEquals("exited", next.status)
        assertFalse(next.isResponding)
    }

    @Test
    fun incrementalOutputReplacesStreamingRoleThenAppendsNextRole() {
        val first = ChatSessionEventReducer.reduce(
            ChatSessionEventState(messages = listOf(textTurn("assistant", "part 1")), messageTotal = 1),
            output(MessageUpdate.Incremental(textTurn("assistant", "part 2"), expectedCount = 1)),
        )
        val second = ChatSessionEventReducer.reduce(
            first,
            output(MessageUpdate.Incremental(textTurn("user", "next"), expectedCount = 2)),
        )

        assertEquals(listOf("part 2", "next"), second.messages.map(::turnText))
        assertEquals(2, second.messageTotal)
    }

    @Test
    fun structuredEscalationWinsOverLegacyPromptAndClearRemovesBoth() {
        val escalation = EscalationRequest("req-1", "run_command", "needed", null, null)
        val legacy = PermissionRequestInfo("run_command", null, "legacy")
        val blocked = ChatSessionEventReducer.reduce(
            ChatSessionEventState(legacyPermissionPrompt = legacy),
            SessionEvent.StatusChanged(
                sessionId = "s1",
                permissionRequest = legacy,
                responding = null,
                changes = SessionChanges(pendingEscalation = escalation, permissionBlocked = true),
            ),
        )

        assertEquals(escalation, blocked.pendingEscalation)
        assertNull(blocked.legacyPermissionPrompt)
        assertTrue(blocked.permissionBlocked)

        val cleared = ChatSessionEventReducer.reduce(
            blocked,
            SessionEvent.StatusChanged(
                sessionId = "s1",
                permissionRequest = null,
                responding = null,
                changes = SessionChanges(permissionBlocked = false),
            ),
        )
        assertNull(cleared.pendingEscalation)
        assertNull(cleared.legacyPermissionPrompt)
        assertFalse(cleared.permissionBlocked)
    }

    @Test
    fun pendingLocalSettingKeepsUiValueWhileRecordingServerConfirmation() {
        val current = ChatSessionEventState(selectedModel = "new-local", confirmedModel = "old")
        val next = ChatSessionEventReducer.reduce(
            current,
            output(MessageUpdate.None, SessionChanges(selectedModel = "server")),
            PendingSessionSettings(model = true),
        )

        assertEquals("new-local", next.selectedModel)
        assertEquals("server", next.confirmedModel)
    }

    @Test
    fun taskEventCanExplicitlyClearTheCurrentTitle() {
        val next = ChatSessionEventReducer.reduce(
            ChatSessionEventState(currentTaskTitle = "running task"),
            SessionEvent.TaskChanged("s1", title = null),
        )

        assertNull(next.currentTaskTitle)
    }

    @Test
    fun restSnapshotUsesTheSameWindowAndPendingSettingRules() {
        val legacy = PermissionRequestInfo("run_command", null, "old prompt")
        val current = ChatSessionEventState(
            messages = listOf(textTurn("assistant", "keep")),
            messageTotal = 1,
            selectedModel = "local-model",
            confirmedModel = "old-model",
            legacyPermissionPrompt = legacy,
            permissionBlocked = true,
        )
        val snapshot = snapshot("s1").copy(
            messages = emptyList(),
            messageOffset = 0,
            messageTotal = 0,
            selectedModel = "server-model",
            permissionBlocked = false,
        )

        val next = ChatSessionEventReducer.applySnapshot(
            current,
            snapshot,
            PendingSessionSettings(model = true),
        )

        assertEquals("keep", turnText(next.messages.single()))
        assertEquals("local-model", next.selectedModel)
        assertEquals("server-model", next.confirmedModel)
        assertNull(next.legacyPermissionPrompt)
        assertFalse(next.permissionBlocked)
    }

    @Test
    fun providerCliExitStopsRespondingWithoutEndingShellSession() {
        val initial = ChatSessionEventReducer.applySnapshot(
            ChatSessionEventState(isResponding = true),
            snapshot("s1").copy(
                sessionKind = "pty",
                providerCliActive = true,
            ),
        ).copy(isResponding = true)

        val next = ChatSessionEventReducer.reduce(
            initial,
            SessionEvent.StatusChanged(
                sessionId = "s1",
                permissionRequest = null,
                responding = null,
                changes = SessionChanges(
                    providerCliActive = false,
                    providerCliExitCode = 7,
                    permissionBlocked = false,
                ),
            ),
        )

        assertEquals("running", next.status)
        assertFalse(next.isResponding)
        assertEquals(false, next.providerCliActive)
        assertEquals(7, next.providerCliExitCode)
        assertEquals(false, next.snapshot?.providerCliActive)
        assertEquals(7, next.snapshot?.providerCliExitCode)
    }

    @Test
    fun applySnapshotToastsNewLastErrorOnce() {
        val first = ChatSessionEventReducer.applySnapshot(
            ChatSessionEventState(),
            snapshot("s1").copy(
                structuredState = StructuredSessionState(
                    runner = "claude-cli-print",
                    model = null,
                    lastError = "服务重启，上一轮已中断",
                    inFlight = false,
                    activeRequestId = null,
                ),
            ),
        )
        assertEquals("服务重启，上一轮已中断", first.errorMessage)

        val second = ChatSessionEventReducer.applySnapshot(
            first.copy(errorMessage = null),
            first.snapshot!!,
        )
        assertNull(second.errorMessage)
    }

    @Test
    fun topicOutputRefreshesSnapshotAndGeneratingState() {
        val initial = ChatSessionEventReducer.applySnapshot(
            ChatSessionEventState(),
            snapshot("s1").copy(title = "旧标题", titleGenerating = true),
        )

        val next = ChatSessionEventReducer.reduce(
            initial,
            output(
                MessageUpdate.None,
                SessionChanges(
                    title = "共同标题",
                    description = "共同总结多轮要求",
                    summary = "共同总结多轮要求",
                    titleGenerating = false,
                ),
            ),
        )

        assertEquals("共同标题", next.snapshot?.displayTitle)
        assertEquals("共同总结多轮要求", next.snapshot?.description)
        assertFalse(next.snapshot?.titleGenerating ?: true)
    }

    @Test
    fun displayTitleDoesNotInventDirectoryOrTaskFallback() {
        val untitled = snapshot("s1").copy(
            title = null,
            summary = "整段用户输入",
            currentTaskTitle = "修登录",
            cwd = "/Users/me/wand",
        )
        assertEquals("会话", untitled.displayTitle)
        assertEquals("收紧 resume 时间窗", untitled.copy(title = "收紧 resume 时间窗").displayTitle)
    }

    @Test
    fun fullRefreshKeepsClientTimestampsWhenServerOmitsThem() {
        val local = ConversationTurn(
            role = "user",
            content = listOf(ContentBlock.Text("hi", subagent = null)),
            createdAt = "2026-03-27T01:02:03Z",
        )
        val current = ChatSessionEventState(
            messages = listOf(local),
            loadedOffset = 0,
            messageTotal = 1,
        )
        val next = ChatSessionEventReducer.reduce(
            current,
            output(MessageUpdate.Full(listOf(textTurn("user", "hi")), offset = 0, total = 1)),
        )
        assertEquals("2026-03-27T01:02:03Z", next.messages.single().createdAt)
    }

    @Test
    fun incrementalReplaceKeepsCreatedAtAndFillsCompletedAt() {
        val streaming = ConversationTurn(
            role = "assistant",
            content = listOf(ContentBlock.Text("hi", subagent = null)),
            createdAt = "2026-03-27T01:02:03Z",
        )
        val current = ChatSessionEventState(messages = listOf(streaming), messageTotal = 1)
        val next = ChatSessionEventReducer.reduce(
            current,
            output(
                MessageUpdate.Incremental(
                    ConversationTurn(
                        role = "assistant",
                        content = listOf(ContentBlock.Text("hi!", subagent = null)),
                        completedAt = "2026-03-27T01:02:09Z",
                    ),
                    expectedCount = 1,
                ),
            ),
        )
        assertEquals("2026-03-27T01:02:03Z", next.messages.single().createdAt)
        assertEquals("2026-03-27T01:02:09Z", next.messages.single().completedAt)
        assertEquals("hi!", turnText(next.messages.single()))
    }

    @Test
    fun enrichDoesNotInventTimesOnFirstHydrate() {
        val incoming = listOf(textTurn("user", "hello"), textTurn("assistant", "world"))
        val next = enrichConversationTimes(
            previous = emptyList(),
            incoming = incoming,
            wasResponding = false,
            nowResponding = false,
            now = "2026-03-27T01:02:03Z",
        )
        assertTrue(next.all { it.createdAt == null && it.completedAt == null })
        assertTrue(next === incoming)
    }

    @Test
    fun enrichStampsAppendedUserAndCompletesAssistant() {
        val user = textTurn("user", "hello")
        val started = enrichConversationTimes(
            previous = listOf(user),
            incoming = listOf(user, textTurn("assistant", "...")),
            wasResponding = false,
            nowResponding = true,
            now = "2026-03-27T01:02:03Z",
        )
        assertEquals("2026-03-27T01:02:03Z", started.last().createdAt)
        assertNull(started.last().completedAt)

        val sent = enrichConversationTimes(
            previous = started,
            incoming = started + textTurn("user", "next"),
            wasResponding = true,
            nowResponding = true,
            now = "2026-03-27T01:03:00Z",
        )
        assertEquals("2026-03-27T01:03:00Z", sent.last().createdAt)

        val done = enrichConversationTimes(
            previous = started,
            incoming = started,
            wasResponding = true,
            nowResponding = false,
            now = "2026-03-27T01:02:09Z",
        )
        assertEquals("2026-03-27T01:02:03Z", done.last().createdAt)
        assertEquals("2026-03-27T01:02:09Z", done.last().completedAt)
    }

    @Test
    fun blockWindowInitKeepsLocallyLoadedEarlierBlocks() {
        val localTurn = blockTurn("assistant", 771, 100)
        val current = ChatSessionEventState(
            messages = listOf(localTurn),
            loadedOffset = 19,
            messageTotal = 20,
            leadingBlockOffset = 771,
            leadingBlockTotal = 871,
        )
        // 服务端每次 init/resync 都只给最近 60 块（blockBudget），不能把用户已翻出的前缀冲掉。
        val next = ChatSessionEventReducer.reduce(
            current,
            output(
                MessageUpdate.Full(
                    messages = listOf(blockTurn("assistant", 811, 60)),
                    offset = 19,
                    total = 20,
                    leadingOffset = 811,
                    leadingTotal = 871,
                ),
            ),
        )

        assertEquals(1, next.messages.size)
        assertEquals(100, next.messages.single().content.size)
        assertEquals(771, next.leadingBlockOffset)
        assertEquals(871, next.leadingBlockTotal)
    }

    @Test
    fun blockWindowPicksTheMoreCompleteVersionOfOverlappingBlocks() {
        // 本地块 0..2，服务端尾窗 2..4；重叠块 2 上服务端更长，应以服务端为准。
        val localTurn = ConversationTurn(
            role = "assistant",
            content = listOf(
                ContentBlock.Text("a", null),
                ContentBlock.Text("b", null),
                ContentBlock.Text("short", null),
            ),
        )
        val incomingTurn = ConversationTurn(
            role = "assistant",
            content = listOf(
                ContentBlock.Text("short but much longer tail", null),
                ContentBlock.Text("d", null),
                ContentBlock.Text("e", null),
            ),
        )
        val next = ChatSessionEventReducer.reduce(
            ChatSessionEventState(
                messages = listOf(localTurn),
                loadedOffset = 0,
                messageTotal = 1,
                leadingBlockOffset = 0,
                leadingBlockTotal = 3,
            ),
            output(
                MessageUpdate.Full(
                    messages = listOf(incomingTurn),
                    offset = 0,
                    total = 1,
                    leadingOffset = 2,
                    leadingTotal = 5,
                ),
            ),
        )

        val blocks = next.messages.single().content.map { (it as ContentBlock.Text).text }
        assertEquals(listOf("a", "b", "short but much longer tail", "d", "e"), blocks)
        assertEquals(0, next.leadingBlockOffset)
        assertEquals(5, next.leadingBlockTotal)
    }

    @Test
    fun emptyMessagesWindowKeepsVisibleHistory() {
        val current = ChatSessionEventState(
            messages = listOf(textTurn("assistant", "keep me")),
            loadedOffset = 19,
            messageTotal = 20,
            leadingBlockOffset = 811,
            leadingBlockTotal = 871,
        )
        val next = ChatSessionEventReducer.reduce(
            current,
            output(
                MessageUpdate.Full(
                    messages = emptyList(),
                    offset = 19,
                    total = 20,
                    leadingOffset = 811,
                    leadingTotal = 871,
                ),
            ),
        )

        assertEquals("keep me", turnText(next.messages.single()))
        assertEquals(811, next.leadingBlockOffset)
    }

    @Test
    fun laterTurnWindowReplacesLeadingCursorInsteadOfMergingTwoTurns() {
        // 新窗口从更晚 turn 开始且自身被截断：leadingBlockOffset 只能描述 messages[0]，
        // 此时采用新窗口，不把旧前缀拼在前面（否则游标指向错误 turn）。
        val current = ChatSessionEventState(
            messages = listOf(textTurn("assistant", "older")),
            loadedOffset = 18,
            messageTotal = 20,
            leadingBlockOffset = 0,
            leadingBlockTotal = 5,
        )
        val next = ChatSessionEventReducer.reduce(
            current,
            output(
                MessageUpdate.Full(
                    messages = listOf(blockTurn("assistant", 811, 60)),
                    offset = 19,
                    total = 20,
                    leadingOffset = 811,
                    leadingTotal = 871,
                ),
            ),
        )

        assertEquals(19, next.loadedOffset)
        assertEquals(1, next.messages.size)
        assertEquals(60, next.messages.single().content.size)
        assertEquals(811, next.leadingBlockOffset)
    }

    private fun blockTurn(role: String, startBlock: Int, count: Int) = ConversationTurn(
        role = role,
        content = (startBlock until startBlock + count).map {
            ContentBlock.Text("block-$it", subagent = null)
        },
    )

    private fun output(
        messages: MessageUpdate,
        changes: SessionChanges = SessionChanges(),
    ) = SessionEvent.Output("s1", messages, responding = null, changes = changes)

    private fun textTurn(role: String, text: String) = ConversationTurn(
        role = role,
        content = listOf(ContentBlock.Text(text, subagent = null)),
    )

    private fun turnText(turn: ConversationTurn): String =
        (turn.content.single() as ContentBlock.Text).text

    private fun snapshot(id: String) = SessionSnapshot(
        id = id,
        sessionKind = "structured",
        provider = "claude",
        runner = null,
        command = null,
        cwd = "/tmp/project",
        mode = "default",
        status = "running",
        exitCode = null,
        startedAt = null,
        endedAt = null,
        archived = false,
        summary = null,
        currentTaskTitle = null,
        selectedModel = null,
        thinkingEffort = null,
        claudeSessionId = null,
        messages = null,
        queuedMessages = emptyList(),
        structuredState = null,
        pendingEscalation = null,
        permissionBlocked = false,
        autoApprovePermissions = null,
    )
}
