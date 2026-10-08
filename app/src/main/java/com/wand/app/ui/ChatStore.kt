package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.ConversationTurn
import com.wand.app.data.CardExpandDefaults
import com.wand.app.data.ChatSessionEventReducer
import com.wand.app.data.ChatSessionEventState
import com.wand.app.data.enrichConversationTimes
import com.wand.app.data.EscalationRequest
import com.wand.app.data.ModelInfo
import com.wand.app.data.PendingSessionSettings
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SessionEvent
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WandSocket
import com.wand.app.data.WandProvider
import com.wand.app.wlog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 单个会话的状态机 —— 逐行移植 iOS ChatStore.swift：
 * 拉取快照、订阅 WebSocket、合并增量推送、发送输入与权限决策。
 * 合流规则对齐浏览器端 websocket.ts：
 *   - init / messages 全量 → 直接替换
 *   - incremental + lastMessage → 末条同 role 时替换，否则按 messageCount 追加
 *   - chunk-only 事件是终端视图的，聊天视图直接忽略
 *
 * WandSocket 的回调已保证主线程 FIFO，handle 直接调用、不再包协程 ——
 * 协程 launch 不保证顺序，会打乱增量合流。
 */
/** AskUserQuestion 卡片的本地选择状态（对齐 Web 端 state.askUserSelections）。 */
data class AskUserSelectionState(
    /** questionIndex → 已选 optionIndex 集合。 */
    val selected: Map<Int, Set<Int>> = emptyMap(),
    val submitted: Boolean = false,
    val submissionUnconfirmed: Boolean = false,
    val unavailableReason: String? = null,
)

/** 回答已发出后，超时/断线/部分 PTY 接受都不能变回可重复提交。 */
internal fun askUserSelectionAfterFailure(selection: AskUserSelectionState, failure: Throwable): AskUserSelectionState =
    if (com.wand.app.data.isDefiniteRequestRejection(failure)) selection.copy(submitted = false, submissionUnconfirmed = false)
    else selection.copy(submitted = true, submissionUnconfirmed = true)

/** 工具答复只属于最新一轮未配对的提问，历史卡不能向当前执行塞入旧答案。 */
internal fun activeAskQuestionIds(messages: List<ConversationTurn>): Set<String> {
    val results = messages.flatMap { it.content }.filterIsInstance<com.wand.app.data.ContentBlock.ToolResult>().map { it.toolUseId }.toSet()
    val current = messages.drop(messages.indexOfLast { it.role == "user" } + 1)
    return current.flatMap { it.content }.filterIsInstance<com.wand.app.data.ContentBlock.ToolUse>()
        .filter { (it.semantic is com.wand.app.data.ToolUseSemantic.QuestionRequest || it.name == "AskUserQuestion") && it.id !in results }
        .map { it.id }.toSet()
}

internal fun canSwitchBlankConversationProvider(snapshot: SessionSnapshot?, messageTotal: Int): Boolean =
    snapshot != null && snapshot.isStructured && snapshot.status == "idle" && snapshot.archived != true &&
        snapshot.claudeSessionId.isNullOrBlank() && snapshot.structuredState?.inFlight != true &&
        snapshot.messages.isNullOrEmpty() && (snapshot.messageTotal ?: 0) == 0 && messageTotal == 0 &&
        snapshot.queuedMessages.isNullOrEmpty()

internal fun canChangeBlankConversationDirectory(snapshot: SessionSnapshot?, messageTotal: Int): Boolean =
    canSwitchBlankConversationProvider(snapshot, messageTotal) && snapshot != null &&
        snapshot.workspaceTaskId.isNullOrBlank() && !snapshot.directoryLocked

class ChatStore(val sessionId: String, val api: WandApi) : ScopedStore() {

    var messages by mutableStateOf<List<ConversationTurn>>(emptyList())
        private set
    var isResponding by mutableStateOf(false)
        private set
    var status by mutableStateOf("running")
        private set
    var queuedMessages by mutableStateOf<List<String>>(emptyList())
        private set
    var pendingEscalation by mutableStateOf<EscalationRequest?>(null)
        private set

    /** PTY 旧式权限提示（permissionBlocked 为 true 但没有结构化 escalation 时）。 */
    var legacyPermissionPrompt by mutableStateOf<PermissionRequestInfo?>(null)
        private set
    var permissionBlocked by mutableStateOf(false)
        private set
    var currentTaskTitle by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(true)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
    var snapshot by mutableStateOf<SessionSnapshot?>(null)
        private set

