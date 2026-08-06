package com.wand.app.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A saved Wand endpoint and its endpoint-scoped credential. */
data class ServerProfile(
    val id: String,
    val baseUrl: String,
    val token: String? = null,
    val customName: String? = null,
) {
    val displayName: String
        get() = customName?.trim()?.takeIf { it.isNotEmpty() } ?: endpointDisplayName(baseUrl)

    val hasToken: Boolean get() = !token.isNullOrBlank()
}

/** Versioned value persisted atomically by ServerStore. */
data class ServerProfilesState(
    val profiles: List<ServerProfile> = emptyList(),
    val activeServerId: String? = null,
)

/**
 * Pure profile codec and state transitions. Keeping migration here makes it JVM-testable without
 * Android SharedPreferences or android.util.Base64.
 */
object ServerProfiles {
    private const val SchemaVersion = 2

    @JvmStatic
    fun canonicalBaseUrl(raw: String): String {
        val normalized = WandHttp.normalizeBaseUrl(raw)
        val parsed = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的服务器地址")
        require(parsed.scheme == "http" || parsed.scheme == "https") { "无效的服务器地址" }
        return normalized
    }

    @JvmStatic
    fun stableId(baseUrl: String): String {
        val canonical = canonicalBaseUrl(baseUrl)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return buildString(31) {
            append("server_")
            for (index in 0 until 12) append("%02x".format(digest[index].toInt() and 0xff))
        }
    }

    @JvmStatic
    fun migrateLegacy(
        lastUrl: String?,
        recentUrls: List<String>,
        legacyToken: String?,
    ): ServerProfilesState {
        val profiles = linkedMapOf<String, ServerProfile>()
        val trimmedLast = lastUrl?.trim().orEmpty()
        var activeServerId: String? = null

        fun add(raw: String, tokenForPlainUrl: String? = null): ServerProfile? {
            val input = parseInput(raw, tokenForPlainUrl) ?: return null
            val id = stableId(input.baseUrl)
            val current = profiles[id]
            val merged = ServerProfile(
                id = id,
                baseUrl = input.baseUrl,
                token = current?.token ?: input.token,
                customName = current?.customName,
            )
            profiles[id] = merged
            return merged
        }

        if (trimmedLast.isNotEmpty()) {
            val decoded = decodeConnectCode(trimmedLast)
            val active = if (decoded != null) {
                add(trimmedLast)
            } else {
                // The legacy global token only ever belonged to the last plain URL.
                add(trimmedLast, legacyToken)
            }
            activeServerId = active?.id
        }
        recentUrls.forEach { add(it) }

        return ServerProfilesState(profiles.values.toList(), activeServerId)
    }

    @JvmStatic
    fun withSavedProfile(
        state: ServerProfilesState,
        baseUrl: String,
        token: String?,
    ): ServerProfilesState {
        val decoded = decodeConnectCode(baseUrl)
        val canonical = decoded?.baseUrl ?: canonicalBaseUrl(baseUrl)
        val id = stableId(canonical)
        val existing = state.profiles.firstOrNull { it.id == id }
        val effectiveToken = normalizeToken(token ?: decoded?.token)
        val saved = ServerProfile(
            id = id,
            baseUrl = canonical,
            token = effectiveToken,
            customName = existing?.customName,
        )
        return state.copy(profiles = listOf(saved) + state.profiles.filterNot { it.id == id })
    }

    @JvmStatic
    fun withActiveServerId(state: ServerProfilesState, id: String?): ServerProfilesState {
        if (id == null) return state.copy(activeServerId = null)
        val selected = state.profiles.firstOrNull { it.id == id } ?: return state
        return state.copy(
            profiles = listOf(selected) + state.profiles.filterNot { it.id == id },
            activeServerId = id,
        )
    }

    @JvmStatic
    fun withoutProfile(state: ServerProfilesState, id: String): ServerProfilesState {
        val remaining = state.profiles.filterNot { it.id == id }
        if (remaining.size == state.profiles.size) return state
        val active = when {
            state.activeServerId == id -> remaining.firstOrNull()?.id
            state.activeServerId != null && remaining.any { it.id == state.activeServerId } -> state.activeServerId
            else -> null
        }
        return ServerProfilesState(remaining, active)
    }

