package com.wand.app.ui.screens

/** Presentation only. ChatComposer remains the owner of data, receipts and sending. */
internal data class MinimalComposerState(
    val editing: Boolean = false,
    val recovery: Boolean = false,
    val awaitingAck: Boolean = false,
    val editorKeyboardVisible: Boolean = false,
) {
    fun edit() = copy(editing = true, awaitingAck = false)
    /** Only the editor's own visible → hidden IME transition means “finish editing”.
     * A sheet, picker, permission dialog or background window temporarily owns focus.
     * Reset the observation while suspended so its closing cannot dismiss the editor later.
     */
    fun observeKeyboard(visible: Boolean, interactionSuspended: Boolean): MinimalComposerState = when {
        interactionSuspended -> copy(editorKeyboardVisible = false)
        visible -> copy(editorKeyboardVisible = true)
        editorKeyboardVisible -> dismissKeyboard().copy(editorKeyboardVisible = false)
        else -> this
    }
    fun dismissKeyboard() = if (recovery || awaitingAck) this else copy(editing = false)
    fun submit() = copy(editing = true, awaitingAck = true)
    fun accepted(hasPendingContent: Boolean) = copy(
        editing = if (hasPendingContent) editing else if (awaitingAck) false else editing,
        recovery = false, awaitingAck = false,
    )
    fun failed() = copy(editing = true, recovery = true, awaitingAck = false)
}
