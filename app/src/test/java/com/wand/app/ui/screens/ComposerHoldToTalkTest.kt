package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerHoldToTalkTest {
    @Test fun nativeTextEditingDoesNotInterceptLongPressForAudio() {
        val source = File("src/main/java/com/wand/app/ui/screens/ComposerHelpers.kt").readText()
            .substringAfter("internal fun ComposerInputField(")
        assertTrue(source.contains("BasicTextField("))
        assertTrue(source.contains(".focusRequester(focusRequester)"))
        assertFalse(source.contains("VoiceInputController"))
        assertFalse(source.contains("onMicDown"))
        assertFalse(source.contains("pointerInput"))
        assertFalse(source.contains("按住说话"))
    }

    @Test fun standaloneVoiceStillExistsButMinimalEditorHasNoDuplicateMic() {
        val root = File("src/main/java/com/wand/app/ui/screens")
        val composer = File(root, "SharedMessageComposer.kt").readText()
        assertFalse(composer.contains("VoiceInputController"))
        assertFalse(composer.contains("onMicDown"))
        val chat = File(root, "ChatScreen.kt").readText()
        assertFalse(chat.contains("if (companion != null) VoiceMicButton"))
        assertTrue(chat.contains("if (companion == null) VoiceMicButton")) // Legacy / non-companion route.
        assertTrue(File(root, "FloatingComposerCompanion.kt").readText().contains("currentMic()"))
        assertTrue(File(root, "PtyTerminalScreen.kt").readText().contains("VoiceMicButton("))
        assertTrue(File(root, "ConversationScreens.kt").readText().contains("VoiceMicButton("))
    }

    @Test fun modelSheetIsOwnedByInputBarNotTheConditionalChip() {
        val source = File("src/main/java/com/wand/app/ui/screens/ChatScreen.kt").readText()
        val chip = source.substringAfter("private fun ModelThinkingChip(").substringBefore("internal fun ComposerActionsMenu(")
        assertFalse(chip.contains("ModelThinkingChoiceSheet("))
        assertFalse(chip.contains("mutableStateOf"))
        assertTrue(source.contains("editorOverlayOpen = modelOpen || modeOpen || showStopConfirm"))
        assertTrue(source.contains("var modelOpen by remember(store.sessionId)"))
        val focus = File("src/main/java/com/wand/app/ui/screens/SharedMessageComposer.kt").readText()
            .substringAfter("LaunchedEffect(editorOverlayOpen, windowFocused)")
            .substringBefore("LaunchedEffect(focusRequest,")
        assertTrue(focus.contains("minimal.editing || minimal.recovery"))
        assertFalse(focus.contains("minimal.edit()")) // A focus return cannot cancel the pending receipt.
        assertFalse(focus.contains("requestFocus()"))
    }
}
