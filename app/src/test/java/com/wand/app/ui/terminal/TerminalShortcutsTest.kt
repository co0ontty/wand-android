package com.wand.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalShortcutsTest {
    @Test
    fun arrowsUseStandardXtermSequences() {
        assertEquals("\u001B[A", encodeTerminalKey(TerminalKeyBinding("arrowUp")))
        assertEquals("\u001B[B", encodeTerminalKey(TerminalKeyBinding("arrowDown")))
        assertEquals("\u001B[D", encodeTerminalKey(TerminalKeyBinding("arrowLeft")))
        assertEquals("\u001B[C", encodeTerminalKey(TerminalKeyBinding("arrowRight")))
    }

    @Test
    fun arrowModifiersUseXtermModifierParameter() {
        assertEquals(
            "\u001B[1;6C",
            encodeTerminalKey(
                TerminalKeyBinding(
                    "arrowRight",
                    setOf(TerminalModifier.Ctrl, TerminalModifier.Shift),
                ),
            ),
        )
    }

    @Test
    fun controlAndAltPrintableKeysEncodeToPtyBytes() {
        assertEquals("\u0003", encodeTerminalKey(TerminalKeyBinding("c", setOf(TerminalModifier.Ctrl))))
        assertEquals(
            "\u001B\u0012",
            encodeTerminalKey(
                TerminalKeyBinding("r", setOf(TerminalModifier.Ctrl, TerminalModifier.Alt)),
            ),
        )
        assertEquals("A", encodeTerminalKey(TerminalKeyBinding("a", setOf(TerminalModifier.Shift))))
    }

    @Test
    fun specialEditingKeysAndShiftTabMatchTerminalConventions() {
        assertEquals("\u001B[Z", encodeTerminalKey(TerminalKeyBinding("tab", setOf(TerminalModifier.Shift))))
        assertEquals("\u007F", encodeTerminalKey(TerminalKeyBinding("backspace")))
        assertEquals("\u001B[3~", encodeTerminalKey(TerminalKeyBinding("delete")))
        assertEquals("\r", encodeTerminalKey(TerminalKeyBinding("enter")))
    }

    @Test
    fun unsupportedInputsAreRejectedInsteadOfSendingAmbiguousBytes() {
        assertNull(encodeTerminalKey(TerminalKeyBinding("")))
        assertNull(encodeTerminalKey(TerminalKeyBinding("hello")))
        assertNull(normalizeTerminalKeyInput("\n\t"))
    }

    @Test
    fun builtInsHaveUniqueIdsAndNonEmptyBytes() {
        assertEquals(BuiltInTerminalShortcuts.size, BuiltInTerminalShortcuts.map { it.id }.toSet().size)
        assertTrue(BuiltInTerminalShortcuts.all { it.bytes.isNotEmpty() })
        assertTrue(DefaultVisibleTerminalShortcutIds.all { id -> BuiltInTerminalShortcuts.any { it.id == id } })
    }
}
