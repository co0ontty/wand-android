package com.wand.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettingsWebAuthenticationTest {
    private fun withServer(status: Int, test: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val base = "http://127.0.0.1:${server.localPort}"
        val url = "$base/".toHttpUrl()
        WandHttp.clientFor(base).cookieJar.saveFromResponse(url, listOf(Cookie.Builder()
            .name("wand_session_local").value("fixture-native-session").hostOnlyDomain(url.host).path("/").httpOnly().build()))
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().startsWith("POST /api/settings/webview-session "))
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                assertTrue(headers.any { it.startsWith("Cookie:", true) && it.contains("fixture-native-session") })
                val body = "{}"
                val cookie = if (status == 200) "Set-Cookie: wand_settings_access=fixture-web-proof; Path=/; HttpOnly\r\n" else ""
                socket.getOutputStream().write(("HTTP/1.1 $status Result\r\nContent-Length: ${body.length}\r\n" +
                    "${cookie}Connection: close\r\n\r\n$body").toByteArray())
            }
        }
        try { test(base); worker.get(5, TimeUnit.SECONDS) }
        finally { server.close(); executor.shutdownNow(); WandHttp.resetClient(base) }
    }

    @Test fun clientLoginIsReusedAndTheSettingsProofNeverChangesTheNativeCookieJar() = withServer(200) { base ->
        val cookies = runBlocking { SettingsWebAuthentication.cookies(base, null) }
        assertTrue(cookies.any { it.startsWith("wand_session_local=") })
        assertTrue(cookies.any { it.startsWith("wand_settings_access=") })
        assertTrue(cookies.all { it.contains("SameSite=Strict") })
        assertFalse(WandHttp.cookieHeaderFor(base).orEmpty().contains("wand_settings_access"))
    }

    @Test fun expiredClientLoginWithoutATokenFailsLocallyInsteadOfRequestingAnotherWebPassword() = withServer(401) { base ->
        val error = runCatching { runBlocking { SettingsWebAuthentication.cookies(base, null) } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("客户端重新连接"))
    }

    @Test fun oldServersHaveAnExplicitUpdateErrorRatherThanFallingBackToHome() = withServer(404) { base ->
        val error = runCatching { runBlocking { SettingsWebAuthentication.cookies(base, null) } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("更新 Wand 服务"))
    }
}
