package com.wand.app.ui.terminal

import com.wand.app.data.PtyTerminalSnapshot

private const val BRACKETED_PASTE_START = "\u001b[200~"
private const val BRACKETED_PASTE_END = "\u001b[201~"
private const val BRACKETED_PASTE_ENABLE = "\u001b[?2004h"
private const val BRACKETED_PASTE_DISABLE = "\u001b[?2004l"

/**
 * Encode clipboard text as one terminal paste. Bracketed paste is what Codex
 * uses to recognize a paste instead of a run of keystrokes. ESC inside the
 * payload is escaped so it cannot close the bracket early.
 */
internal fun buildTerminalPasteSequence(text: String, bracketed: Boolean): String {
    if (text.isEmpty()) return ""
    val normalized = text.replace("\r\n", "\r").replace("\n", "\r")
    if (!bracketed) return normalized
    val sanitized = normalized.replace("\u001b", "\u241b")
    return BRACKETED_PASTE_START + sanitized + BRACKETED_PASTE_END
}

/** Codex always wants a paste boundary. Other programs only get one after they enable it. */
internal fun shouldBracketTerminalPaste(provider: String?, bracketedPasteMode: Boolean): Boolean {
    if (bracketedPasteMode) return true
    return provider?.trim()?.equals("codex", ignoreCase = true) == true
}

/**
 * Tracks DECSET 2004 (`CSI ? 2004 h/l`) across PTY chunks. The mode sequence can
 * be split between websocket frames, so a short unmatched tail is kept.
 */
internal class BracketedPasteModeTracker {
    var enabled: Boolean = false
        private set

    private var pending: String = ""

    fun reset() {
        enabled = false
        pending = ""
    }

    fun observe(chunk: String) {
        if (chunk.isEmpty() && pending.isEmpty()) return
        val text = if (pending.isEmpty()) chunk else pending + chunk
        pending = ""
        var index = 0
        while (index < text.length) {
            val esc = text.indexOf('\u001b', index)
            if (esc < 0) break
            val tail = text.substring(esc)
            when {
                tail.startsWith(BRACKETED_PASTE_ENABLE) -> {
                    enabled = true
                    index = esc + BRACKETED_PASTE_ENABLE.length
                }
                tail.startsWith(BRACKETED_PASTE_DISABLE) -> {
                    enabled = false
                    index = esc + BRACKETED_PASTE_DISABLE.length
                }
                tail.length < BRACKETED_PASTE_ENABLE.length &&
                    (BRACKETED_PASTE_ENABLE.startsWith(tail) || BRACKETED_PASTE_DISABLE.startsWith(tail)) -> {
                    pending = tail
                    return
                }
                else -> index = esc + 1
            }
        }
    }

    fun observeReplay(snapshot: PtyTerminalSnapshot?, output: String?) {
        if (snapshot != null && snapshot.isReplayable) {
            observe(snapshot.data)
            snapshot.pending.forEach { operation ->
                if (operation is PtyTerminalSnapshot.Operation.Data) observe(operation.text)
            }
            return
        }
        if (snapshot == null && output != null) observe(output)
    }
}
