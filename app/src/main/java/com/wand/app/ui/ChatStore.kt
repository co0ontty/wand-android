package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.ModelInfo
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WandSocket
import com.wand.app.data.WsData
import com.wand.app.data.WsIncoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class ChatStore(val sessionId: String, val api: WandApi) {

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

    /**
     * AskUserQuestion 卡片的选择状态（toolUseId → 各题已选项 + 是否已提交）。
     * 放 store 而非卡片 remember：流式推送会整条替换消息重组视图，局部状态会丢。
     */
    var askUserSelections by mutableStateOf<Map<String, AskUserSelectionState>>(emptyMap())
        private set

    private val socket = WandSocket(api.baseUrl)
    private var started = false

    /** UI 主线程作用域；shutdown 时取消所有未完成请求。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
                loading = false
            } catch (e: Exception) {
                loading = false
                loadError = e.message ?: "加载失败"
            }
            loadModels()
            socket.connect()
            socket.subscribe(sessionId)
        }
    }

    fun shutdown() {
        socket.close()
        scope.cancel()
    }

    // MARK: - 推送合流

    private fun apply(snap: SessionSnapshot) {
        snapshot = snap
        snap.messages?.let { messages = it }
        status = snap.status ?: status
        isResponding = snap.isResponding
        queuedMessages = snap.queuedMessages ?: emptyList()
        pendingEscalation = snap.pendingEscalation
        permissionBlocked = snap.permissionBlocked ?: (snap.pendingEscalation != null)
        currentTaskTitle = snap.currentTaskTitle
        selectedModel = snap.selectedModel
        thinkingEffort = snap.thinkingEffort ?: "off"
        if (snap.pendingEscalation != null) legacyPermissionPrompt = null
    }

    private fun handle(event: WsIncoming) {
        if (event.sessionId != null && event.sessionId != sessionId) return
        when (event.type) {
            "init" -> event.data?.let {
                applyWsSnapshot(it)
                loading = false
            }
            "output" -> event.data?.let { applyOutput(it) }
            "status" -> event.data?.let { applyStatus(it) }
            // 当前任务实时更新（对齐网页 state.currentTask）；data 为 null 表示任务清空。
            "task" -> currentTaskTitle = event.data?.taskTitle
            "ended" -> {
                val data = event.data
                if (data != null) {
                    data.messages?.let { messages = it }
                    status = data.status ?: "exited"
                    isResponding = false
                    applyCommonFields(data)
                } else {
                    status = "exited"
                    isResponding = false
                }
            }
            "error" -> event.error?.takeIf { it.isNotEmpty() }?.let { toast = it }
        }
    }

    /** init 的 data 就是一份完整 SessionSnapshot（以 WsData 超集形状承接）。 */
    private fun applyWsSnapshot(data: WsData) {
        data.messages?.let { messages = it }
        status = data.status ?: status
        data.structuredState?.let { isResponding = it.inFlight ?: false }
        applyCommonFields(data)
        if (snapshot == null) {
            // 极端情况：REST 快照还没回来 WS init 先到，补一份最小 snapshot。
            data.toSnapshot()?.let { snapshot = it }
        }
    }

    private fun applyOutput(data: WsData) {
        val incremental = data.incremental ?: false
        val full = data.messages
        val incoming = data.lastMessage
        if (full != null) {
            // 全量赢
            messages = full
        } else if (incremental && incoming != null) {
            val expected = data.messageCount ?: 0
            val last = messages.lastOrNull()
            if (last != null && last.role == incoming.role) {
                messages = messages.dropLast(1) + incoming
            } else if (messages.size < expected || expected == 0) {
                messages = messages + incoming
            }
        }
        data.isResponding?.let { isResponding = it }
        applyCommonFields(data)
    }

    private fun applyStatus(data: WsData) {
        data.status?.let { status = it }
        applyCommonFields(data)
        // PTY 旧式权限提示：status 事件带 permissionRequest（无结构化 escalation 时启用）。
        val prompt = data.permissionRequest
        if (prompt != null && pendingEscalation == null) {
            legacyPermissionPrompt = prompt
            permissionBlocked = true
        }
    }

    private fun applyCommonFields(data: WsData) {
        data.structuredState?.let { isResponding = it.inFlight ?: isResponding }
        data.queuedMessages?.let { queuedMessages = it }
        data.pendingEscalation?.let {
            pendingEscalation = it
            legacyPermissionPrompt = null
        }
        data.permissionBlocked?.let { blocked ->
            permissionBlocked = blocked
            if (!blocked) {
                pendingEscalation = null
                legacyPermissionPrompt = null
            }
        }
        data.currentTaskTitle?.let { currentTaskTitle = it }
        data.selectedModel?.let { selectedModel = it }
        data.thinkingEffort?.let { thinkingEffort = it }
    }

    // MARK: - 模型与思考深度（对齐 iOS setModel / setThinkingEffort：乐观更新 + 失败回滚）

    fun setModel(model: String?) {
        val previous = selectedModel
        selectedModel = model
        scope.launch {
            try {
                val snap = api.setModel(sessionId, model)
                apply(snap)
            } catch (e: Exception) {
                selectedModel = previous
                toast = e.message ?: "切换模型失败"
            }
        }
    }

    fun chooseThinkingEffort(effort: String) {
        val previous = thinkingEffort
        thinkingEffort = effort
        scope.launch {
            try {
                val snap = api.setThinkingEffort(sessionId, effort)
                apply(snap)
            } catch (e: Exception) {
                thinkingEffort = previous
                toast = e.message ?: "调整思考深度失败"
            }
        }
    }

    private suspend fun loadModels() {
        val response = try {
            api.models()
        } catch (_: Exception) {
            return
        }
        availableModels =
            if (snapshot?.provider == "codex") response.codexModels else response.models
        defaultModel = response.defaultModel
    }

    // MARK: - 用户动作

    /** 发送一条消息。PTY 会话走 chat 视图语义（结尾补换行），结构化会话直接发文本。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // 乐观插入用户消息，等服务端推送修正。
        if (isStructured) {
            messages = messages + ConversationTurn(
                role = "user",
                content = listOf(com.wand.app.data.ContentBlock.Text(trimmed, null)),
            )
            isResponding = true
        }
        scope.launch {
            try {
                if (isStructured) {
                    api.sendInput(sessionId, trimmed)
                } else {
                    api.sendInput(sessionId, trimmed + "\n", view = "chat")
                }
            } catch (e: Exception) {
                toast = e.message ?: "发送失败"
                if (isStructured) isResponding = false
            }
        }
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
                    api.sendInput(sessionId, answerText)
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
        get() = snapshot?.structuredState?.inFlight == true && status == "running"

    /**
     * 把第 index 条排队消息「立即发送」。
     * 乐观剥掉这一条；inFlight 时带 interrupt+preserveQueue（中断当前回复保留余下队列）。
     * 失败回滚整段队列。对齐 Web queueBarPromoteIndex。
     */
    fun promoteQueued(index: Int) {
        val prev = queuedMessages
        if (index < 0 || index >= prev.size) return
        val picked = prev[index]
        val rest = prev.toMutableList().apply { removeAt(index) }
        val inFlight = isInFlight
        queuedMessages = rest
        toast = if (inFlight) "已请求中断当前回复，立即发送这条。" else "已立即发送这条消息。"
        scope.launch {
            try {
                val snap = api.promoteQueued(sessionId, picked, inFlight)
                apply(snap)
            } catch (e: Exception) {
                queuedMessages = prev
                toast = e.message ?: "立即发送失败"
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
}