    var providerSwitching by mutableStateOf(false)
        private set
    var providerSwitchError by mutableStateOf<String?>(null)
        private set
    var providerSwitchResult by mutableStateOf<String?>(null)
        private set
    val canSwitchProvider: Boolean get() = !loading && !providerSwitching && !directoryChanging &&
        pendingModelMutations == 0 && pendingThinkingMutations == 0 && pendingModeMutations == 0 &&
        canSwitchBlankConversationProvider(snapshot, messageTotal)

    var directoryChanging by mutableStateOf(false)
        private set
    var directoryChangeError by mutableStateOf<String?>(null)
        private set
    val canChangeDirectory: Boolean get() = canSwitchProvider &&
        canChangeBlankConversationDirectory(snapshot, messageTotal)

    var availableModels by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var defaultModel by mutableStateOf<String?>(null)
        private set
    var selectedModel by mutableStateOf<String?>(null)
        private set
    var thinkingEffort by mutableStateOf("off")
        private set
    /** 服务端全局卡片默认展开偏好；旧服务端缺字段时安全回退为全部收起。 */
    var cardDefaults by mutableStateOf(CardExpandDefaults())
        private set
    /** 当前执行模式（managed / full-access / auto-edit / default / native）。输入栏模式徽标读它，可中途切换。 */
    var mode by mutableStateOf("default")
        private set

    /**
     * AskUserQuestion 卡片的选择状态（toolUseId → 各题已选项 + 是否已提交）。
     * 放 store 而非卡片 remember：流式推送会整条替换消息重组视图，局部状态会丢。
     */
    var askUserSelections by mutableStateOf<Map<String, AskUserSelectionState>>(emptyMap())
        private set

    // 消息窗口化：messages 是完整历史的「后缀」，loadedOffset = messages[0] 的绝对下标，
    // messageTotal = 完整 turn 数。loadedOffset > 0 表示顶部还有更早的可加载。
    var loadedOffset by mutableIntStateOf(0)
        private set
    var messageTotal by mutableIntStateOf(0)
        private set
    // 块级窗口游标（带 blockBudget 时服务端才下发）：messages[0] 被切掉的头部块数 /
    // 这条 turn 的完整块数。leadingBlockOffset > 0 表示顶部还有更早的步骤。
    var leadingBlockOffset by mutableIntStateOf(0)
        private set
    var leadingBlockTotal by mutableIntStateOf(0)
        private set
    /** 头部里用户可感知的条数（默认收起的工具 / 思考块不计入）；null = 旧服务端未下发。 */
    var leadingVisibleCount by mutableStateOf<Int?>(null)
        private set
    var loadingEarlier by mutableStateOf(false)
        private set
    /**
     * 上一页没取回来（网络层失败等）。不弹提示：顶部静默加载位据此继续显示 loading，
     * 稍后自动重试，取回后清零。
     */
    var earlierLoadFailed by mutableStateOf(false)
        private set
    /** 每次静默翻页结束（无论成败）自增，UI 据此知道「上一页已经有结果了」。 */
    var earlierPageAttempts by mutableIntStateOf(0)
        private set
    val canLoadEarlier: Boolean get() = leadingBlockOffset > 0 || loadedOffset > 0
    private val earlierPageSize = 40
    private val earlierBlockPageSize = 40

    private val socket = WandSocket(api.baseUrl, api.token)
        .apply { blockBudget = WandApi.CHAT_BLOCK_WINDOW }
    /**
     * started = 对象是否跑过首次加载；active = 页面当前是否可见。
     * Compose / Navigation 可能复用同一个 ChatStore：shutdown 关 socket 后，
     * 若只看 started 会把重进详情永久挡住，红条就一直挂着，退出再进才好。
     */
    private var started = false
    private var active = false
    private var queuePromotePending = false
    private val settingsMutationMutex = Mutex()
    private var modelMutationGeneration = 0L
    private var thinkingMutationGeneration = 0L
    private var modeMutationGeneration = 0L
    private var modelCatalogGeneration = 0L
    private var pendingModelMutations by mutableIntStateOf(0)
    private var pendingThinkingMutations by mutableIntStateOf(0)
    private var pendingModeMutations by mutableIntStateOf(0)
    private var confirmedModel: String? = null
    private var confirmedThinkingEffort = "off"
    private var confirmedMode = "default"

    val isStructured: Boolean get() = snapshot?.isStructured ?: true
    val sessionEnded: Boolean get() = status in SESSION_ENDED_STATUSES

    // MARK: - 生命周期