    @JvmStatic
    fun profileByUrl(state: ServerProfilesState, rawUrl: String): ServerProfile? {
        val canonical = decodeConnectCode(rawUrl)?.baseUrl
            ?: runCatching { canonicalBaseUrl(rawUrl) }.getOrNull()
            ?: return null
        val id = stableId(canonical)
        return state.profiles.firstOrNull { it.id == id }
    }

    @JvmStatic
    fun encode(state: ServerProfilesState): String {
        val profiles = JSONArray()
        state.profiles.forEach { profile ->
            profiles.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("baseUrl", profile.baseUrl)
                    .put("token", profile.token ?: JSONObject.NULL)
                    .put("customName", profile.customName ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("version", SchemaVersion)
            .put("activeServerId", state.activeServerId ?: JSONObject.NULL)
            .put("profiles", profiles)
            .toString()
    }

    @JvmStatic
    fun decode(raw: String?): ServerProfilesState = decodeOrNull(raw) ?: ServerProfilesState()

    /** Returns null for a missing/corrupt payload so ServerStore can recover from its legacy mirror. */
    @JvmStatic
    fun decodeOrNull(raw: String?): ServerProfilesState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val version = root.opt("version")
            if (version !is Number || version.toInt() != SchemaVersion) return@runCatching null
            val array = root.opt("profiles") as? JSONArray ?: return@runCatching null
            val profiles = linkedMapOf<String, ServerProfile>()
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: return@runCatching null
                val canonical = runCatching {
                    canonicalBaseUrl(value.optString("baseUrl"))
                }.getOrNull() ?: return@runCatching null
                val id = stableId(canonical)
                val incoming = ServerProfile(
                    id = id,
                    baseUrl = canonical,
                    token = value.nullableString("token")?.let(::normalizeToken),
                    customName = value.nullableString("customName")?.trim()?.takeIf { it.isNotEmpty() },
                )
                val current = profiles[id]
                profiles[id] = if (current == null) incoming else current.copy(
                    token = current.token ?: incoming.token,
                    customName = current.customName ?: incoming.customName,
                )
            }
            val active = root.nullableString("activeServerId")
            if (active != null && !profiles.containsKey(active)) return@runCatching null
            ServerProfilesState(profiles.values.toList(), active)
        }.getOrNull()
    }

    private data class ParsedInput(val baseUrl: String, val token: String?)

    private fun parseInput(raw: String, tokenForPlainUrl: String?): ParsedInput? {
        decodeConnectCode(raw)?.let { return it }
        val canonical = runCatching { canonicalBaseUrl(raw) }.getOrNull() ?: return null
        return ParsedInput(canonical, normalizeToken(tokenForPlainUrl))
    }

    /** Compatible with both standard and URL-safe unpadded Wand connection codes. */
    private fun decodeConnectCode(raw: String): ParsedInput? {
        val cleaned = raw.replace(Regex("\\s+"), "")
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        val bytes = sequenceOf(Base64.getDecoder(), Base64.getUrlDecoder())
            .mapNotNull { decoder -> runCatching { decoder.decode(padded) }.getOrNull() }
            .firstOrNull() ?: return null
        val decoded = String(bytes, StandardCharsets.UTF_8)
        val separator = decoded.lastIndexOf('#')
        if (separator < 1) return null
        val token = decoded.substring(separator + 1)
        if (token.length < 16) return null
        val canonical = runCatching { canonicalBaseUrl(decoded.substring(0, separator)) }.getOrNull()
            ?: return null
        return ParsedInput(canonical, token)
    }

    private fun normalizeToken(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}

private fun endpointDisplayName(baseUrl: String): String {
    val url = baseUrl.toHttpUrlOrNull() ?: return baseUrl
    return buildString {
        append(url.host)
        val defaultPort = if (url.scheme == "https") 443 else 80
        if (url.port != defaultPort) append(":${url.port}")
        if (url.encodedPath != "/") append(url.encodedPath.trimEnd('/'))
    }
}
