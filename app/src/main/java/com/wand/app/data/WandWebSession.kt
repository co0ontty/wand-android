package com.wand.app.data

import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Serializes the process-global WebView cookie store onto one explicit endpoint. Native requests
 * never use this store; every embedded/fallback WebView must call prepare before its first load.
 */
object WandWebSession {
    private val mutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val credentialEpoch = AtomicLong(0L)
    private val foregroundOwner = AtomicReference<OwnerLease?>(null)

    private data class OwnerLease(
        val ownerId: String,
        val onRevoked: OwnerRevocation,
    )

    fun interface Callback {
        fun onComplete(errorMessage: String?)
    }

    /** Must synchronously stop and destroy every WebView owned by this lease. */
    fun interface OwnerRevocation {
        fun onRevoked()
    }

    /** Java-friendly entry point; all calls still pass through the same process-global mutex. */
    @JvmStatic
    fun prepareAsync(
        ownerId: String,
        baseUrl: String,
        token: String?,
        onRevoked: OwnerRevocation,
        callback: Callback,
    ) {
        callbackScope.launch {
            val error = try {
                prepare(ownerId, baseUrl, token, onRevoked)
                null
            } catch (error: Exception) {
                error.message ?: "网页版认证失败"
            }
            callback.onComplete(error)
        }
    }

    /** Invalidates queued old preparations, then clears the global WebView credential store. */
    @JvmStatic
    fun clearAsync() {
        callbackScope.launch {
            runCatching {
                revokeCurrentOwner()
                mutex.withLock { replaceCookies(baseUrl = null, setCookies = emptyList()) }
            }
        }
    }

    /** Releases an already-destroyed WebView so its in-flight prepare cannot become current. */
    @JvmStatic
    fun release(ownerId: String) {
        while (true) {
            val current = foregroundOwner.get() ?: return
            if (current.ownerId != ownerId) return
            if (foregroundOwner.compareAndSet(current, null)) {
                credentialEpoch.incrementAndGet()
                return
            }
        }
    }

    suspend fun prepare(
        ownerId: String,
        baseUrl: String,
        token: String?,
        onRevoked: OwnerRevocation,
    ) {
        val requestEpoch = claimOwner(ownerId, onRevoked)
        mutex.withLock {
            if (!isCurrent(ownerId, requestEpoch)) {
                throw WandAuth.AuthException("服务器连接已变更")
            }
            val normalized = WandHttp.normalizeBaseUrl(baseUrl)
            val setCookies = if (token.isNullOrEmpty()) {
                emptyList()
            } else {
                loginCookieHeaders(normalized, token)
            }
            if (!isCurrent(ownerId, requestEpoch)) {
                throw WandAuth.AuthException("服务器连接已变更")
            }
            replaceCookies(normalized, setCookies)
        }
    }

    private fun isCurrent(ownerId: String, requestEpoch: Long): Boolean =
        foregroundOwner.get()?.ownerId == ownerId && credentialEpoch.get() == requestEpoch

    /**
     * A new endpoint may replace global cookies only after the previous owner's WebViews have
     * synchronously stopped. Running this on Main makes revocation ordered with Activity/Compose UI.
     */
    private suspend fun claimOwner(
        ownerId: String,
        onRevoked: OwnerRevocation,
    ): Long = withContext(Dispatchers.Main.immediate) {
        // A Service Worker outlives its creating WebView. Blocking its network access prevents an
        // old endpoint worker from waking after the global cookie store has switched endpoints.
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.blockNetworkLoads = true
        val previous = foregroundOwner.get()
        if (previous != null && previous.ownerId != ownerId) {
            previous.onRevoked.onRevoked()
        }
        foregroundOwner.set(OwnerLease(ownerId, onRevoked))
        credentialEpoch.incrementAndGet()
    }

    private fun revokeCurrentOwner() {
        val previous = foregroundOwner.get()
        previous?.onRevoked?.onRevoked()
        foregroundOwner.set(null)
        credentialEpoch.incrementAndGet()
    }

    private suspend fun loginCookieHeaders(baseUrl: String, token: String): List<String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("appToken", token).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/login")
                .post(body)
                .build()
            WandHttp.clientFor(baseUrl).newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> response.headers("Set-Cookie").takeIf { it.isNotEmpty() }
                        ?: throw WandAuth.AuthException("服务器未返回网页版登录凭据")
                    401 -> throw WandAuth.AuthException("认证失败，连接码可能已过期，请重新连接")
                    429 -> throw WandAuth.AuthException("登录尝试次数过多，请稍后再试")
                    else -> throw WandAuth.AuthException("服务器返回异常状态码：${response.code}")
                }
            }
        }

    private suspend fun replaceCookies(baseUrl: String?, setCookies: List<String>) {
        withContext(Dispatchers.Main.immediate) {
            // Deliberately non-cancellable: releasing the mutex before CookieManager's async
            // removal callback would let an older clear finish after a newer endpoint was set.
            suspendCoroutine { continuation ->
                val manager = CookieManager.getInstance()
                manager.removeAllCookies {
                    if (baseUrl != null) {
                        setCookies.forEach { cookie -> manager.setCookie(baseUrl, cookie) }
                    }
                    manager.flush()
                    continuation.resume(Unit)
                }
            }
        }
    }
}