    fun start() {
        when (chatRealtimeStartKind(active = active, started = started)) {
            ChatRealtimeStartKind.Skip -> return
            ChatRealtimeStartKind.Reconnect -> {
                active = true
                ensureScope()
                connectSocket()
                return
            }
            ChatRealtimeStartKind.FirstConnect -> {
                active = true
                started = true
                ensureScope()
                socket.onEvent = { event -> handle(event) }
                socket.onConnectionChange = { up ->
                    connected = up
                    if (up && active) scope.launch { loadModels(normalizeThinking = false) }
                }
                socket.onModelCatalogChanged = {
                    if (active) scope.launch { loadModels(normalizeThinking = false) }
                }
                socket.onAuthenticationFailure = { message ->
                    wlog("chat", "socket 鉴权失败 session=$sessionId：$message")
                    loadError = message
                }
                // 实时连接不能被 REST / 模型目录 / 卡片默认值挡住。旧逻辑等三段请求收尾才
                // connect，页面看起来已经打开、红条却一直挂着；退回再进才重新建连。
                connectSocket()
                wlog("chat", "打开会话 session=$sessionId")
                scope.launch {
                    try {
                        val snap = api.getSession(sessionId)
                        wlog(
                            "chat",
                            "REST 快照 session=$sessionId msgs=${snap.messages?.size ?: -1} " +
                                "status=${snap.status} structured=${snap.isStructured}",
                        )
                        apply(snap)
                    } catch (e: Exception) {
                        wlog("chat", "REST 快照失败 session=$sessionId：${e.message}", e)
                        loadError = e.message ?: "加载失败"
                    }
                    loadModels()
                    loadCardDefaults()
                    loading = false
                }
            }
        }
    }

    fun handleEnterForeground() {
        if (!active) return
        socket.reconnectForForeground()
    }

    private fun connectSocket() {
        socket.connect()
        socket.subscribe(sessionId)
    }

    override fun shutdown() {
        if (active) {
            active = false
            socket.close()
        }
        super.shutdown()
    }

    // MARK: - 推送合流

    private fun apply(snap: SessionSnapshot) {
        applyRealtimeState(
            ChatSessionEventReducer.applySnapshot(
                current = realtimeState(),
                snapshot = snap,
                pending = pendingSessionSettings(),
            ),
        )
    }

    /** 是否有未落地的本地设置变更；有则不被快照反向覆盖。 */
    private fun pendingSessionSettings() = PendingSessionSettings(
        model = pendingModelMutations > 0,
        thinkingEffort = pendingThinkingMutations > 0,
        mode = pendingModeMutations > 0,
    )

    private fun handle(event: SessionEvent) {
        if (event.sessionId != null && event.sessionId != sessionId) return
        val next = ChatSessionEventReducer.reduce(
            current = realtimeState(),
            event = event,
            pending = pendingSessionSettings(),
        )
        applyRealtimeState(next)
    }

    private fun realtimeState() = ChatSessionEventState(
        messages = messages,
        loadedOffset = loadedOffset,
        messageTotal = messageTotal,
        leadingBlockOffset = leadingBlockOffset,
        leadingBlockTotal = leadingBlockTotal,
        leadingVisibleCount = leadingVisibleCount,
        status = status,
        isResponding = isResponding,
        queuedMessages = queuedMessages,
        pendingEscalation = pendingEscalation,
        legacyPermissionPrompt = legacyPermissionPrompt,
        permissionBlocked = permissionBlocked,
        currentTaskTitle = currentTaskTitle,
        snapshot = snapshot,
        selectedModel = selectedModel,
        thinkingEffort = thinkingEffort,
        mode = mode,
        confirmedModel = confirmedModel,
        confirmedThinkingEffort = confirmedThinkingEffort,
        confirmedMode = confirmedMode,
    )

