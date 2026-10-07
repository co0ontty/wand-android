package com.wand.app

import com.wand.app.data.WandHttp
import com.wand.app.data.ServerProfile
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SettingsWebSessionTest {
    @Test fun settingsUseTheSelectedAddressWithoutCredentialsInTheUrl() {
        val session = SettingsWebSession("https://EXAMPLE.com:8443/wand/?ignored=yes#private")
        assertEquals("https://example.com:8443/wand/settings?client=app", session.startUrl)
        assertTrue(session.accepts("https://example.com:8443/api/settings"))
        assertFalse(session.accepts("https://example.com:8444/api/settings"))
        assertFalse(session.accepts("http://example.com:8443/api/settings"))
        assertFalse(session.accepts("https://example.com.attacker.invalid:8443/"))
        assertFalse(session.accepts("https://password@example.com:8443/"))
        assertFalse(session.accepts("javascript:alert(1)"))
        assertFalse(session.accepts("file:///data/private"))
    }

    @Test fun userInfoIsNotPropagatedIntoTheSettingsBrowserUrl() {
        val session = SettingsWebSession("https://fixture-user:fixture-password@example.invalid:8443/")
        assertEquals("https://example.invalid:8443/settings?client=app", session.startUrl)
        assertTrue(session.accepts("https://example.invalid:8443/api/settings"))
    }

    @Test fun labelChangesKeepTheViewButCredentialChangesAndRemovalRetireIt() {
        val initial = ServerProfile("server-a", "https://example.invalid:8443", "fixture-token", "old")
        assertTrue(settingsConnectionUnchanged(initial, initial.copy(customName = "new"), initial.id))
        assertFalse(settingsConnectionUnchanged(initial, initial.copy(token = null), initial.id))
        assertFalse(settingsConnectionUnchanged(initial, initial.copy(baseUrl = "https://other.invalid"), initial.id))
        assertFalse(settingsConnectionUnchanged(initial, initial, "server-b"))
        assertFalse(settingsConnectionUnchanged(initial, null, initial.id))
    }

    @Test fun cookieHandoffPreservesHttpOnlySecureAndEndpointIsolation() {
        val base = "https://example.invalid:49201"
        val url = "$base/".toHttpUrl()
        val client = WandHttp.clientFor(base)
        val cookie = Cookie.Builder().name("__Host-wand_session").value("fixture-session")
            .hostOnlyDomain(url.host).path("/").httpOnly().secure().build()
        client.cookieJar.saveFromResponse(url, listOf(cookie))
        val exported = WandHttp.webSessionCookies(base).single()
        assertTrue(exported.contains("httponly"))
        assertTrue(exported.contains("secure"))
        assertTrue(exported.contains("SameSite=Strict"))
        assertTrue(WandHttp.webSessionCookies("https://example.invalid:49202").isEmpty())
        WandHttp.resetClient(base)
        assertTrue(WandHttp.webSessionCookies(base).isEmpty())
    }

    @Test fun settingsOpenADedicatedPageInsteadOfDependingOnTheHomepageShell() {
        val session = SettingsWebSession("http://192.168.1.10:8181/")
        assertEquals("http://192.168.1.10:8181/settings?client=app", session.startUrl)
        assertTrue(session.accepts(session.startUrl))
        assertFalse(session.startUrl.contains("token"))
        assertTrue(session.startUrl.endsWith("?client=app"))
    }
}
