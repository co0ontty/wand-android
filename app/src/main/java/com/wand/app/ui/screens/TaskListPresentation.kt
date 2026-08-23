package com.wand.app.ui.screens

/** Single-unit relative duration used by compact task/history timestamps. */
internal fun singleUnitDurationLabel(deltaMillis: Long): String {
    val minutes = deltaMillis.coerceAtLeast(0L) / 60_000L
    if (minutes < 1) return "刚刚"
    val hours = minutes / 60
    if (hours < 1) return "${minutes}分钟"
    val days = hours / 24
    if (days < 1) return "${hours}小时"
    return "${days}天"
}
