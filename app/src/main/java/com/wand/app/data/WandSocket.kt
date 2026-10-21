package com.wand.app.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.wand.app.WandLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * /ws 的 WebSocket 客户端 —— 对称 iOS 端 WandSocket.swift。
 * 订阅单个会话，处理 init/output/status/ended 推送、应用层 ping/pong、
 * seq 间隙检测（自动 resync）、断线指数退避重连与 40s 看门狗。
 *
 * 所有状态读写与回调都经主线程 Handler 串行化 —— Handler.post 保证 FIFO，
 * 这是增量合流（替换末条 vs 追加）正确性的前提；不能用协程 launch
 * （不保证顺序）。复用当前 endpoint 的 WandHttp client，自签证书与 session cookie 自动生效。
 */
class WandSocket(baseUrl: String, private val appToken: String? = null) {

    /** 解析后的服务端推送，主线程回调。 */
    var onEvent: ((SessionEvent) -> Unit)? = null
    /** System notification for a changed employee definition; unrelated to session input. */
    var onEmployeeDefinitionChanged: ((String) -> Unit)? = null
    /** Read-only catalog invalidation, not an input or model mutation. */
    var onModelCatalogChanged: (() -> Unit)? = null
    /** Existing bounded team live feed; never treated as session input. */
    var onTeamStepLive: ((AiTeamRunLive) -> Unit)? = null
    var onConversationChanged: (() -> Unit)? = null
    var onConversationSessionPreview: ((ConversationSessionUpdate) -> Unit)? = null

    /** 连接状态变化（true=已连上），主线程回调。 */
    var onConnectionChange: ((Boolean) -> Unit)? = null
    var onAuthenticationFailure: ((String) -> Unit)? = null

    /**
     * 订阅时声明的块级窗口预算（结构化聊天用，对齐 Web/iOS）：服务端据此把
     * init/resync/全量快照切成最近 N 个内容块，长任务首屏就不会因为单条 turn
     * 上百块而拉回整份历史。null 表示继续走 turn 级窗口（PTY / 通知中枢）。
     */
    var blockBudget: Int? = null
    var byteBudget: Int? = null

    /** 原生 PTY 专用原始帧；聊天订阅仍只收到类型化 SessionEvent。 */
    internal var onPtyEvent: ((WsIncoming) -> Unit)? = null
    internal var onPtyResync: (() -> Unit)? = null

    private val baseUrl = WandHttp.normalizeBaseUrl(baseUrl)
    private val client = WandHttp.clientFor(this.baseUrl)
    private val feedback = ClientFeedback(this.baseUrl, appToken)
    private val handler = Handler(Looper.getMainLooper())

    private var sharedHub: ChatHub? = null
    internal val sharedConnection: WandSocket get() = sharedHub?.host ?: this
    private var wireFrame: ((DecodedFrame) -> Unit)? = null
    private var webSocket: WebSocket? = null
    private var connected = false
    private var subscribedSessionId: String? = null
    private var ptyAck = false
    private var awaitingPtySnapshot = false
    private val lastSeqBySession = mutableMapOf<String, Int>()
    private var lastMessageAt = SystemClock.elapsedRealtime()
    private var reconnectDelayMs = 1_000L
    private var reconnectScheduled = false
    private var closed = true
    private var authenticationJob: Job? = null
    private var forceReauthenticate = false

