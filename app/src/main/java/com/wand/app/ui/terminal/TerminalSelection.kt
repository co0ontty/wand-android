package com.wand.app.ui.terminal

import org.connectbot.terminal.SelectionController

internal data class TerminalSelectionSnapshot(
    val active: Boolean,
    val dragging: Boolean,
    val lineMode: Boolean,
    val singleCell: Boolean,
) {
    companion object {
        val Inactive = TerminalSelectionSnapshot(
            active = false,
            dragging = false,
            lineMode = false,
            singleCell = false,
        )
    }
}

/** The action bar is for a finished selection. A finger still dragging keeps the magnifier clear. */
internal fun shouldShowTerminalMenu(selection: TerminalSelectionSnapshot): Boolean =
    selection.active && !selection.dragging

/**
 * A long press that never moved selects one cell. Expand that to the whole line
 * once, and leave a drag the user already sized alone.
 */
internal fun shouldExpandTerminalSelection(
    selection: TerminalSelectionSnapshot,
    alreadyExpanded: Boolean,
): Boolean = shouldShowTerminalMenu(selection) &&
    !alreadyExpanded &&
    selection.singleCell &&
    !selection.lineMode

/** Scrollback first, then the visible screen. Blank padding at the ends is dropped per line. */
internal fun joinTerminalBuffer(scrollback: List<String>, screen: List<String>): String {
    if (scrollback.isEmpty() && screen.isEmpty()) return ""
    return (scrollback + screen).joinToString("\n") { it.trimEnd() }.trimEnd()
}

/**
 * termlib 0.0.10 only exposes [SelectionController]. The live range, drag flag,
 * and screen buffer sit on the controller's captured manager and screen state.
 */
internal class TerminalSelectionProbe(controller: SelectionController) {
    private val manager: Any? = fieldOfType(controller, "org.connectbot.terminal.SelectionManager")
    private val screenState: Any? = fieldOfType(controller, "org.connectbot.terminal.TerminalScreenState")
    private val mode = manager.method("getMode")
    private val selecting = manager.method("isSelecting")
    private val range = manager.method("getSelectionRange")
    private val snapshot = screenState.method("getSnapshot")

    fun read(): TerminalSelectionSnapshot {
        val manager = this.manager ?: return TerminalSelectionSnapshot.Inactive
        val mode = this.mode ?: return TerminalSelectionSnapshot.Inactive
        val selecting = this.selecting ?: return TerminalSelectionSnapshot.Inactive
        val range = this.range ?: return TerminalSelectionSnapshot.Inactive
        return try {
            val modeName = (mode.invoke(manager) as Enum<*>).name
            val dragging = selecting.invoke(manager) as Boolean
            val current = range.invoke(manager)
            val single = current != null &&
                intProp(current, "getStartRow") == intProp(current, "getEndRow") &&
                intProp(current, "getStartCol") == intProp(current, "getEndCol")
            TerminalSelectionSnapshot(
                active = modeName != "NONE",
                dragging = dragging,
                lineMode = modeName == "LINE",
                singleCell = single,
            )
        } catch (_: Exception) {
            TerminalSelectionSnapshot.Inactive
        }
    }

    fun bufferText(): String {
        val screenState = this.screenState ?: return ""
        val snapshot = this.snapshot ?: return ""
        return try {
            val current = snapshot.invoke(screenState) ?: return ""
            val scrollback = strings(current, "getScrollback")
            val screen = strings(current, "getLines")
            joinTerminalBuffer(scrollback, screen)
        } catch (_: Exception) {
            ""
        }
    }

    private fun strings(snapshot: Any, getter: String): List<String> {
        val lines = snapshot.javaClass.getMethod(getter).invoke(snapshot) as? List<*> ?: return emptyList()
        return lines.mapNotNull { line ->
            if (line == null) null else line.javaClass.getMethod("getText").invoke(line) as? String
        }
    }
}

private fun fieldOfType(target: Any, typeName: String): Any? {
    val field = target.javaClass.declaredFields.firstOrNull { it.type.name == typeName } ?: return null
    field.isAccessible = true
    return field.get(target)
}

private fun Any?.method(name: String): java.lang.reflect.Method? =
    this?.let { runCatching { it.javaClass.getMethod(name) }.getOrNull() }

private fun intProp(target: Any, name: String): Int = target.javaClass.getMethod(name).invoke(target) as Int
