package com.wand.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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
 * 登录成功后同时把 Set-Cookie 镜像进 android.webkit.CookieManager，
 * 这样从设置页打开「网页版」（MainActivity WebView）时已是登录态。
 */
object WandAuth {

    class AuthException(message: String) : Exception(message)

    /** 解码连接码：base64(url#token)。 */
    @JvmStatic
    fun decodeConnectCode(input: String): Pair<String, String>? {
        return try {
            val cleaned = input.replace(Regex("\\s+"), "")
            if (cleaned.isEmpty()) return null
            val buf = Base64.decode(cleaned, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE)
            val decoded = String(buf, Charsets.UTF_8)
            val hashIdx = decoded.lastIndexOf('#')
            if (hashIdx < 1) return null
            val url = decoded.substring(0, hashIdx)
            val token = decoded.substring(hashIdx + 1)
            if (!url.startsWith("http") || token.length < 16) return null
            url to token
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POST /api/login with `{"appToken": ...}`。成功后 cookie 已进入 CookieJar；
     * 失败抛 AuthException（中文文案与 ConnectActivity 对齐）。
     */
    suspend fun loginWithToken(baseUrl: String, appToken: String) {
        withContext(Dispatchers.IO) {
            val normalized = WandHttp.normalizeBaseUrl(baseUrl)
            val body = JSONObject().put("appToken", appToken).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$normalized/api/login")
                .post(body)
                .build()
            try {
                WandHttp.client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            // 镜像 cookie 到 WebView，保证「网页版」兜底入口已登录。
                            try {
                                val cookieManager = android.webkit.CookieManager.getInstance()
                                for (setCookie in response.headers("Set-Cookie")) {
                                    cookieManager.setCookie(normalized, setCookie)
                                }
                                cookieManager.flush()
                            } catch (_: Exception) {
                                // WebView 不可用（极少数环境）时忽略，不影响原生路径
                            }
                        }
                        401 -> throw AuthException("认证失败，连接码可能已过期（密码已更改），请重新获取连接码")
                        429 -> throw AuthException("登录尝试次数过多，请稍后再试")
                        else -> throw AuthException("服务器返回异常状态码：${response.code}")
                    }
                }
            } catch (e: AuthException) {
                throw e
            } catch (e: IOException) {
                throw AuthException("无法连接到服务器：${e.message ?: "网络错误"}")
            }
        }
    }
}