    /** 当前连接的代号，旧连接的回调用它识别后丢弃，避免互相干扰。 */
    private var generation = 0

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (closed) return
            // 服务端每 20s 发应用层 ping；40s 没收到任何消息视为半开连接，强制重建。
            if (webSocket != null &&
                SystemClock.elapsedRealtime() - lastMessageAt > WATCHDOG_TIMEOUT_MS
            ) {
                lastMessageAt = SystemClock.elapsedRealtime()
                feedback.report("websocket_error", "realtime", java.net.SocketTimeoutException("WebSocket heartbeat timeout"))
                scheduleReconnect()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // MARK: - 生命周期（主线程调用）

    fun connect() {
        if (!closed) return
        closed = false
        sharedHub?.let { it.add(this); return }
        openSocket()
        restartWatchdog()
    }

    /**
     * 回前台强制拆掉可能已被系统冻住的半开连接。不先发 disconnected：主动重建不应闪红条，
     * 只有新连接失败才走 onFailure → scheduleReconnect。
     */
    fun reconnectForForeground() {
        if (closed) return
        sharedHub?.let { it.recover(this); return }
        authenticationJob?.cancel()
        authenticationJob = null
        reconnectScheduled = false
        reconnectDelayMs = 1_000L
        generation += 1
        webSocket?.cancel()
        webSocket = null
        connected = false
        if (ptyAck) {
            awaitingPtySnapshot = true
            onPtyResync?.invoke()
        }
        lastMessageAt = SystemClock.elapsedRealtime()
        openSocket()
        restartWatchdog()
    }

    fun close() {
        closed = true
        sharedHub?.let { it.remove(this); connected = false; lastSeqBySession.clear(); return }
        authenticationJob?.cancel()
        authenticationJob = null
        handler.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        generation += 1
        webSocket?.close(1001, null)
        webSocket = null
        connected = false
        awaitingPtySnapshot = true
    }

    fun subscribe(sessionId: String, ptyAck: Boolean = false) {
        val previous = subscribedSessionId
        subscribedSessionId = sessionId
        if (previous != null && previous != sessionId) sharedHub?.release(previous)
        this.ptyAck = ptyAck
        awaitingPtySnapshot = true
        sendSubscribe(sessionId)
    }

    fun requestResync() {
        val id = subscribedSessionId ?: return
        WandLog.i(TAG, "请求重发全量快照 session=$id")
        lastSeqBySession.remove(id)
        if (ptyAck) {
            awaitingPtySnapshot = true
            onPtyResync?.invoke()
        }
        sendJson(JSONObject().put("type", "resync").put("sessionId", id))
    }

    /** 不重放未确认的键击；只有持有最新快照的连接才能发送 PTY 输入。 */
    fun sendPtyInput(text: String, userInput: Boolean = true, shortcutKey: String? = null): Boolean {
        val id = subscribedSessionId ?: return false
        if (text.isEmpty() || !connected || (ptyAck && awaitingPtySnapshot)) return false
        for (chunk in ptyInputChunks(text)) {
            val payload = JSONObject().put("type", "pty_input").put("sessionId", id)
                .put("data", chunk).put("userInput", userInput)
            if (userInput && chunk == "\r") payload.put("shortcutKey", shortcutKey ?: "enter_text")
            else if (shortcutKey != null) payload.put("shortcutKey", shortcutKey)
            if (!sendJson(payload)) return false
        }
        return true
    }

    fun resizePty(cols: Int, rows: Int) {
        val id = subscribedSessionId ?: return
        if (!connected || cols !in 1..1000 || rows !in 1..1000 || awaitingPtySnapshot) return
        sendJson(JSONObject().put("type", "pty_resize").put("sessionId", id)
            .put("cols", cols).put("rows", rows))
    }

    fun acknowledgePty(bytes: Int) {
        val id = subscribedSessionId ?: return
        if (ptyAck && bytes > 0) {
            sendJson(JSONObject().put("type", "pty_ack").put("sessionId", id).put("bytes", bytes))
        }
    }

    // MARK: - 内部

    /** OkHttp 接受 ws:// 形式也接受 http:// 形式的 WS 升级 URL；直接复用 http(s) base。 */
    private val wsUrl: String get() = "$baseUrl/ws"

    private fun restartWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    /** 重新订阅一个会话：丢掉旧的 seq 基准，服务端随即推一份 init 快照。 */
    private fun sendSubscribe(sessionId: String) {
        lastSeqBySession.remove(sessionId)
        if (ptyAck) awaitingPtySnapshot = true
        val payload = JSONObject()
            .put("type", "subscribe")
            .put("sessionId", sessionId)
            .put("compactTools", true)
            .put("capabilities", JSONObject().put("ptyAck", ptyAck))
        blockBudget?.takeIf { it > 0 }?.let {
            payload.put("blockBudget", it).put("omitStructuredOutput", true)
        }
        byteBudget?.takeIf { it > 0 }?.let { payload.put("byteBudget", it) }
        sendJson(payload)
    }

    private fun openSocket() {
        if (closed) return
        reconnectScheduled = false
        generation += 1
        val gen = generation
        lastMessageAt = SystemClock.elapsedRealtime()
        val token = appToken
        if (!token.isNullOrEmpty() &&
            (forceReauthenticate || WandHttp.cookieHeaderFor(baseUrl).isNullOrEmpty())
        ) {
            authenticationJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                try {
                    WandAuth.loginWithToken(baseUrl, token, client)
                    if (closed || gen != generation) return@launch
                    forceReauthenticate = false
                    createSocket(gen)
                } catch (error: Exception) {
                    if (closed || gen != generation) return@launch
                    if (error is WandAuth.AuthException && !error.retryable) {
                        WandLog.w(TAG, "WebSocket 鉴权被拒")
                        onConnectionChange?.invoke(false)
                        onAuthenticationFailure?.invoke(error.message ?: "登录已失效，请重新连接")
                        return@launch
                    }
                    WandLog.w(TAG, "WebSocket 重新登录失败：${error.javaClass.simpleName}", error)
                    scheduleReconnect(authRequired = true)
                }
            }
            return
        }
        createSocket(gen)
    }