    private fun applyRealtimeState(next: ChatSessionEventState) {
        // 逐字段相等短路：WS 事件很密（服务端 16ms debounce），无变化的字段
        // 不回写 Compose state，避免下游 remember(store.xxx) 因引用变化被反复击穿。
        val nextMessages = enrichConversationTimes(
            messages,
            next.messages,
            wasResponding = isResponding,
            nowResponding = next.isResponding,
        )
        if (messages !== nextMessages) messages = nextMessages
        if (loadedOffset != next.loadedOffset) loadedOffset = next.loadedOffset
        if (messageTotal != next.messageTotal) messageTotal = next.messageTotal
        if (leadingBlockOffset != next.leadingBlockOffset) leadingBlockOffset = next.leadingBlockOffset
        if (leadingBlockTotal != next.leadingBlockTotal) leadingBlockTotal = next.leadingBlockTotal
        if (leadingVisibleCount != next.leadingVisibleCount) leadingVisibleCount = next.leadingVisibleCount
        if (status != next.status) status = next.status
        if (isResponding != next.isResponding) isResponding = next.isResponding
        if (queuedMessages !== next.queuedMessages) queuedMessages = next.queuedMessages
        if (pendingEscalation != next.pendingEscalation) pendingEscalation = next.pendingEscalation
        if (legacyPermissionPrompt != next.legacyPermissionPrompt) legacyPermissionPrompt = next.legacyPermissionPrompt
        if (permissionBlocked != next.permissionBlocked) permissionBlocked = next.permissionBlocked
        if (currentTaskTitle != next.currentTaskTitle) currentTaskTitle = next.currentTaskTitle
        if (snapshot != next.snapshot) {
            snapshot = next.snapshot
            next.snapshot?.let { snap ->
                SessionTitleStore.apply(
                    sessionId,
                    title = snap.title,
                    generating = snap.titleGenerating,
                    ptyBusy = snap.ptyBusy,
                    permissionBlocked = snap.hasPendingPermission,
                    completionRevision = snap.completionRevision,
                    viewedCompletionRevision = snap.viewedCompletionRevision,
                )
            }
        }
        if (selectedModel != next.selectedModel) selectedModel = next.selectedModel
        if (thinkingEffort != next.thinkingEffort) thinkingEffort = next.thinkingEffort
        if (mode != next.mode) mode = next.mode
        if (confirmedModel != next.confirmedModel) confirmedModel = next.confirmedModel
        if (confirmedThinkingEffort != next.confirmedThinkingEffort) confirmedThinkingEffort = next.confirmedThinkingEffort
        if (confirmedMode != next.confirmedMode) confirmedMode = next.confirmedMode
        next.errorMessage?.let { toast = it }
        if (next.initialized) loading = false
    }

    // MARK: - 模型与思考深度（乐观更新 + 串行请求 + generation 防旧响应覆盖）

