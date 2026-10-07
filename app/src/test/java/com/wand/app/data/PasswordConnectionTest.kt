package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PasswordConnectionTest {
    private fun withServer(status: Int, code: String? = null, test: (String, AtomicInteger, MutableList<String>) -> Unit) {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val logins = AtomicInteger()
        val passwords = mutableListOf<String>()
        val token = "fixture-app-token-0123456789"
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit {
            repeat(if (status == 200) 2 else 1) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val login = request.startsWith("POST /api/login ")
                    val body: String
                    if (login) {
                        logins.incrementAndGet()
                        val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        val input = CharArray(length)
                        var offset = 0
                        while (offset < length) offset += reader.read(input, offset, length - offset)
                        passwords.add(JSONObject(String(input)).getString("password"))
                        body = JSONObject().put("ok", status == 200).toString()
                    } else {
                        assertTrue(request.startsWith("GET /api/app-connect-code "))
                        assertTrue(headers.any { it.startsWith("Cookie:", true) && it.contains("fixture-session") })
                        val encoded = code ?: Base64.getEncoder().encodeToString("https://public.example:8443#$token".toByteArray())
                        body = JSONObject().put("code", encoded).toString()
                    }
                    val cookie = if (login && status == 200) "Set-Cookie: wand_session_local=fixture-session; Path=/; HttpOnly\r\n" else ""
                    val response = "HTTP/1.1 ${if (login) status else 200} Result\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n${cookie}Connection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
        }
        val base = "http://127.0.0.1:${server.localPort}"
        try {
            test(base, logins, passwords)
            worker.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            WandHttp.resetClient(base)
        }
    }

    @Test fun passwordLoginReturnsOnlyTheDurableTokenAndKeepsTheSelectedAddress() = withServer(200) { base, calls, passwords ->
        val result = PasswordConnection.connect(base, "  fixture-password  ", 2000)
        assertNull(result.error)
        assertEquals(base, result.baseUrl)
        assertEquals("fixture-app-token-0123456789", result.token)
        assertFalse(result.needsPassword)
        assertEquals(listOf("  fixture-password  "), passwords)
        assertEquals(1, calls.get())
    }

    @Test fun anOpenServerConnectsWithOneEmptyPasswordRequest() = withServer(200) { base, calls, passwords ->
        val result = PasswordConnection.connect(base, "", 2000)
        assertNull(result.error)
        assertNotNull(result.token)
        assertEquals(listOf(""), passwords)
        assertEquals(1, calls.get())
    }

    @Test fun aPasswordProtectedAddressRequestsPasswordInsteadOfClaimingSuccess() = withServer(401) { base, calls, _ ->
        val result = PasswordConnection.connect(base, "", 2000)
        assertTrue(result.needsPassword)
        assertEquals("请输入服务器密码", result.error)
        assertNull(result.token)
        assertFalse(result.retryable)
        assertEquals(1, calls.get())
    }

    @Test fun wrongPasswordsAreNotAutomaticallyRetriedOrSaved() = withServer(401) { base, calls, _ ->
        val result = PasswordConnection.connect(base, "wrong-fixture", 2000)
        assertTrue(result.needsPassword)
        assertEquals("密码错误，请重试", result.error)
        assertNull(result.token)
        assertEquals(1, calls.get())
    }

    @Test fun rateLimitingDoesNotCreateALoginRetryLoop() = withServer(429) { base, calls, _ ->
        val result = PasswordConnection.connect(base, "", 2000)
        assertFalse(result.retryable)
        assertFalse(result.needsPassword)
        assertNull(result.token)
        assertEquals(1, calls.get())
    }

    @Test fun temporaryServerFailureIsNotAPasswordPrompt() = withServer(503) { base, _, _ ->
        val result = PasswordConnection.connect(base, "", 2000)
        assertTrue(result.retryable)
        assertFalse(result.needsPassword)
        assertNull(result.token)
    }

    @Test fun invalidServerCredentialsDoNotCountAsSuccessfulConnection() = withServer(200, "not-a-connect-code") { base, _, _ ->
        val result = PasswordConnection.connect(base, "fixture-password", 2000)
        assertNotNull(result.error)
        assertNull(result.token)
    }

    @Test fun passwordRedirectsOnlyAllowASameHostHttpsUpgrade() {
        assertEquals("https://example.com", PasswordConnection.upgradeRedirect("http://example.com", "https://example.com/api/login"))
        assertEquals("https://example.com:8443/wand", PasswordConnection.upgradeRedirect("http://example.com/wand", "https://example.com:8443/wand/api/login"))
        for (target in listOf("https://other.example/api/login", "http://example.com/api/login",
            "https://secret@example.com/api/login", "https://example.com/other", "https://example.com/api/login?token=x")) {
            assertNull(PasswordConnection.upgradeRedirect("http://example.com", target))
        }
        assertNull(PasswordConnection.upgradeRedirect("https://example.com", "http://example.com/api/login"))
    }

    @Test fun connectionCodeDecoderAcceptsWrappedAndUrlSafeCodes() {
        val input = "https://example.invalid:8443#fixture-token-0123456789"
        val code = Base64.getEncoder().encodeToString(input.toByteArray())
        assertEquals("fixture-token-0123456789", WandAuth.decodeConnectCode(code.chunked(10).joinToString("\n"))?.second)
        assertEquals("https://example.invalid:8443", WandAuth.decodeConnectCode(Base64.getUrlEncoder()
            .withoutPadding().encodeToString(input.toByteArray()))?.first)
        assertNull(WandAuth.decodeConnectCode("not a connection code"))
    }
}
