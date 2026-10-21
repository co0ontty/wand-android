package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Ordered materialized view: updates, removals and old-message edits use one protocol. */
internal fun mergeConversationUpdate(previous: JSONObject?, update: JSONObject): JSONObject {
    if (!update.has("history")) return update
    val prior = previous?.takeIf { it.optString("id") == update.optString("id") }
    if (update.optBoolean("unchanged")) {
        check(prior != null) { "对话同步缺少基线" }
        return JSONObject(prior.toString()).put("history", update.getJSONObject("history"))
    }
    val metadata = update.optJSONObject("detail") ?: prior ?: error("对话同步缺少详情")
    val merged = JSONObject(metadata.toString())
    val rows = linkedMapOf<String, JSONObject>()
    fun add(array: JSONArray?) { if (array != null) repeat(array.length()) {
        val row = array.getJSONObject(it); rows[row.getString("messageId")] = row
    } }
    if (!update.optBoolean("reset")) add(prior?.optJSONArray("messages"))
    add(update.optJSONArray("messages"))
    val result = JSONArray()
    val order = update.getJSONArray("messageOrder")
    repeat(order.length()) { result.put(rows[order.getString(it)] ?: error("对话同步缺少消息")) }
    return merged.put("messages", result).put("history", update.getJSONObject("history"))
}
