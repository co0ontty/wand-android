package com.wand.app

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.MessageUpdate
import com.wand.app.data.SessionChanges
import com.wand.app.data.SessionEvent
import com.wand.app.data.arrayField
import com.wand.app.data.WandApi
import com.wand.app.data.WandSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话通知中枢 —— 原生路径对 WebView 时代「网页驱动通知」的替代（v1.1 缺口）。
 *
 * 服务端 /ws 把 task / status / ended / output 事件广播给所有客户端（无须逐会话
 * subscribe），所以一条不订阅的全局连接就能看到所有会话的动态。本类把这些事件
 * 转成系统通知，语义逐条对齐网页端 notifications.ts / websocket.ts：
 *
 *  - task    → 实时进度通知（NotificationHelper.updateSessionProgress）
 *              +「任务进行中」（90s 节流，仅 App 在后台时发，对齐 onlyWhenHidden）
 *  - status  → permissionRequest / pendingEscalation →「需要你的授权」（60s 节流；
 *              后台或没在看该会话时发）；同步进度通知
 *  - output  → 维护 latestUserText / latestAssistantText / TodoWrite todos 供进度
 *              通知展示；isResponding true→false 视为「回合完成」（10s 节流，仅
 *              后台或没在看该会话时发，带提示音）—— PTY 由 ClaudePtyBridge 透传、
 *              结构化由 structuredState.inFlight 驱动，两个 runner 都覆盖
 *  - ended   →「任务已完成 / 任务异常结束」+ 清进度通知（对齐 notifyTaskEnded）
 *
 * 由 HomeActivity 在认证成功后 start()；切换服务器 / 断开连接时 stop()。
 * 生命周期跟随进程 —— 配合设置页「后台保活」前台服务可在后台长期接收。
 */
object SessionWatcher {

    private const val LIST_REFRESH_MIN_INTERVAL_MS = 5_000L

    /** 单个被观察会话的轻量状态（只存进度通知需要的字段，不存完整消息）。 */
    private class Watched {
        var label: String = ""
        var status: String = "running"
        var archived: Boolean = false
        var busy: Boolean = false
        var permissionBlocked: Boolean = false
        var latestUserText: String = ""
        var latestAssistantText: String = ""
        var todos: JSONArray? = null
    }

    private var appContext: Context? = null
    private var api: WandApi? = null
    private var socket: WandSocket? = null
    private var helper: NotificationHelper? = null
    private var serverStore: ServerStore? = null
    private var scope: CoroutineScope? = null
    private var serverUrl: String = ""
    private var appToken: String? = null

    private val sessions = HashMap<String, Watched>()
    private val notificationPolicy = SessionNotificationPolicy { SystemClock.elapsedRealtime() }
    private var lastListRefreshAt = 0L

    /** ChatScreen 注册的「正在看」的会话；前台 + 正在看 → 抑制该会话的打扰通知。 */
    var activeChatSessionId: String? = null

    private var startedActivities = 0
    private var lifecycleRegistered = false
    private val appInForeground: Boolean get() = startedActivities > 0

    // MARK: - 生命周期（主线程调用）

