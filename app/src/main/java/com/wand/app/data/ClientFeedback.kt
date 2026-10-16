package com.wand.app.data

import com.wand.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Best-effort diagnostics only: no business retries, response bodies, or persistent queue. */
class ClientFeedback(private val baseUrl: String, private val token: String?) {
    fun report(event: String, feature: String, error: Throwable, code: String? = null) {
        if (isCancellation(error)) return
        runCatching {
            submit(event, feature, error.javaClass.simpleName.ifBlank { "Error" },
                error.message ?: error.javaClass.simpleName, code, error.stackTraceToString())
        }
    }

    fun httpFailure(path: String, status: Int, text: String, fallback: String = "HTTP $status") {
        if (status !in 500..599 || path.substringBefore('?') == "/api/feedback/client") return
        runCatching {
            // Only the explicit error string, never the HTTP body or arbitrary JSON fields.
            val message = (runCatching { JSONObject(text).opt("error") }.getOrNull() as? String) ?: fallback
            submit("http_5xx", featureForPath(path), "HTTPError", message, status.toString(), null)
        }
    }

    private fun submit(event: String, feature: String, name: String, message: String, code: String?, stack: String?) {
        if (!allowsDiagnostics(baseUrl)) return
        val client = WandHttp.clientFor(baseUrl)
        val url = "$baseUrl/api/feedback/client".toHttpUrlOrNull() ?: return
        val secrets = listOfNotNull(token).filter { it.isNotEmpty() } +
            client.cookieJar.loadForRequest(url).map { it.value }.filter { it.isNotEmpty() }
        fun clean(value: String, bytes: Int) = utf8Prefix(redact(value, secrets), bytes)
        val safeMessage = clean(message, 8192)
        val safeName = clean(name, 120)
        val error = JSONObject().put("name", safeName).put("message", safeMessage)
        code?.let { error.put("code", clean(it, 120)) }
        stack?.let { error.put("stack", clean(it, 16384)) }
        val payload = JSONObject().put("schema", 1).put("source", "android")
            .put("event", event).put("version", BuildConfig.VERSION_NAME.ifBlank { "0.0.0" })
            .put("feature", feature).put("error", error).toString()
        if (payload.toByteArray(Charsets.UTF_8).size > 32 * 1024) return
        val key = listOf(baseUrl, event, feature, safeName, code.orEmpty(), safeMessage).joinToString("\u0000")
        if (!gate.accept(key, System.nanoTime() / 1_000_000)) return
        // One running request and one queued report across the process, then drop. No retries.
        executor.execute {
            runCatching {
                val request = Request.Builder().url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }.build()
                client.newBuilder().followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS)
                    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
                    .writeTimeout(2, TimeUnit.SECONDS).build().newCall(request).execute().close()
            }
        }
    }

    companion object {
        private val gate = FeedbackRateLimit()
        private val executor = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            ArrayBlockingQueue(1), { runnable -> Thread(runnable, "wand-feedback").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy())

        @JvmStatic
        fun updateFailed(baseUrl: String?, token: String?, error: Throwable, code: String? = null) {
            if (baseUrl.isNullOrBlank()) return
            ClientFeedback(baseUrl, token).report("update_failed", "app_update", error, code)
        }

        internal fun allowsDiagnostics(baseUrl: String): Boolean {
            val url = baseUrl.toHttpUrlOrNull() ?: return false
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
            return url.isHttps || (url.scheme == "http" && (url.host == "localhost" || url.host == "::1" ||
                Regex("127(?:\\.\\d{1,3}){3}").matches(url.host)))
        }

        internal fun isCancellation(error: Throwable): Boolean = generateSequence(error) { it.cause }
            .take(16).any { it is java.util.concurrent.CancellationException || it is InterruptedException ||
                (it is java.io.IOException && it.message.equals("Canceled", ignoreCase = true)) }

        internal fun redact(text: String, secrets: List<String>): String {
            var result = text
            // Replace actual credentials before any truncation or generic matching.
            secrets.sortedByDescending { it.length }.forEach { secret ->
                if (secret.isNotEmpty()) result = result.replace(secret, "[REDACTED]")
            }
            result = Regex("(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)([\\\"']?\\s*[:=]\\s*)[^\\r\\n]*")
                .replace(result, "$1$2[REDACTED]")
            result = Regex("(?i)\\b(bearer|basic)\\s+[^\\s\\\"',;]+")
                .replace(result, "$1 [REDACTED]")
            result = Regex("(?i)\\b(app[_-]?token|access[_-]?token|auth[_-]?token|refresh[_-]?token|token|password|passwd|app[_-]?secret|client[_-]?secret|secret|api[_-]?key)([\\\"']?\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&\\\"']+)")
                .replace(result, "$1$2[REDACTED]")
            result = Regex("(?i)(https?://)[^/\\s@]+@").replace(result, "$1[REDACTED]@")
            result = Regex("-----BEGIN [^-\\r\\n]*PRIVATE KEY-----[\\s\\S]*?-----END [^-\\r\\n]*PRIVATE KEY-----")
                .replace(result, "[REDACTED PRIVATE KEY]")
            return result
        }

        internal fun utf8Prefix(text: String, limit: Int): String {
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val point = text.codePointAt(end)
                val size = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
                if (bytes + size > limit) break
                bytes += size
                end += Character.charCount(point)
            }
            return text.substring(0, end)
        }

        /** Mirrors the canonical FEEDBACK_API_FEATURES in the server's src/feedback-feature-map.ts. */
        internal fun featureForPath(path: String): String = when (path.substringBefore('?').split('/').getOrNull(2)) {
            "sessions", "structured-sessions", "commands", "session-list", "session-directories" -> "sessions"
            "conversations" -> "conversations"
            "wand-tasks", "wand-task-agent-defaults", "tasks", "workspace-tasks", "workspaces", "wand-milestones",
            "missions", "scheduled-tasks" -> "tasks"
            "files", "upload", "directory", "session-files", "file-preview", "file-read", "file-write", "recent-paths" -> "files"
            "speech" -> "speech"
            "settings", "config", "user-profile", "auto-update", "update-channel" -> "settings"
            "models", "model-groups", "local-models" -> "models"
            "login", "logout", "app-login", "session-check", "set-password" -> "authentication"
            "update", "update-status", "check-update" -> "server_update"
            "android-apk-update", "android-apk", "macos-app-update", "macos-dmg-update", "macos-dmg",
            "ios-ipa-update", "ios-ipa" -> "app_update"
            "provider-cli-updates" -> "provider_update"
            "daemon-maintenance" -> "daemon_update"
            "storage-cleanup" -> "storage"
            "git", "github" -> "repository"
            else -> "api"
        }
    }
}

/** Monotonic clock; bounded dedup state and at most ten reports per minute process-wide. */
internal class FeedbackRateLimit {
    private val recent = LinkedHashMap<String, Long>()
    private var windowStart = 0L
    private var count = 0

    @Synchronized fun accept(key: String, now: Long): Boolean {
        recent.entries.removeAll { now - it.value >= 300_000 }
        if (recent.containsKey(key)) return false
        if (now - windowStart >= 60_000) { windowStart = now; count = 0 }
        if (count >= 10) return false
        if (recent.size >= 64) recent.remove(recent.keys.first())
        recent[key] = now
        count += 1
        return true
    }
}
