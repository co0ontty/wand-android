package com.wand.app.data

import org.json.JSONObject

data class ServerSpeechStatus(val ready: Boolean, val reason: String?, val model: String, val backend: String) {
    companion object {
        fun parse(json: JSONObject): ServerSpeechStatus = ServerSpeechStatus(
            ready = json.optBoolean("ready", false),
            reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
            model = json.optJSONObject("settings")?.optString("model").orEmpty(),
            backend = json.optJSONObject("runtime")?.optString("backend").orEmpty(),
        )
    }
}
