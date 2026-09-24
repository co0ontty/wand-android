package com.wand.app.data

import android.util.Base64
import com.wand.app.WandLog
import kotlinx.coroutines.Dispatchers
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
 * 原生终端与聊天都复用端点隔离的 WandHttp CookieJar，不再镜像 WebView cookie。
 */
object WandAuth {

    class AuthException(message: String, val retryable: Boolean = true) : Exception(message)

    /** 解码连接码：base64(url#token)。 */
    @JvmStatic
    fun decodeConnectCode(input: String): Pair<String, String>? {
        val cleaned = input.replace(Regex("\\s+"), "")
        if (cleaned.isEmpty()) return null
        val decoded = try {
            String(
                Base64.decode(cleaned, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE),
                Charsets.UTF_8,
            )
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
     * 失败抛 AuthException（中文文案与 ConnectActivity 对齐）。
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
            val body = JSONObject().put("appToken", appToken).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$normalized/api/login")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> WandLog.i(AUTH_TAG, "登录成功 $normalized")
                        401 -> {
                            WandLog.w(AUTH_TAG, "登录被拒 401 $normalized（连接码可能已过期）")
                            throw AuthException(
                                "认证失败，连接码可能已过期（密码已更改），请重新获取连接码",
                                retryable = false,
                            )
                        }
                        429 -> {
                            WandLog.w(AUTH_TAG, "登录限流 429 $normalized")
                            throw AuthException("登录尝试次数过多，请稍后再试")
                        }
                        else -> {
                            WandLog.w(AUTH_TAG, "登录异常状态码 ${response.code} $normalized")
                            throw AuthException("服务器返回异常状态码：${response.code}")
                        }
                    }
                }
            } catch (e: IOException) {
                WandLog.e(AUTH_TAG, "登录网络错误 $normalized：${e.message}", e)
                throw AuthException("无法连接到服务器：${e.message ?: "网络错误"}")
            }
        }
    }

    private const val AUTH_TAG = "auth"
}
