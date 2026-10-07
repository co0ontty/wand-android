package com.wand.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Reuse the live client login without copying the WebView-only access proof back to native APIs. */
object SettingsWebAuthentication {
    private data class Attempt(val status: Int, val cookies: List<String>) {
        fun requireCookies(): List<String> {
            when {
                status == 401 -> error("客户端登录已失效，请返回客户端重新连接")
                status == 404 -> error("服务器不支持客户端认证的完整设置，请更新 Wand 服务")
                status !in 200..299 -> error("无法打开完整设置，请稍后重试")
                cookies.none { it.startsWith("wand_settings_access=") } -> error("服务器没有返回客户端设置登录态，请更新 Wand 服务")
            }
            return cookies
        }
    }

    suspend fun cookies(baseUrl: String, appToken: String?): List<String> = withContext(Dispatchers.IO) {
        val base = ServerProfiles.canonicalBaseUrl(baseUrl)
        val native = WandHttp.clientFor(base)
        val isolated = native.newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).build()
        val url = "$base/api/settings/webview-session".toHttpUrl()
        fun attempt(): Attempt {
            val existing = native.cookieJar.loadForRequest(url)
            val request = Request.Builder().url(url)
                .header("Cookie", existing.joinToString("; ") { "${it.name}=${it.value}" })
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            return isolated.newCall(request).execute().use { response ->
                val proof = Cookie.parseAll(url, response.headers)
                Attempt(response.code, (existing + proof).map { "$it; SameSite=Strict" })
            }
        }
        val first = attempt()
        if (first.status != 401 || appToken.isNullOrBlank()) return@withContext first.requireCookies()
        try { WandAuth.loginWithToken(base, appToken, native) }
        catch (_: WandAuth.AuthException) { error("客户端登录已失效，请返回客户端重新连接") }
        attempt().requireCookies()
    }
}
