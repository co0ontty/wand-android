package com.wand.app.data

import java.util.Base64
import com.wand.app.WandLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Token 登录与连接码解码 —— 对称 iOS 端 WandAuth.swift。
 *
 * 服务端不接受 `?token=` query（requireAuth 只读 cookie），原生客户端必须用
 * appToken 走一次 POST /api/login，session cookie 由 WandHttp 的 CookieJar 承接，
 * 之后的 REST 请求与 /ws 升级请求自动携带。
 *
 * 原生终端与聊天复用端点隔离的 WandHttp CookieJar。完整设置 WebView 只接收当前端点的
 * cookie，网页管理员登录不会反向替换原生会话的认证。
 */
object WandAuth {

    class AuthException(message: String, val retryable: Boolean = true) : Exception(message)

    /**
     * 「连不上服务器」这一句话的唯一来源。登录探测（本文件）与更新/下载
     * （`com.wand.app.NetworkErrorHelper`）都取这里，不再各写一份 —— 同一件事实不两种说法。
     */
    const val UNREACHABLE_MESSAGE = "无法连接到服务器，请检查网络"

    /** 没拿到 HTTP 响应时的本地失败类别。`BadUrl` 与网络抖动不是一回事，不该一起退避重试。 */
    enum class LocalLoginError { Network, Timeout, BadUrl }

    /**
     * 一次登录失败的判定结果：要不要继续重试 + 该给用户哪类话。
     * 文案一律由 [loginFailureMessage] 出，三处调用点（本文件、ConnectActivity 的两处探测）共用同一份。
     */
    enum class AuthFailure(val retryable: Boolean) {
        /** 首次 401：口令轮换后 appToken 换发会话有竞态窗口，先当瞬时，不当「码已过期」。 */
        AuthPending(true),

        /** 429 / 5xx：服务端限流或暂时不可用。 */
        ServerBusy(true),

        /** 连不上、超时。 */
        Unreachable(true),

        /** 退避重探之后仍然 401：这个连接的凭据确实不被接受，要用户重新连接。 */
        CredentialRejected(false),

        /** 403：认下来了但没有该操作的权限 —— 不是凭据过期，别引导换码。 */
        PermissionDenied(false),

        /** 地址本身不合法（粘了半截连接码、host 写错）。 */
        BadAddress(false),

        /** 其余状态码：不猜语义，也不拿「换码」当第一反应。 */
        UnexpectedStatus(false),
    }

    /**
     * 纯判定，无副作用、不碰 android API —— 单测直接覆盖状态码 × 异常 × 尝试次数。
     *
     * @param attempt 第几次尝试，从 1 计。401 只在第一次算 [AuthFailure.AuthPending]，
     * 重试后仍 401 才判 [AuthFailure.CredentialRejected]，这是「单次 401 不判死」的实现点。
     */
    @JvmStatic
    fun classifyLoginFailure(
        status: Int?,
        error: LocalLoginError?,
        attempt: Int,
    ): AuthFailure {
        if (error != null) {
            return if (error == LocalLoginError.BadUrl) AuthFailure.BadAddress else AuthFailure.Unreachable
        }
        val code = status ?: return AuthFailure.Unreachable
        return when {
            code == 401 ->
                if (attempt <= 1) AuthFailure.AuthPending else AuthFailure.CredentialRejected
            code == 403 -> AuthFailure.PermissionDenied
            code == 429 || code >= 500 -> AuthFailure.ServerBusy
            else -> AuthFailure.UnexpectedStatus
        }
    }

    @JvmStatic
    fun loginFailureMessage(failure: AuthFailure, status: Int?): String = when (failure) {
        AuthFailure.AuthPending -> "登录暂未通过，正在重试…"
        AuthFailure.ServerBusy -> "服务器暂时不可用，请稍后再试"
        AuthFailure.Unreachable -> UNREACHABLE_MESSAGE
        AuthFailure.CredentialRejected -> "连接失败，请重试；若一直失败，再重新获取连接码"
        AuthFailure.PermissionDenied -> "没有权限完成此操作，请检查该连接的权限"
        AuthFailure.BadAddress -> "服务器地址不正确，请重新粘贴连接码或地址"
        AuthFailure.UnexpectedStatus -> "服务器返回了异常状态码：${status ?: "?"}"
    }

