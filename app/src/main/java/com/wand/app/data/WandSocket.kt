package com.wand.app.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * （不保证顺序）。复用 WandHttp 的 client，自签证书与 session cookie 自动生效。
 */
class WandSocket(baseUrl: String) {

    /** 解析后的服务端推送，主线程回调。 */
    var onEvent: ((WsIncoming) -> Unit)? = null

    /** 连接状态变化（true=已连上），主线程回调。 */
    var onConnectionChange: ((Boolean) -> Unit)? = null

    private val baseUrl = WandHttp.normalizeBaseUrl(baseUrl)
    private val handler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var subscribedSessionId: String? = null
    private val lastSeqBySession = mutableMapOf<String, Int>()
    private var lastMessageAt = SystemClock.elapsedRealtime()
    private var reconnectDelayMs = 1_000L
    private var closed = false

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
                scheduleReconnect()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // MARK: - 生命周期（主线程调用）

    fun connect() {
        closed = false
        openSocket()
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        generation += 1
        webSocket?.close(1001, null)
        webSocket = null
    }

    fun subscribe(sessionId: String) {
        subscribedSessionId = sessionId
        lastSeqBySession.remove(sessionId)
        sendJson(JSONObject().put("type", "subscribe").put("sessionId", sessionId))
    }

    fun requestResync() {
        val id = subscribedSessionId ?: return
        lastSeqBySession.remove(id)
        sendJson(JSONObject().put("type", "resync").put("sessionId", id))
    }

    // MARK: - 内部

    /** OkHttp 接受 ws:// 形式也接受 http:// 形式的 WS 升级 URL；直接复用 http(s) base。 */
    private val wsUrl: String get() = "$baseUrl/ws"

    private fun openSocket() {
        if (closed) return
        generation += 1
        val gen = generation
        lastMessageAt = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(wsUrl).build()
        val socket = WandHttp.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (gen != generation || closed) return@post
                    lastMessageAt = SystemClock.elapsedRealtime()
                    onConnectionChange?.invoke(true)
                    // 重新订阅当前会话；服务端会推一份 init 快照，相当于天然 resync。
                    subscribedSessionId?.let { id ->
                        lastSeqBySession.remove(id)
                        sendJson(JSONObject().put("type", "subscribe").put("sessionId", id))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post {
                    if (gen != generation || closed) return@post
                    lastMessageAt = SystemClock.elapsedRealtime()
                    reconnectDelayMs = 1_000L
                    handleText(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    if (gen != generation || closed) return@post
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    if (gen != generation || closed) return@post
                    scheduleReconnect()
                }
            }
        })
        webSocket = socket
    }

    private fun handleText(text: String) {
        val incoming = try {
            WsIncoming.parse(JSONObject(text))
        } catch (_: Exception) {
            return
        }

        when (incoming.type) {
            "ping" -> {
                sendJson(JSONObject().put("type", "pong").put("t", incoming.t ?: 0.0))
                return
            }
            "resync_required" -> {
                requestResync()
                return
            }
            "init" -> {
                val id = incoming.sessionId
                val seq = incoming.seq
                if (id != null && seq != null) lastSeqBySession[id] = seq
            }
            "output" -> {
                // seq 间隙说明服务端因背压丢过事件，主动要一份全量快照。
                val id = incoming.sessionId
                val seq = incoming.seq
                if (id != null && seq != null) {
                    val last = lastSeqBySession[id]
                    if (last != null && seq > last + 1) {
                        lastSeqBySession[id] = seq
                        requestResync()
                        return
                    }
                    lastSeqBySession[id] = seq
                }
            }
        }
        onEvent?.invoke(incoming)
    }

    private fun sendJson(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    // MARK: - 重连与看门狗

    private fun scheduleReconnect() {
        if (closed) return
        onConnectionChange?.invoke(false)
        webSocket?.cancel()
        webSocket = null
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
        handler.postDelayed({
            if (!closed && webSocket == null) openSocket()
        }, delay)
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val WATCHDOG_TIMEOUT_MS = 40_000L
    }
}