    fun start(context: Context, baseUrl: String, token: String?) {
        // 同一服务器重复 start（Activity 重建 / 重新认证）幂等。
        if (socket != null && serverUrl == baseUrl) return
        stop()

        val app = context.applicationContext
        appContext = app
        serverUrl = baseUrl
        appToken = token
        api = WandApi(baseUrl, token)
        helper = NotificationHelper(app).also { it.createChannels() }
        serverStore = ServerStore(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        registerForegroundTracking(app)

        val ws = WandSocket(baseUrl)
        ws.onEvent = { event -> handle(event) }
        ws.onConnectionChange = { up -> if (up) refreshSessions() }
        socket = ws
        ws.connect()
    }

    fun stop() {
        socket?.close()
        socket = null
        scope?.cancel()
        scope = null
        helper?.cancelAllProgress()
        sessions.clear()
        notificationPolicy.reset()
        serverUrl = ""
    }

    private fun registerForegroundTracking(app: Context) {
        if (lifecycleRegistered) return
        val application = app as? Application ?: return
        lifecycleRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // MARK: - 会话列表（label 来源）

    /** 拉一次会话列表补全 label / 初始状态。重连和新会话时调用，5s 去抖。 */
    private fun refreshSessions(force: Boolean = false) {
        val currentApi = api ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastListRefreshAt < LIST_REFRESH_MIN_INTERVAL_MS) return
        lastListRefreshAt = now
        scope?.launch {
            try {
                val list = currentApi.listSessions()
                for (snap in list) {
                    val w = sessions.getOrPut(snap.id) { Watched() }
                    val label = snap.summary?.takeIf { it.isNotEmpty() }
                        ?: snap.command?.takeIf { it.isNotEmpty() }
                        ?: snap.id
                    w.label = label
                    snap.status?.let { w.status = it }
                    w.archived = snap.archived ?: false
                    w.permissionBlocked = snap.hasPendingPermission
                }
            } catch (_: Exception) {
                // 静默：下一次重连 / started 事件还会再试。
            }
        }
    }

    // MARK: - 事件分发（WandSocket 已保证主线程 FIFO）

    private fun handle(event: SessionEvent) {
        val sid = event.sessionId ?: return
        when (event) {
            is SessionEvent.TaskChanged -> handleTask(sid, event.title)
            is SessionEvent.StatusChanged -> handleStatus(sid, event)
            is SessionEvent.Output -> handleOutput(sid, event)
            is SessionEvent.Ended -> handleEnded(sid, event)
            is SessionEvent.Started -> refreshSessions(force = true)
            is SessionEvent.Initialized, is SessionEvent.Error -> Unit
        }
    }

    private fun watched(sid: String): Watched = sessions.getOrPut(sid) {
        // 事件先于列表到达（如 App 启动瞬间）：补一次列表拿 label。
        refreshSessions()
        Watched()
    }

    private fun handleTask(sid: String, title: String?) {
        val w = watched(sid)
        deliver(notificationPolicy.taskProgress(sid, labelOf(sid, w), title, visibility()))
        syncProgress(sid, w)
    }

    private fun handleStatus(sid: String, event: SessionEvent.StatusChanged) {
        val w = watched(sid)
        applyCommon(w, event.changes)

        // 权限通知：PTY 的 permissionRequest 与结构化的 pendingEscalation 同语义。
        val perm = event.permissionRequest
        val esc = event.changes.pendingEscalation
        if (perm != null || esc != null) {
            w.permissionBlocked = true
            val detail = perm?.prompt?.takeIf { it.isNotEmpty() }
                ?: esc?.reason?.takeIf { it.isNotEmpty() }
                ?: "需要权限审批"
            val target = perm?.target ?: esc?.target
            deliver(
                notificationPolicy.permissionRequired(
                    sid, labelOf(sid, w), detail, target, visibility(),
                ),
            )
        }

        event.responding?.let { updateBusy(sid, w, it) }
        syncProgress(sid, w)
    }

    private fun handleOutput(sid: String, event: SessionEvent.Output) {
        val w = watched(sid)
        applyCommon(w, event.changes)
        when (val messages = event.messages) {
            is MessageUpdate.Full -> scanTurns(w, messages.messages)
            is MessageUpdate.Incremental -> scanTurns(w, listOf(messages.message))
            MessageUpdate.None -> Unit
        }
        event.responding?.let { updateBusy(sid, w, it) }
        // 对齐网页：output 不主动刷进度通知（task/status 事件才刷），避免高频写通知。
    }

    private fun handleEnded(sid: String, event: SessionEvent.Ended) {
        val w = watched(sid)
        applyCommon(w, event.changes)
        w.status = event.status
        w.busy = false
        event.messages?.let { scanTurns(w, it.messages) }
        val exitCode = event.exitCode
        val isError = exitCode != null && exitCode != 0
        deliver(
            notificationPolicy.sessionEnded(
                sid, labelOf(sid, w), w.latestAssistantText, isError, visibility(),
            ),
        )
        helper?.clearSessionProgress(sid)
    }

    private fun applyCommon(w: Watched, changes: SessionChanges) {
        changes.status?.let { w.status = it }
        changes.archived?.let { w.archived = it }
        changes.summary?.takeIf { it.isNotEmpty() }?.let { w.label = it }
        changes.permissionBlocked?.let { w.permissionBlocked = it }
    }

    /** busy true→false 即「回合完成」——两个 runner 的完成信号都汇到这里。 */
    private fun updateBusy(sid: String, w: Watched, busy: Boolean) {
        val was = w.busy
        w.busy = busy
        if (!was || busy) return
        deliver(
            notificationPolicy.responseCompleted(
                sid,
                labelOf(sid, w),
                w.latestAssistantText,
                w.permissionBlocked,
                w.status,
                visibility(),
            ),
        )
    }

    // MARK: - 消息扫描（latest texts + TodoWrite todos）

    /** 从尾往前扫一段对话，更新进度通知用的最新用户/助手文本与 todos。 */
    private fun scanTurns(w: Watched, turns: List<ConversationTurn>) {
        for (i in turns.indices.reversed()) {
            val turn = turns[i]
            if (turn.role == "user") {
                val text = firstText(turn)
                if (text.isNotEmpty()) {
                    w.latestUserText = compact(text)
                    // 增量场景只有一条，全量场景最新一条就够 —— 找到即止。
                    updateTodos(w, turn)
                    break
                }
            } else if (turn.role == "assistant") {
                val text = lastText(turn)
                if (text.isNotEmpty()) w.latestAssistantText = compact(text)
                updateTodos(w, turn)
                if (turns.size == 1) break
            }
            if (i == 0) break
        }
        // 全量列表时单独再扫一遍 todos（最近一次 TodoWrite 可能不在末条）。
        if (turns.size > 1) {
            outer@ for (i in turns.indices.reversed()) {
                for (block in turns[i].content.reversed()) {
                    if (block is ContentBlock.ToolUse && block.name == "TodoWrite") {
                        block.input.arrayField("todos")?.let { w.todos = it }
                        break@outer
                    }
                }
            }
        }
    }

    private fun updateTodos(w: Watched, turn: ConversationTurn) {
        for (block in turn.content.reversed()) {
            if (block is ContentBlock.ToolUse && block.name == "TodoWrite") {
                block.input.arrayField("todos")?.let { w.todos = it }
                return
            }
        }
    }

    private fun firstText(turn: ConversationTurn): String {
        for (block in turn.content) {
            if (block is ContentBlock.Text && block.text.isNotBlank()) return block.text
        }
        return ""
    }

    private fun lastText(turn: ConversationTurn): String {
        for (block in turn.content.reversed()) {
            if (block is ContentBlock.Text && block.text.isNotBlank()) return block.text
        }
        return ""
    }

    /** 对齐网页 _compactNotificationText：去 markdown、取首行、截 100 字。 */
    private fun compact(text: String): String {
        val cleaned = text
            .replace(Regex("(?m)^#+\\s+"), "")
            .replace("**", "")
            .replace("`", "")
            .trim()
        val firstLine = cleaned.lineSequence().firstOrNull()?.trim() ?: ""
        return if (firstLine.length > 100) firstLine.take(100) + "…" else firstLine
    }

    // MARK: - 进度通知（对齐 _doSyncSessionProgress）

    private fun syncProgress(sid: String, w: Watched) {
        val h = helper ?: return
        if (w.archived || w.status in CLEAR_PROGRESS_STATUSES) {
            h.clearSessionProgress(sid)
            return
        }
        val data = JSONObject()
            .put("sessionLabel", labelOf(sid, w))
            .put("status", w.status)
            .put("latestUserText", w.latestUserText)
            .put("todos", w.todos ?: JSONArray())
        h.updateSessionProgress(sid, data.toString(), contentIntent())
    }

    // MARK: - 通知小工具

    private fun labelOf(sid: String, w: Watched): String =
        w.label.ifEmpty { sid.take(8) }

    private fun visibility() = NotificationVisibility(appInForeground, activeChatSessionId)

    private fun deliver(notification: SessionNotification?) {
        notification ?: return
        val h = helper ?: return
        val store = serverStore ?: return
        h.sendNotification(
            notification.title,
            notification.body,
            notification.tag,
            contentIntent(),
            store,
        )
    }

    private fun contentIntent(): PendingIntent? {
        val ctx = appContext ?: return null
        val intent = Intent(ctx, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("server_url", serverUrl)
            appToken?.let { putExtra("app_token", it) }
        }
        return PendingIntent.getActivity(
            ctx,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val CLEAR_PROGRESS_STATUSES = setOf("idle", "archived", "exited", "failed", "stopped")
}
