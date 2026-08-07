package com.wand.app.ui.terminal

import androidx.compose.runtime.Immutable
import java.util.UUID

private const val Escape = "\u001B"

enum class TerminalModifier(val id: String, val label: String) {
    Ctrl("ctrl", "Ctrl"),
    Alt("alt", "Alt"),
    Shift("shift", "Shift"),
}

@Immutable
data class TerminalKeyBinding(
    val key: String,
    val modifiers: Set<TerminalModifier> = emptySet(),
)

@Immutable
data class TerminalShortcut(
    val id: String,
    val label: String,
    val accessibilityLabel: String,
    val binding: TerminalKeyBinding,
    val builtIn: Boolean,
    val repeatable: Boolean = false,
) {
    val bytes: String get() = encodeTerminalKey(binding).orEmpty()
}

data class TerminalSpecialKey(
    val id: String,
    val label: String,
    val accessibilityLabel: String,
)

val TerminalSpecialKeys = listOf(
    TerminalSpecialKey("escape", "Esc", "Escape"),
    TerminalSpecialKey("tab", "Tab", "Tab"),
    TerminalSpecialKey("enter", "Enter", "Enter"),
    TerminalSpecialKey("backspace", "⌫", "退格"),
    TerminalSpecialKey("delete", "Del", "向前删除"),
    TerminalSpecialKey("arrowLeft", "←", "左方向键"),
    TerminalSpecialKey("arrowUp", "↑", "上方向键"),
    TerminalSpecialKey("arrowDown", "↓", "下方向键"),
    TerminalSpecialKey("arrowRight", "→", "右方向键"),
    TerminalSpecialKey("home", "Home", "行首"),
    TerminalSpecialKey("end", "End", "行尾"),
    TerminalSpecialKey("pageUp", "PgUp", "向上翻页"),
    TerminalSpecialKey("pageDown", "PgDn", "向下翻页"),
    TerminalSpecialKey("space", "Space", "空格"),
)

fun buildTerminalShortcut(
    binding: TerminalKeyBinding,
    id: String = "custom-${UUID.randomUUID()}",
    builtIn: Boolean = false,
    accessibilityLabel: String? = null,
): TerminalShortcut? {
    val normalized = normalizeTerminalBinding(binding) ?: return null
    if (encodeTerminalKey(normalized) == null) return null
    val label = terminalShortcutLabel(normalized)
    val repeatable = normalized.key in setOf(
        "arrowLeft",
        "arrowUp",
        "arrowDown",
        "arrowRight",
        "backspace",
        "delete",
        "home",
        "end",
        "pageUp",
        "pageDown",
    )
    return TerminalShortcut(
        id = id,
        label = label,
        accessibilityLabel = accessibilityLabel ?: label.replace("+", " "),
        binding = normalized,
        builtIn = builtIn,
        repeatable = repeatable,
    )
}

fun normalizeTerminalKeyInput(raw: String): String? {
    val key = raw.firstOrNull { it != '\n' && it != '\r' && it != '\t' } ?: return null
    if (key.code !in 32..126) return null
    return key.lowercaseChar().toString()
}

fun terminalShortcutLabel(binding: TerminalKeyBinding): String {
    val normalized = normalizeTerminalBinding(binding) ?: return ""
    val modifiers = TerminalModifier.entries
        .filter { it in normalized.modifiers }
        .map(TerminalModifier::label)
    val keyLabel = TerminalSpecialKeys.firstOrNull { it.id == normalized.key }?.label
        ?: normalized.key.uppercase()
    return (modifiers + keyLabel).joinToString("+")
}

