package com.wand.app.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfilesTest {
    @Test
    fun canonicalUrlDrivesStableIdentityAndDisplayName() {
        val canonical = ServerProfiles.canonicalBaseUrl(" HTTP://Example.COM:80/work/?ignored=1#part ")
        val profile = ServerProfile(ServerProfiles.stableId(canonical), canonical)

        assertEquals("http://example.com/work", canonical)
        assertEquals(ServerProfiles.stableId("example.com/work"), profile.id)
        assertEquals("example.com/work", profile.displayName)
        assertFalse(profile.hasToken)
    }

    @Test
    fun migrationSplitsCodesAndOnlyAssignsGlobalTokenToLastPlainUrl() {
        val betaToken = "beta-token-1234567890"
        val betaCode = connectCode("HTTPS://Beta.Example:443/", betaToken, urlSafe = true)
        val staleAlphaCode = connectCode("http://alpha.example", "stale-alpha-123456", urlSafe = false)

        val state = ServerProfiles.migrateLegacy(
            lastUrl = "HTTP://Alpha.Example:80/",
            recentUrls = listOf(betaCode, "gamma.example:4040/", staleAlphaCode),
            legacyToken = "active-alpha-123456",
        )

        assertEquals(
            listOf("http://alpha.example", "https://beta.example", "http://gamma.example:4040"),
            state.profiles.map { it.baseUrl },
        )
        assertEquals(state.profiles[0].id, state.activeServerId)
        assertEquals("active-alpha-123456", state.profiles[0].token)
        assertEquals(betaToken, state.profiles[1].token)
        assertNull(state.profiles[2].token)
        assertFalse(ServerProfiles.encode(state).contains(betaCode))
    }

    @Test
    fun embeddedTokenWinsForLastConnectionCode() {
        val token = "embedded-token-123456"
        val code = connectCode("http://host.local:8123/", token, urlSafe = false)

        val state = ServerProfiles.migrateLegacy(code, emptyList(), "unrelated-global-token")

        assertEquals("http://host.local:8123", state.profiles.single().baseUrl)
        assertEquals(token, state.profiles.single().token)
        assertEquals(state.profiles.single().id, state.activeServerId)
        assertFalse(ServerProfiles.encode(state).contains(code))
    }

    @Test
    fun emptyLegacyLastKeepsProfilesDisconnectedAndDoesNotLeakGlobalToken() {
        val state = ServerProfiles.migrateLegacy(
            lastUrl = "",
            recentUrls = listOf("recent.example:9000"),
            legacyToken = "must-not-leak-123456",
        )

        assertEquals(1, state.profiles.size)
        assertNull(state.profiles.single().token)
        assertNull(state.activeServerId)
    }

    @Test
    fun removingActiveProfileUsesFirstRemainingProfileAsFallback() {
        var state = ServerProfilesState()
        state = ServerProfiles.withSavedProfile(state, "one.example", "one-token-123456")
        val one = state.profiles.single()
        state = ServerProfiles.withActiveServerId(state, one.id)
        state = ServerProfiles.withSavedProfile(state, "two.example", null)
        val two = state.profiles.first()

        val removed = ServerProfiles.withoutProfile(state, one.id)

        assertEquals(listOf(two.id), removed.profiles.map { it.id })
        assertEquals(two.id, removed.activeServerId)
    }

    @Test
    fun savingNullTokenClearsCredentialWithoutChangingStableId() {
        var state = ServerProfiles.withSavedProfile(
            ServerProfilesState(),
            "https://same.example/",
            "saved-token-123456",
        )
        val originalId = state.profiles.single().id

        state = ServerProfiles.withSavedProfile(state, "HTTPS://SAME.EXAMPLE:443", null)

        assertEquals(originalId, state.profiles.single().id)
        assertNull(state.profiles.single().token)
        assertFalse(state.profiles.single().hasToken)
    }

    @Test
    fun codecRoundTripsCustomNameAndFailsClosedOnMalformedJson() {
        val profile = ServerProfile(
            id = ServerProfiles.stableId("https://named.example"),
            baseUrl = "https://named.example",
            token = "named-token-123456",
            customName = "工作站",
        )
        val state = ServerProfilesState(listOf(profile), profile.id)

        val decoded = ServerProfiles.decode(ServerProfiles.encode(state))

        assertEquals(state, decoded)
        assertEquals("工作站", decoded.profiles.single().displayName)
        assertTrue(decoded.profiles.single().hasToken)
        assertEquals(ServerProfilesState(), ServerProfiles.decode("{broken"))
        assertNull(ServerProfiles.decodeOrNull("{broken"))
        assertNull(ServerProfiles.decodeOrNull(""))
        assertNull(ServerProfiles.decodeOrNull("{\"version\":2}"))
        assertNull(ServerProfiles.decodeOrNull("{\"version\":2,\"profiles\":{}}"))
    }

    private fun connectCode(url: String, token: String, urlSafe: Boolean): String {
        val bytes = "$url#$token".toByteArray(StandardCharsets.UTF_8)
        val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        return encoder.withoutPadding().encodeToString(bytes)
    }
}
