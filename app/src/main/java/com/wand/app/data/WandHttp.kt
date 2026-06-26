package com.wand.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 原生客户端共享的 OkHttpClient：自签名证书放行 + 内存 CookieJar。
 * REST 与 WebSocket 共用同一个 client，登录拿到的 session cookie
 * （__Host-wand_session / wand_session / wand_session_local）会自动带到
 * 每个 REST 请求和 /ws 升级请求上 —— 对称 iOS 端的 SelfSignedSession。
 *
 * 自签证书放行策略与现有 NetUtils.trustSelfSigned 一致（wand 是局域网自托管
 * 服务，HTTPS 默认用自签证书）。
 */
object WandHttp {

    /** 按 host 存、按 cookie name 替换的内存 CookieJar（进程级，冷启动后需重新登录）。 */
    private val cookieJar = object : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            for (cookie in cookies) {
                list.removeAll { it.name == cookie.name }
                list.add(cookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return store[url.host]?.toList() ?: emptyList()
        }
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val client: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllManager), SecureRandom())
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // WebSocket 心跳由应用层 ping/pong + watchdog 负责，这里不设 pingInterval
            .build()
    }

    /** 补全协议并去掉末尾斜杠的 base URL 归一化。 */
    @JvmStatic
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        while (url.endsWith("/")) url = url.dropLast(1)
        return url
    }
}