    /** 把 OkHttp / java.net 的异常归到 [LocalLoginError]；超时要在 IOException 之前判，否则被吞掉。 */
    @JvmStatic
    fun localErrorOf(error: Throwable): LocalLoginError = when {
        error is java.net.SocketTimeoutException -> LocalLoginError.Timeout
        error is java.net.MalformedURLException -> LocalLoginError.BadUrl
        error is IllegalArgumentException -> LocalLoginError.BadUrl
        else -> LocalLoginError.Network
    }

    /** 解码连接码：base64(url#token)。 */
    @JvmStatic
    fun decodeConnectCode(input: String): Pair<String, String>? {
        val cleaned = input.replace(Regex("\\s+"), "")
        if (cleaned.isEmpty()) return null
        val decoded = try {
            val bytes = runCatching { Base64.getDecoder().decode(cleaned) }
                .getOrElse { Base64.getUrlDecoder().decode(cleaned) }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val hashIdx = decoded.lastIndexOf('#')
        if (hashIdx < 1) return null
        val url = decoded.substring(0, hashIdx)
        val token = decoded.substring(hashIdx + 1)
        if (!url.startsWith("http") || token.length < 16) return null
        return url to token
    }

    /**
     * POST /api/login with `{"appToken": ...}`。成功后 cookie 已进入 CookieJar；
     * 失败抛 AuthException（中文文案由 [loginFailureMessage] 出，与 ConnectActivity 同一份）。
     *
     * 401 会就地退避重探一次：口令轮换 / 会话换发窗口里的 401 是瞬时的，第二次通常就 200。
     * 只有重探仍被拒才认定凭据真的失效 —— 之前任何一次 401 都直接判死，把用户推去
     * 「重新获取连接码」，实际制造了一轮又一轮的换码与漂移。
     */
    suspend fun loginWithToken(baseUrl: String, appToken: String) {
        loginWithToken(baseUrl, appToken, WandHttp.clientFor(baseUrl))
    }

    /** Relogin for an existing API must stay bound to that API's retirement-aware client. */
    internal suspend fun loginWithToken(
        baseUrl: String,
        appToken: String,
        client: OkHttpClient,
    ) {
        withContext(Dispatchers.IO) {
            val normalized = WandHttp.normalizeBaseUrl(baseUrl)
            var attempt = 1
            var outcome = attemptLogin(client, normalized, appToken)
            while (
                classifyLoginFailure(outcome.first, outcome.second, attempt) ==
                AuthFailure.AuthPending && attempt < LOGIN_MAX_ATTEMPTS
            ) {
                WandLog.i(AUTH_TAG, "登录 401，退避后重探（第 ${attempt + 1} 次）")
                delay(LOGIN_RETRY_DELAY_MS * attempt)
                attempt += 1
                outcome = attemptLogin(client, normalized, appToken)
            }
            val (status, local) = outcome
            if (status != null && status in 200..299) {
                WandLog.i(AUTH_TAG, "登录成功")
                return@withContext
            }
            val failure = classifyLoginFailure(status, local, attempt)
            WandLog.w(AUTH_TAG, "登录失败 $failure（status=${status ?: "-"} attempt=$attempt）")
            throw AuthException(loginFailureMessage(failure, status), failure.retryable)
        }
    }

    private suspend fun attemptLogin(
        client: OkHttpClient,
        normalizedBaseUrl: String,
        appToken: String,
    ): Pair<Int?, LocalLoginError?> {
        val body = JSONObject().put("appToken", appToken).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$normalizedBaseUrl/api/login")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response -> response.code to null }
        } catch (error: IOException) {
            // 固定诊断：网络异常 message/堆栈/URL 凭据参数一律不落日志、不进用户文案。
            WandLog.e(AUTH_TAG, "登录网络错误 ${error.javaClass.simpleName}")
            null to localErrorOf(error)
        }
    }

    private const val AUTH_TAG = "auth"
    private const val LOGIN_MAX_ATTEMPTS = 2
    private const val LOGIN_RETRY_DELAY_MS = 1_000L
}