    private fun createSocket(gen: Int) {
        val request = Request.Builder().url(wsUrl).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (gen != generation || closed) return@post
                    lastMessageAt = SystemClock.elapsedRealtime()
                    reconnectDelayMs = 1_000L
                    connected = true
                    WandLog.i(TAG, "WebSocket 已连接 $wsUrl")
                    onConnectionChange?.invoke(true)
                    // 重新订阅当前会话；服务端会推一份 init 快照，相当于天然 resync。
                    subscribedSessionId?.let { id -> sendSubscribe(id) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // OkHttp invokes onMessage serially on its reader thread. Decode there,
                // then post one typed frame: preserve FIFO without UI-thread JSON work.
                val frame = decodeFrame(text) ?: return
                handler.post {
                    if (gen != generation || closed) return@post
                    lastMessageAt = SystemClock.elapsedRealtime()
                    reconnectDelayMs = 1_000L
                    handleFrame(frame)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    if (gen != generation || closed) return@post
                    WandLog.w(TAG, "WebSocket 断开：${t.javaClass.simpleName} HTTP ${response?.code ?: 0}", t)
                    feedback.report("websocket_error", "realtime", t, response?.code?.toString())
                    scheduleReconnect(authRequired = response?.code == 401)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    if (gen != generation || closed) return@post
                    val unauthorized = code == 1008 && reason == "Unauthorized"
                    WandLog.w(TAG, "WebSocket 关闭 code=$code auth=$unauthorized")
                    if (code != 1000 && code != 1001) {
                        feedback.report("websocket_error", "realtime", java.io.IOException(reason), code.toString())
                    }
                    scheduleReconnect(authRequired = unauthorized)
                }
            }
        })
        webSocket = socket
    }

    private fun handleFrame(frame: DecodedFrame) {
        if (frame.incoming.type != "ping") wireFrame?.let { it(frame); return }
        if (frame.modelsChanged) onModelCatalogChanged?.invoke()
        if (frame.conversationChanged) onConversationChanged?.invoke()
        frame.teamLive?.let { onTeamStepLive?.invoke(it) }
        frame.preview?.let { onConversationSessionPreview?.invoke(it) }
        frame.employeeId?.let { onEmployeeDefinitionChanged?.invoke(it) }
        val incoming = frame.incoming
        if (incoming.sessionId != null && incoming.sessionId != "__system__" && incoming.sessionId != subscribedSessionId) return

        when (incoming.type) {
            "ping" -> {
                sendJson(JSONObject().put("type", "pong").put("t", incoming.t ?: 0.0))
                return
            }
            "resync_required" -> {
                if (incoming.sessionId == null || incoming.sessionId == subscribedSessionId) {
                    requestResync()
                }
                return
            }
            "init" -> {
                val id = incoming.sessionId
                val seq = incoming.seq
                if (id != null && seq != null) lastSeqBySession[id] = seq
                if (ptyAck && id == subscribedSessionId) awaitingPtySnapshot = false
                WandLog.d(TAG, "收到 init session=$id seq=$seq")
            }
            "output" -> {
                // seq 间隙说明服务端因背压丢过事件，主动要一份全量快照。
                val id = incoming.sessionId
                val seq = incoming.seq
                if (id != null && seq != null) {
                    val last = lastSeqBySession[id]
                    if (ptyAck && id == subscribedSessionId &&
                        (awaitingPtySnapshot || (last != null && seq <= last))) {
                        acknowledgePty(incoming.ptyBytes ?: 0)
                        return
                    }
                    if (last != null && seq > last + 1) {
                        WandLog.w(TAG, "事件序号出现间隙 session=$id seq=$last→$seq，触发 resync")
                        if (ptyAck && id == subscribedSessionId) acknowledgePty(incoming.ptyBytes ?: 0)
                        requestResync()
                        return
                    }
                    lastSeqBySession[id] = seq
                }
            }
        }
        if (incoming.sessionId == null || incoming.sessionId == subscribedSessionId) {
            onPtyEvent?.invoke(incoming)
        }
        frame.event?.let { onEvent?.invoke(it) }
    }

    private fun sendJson(payload: JSONObject): Boolean {
        sharedHub?.let { return if (closed) false else it.send(payload) }
        return webSocket?.send(payload.toString()) == true
    }

    // MARK: - 重连与看门狗

    private fun scheduleReconnect(authRequired: Boolean = false) {
        if (authRequired) forceReauthenticate = true
        if (closed || reconnectScheduled) return
        reconnectScheduled = true
        generation += 1
        onConnectionChange?.invoke(false)
        webSocket?.cancel()
        webSocket = null
        connected = false
        awaitingPtySnapshot = ptyAck
        onPtyResync?.invoke()
        val delay = reconnectDelayMs
        WandLog.i(TAG, "WebSocket 将在 ${delay}ms 后重连（authRequired=$authRequired）")
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
        val scheduledGeneration = generation
        handler.postDelayed({
            if (generation != scheduledGeneration || closed) return@postDelayed
            reconnectScheduled = false
            if (webSocket == null) openSocket()
        }, delay)
    }

    /** Read-only chat leases only; PTY transports retain independent ACK/input ownership. */
    private class ChatHub(api: WandApi) {
        val host = WandSocket(api.baseUrl, api.token)
        private val peers = ChatLeaseSet<WandSocket> { if (it.closed) null else it.subscribedSessionId }
        private var lastRecovery = 0L
        init {
            val weak = java.lang.ref.WeakReference(this)
            host.wireFrame = { frame -> weak.get()?.peers?.snapshot()?.filter { !it.closed }?.forEach { it.handleFrame(frame) } }
            host.onConnectionChange = { up ->
                weak.get()?.peers?.snapshot()?.filter { !it.closed }?.forEach { peer ->
                    peer.connected = up
                    if (!up) peer.lastSeqBySession.clear()
                    peer.onConnectionChange?.invoke(up)
                    if (up) peer.subscribedSessionId?.let { peer.sendSubscribe(it) }
                }
            }
            host.onAuthenticationFailure = { error -> weak.get()?.peers?.snapshot()?.forEach { it.onAuthenticationFailure?.invoke(error) } }
        }
        fun add(peer: WandSocket) {
            peers.add(peer)
            if (host.closed) host.connect()
            else {
                peer.connected = host.connected
                peer.onConnectionChange?.invoke(host.connected)
                if (host.connected) peer.subscribedSessionId?.let { peer.sendSubscribe(it) }
            }
        }
        fun release(id: String) {
            if (!peers.uses(id)) host.sendJson(JSONObject().put("type", "unsubscribe").put("sessionId", id))
        }
        fun remove(peer: WandSocket) {
            peers.remove(peer)
            peer.subscribedSessionId?.let(::release)
            if (peers.isEmpty()) host.close()
        }
        fun send(payload: JSONObject): Boolean {
            if (payload.optString("type") == "subscribe") payload.put("mode", "add")
            return host.sendJson(payload)
        }
        fun recover(peer: WandSocket) {
            val now = SystemClock.elapsedRealtime()
            if (host.connected && now - host.lastMessageAt < 35_000) { peer.requestResync(); return }
            if (now - lastRecovery < 1_000) return
            lastRecovery = now
            host.reconnectForForeground()
        }
    }

    internal data class DecodedFrame(
        val incoming: WsIncoming,
        val event: SessionEvent?,
        val modelsChanged: Boolean,
        val teamLive: AiTeamRunLive?,
        val preview: ConversationSessionUpdate?,
        val employeeId: String?,
        val conversationChanged: Boolean,
    )

    companion object {
        private const val TAG = "ws"
        private val chatHubs = java.util.WeakHashMap<WandApi, java.lang.ref.WeakReference<ChatHub>>()
        fun sharedChat(api: WandApi): WandSocket {
            val hub = chatHubs[api]?.get() ?: ChatHub(api).also { chatHubs[api] = java.lang.ref.WeakReference(it) }
            return WandSocket(api.baseUrl, api.token).also { it.sharedHub = hub }
        }
        internal fun decodeFrame(text: String): DecodedFrame? = try {
            val packet = JSONObject(text)
            val data = if (packet.optString("type") == "notification") packet.optJSONObject("data") else null
            val kind = data?.optString("kind")
            val incoming = WsIncoming.parse(packet)
            DecodedFrame(
                incoming = incoming,
                event = incoming.toSessionEvent(),
                modelsChanged = kind == "models",
                conversationChanged = kind in listOf("conversations-changed", "ai-team-run", "silicon-employee-definition", "ai-team-definition"),
                teamLive = if (data != null && kind == "ai-team-step-live") AiTeamRunLive.parse(data) else null,
                preview = if (kind == "conversation-session-preview") ConversationSessionUpdate.parse(data) else null,
                employeeId = if (kind == "silicon-employee-definition") data?.optString("employeeId")?.takeIf { it.isNotBlank() } else null,
            )
        } catch (_: Exception) { null }
        /** Split at UTF-8 scalar boundaries below the server per-frame limit. */
        internal fun ptyInputChunks(text: String, maxBytes: Int = 16 * 1024): List<String> {
            if (text.isEmpty()) return emptyList()
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            var bytes = 0
            var offset = 0
            while (offset < text.length) {
                val codepoint = text.codePointAt(offset)
                val part = String(Character.toChars(codepoint))
                val size = part.toByteArray(Charsets.UTF_8).size
                if (bytes + size > maxBytes.coerceAtLeast(4) && current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current.clear()
                    bytes = 0
                }
                current.append(part)
                bytes += size
                offset += Character.charCount(codepoint)
            }
            if (current.isNotEmpty()) chunks.add(current.toString())
            return chunks
        }

        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val WATCHDOG_TIMEOUT_MS = 40_000L
    }
}
