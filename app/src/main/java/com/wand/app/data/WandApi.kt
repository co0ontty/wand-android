package com.wand.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** REST 错误：status 为 null 表示网络层失败。message 面向用户（中文）。 */
class WandApiException(val status: Int?, message: String) : Exception(message)

/**
 * wand 服务端 REST 客户端 —— 对称 iOS 端 WandAPI.swift。
 * 复用 WandHttp（自签证书放行 + 共享 CookieJar），登录 cookie 自动携带；
 * 遇到 401 时用存储的 appToken 重新登录一次再重试。
 */
class WandApi(baseUrl: String, val token: String?) {

    val baseUrl: String = WandHttp.normalizeBaseUrl(baseUrl)

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

    private fun execute(request: Request, timeoutSec: Int): Pair<Int, String> {
        val client = if (timeoutSec == 30) {
            WandHttp.client
        } else {
            WandHttp.client.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
        }
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    /** 带 401 自动重登的请求入口。返回响应 body 字符串。 */
    private suspend fun requestData(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutSec: Int = 30,
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(method, path, body)
        var (code, text) = try {
            execute(request, timeoutSec)
        } catch (e: IOException) {
            throw WandApiException(null, "网络错误：${e.message ?: "请求失败"}")
        }
        if (code == 401 && !token.isNullOrEmpty()) {
            // session cookie 过期：用 appToken 重新登录一次，cookie 注入共享 CookieJar 后重试。
            try {
                WandAuth.loginWithToken(baseUrl, token)
            } catch (_: Exception) {
                throw WandApiException(401, "登录已失效，请重新连接")
            }
            val retried = try {
                execute(buildRequest(method, path, body), timeoutSec)
            } catch (e: IOException) {
                throw WandApiException(null, "网络错误：${e.message ?: "请求失败"}")
            }
            code = retried.first
            text = retried.second
        }
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
        text
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

    suspend fun listSessions(): List<SessionSnapshot> =
        SessionSnapshot.parseList(requestArray("GET", "/api/sessions"))

    suspend fun getSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("GET", "/api/sessions/$id?format=chat"))

    suspend fun sendInput(
        id: String,
        input: String,
        view: String? = null,
        shortcutKey: String? = null,
    ): SessionSnapshot {
        val body = JSONObject().put("input", input)
        if (view != null) body.put("view", view)
        if (shortcutKey != null) body.put("shortcutKey", shortcutKey)
        return SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/input", body))
    }

    suspend fun stopSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/stop"))

    suspend fun deleteSession(id: String) {
        requestData("DELETE", "/api/sessions/$id")
    }

    suspend fun resumeSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/resume"))

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
     * 对齐 Web createStructuredSession：codex 显式走 codex-cli-exec runner，
     * claude 不传 runner、由服务端按默认（claude-cli-print）解析。
     */
    suspend fun createStructuredSession(
        cwd: String,
        mode: String?,
        prompt: String?,
        provider: String = "claude",
    ): SessionSnapshot {
        val body = JSONObject().put("cwd", cwd).put("provider", provider)
        if (provider == "codex") body.put("runner", "codex-cli-exec")
        if (!mode.isNullOrEmpty()) body.put("mode", mode)
        if (!prompt.isNullOrEmpty()) body.put("prompt", prompt)
        return SessionSnapshot.parse(requestObject("POST", "/api/structured-sessions", body))
    }

    /** PTY 会话：POST /api/commands。对齐 Web runPtyCommandFromModal：command 即 provider（claude / codex）。 */
    suspend fun createPtySession(
        cwd: String,
        mode: String?,
        initialInput: String?,
        provider: String = "claude",
    ): SessionSnapshot {
        val body = JSONObject().put("command", provider).put("provider", provider).put("cwd", cwd)
        if (!mode.isNullOrEmpty()) body.put("mode", mode)
        if (!initialInput.isNullOrEmpty()) body.put("initialInput", initialInput)
        return SessionSnapshot.parse(requestObject("POST", "/api/commands", body))
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

    suspend fun recentPaths(): List<RecentPath> =
        RecentPath.parseList(requestArray("GET", "/api/recent-paths"))

    suspend fun serverConfig(): ServerConfigInfo =
        ServerConfigInfo.parse(requestObject("GET", "/api/config"))
}
