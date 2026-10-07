package com.wand.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Read-only, bounded discovery. No credentials, login requests, cellular or VPN subnet scans. */
class LanDiscovery(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var scope: CoroutineScope? = null
    private val client = WandHttp.clientFor("https://lan-discovery.invalid").newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(650, TimeUnit.MILLISECONDS)
        .readTimeout(650, TimeUnit.MILLISECONDS)
        .callTimeout(1200, TimeUnit.MILLISECONDS)
        .build()

    fun start(
        profiles: List<ServerProfile>,
        onServers: (List<ServerProfile>) -> Unit,
        onStatus: (String, Boolean) -> Unit,
    ) {
        stop()
        val network = connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val address = network?.let { connectivity.getLinkProperties(it) }?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && LanDiscoveryPlan.isPrivateIpv4(it.address.hostAddress.orEmpty()) }
        if (network == null || address == null) {
            onServers(emptyList())
            onStatus("连接 Wi-Fi 或有线内网后，可发现附近的 Wand 服务", false)
            return
        }
        val endpoints = LanDiscoveryPlan.endpoints(address.address.hostAddress.orEmpty(), address.prefixLength, profiles)
        val scanClient = client.newBuilder().socketFactory(network.socketFactory).build()
        val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = scanScope
        onServers(emptyList())
        onStatus("正在发现内网 Wand 服务…", true)
        scanScope.launch {
            val found = linkedMapOf<String, ServerProfile>()
            val completed = withTimeoutOrNull(30_000) {
                coroutineScope {
                    val queue = Channel<String>(Channel.RENDEZVOUS)
                    launch {
                        endpoints.forEach { queue.send(it) }
                        queue.close()
                    }
                    repeat(16) {
                        launch {
                            for (endpoint in queue) {
                                val request = Request.Builder().url("$endpoint/api/session-check").build()
                                val body = suspendCancellableCoroutine<String?> { continuation ->
                                    val call = scanClient.newCall(request)
                                    continuation.invokeOnCancellation { call.cancel() }
                                    call.enqueue(object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            continuation.resume(null)
                                        }
                                        override fun onResponse(call: Call, response: Response) {
                                            val text = response.use {
                                                if (it.code == 200) runCatching {
                                                    // Never read an unbounded response from an unrelated LAN service.
                                                    it.peekBody(4096).string()
                                                }.getOrNull() else null
                                            }
                                            continuation.resume(text)
                                        }
                                    })
                                }
                                if (!LanDiscoveryPlan.isWandProbe(body)) continue
                                val profile = ServerProfile(ServerProfiles.stableId(endpoint), endpoint)
                                found[profile.id] = profile
                                onServers(found.values.toList())
                            }
                        }
                    }
                }
                true
            }
            onStatus(
                if (found.isNotEmpty()) "发现 ${found.size} 台服务，点按即可连接"
                else if (completed == null) "发现已结束，可重试或直接输入地址"
                else "未发现服务，可直接输入地址（扫描 8443 及已保存端口）",
                false,
            )
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }
}

internal object LanDiscoveryPlan {
    fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254
    }

    fun endpoints(localAddress: String, prefixLength: Int, profiles: List<ServerProfile>): List<String> {
        if (!isPrivateIpv4(localAddress) || prefixLength !in 1..30) return emptyList()
        val octets = localAddress.split('.').map(String::toLong)
        val address = octets.fold(0L) { value, octet -> value.shl(8) or octet }
        // At most the local /24, never enumerate a corporate /8 or /16.
        val prefix = maxOf(prefixLength, 24)
        val mask = (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
        val network = address and mask
        val broadcast = network or (mask xor 0xffff_ffffL)
        val ports = linkedSetOf(8443)
        val saved = profiles.mapNotNull { it.baseUrl.toHttpUrlOrNull() }
            .filter { isPrivateIpv4(it.host) }
            .map { it.newBuilder().username("").password("").query(null).fragment(null).build() }
        saved.take(4).forEach { ports.add(it.port) }
        val result = linkedSetOf<String>()
        saved.take(16).forEach { result.add(it.toString().trimEnd('/')) }
        for (host in (network + 1) until broadcast) {
            val ip = (3 downTo 0).joinToString(".") { ((host shr (it * 8)) and 255).toString() }
            for (port in ports) {
                result.add("https://$ip:$port")
                result.add("http://$ip:$port")
            }
        }
        return result.toList()
    }

    fun isWandProbe(body: String?): Boolean = body != null && runCatching {
        val probe = JSONObject(body)
        probe.length() == 1 && probe.opt("authed") is Boolean
    }.getOrDefault(false)
}
