package com.wand.app.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WandHttpTest {
    @Test
    fun canonicalAliasesReuseTheSameClient() {
        val canonical = WandHttp.clientFor("http://example.com")
        val implicitScheme = WandHttp.clientFor("example.com/")
        val explicitDefaultPort = WandHttp.clientFor(
            "HTTP://EXAMPLE.COM:80///?ignored=yes#fragment",
        )

        assertSame(canonical, implicitScheme)
        assertSame(canonical, explicitDefaultPort)
        assertEquals(
            "https://example.com/wand",
            WandHttp.normalizeBaseUrl(" HTTPS://EXAMPLE.COM:443/wand///?ignored=yes#fragment "),
        )
    }

    @Test
    fun differentEndpointsUseDifferentClients() {
        val firstPort = WandHttp.clientFor("http://127.0.0.1:48101")
        val secondPort = WandHttp.clientFor("http://127.0.0.1:48102")
        val differentScheme = WandHttp.clientFor("https://127.0.0.1:48101")
        val differentBasePath = WandHttp.clientFor("http://127.0.0.1:48101/other")

        assertNotSame(firstPort, secondPort)
        assertNotSame(firstPort, differentScheme)
        assertNotSame(firstPort, differentBasePath)
    }

    @Test
    fun sameHostDifferentPortCookiesStayIsolated() {
        val firstUrl = "http://127.0.0.1:48201/api/config".toHttpUrl()
        val secondUrl = "http://127.0.0.1:48202/api/config".toHttpUrl()
        val firstClient = WandHttp.clientFor("http://127.0.0.1:48201")
        val secondClient = WandHttp.clientFor("http://127.0.0.1:48202")
        val firstCookie = Cookie.Builder()
            .name("wand_session")
            .value("server-a")
            .hostOnlyDomain("127.0.0.1")
            .path("/")
            .build()

        firstClient.cookieJar.saveFromResponse(firstUrl, listOf(firstCookie))

        assertEquals("server-a", firstClient.cookieJar.loadForRequest(firstUrl).single().value)
        assertTrue(secondClient.cookieJar.loadForRequest(secondUrl).isEmpty())
        assertEquals("wand_session=server-a", WandHttp.cookieHeaderFor("http://127.0.0.1:48201"))
        assertNull(WandHttp.cookieHeaderFor("http://127.0.0.1:48202"))
    }

    @Test
    fun canonicalAliasSharesTheEndpointCookieJar() {
        val requestUrl = "http://127.0.0.1:48301/api/config".toHttpUrl()
        val firstClient = WandHttp.clientFor("127.0.0.1:48301")
        val aliasClient = WandHttp.clientFor("http://127.0.0.1:48301/")
        val cookie = Cookie.Builder()
            .name("wand_session")
            .value("shared")
            .hostOnlyDomain("127.0.0.1")
            .path("/")
            .build()

        firstClient.cookieJar.saveFromResponse(requestUrl, listOf(cookie))

        assertSame(firstClient, aliasClient)
        assertEquals("shared", aliasClient.cookieJar.loadForRequest(requestUrl).single().value)
    }

    @Test
    fun derivedTimeoutClientsKeepTheEndpointCookieJar() {
        val endpointClient = WandHttp.clientFor("http://127.0.0.1:48401")
        val derivedClient = endpointClient.newBuilder().build()

        assertSame(endpointClient.cookieJar, derivedClient.cookieJar)
    }

    @Test
    fun resetDropsTheCanonicalEndpointClientAndCookies() {
        val requestUrl = "http://127.0.0.1:48501/api/config".toHttpUrl()
        val original = WandHttp.clientFor("127.0.0.1:48501")
        original.cookieJar.saveFromResponse(
            requestUrl,
            listOf(
                Cookie.Builder()
                    .name("wand_session")
                    .value("old-session")
                    .hostOnlyDomain("127.0.0.1")
                    .path("/")
                    .build(),
            ),
        )

        WandHttp.resetClient("http://127.0.0.1:48501/")
        val replacement = WandHttp.clientFor("http://127.0.0.1:48501")

        assertNotSame(original, replacement)
        assertTrue(original.dispatcher.executorService.isShutdown)
        assertTrue(original.cookieJar.loadForRequest(requestUrl).isEmpty())
        val retiredFailure = runCatching {
            original.newCall(Request.Builder().url(requestUrl).build()).execute()
        }.exceptionOrNull()
        assertTrue(retiredFailure?.message?.contains("已从此设备移除") == true)
        assertTrue(replacement.cookieJar.loadForRequest(requestUrl).isEmpty())
    }
}
