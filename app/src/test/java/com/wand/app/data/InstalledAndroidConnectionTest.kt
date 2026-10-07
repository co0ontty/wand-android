package com.wand.app.data

import com.wand.app.SettingsWebSession
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in acceptance against the installed server. Reads private credentials without emitting them. */
class InstalledAndroidConnectionTest {
    @Test fun installedServerSupportsDiscoveryPasswordPromptNativeLoginAndWebSettings() {
        assumeTrue(System.getenv("WAND_ANDROID_INSTALLED_ACCEPTANCE") == "1")
        val connection = JSONObject(File(System.getenv("HOME"), ".wand/acceptance-connection.json").readText())
        val base = ServerProfiles.canonicalBaseUrl(connection.getString("serverURL"))
        val decoded = WandAuth.decodeConnectCode(connection.getString("connectionCode"))
        assertTrue("Private acceptance credentials must be valid", decoded != null)
        val anonymous = WandHttp.clientFor(base).newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        anonymous.newCall(Request.Builder().url("$base/api/session-check").build()).execute().use {
            assertEquals(200, it.code)
            assertTrue("Installed service must match the read-only LAN discovery probe",
                LanDiscoveryPlan.isWandProbe(it.body?.string()))
        }
        // One explicit empty-password attempt verifies that 401 is a prompt, not a false success.
        val webSession = SettingsWebSession(base)
        val page = WandHttp.get(webSession.startUrl, 10_000, base)
        assertEquals("The current service must provide a dedicated settings route", 200, page.code)
        assertTrue("Settings HTML must select full-page rendering before JS starts",
            page.body.contains("data-wand-page=\"settings\""))
        val passwordResult = PasswordConnection.connect(base, "", 10_000)
        assertTrue("The installed service either connects openly or requests its password",
            passwordResult.error == null && passwordResult.token != null || passwordResult.needsPassword)
        try {
            val login = WandHttp.postJson("$base/api/login",
                JSONObject().put("appToken", decoded!!.second).toString(), 10_000, base)
            assertEquals(200, login.code)
            val session = WandHttp.get("$base/api/session-check", 10_000, base)
            assertTrue(JSONObject(session.body).getBoolean("authed"))
            assertTrue("Native session cookies must be available to the selected WebView only",
                WandHttp.webSessionCookies(base).isNotEmpty())
            val config = WandHttp.get("$base/api/config", 10_000, base)
            assertEquals(200, config.code)
            assertTrue(JSONObject(config.body).getString("currentVersion").isNotBlank())
            val about = WandHttp.get("$base/api/settings/about", 10_000, base)
            assertEquals(200, about.code)
            val settings = WandHttp.get("$base/api/settings", 10_000, base)
            assertEquals("The native API retains its original connection permissions", 403, settings.code)
            val webCookies = runBlocking { SettingsWebAuthentication.cookies(base, decoded.second) }
            assertTrue(webCookies.any { it.startsWith("wand_settings_access=") })
            assertTrue(WandHttp.cookieHeaderFor(base).orEmpty().contains("wand_settings_access=").not())
            val webHeader = webCookies.joinToString("; ") { it.substringBefore(';') }
            anonymous.newCall(Request.Builder().url("$base/api/settings").header("Cookie", webHeader).build()).execute().use {
                assertEquals("The WebView reuses client authentication for all settings without a password", 200, it.code)
            }
            assertEquals(403, WandHttp.get("$base/api/settings", 10_000, base).code)
        } finally {
            runCatching { WandHttp.postJson("$base/api/logout", "{}", 10_000, base) }
            WandHttp.resetClient(base)
        }
    }
}
