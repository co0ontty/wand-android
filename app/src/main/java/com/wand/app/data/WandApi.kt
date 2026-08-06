package com.wand.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.wand.app.ui.parseIsoMillis

/** REST 错误：status 为 null 表示网络层失败。message 面向用户（中文）。 */
class WandApiException(val status: Int?, message: String) : Exception(message)

/**
 * wand 服务端 REST 客户端 —— 对称 iOS 端 WandAPI.swift。
 * 复用当前 endpoint 的 WandHttp client（自签证书放行 + endpoint 独立 CookieJar），
 * 登录 cookie 自动携带；
 * 遇到 401 时用存储的 appToken 重新登录一次再重试。
 */
class WandApi(baseUrl: String, val token: String?) : SessionListPort, NewSessionPort, MissionsPort {

    val baseUrl: String = WandHttp.normalizeBaseUrl(baseUrl)
    private val client = WandHttp.clientFor(this.baseUrl)

    // MARK: - 基础请求

    private fun buildRequest(method: String, path: String, body: JSONObject?): Request {
        val builder = Request.Builder().url("$baseUrl$path")
        if (body != null) {
            builder.method(method, body.toString().toRequestBody("application/json".toMediaType()))
        } else if (method == "POST" || method == "PUT") {
            builder.method(method, "{}".toRequestBody("application/json".toMediaType()))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private val longTimeoutClient by lazy {
        client.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private fun execute(request: Request, timeoutSec: Int): Pair<Int, String> {
        val requestClient = when (timeoutSec) {
            30 -> client
            180 -> longTimeoutClient
            else -> client.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
        }
        requestClient.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun IOException.toApiException() =
        WandApiException(null, "网络错误：${message ?: "请求失败"}")

    /** 执行一次 HTTP 请求并处理 401 自动重登。 */
    private suspend fun executeWithRetry(request: Request, timeoutSec: Int = 30): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            var (code, text) = try {
                execute(request, timeoutSec)
            } catch (e: IOException) {
                throw e.toApiException()
            }
            if (code == 401 && !token.isNullOrEmpty()) {
                try {
                    WandAuth.loginWithToken(baseUrl, token, client)
                } catch (_: Exception) {
                    throw WandApiException(401, "登录已失效，请重新连接")
                }
                val retried = try {
                    execute(request, timeoutSec)
                } catch (e: IOException) {
                    throw e.toApiException()
                }
                code = retried.first
                text = retried.second
            }
            code to text
        }

    /** 带 401 自动重登的请求入口。返回响应 body 字符串。 */
    private suspend fun requestData(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutSec: Int = 30,
    ): String {
        val request = buildRequest(method, path, body)
        val (code, text) = executeWithRetry(request, timeoutSec)
        if (code !in 200..299) {
            if (code == 401) throw WandApiException(401, "登录已失效，请重新连接")
            var message = "服务器返回 $code"
            try {
                val err = JSONObject(text).str("error")
                if (!err.isNullOrEmpty()) message = err
            } catch (_: Exception) {
            }
            throw WandApiException(code, message)
        }
        return text
    }

    private suspend fun requestObject(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutSec: Int = 30,
    ): JSONObject {
        val text = requestData(method, path, body, timeoutSec)
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw WandApiException(null, "响应解析失败：${e.message}")
        }
    }

    private suspend fun requestArray(method: String, path: String): JSONArray {
        val text = requestData(method, path)
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            throw WandApiException(null, "响应解析失败：${e.message}")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // MARK: - 会话

    override suspend fun fetchSessionList(
        offset: Int,
        limit: Int,
        revision: String?,
    ): SessionListPage = try {
        val revisionQuery = revision?.let { "&revision=${encode(it)}" }.orEmpty()
        SessionListPage.parse(
            requestObject("GET", "/api/session-list?offset=$offset&limit=$limit$revisionQuery"),
        )
    } catch (e: WandApiException) {
        if (e.status != 404) throw e
        fetchLegacySessionList(offset, limit, revision)
    }

    override suspend fun fetchSessionDirectories(): SessionDirectoryTreeResponse =
        SessionDirectoryTreeResponse.parse(requestObject("GET", "/api/session-directories"))

    override suspend fun renameSessionDirectory(path: String, name: String) {
        requestObject(
            "PUT",
            "/api/session-directories/name",
            JSONObject().put("path", path).put("name", name),
        )
    }

    private suspend fun fetchLegacySessionList(
        offset: Int,
        limit: Int,
        requestedRevision: String?,
    ): SessionListPage {
        val sessions = SessionSnapshot.parseList(requestArray("GET", "/api/sessions"))
        val claudeHistory = HistorySession.parseList(
            requestArray("GET", "/api/claude-history"),
            provider = "claude",
        )
        val codexHistory = HistorySession.parseList(
            requestArray("GET", "/api/codex-history"),
            provider = "codex",
        )
        val openCodeHistory = HistorySession.parseList(
            requestArray("GET", "/api/opencode-history"),
            provider = "opencode",
        )
        val qoderHistory = HistorySession.parseList(
            requestArray("GET", "/api/qoder-history"),
            provider = "qoder",
        )
        val managedHistory = sessions.mapNotNull { session ->
            session.claudeSessionId?.let { id -> historyProvider(session.provider) to id }
        }.toSet()
        val entries = buildList {
            sessions.forEach { session ->
                add(SessionListEntry.Managed(
                    key = "session-${session.id}",
                    sortTimestamp = parseIsoMillis(session.startedAt) ?: 0L,
                    session = session,
                ))
            }
            (claudeHistory + codexHistory + openCodeHistory + qoderHistory)
                .filter { history ->
                    (history.hasConversation ?: true) &&
                        !(history.managedByWand ?: false) &&
                        (history.apiProvider to history.claudeSessionId) !in managedHistory
                }
                .forEach { history ->
                    add(SessionListEntry.Recoverable(
                        key = "recoverable-${history.apiProvider}-${history.id}",
                        sortTimestamp = history.mtimeMs?.toLong() ?: parseIsoMillis(history.timestamp) ?: 0L,
                        history = history,
                    ))
                }
        }.sortedWith(compareByDescending<SessionListEntry> { it.sortTimestamp }.thenBy { it.key })
        val revision = legacySessionListRevision(entries)
        if (offset > 0 && requestedRevision != revision) {
            throw WandApiException(409, "会话列表已更新，请重新加载")
        }
        val boundedOffset = offset.coerceIn(0, entries.size)
        return SessionListPage(
            entries = entries.drop(boundedOffset).take(limit),
            offset = boundedOffset,
            total = entries.size,
            revision = revision,
        )
    }

    private fun legacySessionListRevision(entries: List<SessionListEntry>): String {
        val content = entries.joinToString("\\n") { it.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun historyProvider(provider: String?): String = when (provider) {
        "codex", "opencode", "qoder", "pi" -> provider
        else -> "claude"
    }

    /** Returns all managed sessions for notification state, without session-list pagination. */
    suspend fun listSessions(): List<SessionSnapshot> =
        SessionSnapshot.parseList(requestArray("GET", "/api/sessions"))

    suspend fun getSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("GET", "/api/sessions/$id?format=chat"))

    /** 历史消息分页：返回完整历史的 [offset, offset+limit) 段 + 总数。 */
    suspend fun fetchMessages(id: String, offset: Int, limit: Int): MessagesPage =
        MessagesPage.parse(requestObject("GET", "/api/sessions/$id/messages?offset=$offset&limit=$limit"))

    /** 按需取回被消息窗口截断的完整 tool_result 内容。 */
    suspend fun fetchToolContent(id: String, toolUseId: String): ContentBlock.ToolResult {
        val response = requestObject(
            "GET",
            "/api/sessions/${encode(id)}/tool-content/${encode(toolUseId)}",
        )
        val normalized = JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", response.str("tool_use_id") ?: toolUseId)
            .put("content", response.opt("content") ?: "")
            .put("is_error", response.bool("is_error") ?: false)
        return ContentBlock.parse(normalized) as? ContentBlock.ToolResult
            ?: throw WandApiException(null, "工具结果解析失败")
    }

    suspend fun sendInput(
        id: String,
        input: String,
        view: String? = null,
        shortcutKey: String? = null,
        respondImmediately: Boolean = false,
    ): SessionSnapshot {
        val body = JSONObject().put("input", input)
        if (view != null) body.put("view", view)
        if (shortcutKey != null) body.put("shortcutKey", shortcutKey)
        if (respondImmediately) body.put("respondImmediately", true)
        return SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/input", body))
    }

    suspend fun stopSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/stop"))

    // MARK: - 排队消息（仅结构化会话）

    /** 由服务端按 index 摘掉队列项并立即发送，避免客户端与自动 flush 重复发送。 */
    suspend fun promoteQueued(id: String, index: Int, expectedText: String): SessionSnapshot {
        val body = JSONObject()
            .put("expectedText", expectedText)
            .put("idempotencyKey", java.util.UUID.randomUUID().toString())
        return SessionSnapshot.parse(
            requestObject("POST", "/api/structured-sessions/$id/queued/$index/promote", body)
        )
    }

    /** 删除第 index 条排队消息。 */
    suspend fun deleteQueued(id: String, index: Int) {
        requestData("DELETE", "/api/structured-sessions/$id/queued/$index")
    }

    /** 清空全部排队消息。 */
    suspend fun clearQueued(id: String) {
        requestData("DELETE", "/api/structured-sessions/$id/queued")
    }

    override suspend fun deleteSession(id: String) {
        requestData("DELETE", "/api/sessions/$id")
    }

    suspend fun resumeSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/resume"))

    // MARK: - 模型与思考深度

    override suspend fun models(): ModelsResponse =
        ModelsResponse.parse(requestObject("GET", "/api/models"))

    /** model 传 null 表示恢复默认（服务端收 JSON null）。 */
    suspend fun setModel(id: String, model: String?): SessionSnapshot {
        val body = JSONObject().put("model", model ?: JSONObject.NULL)
        return SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/model", body))
    }

    suspend fun setThinkingEffort(id: String, thinkingEffort: String): SessionSnapshot =
        SessionSnapshot.parse(
            requestObject(
                "POST",
                "/api/sessions/$id/thinking-effort",
                JSONObject().put("thinkingEffort", thinkingEffort),
            )
        )

    suspend fun setMode(id: String, mode: String): SessionSnapshot =
        SessionSnapshot.parse(
            requestObject(
                "POST",
                "/api/sessions/$id/mode",
                JSONObject().put("mode", mode),
            )
        )

    // MARK: - 附件上传

    /**
     * 上传附件：multipart/form-data，字段名 files，服务端限制单文件 10MB、单次最多 5 个。
     * 入参是 (文件名, 字节) 对 —— UI 层负责从 content Uri 读出字节。
     */
    suspend fun uploadAttachments(
        id: String,
        files: List<Pair<String, ByteArray>>,
    ): List<UploadedFile> {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((name, bytes) in files.take(5)) {
            if (bytes.size > 10 * 1024 * 1024) {
                throw WandApiException(null, "$name 超过 10 MB")
            }
            multipart.addFormDataPart(
                "files",
                name,
                bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
        }
        val request = Request.Builder()
            .url("$baseUrl/api/sessions/$id/upload")
            .post(multipart.build())
            .build()
        val (code, text) = executeWithRetry(request, timeoutSec = 60)
        if (code !in 200..299) throw WandApiException(code, "附件上传失败")
        return try {
            UploadedFile.parseList(JSONObject(text))
        } catch (e: Exception) {
            throw WandApiException(null, "响应解析失败：${e.message}")
        }
    }

    // MARK: - 历史会话

    override suspend fun resumeHistory(history: HistorySession): SessionSnapshot {
        val provider = history.apiProvider
        return SessionSnapshot.parse(
            requestObject(
                "POST",
                "/api/$provider-sessions/${encode(history.claudeSessionId)}/resume",
                JSONObject().put("cwd", history.cwd),
            )
        )
    }

    override suspend fun deleteHistoryBatch(provider: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val body = JSONObject().put("claudeSessionIds", JSONArray(ids))
        requestData("POST", "/api/$provider-history/batch-delete", body)
    }

    // MARK: - 权限

    suspend fun resolveEscalation(
        sessionId: String,
        requestId: String,
        resolution: String,
    ): SessionSnapshot = SessionSnapshot.parse(
        requestObject(
            "POST",
            "/api/sessions/$sessionId/escalations/${encode(requestId)}/resolve",
            JSONObject().put("resolution", resolution),
        )
    )

    suspend fun approvePermission(sessionId: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$sessionId/approve-permission"))

    suspend fun denyPermission(sessionId: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$sessionId/deny-permission"))

    // MARK: - 新建会话

    /**
     * 结构化会话（非 PTY）：POST /api/structured-sessions。
     * 对齐 Web createStructuredSession：Codex / OpenCode 显式指定各自 runner，
     * Claude 不传 runner、由服务端按默认（claude-cli-print）解析。
     */
    override suspend fun createStructuredSession(
        cwd: String,
        mode: String?,
        prompt: String?,
        provider: String,
        model: String?,
        thinkingEffort: String?,
    ): SessionSnapshot {
        val body = JSONObject().put("cwd", cwd).put("provider", provider)
        when (provider) {
            "codex" -> body.put("runner", "codex-cli-exec")
            "opencode" -> body.put("runner", "opencode-cli-run")
            "grok" -> body.put("runner", "grok-cli-headless")
        }
        if (!mode.isNullOrEmpty()) body.put("mode", mode)
        if (!model.isNullOrEmpty()) body.put("model", model)
        if (!thinkingEffort.isNullOrEmpty()) body.put("thinkingEffort", thinkingEffort)
        if (!prompt.isNullOrEmpty()) body.put("prompt", prompt)
        return SessionSnapshot.parse(requestObject("POST", "/api/structured-sessions", body))
    }

    /** PTY 会话：POST /api/commands。Qoder 的 provider ID 与可执行命令名称不同。 */
    override suspend fun createPtySession(
        cwd: String,
        mode: String?,
        initialInput: String?,
        provider: String,
        model: String?,
        thinkingEffort: String?,
    ): SessionSnapshot {
        val command = if (provider == "qoder") "qodercli" else provider
        val body = JSONObject().put("command", command).put("provider", provider).put("cwd", cwd)
        if (!mode.isNullOrEmpty()) body.put("mode", mode)
        if (!model.isNullOrEmpty()) body.put("model", model)
        if (!thinkingEffort.isNullOrEmpty()) body.put("thinkingEffort", thinkingEffort)
        if (!initialInput.isNullOrEmpty()) body.put("initialInput", initialInput)
        return SessionSnapshot.parse(requestObject("POST", "/api/commands", body))
    }

    /** 空白终端：仅启动服务端配置的登录 Shell，不运行任何 Provider CLI。 */
    override suspend fun createShellSession(cwd: String): SessionSnapshot {
        val body = JSONObject().put("shell", true).put("cwd", cwd)
        return SessionSnapshot.parse(requestObject("POST", "/api/commands", body))
    }

    /** 将「新建会话」默认项持久化到服务端配置。 */
    override suspend fun updateNewSessionDefaults(
        mode: String?,
        model: String?,
        modelProvider: String,
        thinkingEffort: String?,
        defaultProvider: String?,
        defaultSessionKind: String?,
    ) {
        val body = JSONObject()
        if (mode != null) body.put("defaultMode", mode)
        if (model != null) {
            when (modelProvider) {
                "codex" -> {
                    body.put("defaultCodexModel", model)
                    body.put("defaultModels", JSONObject().put("codex", model))
                }
                "opencode" -> {
                    body.put("defaultOpenCodeModel", model)
                    body.put("defaultModels", JSONObject().put("opencode", model))
                }
                "qoder" -> {
                    body.put("defaultQoderModel", model)
                    body.put("defaultModels", JSONObject().put("qoder", model))
                }
                "grok" -> {
                    body.put("defaultGrokModel", model)
                    body.put("defaultModels", JSONObject().put("grok", model))
                }
                "pi" -> {
                    body.put("defaultPiModel", model)
                    body.put("defaultModels", JSONObject().put("pi", model))
                }
                else -> {
                    body.put("defaultModel", model)
                    body.put("defaultModels", JSONObject().put("claude", model))
                }
            }
        }
        if (thinkingEffort != null) body.put("defaultThinkingEffort", thinkingEffort)
        if (defaultProvider != null) body.put("defaultProvider", defaultProvider)
        if (defaultSessionKind != null) body.put("defaultSessionKind", defaultSessionKind)
        requestData("POST", "/api/settings/config", body)
    }

    // MARK: - Missions / Agent Inbox

    override suspend fun defaultMissionCwd(): String =
        requestObject("GET", "/api/config").str("defaultCwd").orEmpty()

    override suspend fun fetchInbox(): List<AgentActivityItem> =
        AgentActivityItem.parseList(requestObject("GET", "/api/inbox").arr("items"))

    override suspend fun fetchMissions(): List<MissionInfo> =
        MissionInfo.parseList(requestObject("GET", "/api/missions").arr("missions"))

    override suspend fun createMission(
        title: String?,
        prompt: String,
        cwd: String,
        providers: List<String>,
        baseRef: String?,
        sharedDirectories: List<String>,
        copyPaths: List<String>,
    ): MissionInfo {
        val body = JSONObject()
            .put("prompt", prompt)
            .put("cwd", cwd)
            .put("providers", JSONArray(providers))
            .put("sharedDirectories", JSONArray(sharedDirectories))
            .put("copyPaths", JSONArray(copyPaths))
        if (!title.isNullOrBlank()) body.put("title", title)
        if (!baseRef.isNullOrBlank()) body.put("baseRef", baseRef)
        return MissionInfo.parse(requestObject("POST", "/api/missions", body, timeoutSec = 180))
    }

    override suspend fun fetchMissionDiff(missionId: String, attemptId: String): MissionDiff =
        MissionDiff.parse(requestObject(
            "GET",
            "/api/missions/${encode(missionId)}/attempts/${encode(attemptId)}/diff",
        ))

    override suspend fun addMissionReviewComment(
        missionId: String,
        attemptId: String,
        filePath: String,
        line: Int?,
        side: String,
        body: String,
    ): MissionReviewComment {
        val payload = JSONObject()
            .put("filePath", filePath)
            .put("side", side)
            .put("body", body)
        if (line != null) payload.put("line", line)
        return MissionReviewComment.parseList(JSONArray().put(requestObject(
            "POST",
            "/api/missions/${encode(missionId)}/attempts/${encode(attemptId)}/comments",
            payload,
        ))).first()
    }

    override suspend fun sendMissionReview(missionId: String, attemptId: String): List<MissionReviewComment> =
        MissionReviewComment.parseList(requestObject(
            "POST",
            "/api/missions/${encode(missionId)}/attempts/${encode(attemptId)}/review/send",
            JSONObject(),
        ).arr("comments"))

    override suspend fun markInboxRead(sessionId: String?) {
        val body = JSONObject()
        if (sessionId != null) body.put("sessionId", sessionId)
        requestData("POST", "/api/inbox/read", body)
    }

    // MARK: - Git 快速提交

    suspend fun gitStatus(sessionId: String): GitStatusResult =
        GitStatusResult.parse(requestObject("GET", "/api/sessions/$sessionId/git-status"))

    /**
     * 快速提交：customMessage 为 null 时服务端用 AI 根据 staged diff 生成；
     * autoTag 时再让 AI 推荐下一个语义化版本号。AI 链路 + push 较慢，超时放宽到 180s。
     */
    suspend fun quickCommit(
        sessionId: String,
        customMessage: String?,
        tag: String?,
        autoTag: Boolean,
        push: Boolean,
        submodule: Boolean,
    ): QuickCommitResult {
        val body = JSONObject()
            .put("autoMessage", customMessage == null)
            .put("autoTag", autoTag)
            .put("push", push)
            .put("submodule", submodule)
        if (customMessage != null) body.put("customMessage", customMessage)
        if (!tag.isNullOrEmpty()) body.put("tag", tag)
        return QuickCommitResult.parse(
            requestObject("POST", "/api/sessions/$sessionId/quick-commit", body, timeoutSec = 180)
        )
    }

    /** AI 预生成 commit message 与推荐 tag（只生成不提交，对应网页版「AI」按钮）。 */
    suspend fun generateCommitMessage(sessionId: String): GenerateCommitMessageResult =
        GenerateCommitMessageResult.parse(
            requestObject(
                "POST",
                "/api/sessions/$sessionId/generate-commit-message",
                timeoutSec = 180,
            )
        )

    /** 补推送：把已有 commit / tag 推到远端；submodule 为 true 时递归推送各 submodule。 */
    suspend fun gitPush(
        sessionId: String,
        pushCommits: Boolean,
        pushTags: Boolean,
        submodule: Boolean,
        tag: String?,
    ): GitPushResult {
        val body = JSONObject()
            .put("pushCommits", pushCommits)
            .put("pushTags", pushTags)
            .put("submodule", submodule)
        if (!tag.isNullOrEmpty()) body.put("tag", tag)
        return GitPushResult.parse(
            requestObject("POST", "/api/sessions/$sessionId/git/push", body, timeoutSec = 180)
        )
    }

    // MARK: - 目录与配置

    suspend fun listDirectory(query: String): DirectoryListing =
        DirectoryListing.parse(requestObject("GET", "/api/directory?q=${encode(query)}"))

    override suspend fun recentPaths(): List<RecentPath> =
        RecentPath.parseList(requestArray("GET", "/api/recent-paths"))

    override suspend fun serverConfig(): ServerConfigInfo =
        ServerConfigInfo.parse(requestObject("GET", "/api/config"))
}
