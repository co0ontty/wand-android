package com.wand.app

import com.wand.app.data.ServerProfiles
import com.wand.app.data.ServerProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.wand.app.data.WandHttp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Settings keep the current endpoint's authentication and never put credentials into URLs or JS. */
internal class SettingsWebSession(baseUrl: String) {
    val baseUrl = ServerProfiles.canonicalBaseUrl(baseUrl).toHttpUrl().newBuilder()
        .username("").password("").build().toString().trimEnd('/')
    val startUrl = "${this.baseUrl}/settings?client=app"

    fun accepts(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.username.isEmpty() && parsed.password.isEmpty() && WandHttp.isSameOrigin(url, baseUrl)
    }

}

/** Local label changes do not revoke a live settings view; endpoint/credential changes do. */
internal fun settingsConnectionUnchanged(initial: ServerProfile, current: ServerProfile?, activeId: String?): Boolean =
    current != null && current.id == initial.id && activeId == initial.id &&
        current.baseUrl == initial.baseUrl && current.token == initial.token