    /** Logo 选择只修改这个空白会话；草稿和附件仍由同一个 composer 持有。 */
    fun chooseProvider(provider: String) {
        if (!canSwitchProvider || WandProvider.fromId(provider) == null || snapshot?.provider == provider) return
        providerSwitching = true
        providerSwitchError = null
        providerSwitchResult = null
        scope.launch {
            try {
                settingsMutationMutex.withLock {
                    val snap = api.setProvider(sessionId, provider)
                    check(snap.id == sessionId && snap.provider == provider && snap.isStructured &&
                        snap.employeeId == snapshot?.employeeId) { "未收到有效的工具切换回执" }
                    currentCoroutineContext().ensureActive()
                    apply(snap)
                    availableModels = emptyList()
                    defaultModel = null
                    loadModels()
                    providerSwitchResult = "已切换为 ${snap.providerLabel}"
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                providerSwitchError = failure.message ?: "切换工具失败，请重试"
            } finally {
                providerSwitching = false
            }
        }
    }

    /** 目录回执属于这个 store/会话；失败不改真实目录，不清草稿，也不自动重发。 */
    fun chooseWorkingDirectory(cwd: String) {
        if (!canChangeDirectory || cwd.isBlank() || snapshot?.cwd == cwd) return
        directoryChanging = true
        directoryChangeError = null
        val employeeId = snapshot?.employeeId
        scope.launch {
            try {
                settingsMutationMutex.withLock {
                    val snap = api.setSessionDirectory(sessionId, cwd)
                    check(snap.id == sessionId && snap.isStructured && snap.employeeId == employeeId) {
                        "未收到有效的运行目录切换回执"
                    }
                    currentCoroutineContext().ensureActive()
                    if (active) {
                        apply(snap)
                        socket.requestResync()
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (active) directoryChangeError = failure.message ?: "切换运行目录失败，请重试"
            } finally { directoryChanging = false }
        }
    }

    fun setModel(model: String?) {
        if (providerSwitching || directoryChanging) return
        val generation = ++modelMutationGeneration
        pendingModelMutations++
        selectedModel = model
        scope.launch {
            var shouldNormalizeThinking = false
            try {
                settingsMutationMutex.withLock {
                    val snap = api.setModel(sessionId, model)
                    // 即使已有更新的本地选择，也记录服务端此刻的已确认基线；只让最新同字段操作改 UI。
                    confirmedModel = snap.selectedModel
                    confirmedThinkingEffort = snap.thinkingEffort ?: "off"
                    if (generation == modelMutationGeneration) {
                        selectedModel = confirmedModel
                        shouldNormalizeThinking = true
                    }
                }
            } catch (e: Exception) {
                if (generation == modelMutationGeneration) {
                    selectedModel = confirmedModel
                    toast = e.message ?: "切换模型失败"
                }
            } finally {
                pendingModelMutations--
            }
            // 只有模型切换已被服务端确认且仍是最新选择时，才联动收敛思考档位。
            // 模型请求失败时保留旧模型的真实思考深度。
            if (shouldNormalizeThinking) normalizeThinkingEffortFor(confirmedModel)
        }
    }

    fun chooseThinkingEffort(effort: String) {
        if (providerSwitching || directoryChanging) return
        val generation = ++thinkingMutationGeneration
        pendingThinkingMutations++
        thinkingEffort = effort
        scope.launch {
            try {
                settingsMutationMutex.withLock {
                    val snap = api.setThinkingEffort(sessionId, effort)
                    confirmedModel = snap.selectedModel
                    confirmedThinkingEffort = snap.thinkingEffort ?: "off"
                    if (generation == thinkingMutationGeneration) {
                        thinkingEffort = confirmedThinkingEffort
                    }
                }
            } catch (e: Exception) {
                if (generation == thinkingMutationGeneration) {
                    thinkingEffort = confirmedThinkingEffort
                    toast = e.message ?: "调整思考深度失败"
                }
            } finally {
                pendingThinkingMutations--
            }
        }
    }

    /** 模型切换后若当前档位已不可用，立即把真实状态与服务端一起收敛到自动。 */
    private fun normalizeThinkingEffortFor(model: String?) {
        val provider = snapshot?.provider ?: "claude"
        // Codex 的动态元数据尚未返回时不能用 legacy fallback 误判并覆盖服务端值。
        if (provider == "codex" && availableModels.isEmpty()) return
        val supported = thinkingEffortOptions(
            provider = provider,
            selectedModel = model,
            defaultModel = defaultModel,
            models = availableModels,
        ).any { it.id == thinkingEffort }
        if (!supported) chooseThinkingEffort("off")
    }

    /** 中途切换执行模式（乐观更新 + 失败回滚）。codex 会话固定 full-access，调用方负责拦。 */
    fun chooseMode(newMode: String) {
        if (providerSwitching || directoryChanging) return
        val generation = ++modeMutationGeneration
        pendingModeMutations++
        mode = newMode
        scope.launch {
            try {
                settingsMutationMutex.withLock {
                    val snap = api.setMode(sessionId, newMode)
                    confirmedMode = snap.mode ?: newMode
                    if (generation == modeMutationGeneration) mode = confirmedMode
                }
            } catch (e: Exception) {
                if (generation == modeMutationGeneration) {
                    mode = confirmedMode
                    toast = e.message ?: "切换模式失败"
                }
            } finally {
                pendingModeMutations--
            }
        }
    }

    /** Read the catalog last persisted by the server; native clients never probe CLIs directly. */
    private suspend fun loadModels(normalizeThinking: Boolean = true) {
        val generation = ++modelCatalogGeneration
        val provider = snapshot?.provider ?: "claude"
        val response = runCatching { api.models() }.getOrNull() ?: return
        currentCoroutineContext().ensureActive()
        if (!active || generation != modelCatalogGeneration || provider != (snapshot?.provider ?: "claude")) return
        availableModels = response.modelsFor(provider)
        defaultModel = response.defaultModelFor(provider)
        // Background group reorder/rename refreshes labels, never rewrites a user's model/depth choice.
        if (normalizeThinking) normalizeThinkingEffortFor(selectedModel)
    }

    private suspend fun loadCardDefaults() {
        cardDefaults = runCatching { api.serverConfig().cardDefaults }
            .getOrDefault(CardExpandDefaults())
    }

    // MARK: - 用户动作

    /** Protocol send only: composer owns content, submit concurrency and inline feedback. */
    suspend fun submitInput(text: String) {
        check(!providerSwitching) { "工具正在切换，请稍后发送" }
        check(!directoryChanging) { "运行目录正在切换，请稍后发送" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val structured = snapshot?.isStructured
            ?: throw IllegalStateException("会话尚未加载完成")
        val queueing = structured && isResponding && status == "running"
        if (queueing && lastSubmittedStructuredInput() == trimmed) {
            toast = "与上一条消息相同，已忽略，不会加入排队。"
            return
        }
        applyProvisionalTopic(trimmed)
        val previousMessages = messages
        val previousQueue = queuedMessages
        val wasResponding = isResponding
        var optimisticMessages: List<ConversationTurn>? = null
        var optimisticQueue: List<String>? = null
        if (structured) {
            if (queueing) {
                optimisticQueue = queuedMessages + trimmed
                queuedMessages = optimisticQueue
                toast = "已加入排队，等当前回复完成会自动发送。"
            } else {
                optimisticMessages = messages + ConversationTurn(
                    role = "user",
                    content = listOf(com.wand.app.data.ContentBlock.Text(trimmed, null)),
                    createdAt = Instant.now().toString(),
                )
                messages = optimisticMessages
                isResponding = true
            }
        }
        try {
            if (structured) {
                // Always acknowledge receipt promptly, including queued input. This flag
                // controls HTTP response timing; the server owns queue scheduling.
                val accepted = api.sendStructuredInput(sessionId, trimmed)
                currentCoroutineContext().ensureActive()
                apply(accepted)
                socket.requestResync()
            } else {
                sendPtyChatInput(trimmed)
            }
        } catch (e: Exception) {
            // A later WS snapshot is authoritative. Roll back only our untouched local
            // insertion rather than replacing newer messages/queue with the old snapshot.
            if (optimisticQueue != null && queuedMessages === optimisticQueue) queuedMessages = previousQueue
            if (optimisticMessages != null && messages === optimisticMessages) {
                messages = previousMessages
                isResponding = wasResponding
            }
            if (e !is CancellationException) {
                wlog("chat", "发送失败 session=$sessionId：${e.message}", e)
                socket.requestResync()
            }
            throw e
        }
    }

    private fun lastSubmittedStructuredInput(): String? {
        queuedMessages.asReversed().firstNotNullOfOrNull { it.trim().takeIf(String::isNotEmpty) }?.let { return it }
        val lastUser = messages.asReversed().firstOrNull { it.role == "user" } ?: return null
        val text = lastUser.content.filterIsInstance<com.wand.app.data.ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (text.isNotEmpty()) return text
        return lastUser.content.filterIsInstance<com.wand.app.data.ContentBlock.ToolResult>()
            .firstOrNull()?.text?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun applyProvisionalTopic(input: String) {
        val title = applyProvisionalSessionTopic(
            sessionId,
            input,
            sessionTopicBlocklist(cwd = snapshot?.cwd),
        ) ?: return
        val snap = snapshot ?: return
        snapshot = snap.copy(
            title = title,
            description = summarizeSessionDescriptionFromInput(input).ifEmpty { title },
            titleGenerating = true,
        )
    }

    private suspend fun sendPtyChatInput(text: String) {
        val chunks = ptyComposerSubmitChunks(text, "chat")
        for ((index, chunk) in chunks.withIndex()) {
            if (index > 0) delay(PTY_CHAT_SUBMIT_CHUNK_INTERVAL_MS)
            try {
                api.sendPtyInputChunk(sessionId, chunk.input, chunk.view, chunk.shortcutKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (index > 0) throw UnconfirmedComposerInputException(e)
                throw e
            }
        }
    }

    // MARK: - AskUserQuestion 交互（对齐 Web 端 __askSelect / __askSubmit）

    fun canAnswerAskUser(toolUseId: String): Boolean =
        !loading && loadError == null && !sessionEnded && toolUseId in activeAskQuestionIds(messages)

    /** 点选一个选项：单选点同一项取消、换选项替换；多选逐项 toggle。已提交后不可改。 */
    fun toggleAskOption(toolUseId: String, questionIndex: Int, optionIndex: Int, multiSelect: Boolean) {
        val sel = askUserSelections[toolUseId] ?: AskUserSelectionState()
        if (sel.submitted || !canAnswerAskUser(toolUseId)) return
        val current = sel.selected[questionIndex] ?: emptySet()
        val next = if (multiSelect) {
            if (optionIndex in current) current - optionIndex else current + optionIndex
        } else {
            if (optionIndex in current) emptySet() else setOf(optionIndex)
        }
        askUserSelections = askUserSelections +
            (toolUseId to sel.copy(selected = sel.selected + (questionIndex to next)))
    }

    /**
     * 提交答案：每道题一行、同题多选 ", " 连接（对齐 Web），走与普通消息相同的输入通道。
     * 答案不乐观插入用户气泡——服务端会把它作为 tool_result 回推、卡片转只读态。
     */
    fun submitAskUser(toolUseId: String, answerText: String) {
        val sel = askUserSelections[toolUseId] ?: AskUserSelectionState()
        if (sel.submitted || !canAnswerAskUser(toolUseId)) return
        askUserSelections = askUserSelections + (toolUseId to sel.copy(submitted = true))
        if (isStructured) isResponding = true
        scope.launch {
            try {
                if (isStructured) {
                    api.sendStructuredInput(sessionId, answerText)
                } else {
                    sendPtyChatInput(answerText)
                }
            } catch (e: Exception) {
                wlog("chat", "回答提问失败 session=$sessionId：${e.message}", e)
                val rollback = askUserSelectionAfterFailure(askUserSelections[toolUseId] ?: AskUserSelectionState(), e)
                askUserSelections = askUserSelections + (toolUseId to rollback)
                toast = if (rollback.submissionUnconfirmed) "回答送达未确认，请核对执行记录，勿重复提交。" else e.message ?: "发送失败"
                if (isStructured && !rollback.submissionUnconfirmed) isResponding = false
                socket.requestResync()
                if (e is CancellationException) throw e
            }
        }
    }

    // MARK: - 排队消息（仅结构化会话）

    /** inFlight 判定：和 Web/iOS 保持一致 —— 结构化态在 running 且 inFlight。 */
    private val isInFlight: Boolean
        get() = isStructured && isResponding && status == "running"

    /**
     * 把第 index 条排队消息「立即发送」。
     * 乐观剥掉这一条；inFlight 时带 interrupt+preserveQueue（中断当前回复保留余下队列）。
     * 失败回滚整段队列。对齐 Web queueBarPromoteIndex。
     */
    fun promoteQueued(index: Int) {
        if (queuePromotePending) return
        val prev = queuedMessages
        if (index < 0 || index >= prev.size) return
        val picked = prev[index]
        val rest = prev.toMutableList().apply { removeAt(index) }
        val inFlight = isInFlight
        queuePromotePending = true
        queuedMessages = rest
        toast = if (inFlight) "已请求中断当前回复，立即发送这条。" else "已立即发送这条消息。"
        scope.launch {
            try {
                val snap = api.promoteQueued(sessionId, index, picked)
                apply(snap)
            } catch (e: Exception) {
                queuedMessages = prev
                toast = e.message ?: "立即发送失败"
            } finally {
                queuePromotePending = false
            }
        }
    }

    fun editQueued(index: Int, text: String) {
        val original = queuedMessages.getOrNull(index) ?: return
        if (text.isBlank()) { toast = "排队消息不能为空。"; return }
        scope.launch {
            try {
                apply(api.editQueued(sessionId, index, original, text.trim()))
            } catch (e: Exception) {
                toast = e.message ?: "编辑排队消息失败"
            }
        }
    }

    /** 删除第 index 条排队消息（乐观 + 失败回滚）。 */
    fun deleteQueued(index: Int) {
        val prev = queuedMessages
        if (index < 0 || index >= prev.size) return
        queuedMessages = prev.toMutableList().apply { removeAt(index) }
        scope.launch {
            try {
                api.deleteQueued(sessionId, index)
            } catch (e: Exception) {
                queuedMessages = prev
                toast = e.message ?: "删除排队消息失败"
            }
        }
    }

    /** 清空全部排队消息（乐观 + 失败回滚）。 */
    fun clearQueued() {
        val prev = queuedMessages
        if (prev.isEmpty()) return
        queuedMessages = emptyList()
        scope.launch {
            try {
                api.clearQueued(sessionId)
                toast = "已清空 ${prev.size} 条排队消息。"
            } catch (e: Exception) {
                queuedMessages = prev
                toast = e.message ?: "清空排队消息失败"
            }
        }
    }

    /** 停止当前回复：结构化会话调 stop（杀掉当前回合），PTY 发 Esc 中断。 */
    fun stopResponding() {
        scope.launch {
            try {
                if (isStructured) {
                    api.stopSession(sessionId)
                    isResponding = false
                } else {
                    api.sendPtyInputChunk(sessionId, "\u001B", "chat", "esc")
                }
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            }
        }
    }

    /** 权限决策。PTY 与 Claude SDK structured 都走 approve/deny；无 pending 时忽略。 */
    fun resolvePermission(resolution: String, expectedRequestId: String? = pendingEscalation?.requestId) {
        if (pendingEscalation?.requestId != expectedRequestId) { toast = "权限请求已变化，请核对当前请求。"; return }
        val esc = pendingEscalation
        if (esc != null) {
            pendingEscalation = null
            permissionBlocked = false
            scope.launch {
                try {
                    val snap = api.resolveEscalation(sessionId, esc.requestId, resolution)
                    apply(snap)
                } catch (e: Exception) {
                    toast = e.message ?: "操作失败"
                    socket.requestResync()
                }
            }
        } else if (legacyPermissionPrompt != null) {
            legacyPermissionPrompt = null
            permissionBlocked = false
            scope.launch {
                try {
                    if (resolution == "deny") {
                        api.denyPermission(sessionId)
                    } else {
                        api.approvePermission(sessionId)
                    }
                } catch (e: Exception) {
                    toast = e.message ?: "操作失败"
                    socket.requestResync()
                }
            }
        }
    }

    /** 会话已结束时按 claudeSessionId 原地恢复（服务端 reuseId 复用本会话）。 */
    fun resume() {
        scope.launch {
            try {
                val snap = api.resumeSession(sessionId)
                apply(snap)
                socket.requestResync()
                toast = "会话已恢复"
            } catch (e: Exception) {
                toast = e.message ?: "恢复失败"
            }
        }
    }

    /**
     * 静默加载更早的一页，挂起直到这一页有结果。两阶段：先按「块」翻完 messages[0] 这条 turn
     * 被块级窗口切掉的头部，再按「整条 turn」往前翻更早的会话 —— 与 iOS ChatStore 一致。
     *
     * 返回 true = 这一页已并入。失败不弹提示，只置 [earlierLoadFailed] 让顶部 loading 留在原位，
     * 由调用方稍后重试（翻页对用户是透明的）。
     */
    suspend fun loadEarlierPage(): Boolean {
        if (loadingEarlier) return false
        val pagingBlocks = leadingBlockOffset > 0
        if (!pagingBlocks && loadedOffset <= 0) return false
        loadingEarlier = true
        earlierLoadFailed = false
        return try {
            if (pagingBlocks) loadEarlierBlocks() else loadEarlierTurns()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            wlog("chat", "加载更早内容失败 session=$sessionId：${failure.message}")
            earlierLoadFailed = true
            false
        } finally {
            loadingEarlier = false
            earlierPageAttempts++
        }
    }

    /** 把 messages[0] 被切掉的头部按页 prepend 回它的 content；true = 这一页已并入。 */
    private suspend fun loadEarlierBlocks(): Boolean {
        val turnIndex = loadedOffset
        val currentBlockOffset = leadingBlockOffset
        if (currentBlockOffset <= 0) return false
        val head = messages.firstOrNull() ?: return false
        val headRole = head.role
        val page = api.fetchEarlierBlocks(
            id = sessionId,
            turn = turnIndex,
            blockOffset = currentBlockOffset,
            blockLimit = earlierBlockPageSize,
        )
        // 起点被其它更新改过、或 messages[0] 不再是同一条 turn 时不合并，避免错位。
        val current = messages.firstOrNull()
        if (loadedOffset != turnIndex ||
            leadingBlockOffset != currentBlockOffset ||
            current == null ||
            current.role != headRole ||
            page.blocks.isEmpty()
        ) {
            return false
        }
        messages = listOf(current.copy(content = page.blocks + current.content)) + messages.drop(1)
        leadingBlockOffset = page.blockOffset
        leadingBlockTotal = maxOf(leadingBlockTotal, page.blockTotal)
        // 翻上来的这一页同样不需要用户数着走：剩余条数以服务端为准（工具块不计入）。
        leadingVisibleCount = page.blockVisible
        return true
    }

    /** 翻更早的整条 turn：messages[0] 已完整、其前面还有更早 turn 时，prepend 整条并前移 loadedOffset。 */
    private suspend fun loadEarlierTurns(): Boolean {
        val currentOffset = loadedOffset
        val newOffset = maxOf(0, currentOffset - earlierPageSize)
        val limit = currentOffset - newOffset
        if (limit <= 0) return false
        val page = api.fetchMessages(sessionId, newOffset, limit)
        // 仅当起点未被其它更新改动时才 prepend，避免错位重复。
        if (loadedOffset != currentOffset) return false
        messages = page.messages + messages
        loadedOffset = newOffset
        messageTotal = maxOf(messageTotal, page.total)
        // 整条翻页拿到的最旧一条是完整 turn，leading 归零并指向新的 messages[0]。
        leadingBlockOffset = 0
        leadingBlockTotal = messages.firstOrNull()?.content?.size ?: 0
        leadingVisibleCount = 0
        return true
    }
}

internal enum class ChatRealtimeStartKind { Skip, FirstConnect, Reconnect }

/** 会话已终结的服务端状态（与 SessionNotificationPolicy 的判定保持一致）。 */
private val SESSION_ENDED_STATUSES = setOf("exited", "failed", "stopped")

/** 详情页实时连接：页面仍可见时跳过；关过 socket 的同一 store 必须重连而不是被 started 挡住。 */
internal fun chatRealtimeStartKind(active: Boolean, started: Boolean): ChatRealtimeStartKind {
    if (active) return ChatRealtimeStartKind.Skip
    return if (started) ChatRealtimeStartKind.Reconnect else ChatRealtimeStartKind.FirstConnect
}

/** Match browser sendTerminalChunks: text and CR remain separate PTY input requests. */
private const val PTY_CHAT_SUBMIT_CHUNK_INTERVAL_MS = 30L
