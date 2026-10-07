package com.wand.app.data

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Passwords are used for this attempt only; persist the server-issued app token, never the password. */
object PasswordConnection {
    data class Result(
        val baseUrl: String,
        val token: String? = null,
        val error: String? = null,
        val retryable: Boolean = false,
        val needsPassword: Boolean = false,
    )

    @JvmStatic
    fun connect(baseUrl: String, password: String, timeoutMs: Int): Result {
        return connectAt(ServerProfiles.canonicalBaseUrl(baseUrl), password, timeoutMs, 2)
    }

    private fun connectAt(endpoint: String, password: String, timeoutMs: Int, redirectsLeft: Int): Result {
        return try {
            val response = WandHttp.postJson(
                "$endpoint/api/login",
                JSONObject().put("password", password).toString(),
                timeoutMs,
                endpoint,
            )
            when {
                response.code in listOf(301, 302, 307, 308) && redirectsLeft > 0 -> {
                    val upgrade = upgradeRedirect(endpoint, response.location)
                    if (upgrade == null) Result(endpoint, error = "服务器重定向到其他地址，请直接输入最终服务器地址")
                    else connectAt(upgrade, password, timeoutMs, redirectsLeft - 1)
                }
                response.code == 401 -> Result(
                    endpoint,
                    error = if (password.isEmpty()) "请输入服务器密码" else "密码错误，请重试",
                    needsPassword = true,
                )
                response.code == 429 -> Result(endpoint, error = "登录尝试过多，请稍后再试")
                response.code in 200..299 -> {
                    val codeResponse = WandHttp.get("$endpoint/api/app-connect-code", timeoutMs, endpoint)
                    check(codeResponse.code == 200) { "无法保存此服务器的登录凭据，请重试" }
                    val token = WandAuth.decodeConnectCode(JSONObject(codeResponse.body).getString("code"))?.second
                    check(!token.isNullOrBlank()) { "服务器返回了无效的登录凭据，请重试" }
                    // The connect code may advertise a public URL. Keep the address the user chose.
                    Result(endpoint, token = token)
                }
                else -> Result(
                    endpoint,
                    error = WandAuth.loginFailureMessage(
                        WandAuth.classifyLoginFailure(response.code, null, 2), response.code,
                    ),
                    retryable = response.code >= 500,
                )
            }
        } catch (_: IllegalStateException) {
            Result(endpoint, error = "服务器未返回可保存的登录凭据，请重试")
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) return Result(endpoint, error = "连接已取消")
            val https = if (WandHttp.looksLikeHttpOnTlsPort(error)) WandHttp.preferHttpsUrl(endpoint) else null
            if (https != null && redirectsLeft > 0) return connectAt(https, password, timeoutMs, redirectsLeft - 1)
            val failure = WandAuth.classifyLoginFailure(null, WandAuth.localErrorOf(error), 2)
            Result(endpoint, error = WandAuth.loginFailureMessage(failure, null), retryable = failure.retryable)
        }
    }

    /** Only a same-host HTTP → HTTPS upgrade may resend a password; never a foreign host or downgrade. */
    internal fun upgradeRedirect(baseUrl: String, location: String?): String? {
        val source = baseUrl.toHttpUrlOrNull() ?: return null
        val target = source.resolve(location ?: return null) ?: return null
        if (source.scheme != "http" || target.scheme != "https" || target.host != source.host ||
            target.username.isNotEmpty() || target.password.isNotEmpty() || target.query != null ||
            target.fragment != null || target.encodedPath != "${source.encodedPath.trimEnd('/')}/api/login") return null
        return target.newBuilder().encodedPath(source.encodedPath).build().toString().trimEnd('/')
    }
}
