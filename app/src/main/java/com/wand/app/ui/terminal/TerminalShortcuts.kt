package com.wand.app.ui.terminal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
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

@Immutable
data class TerminalShortcutSnapshot(
    val visibleBuiltInIds: Set<String>,
    val customShortcuts: List<TerminalShortcut>,
    val hasSeenGuide: Boolean,
) {
    val visibleShortcuts: List<TerminalShortcut>
        get() = BuiltInTerminalShortcuts.filter { it.id in visibleBuiltInIds } + customShortcuts
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

val BuiltInTerminalShortcuts: List<TerminalShortcut> = listOf(
    builtIn("escape", TerminalKeyBinding("escape")),
    builtIn("tab", TerminalKeyBinding("tab")),
    builtIn("arrowLeft", TerminalKeyBinding("arrowLeft"), repeatable = true),
    builtIn("arrowUp", TerminalKeyBinding("arrowUp"), repeatable = true),
    builtIn("arrowDown", TerminalKeyBinding("arrowDown"), repeatable = true),
    builtIn("arrowRight", TerminalKeyBinding("arrowRight"), repeatable = true),
    builtIn("ctrlC", TerminalKeyBinding("c", setOf(TerminalModifier.Ctrl)), "中断当前任务"),
    builtIn("ctrlD", TerminalKeyBinding("d", setOf(TerminalModifier.Ctrl)), "发送 EOF"),
    builtIn("ctrlL", TerminalKeyBinding("l", setOf(TerminalModifier.Ctrl)), "清空终端画面"),
    builtIn("ctrlR", TerminalKeyBinding("r", setOf(TerminalModifier.Ctrl)), "反向搜索历史"),
    builtIn("ctrlA", TerminalKeyBinding("a", setOf(TerminalModifier.Ctrl)), "移动到行首"),
    builtIn("ctrlE", TerminalKeyBinding("e", setOf(TerminalModifier.Ctrl)), "移动到行尾"),
    builtIn("ctrlW", TerminalKeyBinding("w", setOf(TerminalModifier.Ctrl)), "删除前一个单词"),
    builtIn("ctrlU", TerminalKeyBinding("u", setOf(TerminalModifier.Ctrl)), "清除光标前内容"),
    builtIn("ctrlZ", TerminalKeyBinding("z", setOf(TerminalModifier.Ctrl)), "挂起当前进程"),
    builtIn("shiftTab", TerminalKeyBinding("tab", setOf(TerminalModifier.Shift)), "反向 Tab"),
    builtIn("enter", TerminalKeyBinding("enter")),
    builtIn("backspace", TerminalKeyBinding("backspace"), repeatable = true),
    builtIn("delete", TerminalKeyBinding("delete"), repeatable = true),
    builtIn("home", TerminalKeyBinding("home"), repeatable = true),
    builtIn("end", TerminalKeyBinding("end"), repeatable = true),
    builtIn("pageUp", TerminalKeyBinding("pageUp"), repeatable = true),
    builtIn("pageDown", TerminalKeyBinding("pageDown"), repeatable = true),
)

val DefaultVisibleTerminalShortcutIds: Set<String> = linkedSetOf(
    "escape",
    "tab",
    "arrowLeft",
    "arrowUp",
    "arrowDown",
    "arrowRight",
    "ctrlC",
    "ctrlD",
    "ctrlL",
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

class TerminalShortcutPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    fun read(): TerminalShortcutSnapshot = TerminalShortcutSnapshot(
        visibleBuiltInIds = readVisibleBuiltIns(),
        customShortcuts = readCustomShortcuts(),
        hasSeenGuide = preferences.getBoolean(KeyGuideSeen, false),
    )

    fun setBuiltInVisible(id: String, visible: Boolean) {
        if (BuiltInTerminalShortcuts.none { it.id == id }) return
        val next = readVisibleBuiltIns().toMutableSet()
        if (visible) next.add(id) else next.remove(id)
        preferences.edit().putString(KeyVisibleBuiltIns, JSONArray(next.toList()).toString()).apply()
    }

    fun resetBuiltIns() {
        preferences.edit().remove(KeyVisibleBuiltIns).apply()
    }

    fun addCustomShortcut(binding: TerminalKeyBinding): TerminalShortcut? {
        val shortcut = buildTerminalShortcut(binding) ?: return null
        val next = readCustomShortcuts() + shortcut
        writeCustomShortcuts(next.takeLast(MaxCustomShortcuts))
        return shortcut
    }

    fun deleteCustomShortcut(id: String) {
        writeCustomShortcuts(readCustomShortcuts().filterNot { it.id == id })
    }

    fun markGuideSeen(seen: Boolean = true) {
        preferences.edit().putBoolean(KeyGuideSeen, seen).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun readVisibleBuiltIns(): Set<String> {
        if (!preferences.contains(KeyVisibleBuiltIns)) return DefaultVisibleTerminalShortcutIds
        val raw = preferences.getString(KeyVisibleBuiltIns, "[]") ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                repeat(array.length()) { index ->
                    val id = array.optString(index)
                    if (BuiltInTerminalShortcuts.any { it.id == id }) add(id)
                }
            }
        }.getOrElse { DefaultVisibleTerminalShortcutIds }
    }

    private fun readCustomShortcuts(): List<TerminalShortcut> {
        val raw = preferences.getString(KeyCustomShortcuts, "[]") ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val objectValue = array.optJSONObject(index) ?: return@repeat
                    val id = objectValue.optString("id").takeIf(String::isNotBlank) ?: return@repeat
                    val key = objectValue.optString("key").takeIf(String::isNotBlank) ?: return@repeat
                    val modifierValues = objectValue.optJSONArray("modifiers") ?: JSONArray()
                    val modifiers = buildSet {
                        repeat(modifierValues.length()) { modifierIndex ->
                            val modifierId = modifierValues.optString(modifierIndex)
                            TerminalModifier.entries.firstOrNull { it.id == modifierId }?.let(::add)
                        }
                    }
                    buildTerminalShortcut(
                        binding = TerminalKeyBinding(key, modifiers),
                        id = id,
                    )?.let(::add)
                }
            }.take(MaxCustomShortcuts)
        }.getOrElse { emptyList() }
    }

    private fun writeCustomShortcuts(shortcuts: List<TerminalShortcut>) {
        val array = JSONArray()
        shortcuts.forEach { shortcut ->
            array.put(
                JSONObject()
                    .put("id", shortcut.id)
                    .put("key", shortcut.binding.key)
                    .put(
                        "modifiers",
                        JSONArray(
                            TerminalModifier.entries
                                .filter { it in shortcut.binding.modifiers }
                                .map(TerminalModifier::id),
                        ),
                    ),
            )
        }
        preferences.edit().putString(KeyCustomShortcuts, array.toString()).apply()
    }

    private companion object {
        const val PreferencesName = "wand_terminal_shortcuts"
        const val KeyVisibleBuiltIns = "visible_built_ins_v1"
        const val KeyCustomShortcuts = "custom_shortcuts_v1"
        const val KeyGuideSeen = "pty_quick_start_seen_v1"
        const val MaxCustomShortcuts = 12
    }
}

@Composable
fun rememberTerminalShortcutPreferences(): Pair<TerminalShortcutPreferenceStore, TerminalShortcutSnapshot> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { TerminalShortcutPreferenceStore(context) }
    var snapshot by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = store.read()
        }
        store.registerListener(listener)
        onDispose { store.unregisterListener(listener) }
    }
    return store to snapshot
}

private fun builtIn(
    id: String,
    binding: TerminalKeyBinding,
    accessibilityLabel: String? = null,
    repeatable: Boolean = false,
): TerminalShortcut {
    val shortcut = requireNotNull(
        buildTerminalShortcut(
            binding = binding,
            id = id,
            builtIn = true,
            accessibilityLabel = accessibilityLabel,
        ),
    )
    return shortcut.copy(repeatable = repeatable || shortcut.repeatable)
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
