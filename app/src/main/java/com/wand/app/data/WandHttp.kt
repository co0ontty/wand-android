package com.wand.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 原生客户端按服务端 endpoint 复用的 OkHttpClient：自签名证书放行 + 内存 CookieJar。
 * 同一 endpoint 的 REST、WebSocket、图片与文件请求共用同一个 client，登录拿到的 session cookie
 * （__Host-wand_session / wand_session / wand_session_local）会自动带到
 * 后续请求上；不同 endpoint 使用独立 CookieJar，避免同 host 不同端口串登录态。
 *
 * 自签证书放行仅用于用户自己的 wand server；跨源下载走系统证书校验。
 */
object WandHttp {

    /** 规范化 base URL → endpoint 专属 client。冷启动后内存 cookie 会自然清空。 */
    private data class EndpointClient(
        val client: OkHttpClient,
        val cookieJar: MemoryCookieJar,
        val retired: AtomicBoolean,
    )

    private val clients = ConcurrentHashMap<String, EndpointClient>()
    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wand-http-cleanup").apply { isDaemon = true }
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt <= now }
            for (cookie in cookies) {
                store.removeAll { existing ->
                    existing.name == cookie.name &&
                        existing.domain == cookie.domain &&
                        existing.path == cookie.path
                }
                if (cookie.expiresAt > now) store.add(cookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt <= now }
            return store.filter { it.matches(url) }
        }

        @Synchronized
        fun clear() {
            store.clear()
        }
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun buildClient(): EndpointClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllManager), SecureRandom())
        val cookieJar = MemoryCookieJar()
        val retired = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                if (retired.get()) throw IOException("服务器连接已从此设备移除")
                chain.proceed(chain.request())
            }
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // WebSocket 心跳由应用层 ping/pong + watchdog 负责，这里不设 pingInterval
            .build()
        return EndpointClient(client, cookieJar, retired)
    }

    /**
     * 返回 endpoint 专属 client。同一规范化 endpoint 始终返回同一实例，登录、REST、
     * WebSocket 和资源加载因此共享 cookie；不同 scheme/host/port/base path 互相隔离。
     */
    @JvmStatic
    fun clientFor(baseUrl: String): OkHttpClient {
        val endpoint = normalizeBaseUrl(baseUrl)
        return clients.computeIfAbsent(endpoint) { buildClient() }.client
    }

    /**
     * 丢弃 endpoint 当前缓存的 client。连接凭据被替换或清除后调用，确保下一次登录、
     * REST、WebSocket 或资源请求从没有旧 session cookie 的新 client 开始。
     */
    @JvmStatic
    fun resetClient(baseUrl: String) {
        val endpoint = normalizeBaseUrl(baseUrl)
        clients.remove(endpoint)?.let { endpointClient ->
            endpointClient.retired.set(true)
            endpointClient.cookieJar.clear()
            val client = endpointClient.client
            client.dispatcher.cancelAll()
            // Existing WandApi/WandSocket instances may still hold this client after a profile is
            // removed. Retire its dispatcher permanently so they cannot reuse deleted credentials.
            client.dispatcher.executorService.shutdownNow()
            // evictAll closes live TLS sockets (SSL write/close). On Android that is network I/O
            // and StrictMode kills the app if it runs on the main thread after auto-connect.
            cleanupExecutor.execute {
                runCatching { client.connectionPool.evictAll() }
            }
        }
    }

    /** Endpoint-scoped cookie header for Java helpers that still use HttpURLConnection. */
    @JvmStatic
    fun cookieHeaderFor(baseUrl: String): String? {
        val endpoint = normalizeBaseUrl(baseUrl)
        val endpointClient = clients[endpoint] ?: return null
        val requestUrl = "$endpoint/".toHttpUrlOrNull() ?: return null
        return endpointClient.client.cookieJar.loadForRequest(requestUrl)
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
            .takeIf { it.isNotEmpty() }
    }

    private val publicClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    class SimpleResponse(val code: Int, val body: String)

    @JvmStatic
    fun isSameOrigin(url: String, originBase: String): Boolean {
        val left = url.toHttpUrlOrNull() ?: return false
        val right = normalizeBaseUrl(originBase).toHttpUrlOrNull() ?: return false
        return left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            left.port == right.port
    }

    /**
     * 同源走 endpoint 的 trust-all client（带 cookie）；跨源（GitHub 等）走系统证书校验。
     * 调用方自行跟随重定向，避免 trust-all 扩散到公网。
     */
    @JvmStatic
    fun requestClient(
        url: String,
        trustOriginBaseUrl: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): OkHttpClient {
        val sameOrigin = !trustOriginBaseUrl.isNullOrBlank() && isSameOrigin(url, trustOriginBaseUrl)
        val base = if (sameOrigin) clientFor(trustOriginBaseUrl!!) else publicClient
        return base.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @JvmStatic
    @JvmOverloads
    fun get(url: String, timeoutMs: Int, trustOriginBaseUrl: String? = null): SimpleResponse {
        // 连接探测必须跟随重定向：HttpURLConnection GET 默认跟随，关掉以后
        // http→https / 反代 301 会被当成「异常状态码」卡在连接页。
        val origin = trustOriginBaseUrl ?: url
        val client = clientFor(origin).newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            return SimpleResponse(response.code, response.body?.string().orEmpty())
        }
    }

    @JvmStatic
    fun postJson(url: String, json: String, timeoutMs: Int, trustOriginBaseUrl: String): SimpleResponse {
        val client = clientFor(trustOriginBaseUrl).newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            return SimpleResponse(response.code, response.body?.string().orEmpty())
        }
    }

    /** 补全协议并规范化 host、默认端口与 base path；query/fragment 不属于 endpoint。 */
    @JvmStatic
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "http://$url"
        }
        val parsed = url.toHttpUrlOrNull()
        if (parsed != null) {
            url = parsed.newBuilder()
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }
        return url.trimEnd('/')
    }
}
