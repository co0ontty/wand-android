package com.wand.app.ui.screens

import com.wand.app.data.ConversationTurn
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 聊天消息时间：当天只显示时分秒，跨天才带月/日。 */
fun formatChatClock(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return ""
    val local = instant.atZone(zone)
    val clock = clockFormatter.format(local)
    val today = ZonedDateTime.now(zone).toLocalDate()
    return if (local.toLocalDate() == today) clock else "${local.monthValue}/${local.dayOfMonth} $clock"
}

fun conversationTurnClock(turn: ConversationTurn): String =
    formatChatClock(turn.completedAt ?: turn.createdAt)