/** Encodes the subset of xterm key sequences that are safe to send directly to a PTY. */
fun encodeTerminalKey(binding: TerminalKeyBinding): String? {
    val normalized = normalizeTerminalBinding(binding) ?: return null
    val key = normalized.key
    val modifiers = normalized.modifiers

    val csiFinal = mapOf(
        "arrowUp" to "A",
        "arrowDown" to "B",
        "arrowRight" to "C",
        "arrowLeft" to "D",
        "home" to "H",
        "end" to "F",
    )[key]
    if (csiFinal != null) {
        val parameter = xtermModifierParameter(modifiers)
        return if (parameter == 1) "$Escape[$csiFinal" else "$Escape[1;${parameter}$csiFinal"
    }

    val csiTilde = mapOf(
        "delete" to 3,
        "pageUp" to 5,
        "pageDown" to 6,
    )[key]
    if (csiTilde != null) {
        val parameter = xtermModifierParameter(modifiers)
        return if (parameter == 1) "$Escape[${csiTilde}~" else "$Escape[${csiTilde};${parameter}~"
    }

    return when (key) {
        "tab" -> {
            if (modifiers == setOf(TerminalModifier.Shift)) "$Escape[Z"
            else prefixAlt("\t", modifiers)
        }
        "escape" -> prefixAlt(Escape, modifiers)
        "enter" -> prefixAlt("\r", modifiers)
        "backspace" -> prefixAlt(
            if (TerminalModifier.Ctrl in modifiers) "\b" else "\u007F",
            modifiers,
        )
        "space" -> encodePrintable(' ', modifiers)
        else -> if (key.length == 1 && key[0].code in 32..126) {
            encodePrintable(key[0], modifiers)
        } else null
    }
}

private fun normalizeTerminalBinding(binding: TerminalKeyBinding): TerminalKeyBinding? {
    val special = TerminalSpecialKeys.any { it.id == binding.key }
    val normalizedKey = when {
        special -> binding.key
        binding.key.length == 1 && binding.key[0].code in 32..126 -> binding.key.lowercase()
        else -> return null
    }
    return TerminalKeyBinding(normalizedKey, TerminalModifier.entries.filterTo(linkedSetOf()) {
        it in binding.modifiers
    })
}

private fun prefixAlt(bytes: String, modifiers: Set<TerminalModifier>): String =
    if (TerminalModifier.Alt in modifiers) Escape + bytes else bytes

private fun xtermModifierParameter(modifiers: Set<TerminalModifier>): Int =
    1 +
        (if (TerminalModifier.Shift in modifiers) 1 else 0) +
        (if (TerminalModifier.Alt in modifiers) 2 else 0) +
        (if (TerminalModifier.Ctrl in modifiers) 4 else 0)

private fun encodePrintable(raw: Char, modifiers: Set<TerminalModifier>): String? {
    val shifted = if (TerminalModifier.Shift in modifiers) applyShift(raw) else raw
    val bytes = if (TerminalModifier.Ctrl in modifiers) {
        controlCharacter(shifted)?.toString() ?: return null
    } else {
        shifted.toString()
    }
    return prefixAlt(bytes, modifiers)
}

private fun controlCharacter(key: Char): Char? {
    val lower = key.lowercaseChar()
    if (lower in 'a'..'z') return (lower.code - 96).toChar()
    return when (key) {
        ' ', '@', '`' -> 0.toChar()
        '[', '{' -> 27.toChar()
        '\\', '|' -> 28.toChar()
        ']', '}' -> 29.toChar()
        '^', '~' -> 30.toChar()
        '_' -> 31.toChar()
        '?' -> 127.toChar()
        else -> null
    }
}

private fun applyShift(key: Char): Char {
    if (key in 'a'..'z') return key.uppercaseChar()
    return mapOf(
        '`' to '~',
        '1' to '!',
        '2' to '@',
        '3' to '#',
        '4' to '$',
        '5' to '%',
        '6' to '^',
        '7' to '&',
        '8' to '*',
        '9' to '(',
        '0' to ')',
        '-' to '_',
        '=' to '+',
        '[' to '{',
        ']' to '}',
        '\\' to '|',
        ';' to ':',
        '\'' to '"',
        ',' to '<',
        '.' to '>',
        '/' to '?',
    )[key] ?: key
}
