package com.wand.app.ui.screens

import org.junit.Assert.*
import org.junit.Test

class MinimalComposerStateTest {
    @Test fun emptyEntryIsHeadOnlyAndTapEdits() {
        val initial = MinimalComposerState()
        assertFalse(initial.editing)
        assertTrue(initial.edit().editing)
    }
    @Test fun keyboardDismissRetainsDraftPresentationAndCanReopen() {
        val state = MinimalComposerState().edit().dismissKeyboard()
        assertFalse(state.editing)
        assertTrue(state.edit().editing)
    }
    @Test fun onlyOwnKeyboardDismissalEndsEditing() {
        val editing = MinimalComposerState().edit().observeKeyboard(true, false)
        assertTrue(editing.editing)
        assertFalse(editing.observeKeyboard(false, false).editing)
    }
    @Test fun modelSearchAndNativePickersBorrowFocusWithoutCollapsingTheEditor() {
        val editing = MinimalComposerState().edit().observeKeyboard(true, false)
        // The sheet opens before its window/IME transition; search owns the second IME.
        val sheet = editing.observeKeyboard(true, true).observeKeyboard(false, true)
            .observeKeyboard(true, true).observeKeyboard(false, true)
        assertTrue(sheet.editing)
        val returned = sheet.observeKeyboard(false, false)
        assertTrue(returned.editing) // Closing a sheet/canceling a picker is not Back on the editor.
        assertFalse(returned.observeKeyboard(true, false).observeKeyboard(false, false).editing)
    }
    @Test fun windowFocusHandoffAndInlineResourcesDoNotReplayAnOldKeyboardDismissal() {
        val editing = MinimalComposerState().edit().observeKeyboard(true, false)
        for (imeAtHandoff in listOf(true, false)) {
            val resumed = editing.observeKeyboard(imeAtHandoff, true).observeKeyboard(false, false)
            assertTrue(resumed.editing)
            assertFalse(resumed.editorKeyboardVisible)
        }
        assertFalse(MinimalComposerState().observeKeyboard(false, true).observeKeyboard(false, false).editing)
    }
    @Test fun receiptsStillOwnCollapseDuringASelectorHandoff() {
        val pending = MinimalComposerState().edit().observeKeyboard(true, false).submit()
            .observeKeyboard(false, true)
        assertFalse(pending.accepted(false).observeKeyboard(false, false).editing)
        assertTrue(pending.accepted(true).observeKeyboard(false, false).editing)
        assertTrue(pending.failed().observeKeyboard(false, false).editing)
    }
    @Test fun onlyEmptyAcknowledgementCollapsesSubmission() {
        val pending = MinimalComposerState().edit().submit()
        assertTrue(pending.dismissKeyboard().editing)
        assertFalse(pending.accepted(false).editing)
        assertTrue(pending.accepted(true).editing) // New text or attachment belongs to next draft.
    }
    @Test fun typingBeforeAckCancelsThatCollapseEvenIfUserDeletesNewText() {
        val state = MinimalComposerState().edit().submit().edit().accepted(false)
        assertTrue(state.editing)
    }
    @Test fun failedOrUnknownDeliveryStaysExpandedAfterFeedbackAndKeyboardDismiss() {
        val state = MinimalComposerState().edit().submit().failed().dismissKeyboard()
        assertTrue(state.editing)
        assertTrue(state.recovery)
        assertTrue(state.edit().dismissKeyboard().editing)
        assertFalse(state.submit().accepted(false).editing)
    }
}
