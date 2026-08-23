package com.wand.app.ui

import java.time.Instant

internal fun parseIsoMillis(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/**
 * 单位时长文案（对齐 iOS SessionTimeFormatting 的相对时间语义，只显示一个单位、无后缀）：
 * 刚刚 / N分钟 / N小时 / N天。秒数由调用方提供：相对时间传 (now - 当时)，会话时长传 (end - start)。
 */
internal fun singleUnitDurationLabel(deltaMillis: Long): String {
    val minutes = deltaMillis.coerceAtLeast(0L) / 60_000L
    if (minutes < 1) return "刚刚"
    val hours = minutes / 60
    if (hours < 1) return "${minutes}分钟"
    val days = hours / 24
    if (days < 1) return "${hours}小时"
    return "${days}天"
}
