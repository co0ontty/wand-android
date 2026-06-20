package com.wand.app.ui.components

/**
 * 共享文本工具函数。
 * middleTruncate 原分居 ChatScreen.kt / SessionListScreen.kt，逻辑相同，此处统一。
 */

/** 中间截断（对齐 iOS .truncationMode(.middle)，Compose 没有内置实现）。
 *  保留头部与尾部、中间塞 "…"；短文本原样返回。 */
fun middleTruncate(text: String, maxChars: Int = 38): String {
    if (text.length <= maxChars) return text
    val head = (maxChars - 1) / 2
    val tail = maxChars - 1 - head
    return text.take(head) + "…" + text.takeLast(tail)
}
