package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.ConversationTurn
import com.wand.app.data.CardExpandDefaults
import com.wand.app.data.ChatSessionEventReducer
import com.wand.app.data.ChatSessionEventState
import com.wand.app.data.EscalationRequest
import com.wand.app.data.ModelInfo
import com.wand.app.data.modelsForProvider
import com.wand.app.data.PendingSessionSettings
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SessionEvent
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WandSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

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
)

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
    var loadingEarlier by mutableStateOf(false)
        private set
    val canLoadEarlier: Boolean get() = loadedOffset > 0
    private val earlierPageSize = 40

    private val socket = WandSocket(api.baseUrl)
    private var started = false
    private var queuePromotePending = false
    private val settingsMutationMutex = Mutex()
    private var modelMutationGeneration = 0L
    private var thinkingMutationGeneration = 0L
    private var modeMutationGeneration = 0L
    private var pendingModelMutations = 0
    private var pendingThinkingMutations = 0
    private var pendingModeMutations = 0
    private var confirmedModel: String? = null
    private var confirmedThinkingEffort = "off"
    private var confirmedMode = "default"

    val isStructured: Boolean get() = snapshot?.isStructured ?: true
    val sessionEnded: Boolean get() = status in listOf("exited", "failed", "stopped")

    // MARK: - 生命周期

    fun start() {
        if (started) return
        started = true

        socket.onEvent = { event -> handle(event) }
        socket.onConnectionChange = { up -> connected = up }

        scope.launch {
            try {
                val snap = api.getSession(sessionId)
                apply(snap)
            } catch (e: Exception) {
                loadError = e.message ?: "加载失败"
            }
            loadModels()
            loadCardDefaults()
            loading = false
            socket.connect()
            socket.subscribe(sessionId)
        }
    }

    override fun shutdown() {
        socket.close()
        super.shutdown()
    }

    // MARK: - 推送合流

    private fun apply(snap: SessionSnapshot) {
        applyRealtimeState(
            ChatSessionEventReducer.applySnapshot(
                current = realtimeState(),
                snapshot = snap,
                pending = PendingSessionSettings(
                    model = pendingModelMutations > 0,
                    thinkingEffort = pendingThinkingMutations > 0,
                    mode = pendingModeMutations > 0,
                ),
            ),
        )
    }

    private fun handle(event: SessionEvent) {
        if (event.sessionId != null && event.sessionId != sessionId) return
        val next = ChatSessionEventReducer.reduce(
            current = realtimeState(),
            event = event,
            pending = PendingSessionSettings(
                model = pendingModelMutations > 0,
                thinkingEffort = pendingThinkingMutations > 0,
                mode = pendingModeMutations > 0,
            ),
        )
        applyRealtimeState(next)
    }

    private fun realtimeState() = ChatSessionEventState(
        messages = messages,
        loadedOffset = loadedOffset,
        messageTotal = messageTotal,
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
        messages = next.messages
        loadedOffset = next.loadedOffset
        messageTotal = next.messageTotal
        status = next.status
        isResponding = next.isResponding
        queuedMessages = next.queuedMessages
        pendingEscalation = next.pendingEscalation
        legacyPermissionPrompt = next.legacyPermissionPrompt
        permissionBlocked = next.permissionBlocked
        currentTaskTitle = next.currentTaskTitle
        snapshot = next.snapshot
        selectedModel = next.selectedModel
        thinkingEffort = next.thinkingEffort
        mode = next.mode
        confirmedModel = next.confirmedModel
        confirmedThinkingEffort = next.confirmedThinkingEffort
        confirmedMode = next.confirmedMode
        next.errorMessage?.let { toast = it }
        if (next.initialized) loading = false
    }

    // MARK: - 模型与思考深度（乐观更新 + 串行请求 + generation 防旧响应覆盖）

    fun setModel(model: String?) {
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

    private suspend fun loadModels() {
        val response = try {
            api.models()
        } catch (_: Exception) {
            return
        }
        val provider = snapshot?.provider ?: "claude"
        availableModels = modelsForProvider(
            provider = provider,
            claude = response.models,
            codex = response.codexModels,
            opencode = response.opencodeModels,
            qoder = response.qoderModels,
        )
        defaultModel = response.defaultModelFor(provider)
        normalizeThinkingEffortFor(selectedModel)
    }

    private suspend fun loadCardDefaults() {
        cardDefaults = try {
            api.serverConfig().cardDefaults
        } catch (_: Exception) {
            CardExpandDefaults()
        }
    }

    // MARK: - 用户动作

    /** 发送一条消息。PTY 会话走 chat 视图语义（结尾补换行），结构化会话直接发文本。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val queueing = isStructured && isResponding && status == "running"
        if (queueing && lastSubmittedStructuredInput() == trimmed) {
            toast = "与上一条消息相同，已忽略，不会加入排队。"
            return
        }
        val previousMessages = messages
        val previousQueue = queuedMessages
        if (isStructured) {
            if (queueing) {
                queuedMessages = queuedMessages + trimmed
                toast = "已加入排队，等当前回复完成会自动发送。"
            } else {
                messages = messages + ConversationTurn(
                    role = "user",
                    content = listOf(com.wand.app.data.ContentBlock.Text(trimmed, null)),
                )
                isResponding = true
            }
        }
        scope.launch {
            try {
                if (isStructured) {
                    // 结构化回复通过事件流持续更新；HTTP 只需确认服务端已接收。
                    // 若等待整轮完成，首轮生成标题与模型回复可能超过 30 秒并被误报为网络超时。
                    api.sendInput(sessionId, trimmed, respondImmediately = !queueing)
                } else {
                    api.sendInput(sessionId, trimmed + "\n", view = "chat")
                }
            } catch (e: Exception) {
                toast = e.message ?: "发送失败"
                if (isStructured) {
                    if (queueing) queuedMessages = previousQueue else messages = previousMessages
                    if (!queueing) isResponding = false
                }
            }
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

    // MARK: - AskUserQuestion 交互（对齐 Web 端 __askSelect / __askSubmit）

    /** 点选一个选项：单选点同一项取消、换选项替换；多选逐项 toggle。已提交后不可改。 */
    fun toggleAskOption(toolUseId: String, questionIndex: Int, optionIndex: Int, multiSelect: Boolean) {
        val sel = askUserSelections[toolUseId] ?: AskUserSelectionState()
        if (sel.submitted) return
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
        if (sel.submitted) return
        askUserSelections = askUserSelections + (toolUseId to sel.copy(submitted = true))
        if (isStructured) isResponding = true
        scope.launch {
            try {
                if (isStructured) {
                    api.sendInput(sessionId, answerText, respondImmediately = true)
                } else {
                    api.sendInput(sessionId, answerText + "\n", view = "chat")
                }
            } catch (e: Exception) {
                toast = e.message ?: "发送失败"
                val rollback = askUserSelections[toolUseId] ?: AskUserSelectionState()
                askUserSelections = askUserSelections + (toolUseId to rollback.copy(submitted = false))
                if (isStructured) isResponding = false
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
                    api.sendInput(sessionId, "\u001B", view = "chat", shortcutKey = "esc")
                }
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            }
        }
    }

    /** 权限决策。结构化 escalation 走 resolve 端点；PTY 旧式提示走 approve/deny。 */
    fun resolvePermission(resolution: String) {
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

    /** 加载更早的一页消息（滚动到顶时触发），prepend 到 messages 并前移 loadedOffset。 */
    fun loadEarlier() {
        if (!canLoadEarlier || loadingEarlier) return
        val currentOffset = loadedOffset
        val newOffset = maxOf(0, currentOffset - earlierPageSize)
        val limit = currentOffset - newOffset
        if (limit <= 0) return
        loadingEarlier = true
        scope.launch {
            try {
                val page = api.fetchMessages(sessionId, newOffset, limit)
                // 仅当起点未被其它更新改动时才 prepend，避免错位重复。
                if (loadedOffset == currentOffset) {
                    messages = page.messages + messages
                    loadedOffset = newOffset
                    messageTotal = maxOf(messageTotal, page.total)
                }
            } catch (e: Exception) {
                toast = e.message ?: "加载更早消息失败"
            } finally {
                loadingEarlier = false
            }
        }
    }
}
