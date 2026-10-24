package com.wand.app.ui.screens

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SessionFilesPresentationTest {
    @Test fun popupGrowsBelowToolbarWithoutLeavingCompactOrTabletWindow() {
        val position = SessionFilesPosition(12)
        for (window in listOf(IntSize(360, 800), IntSize(800, 1200))) {
            val panel = IntSize(minOf(560, window.width - 24), 600)
            val anchor = IntRect(window.width - 64, 28, window.width - 16, 76)
            val offset = position.calculatePosition(anchor, window, LayoutDirection.Ltr, panel)
            assertEquals(76, offset.y)
            assertTrue(offset.x >= 12)
            assertTrue(offset.x + panel.width <= window.width - 12)
            assertTrue(offset.y + panel.height <= window.height - 12)
        }
    }

    @Test fun popupClampsInShortWindowAndRtlWithoutMovingTrigger() {
        val position = SessionFilesPosition(12)
        val anchor = IntRect(240, 20, 288, 68)
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val offset = position.calculatePosition(anchor, IntSize(320, 260), direction, IntSize(296, 160))
            assertEquals(12, offset.x)
            assertEquals(68, offset.y)
        }
    }

    @Test fun bothSessionKindsKeepFilesOutsideTransientMenuAndUseActualSessionCwd() {
        fun source(name: String) = File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()
        val structured = source("ChatScreen")
        val pty = source("PtyTerminalScreen")
        assertTrue(structured.contains("SessionFilesAnchor(api, sessionId, store.snapshot?.cwd,"))
        assertTrue(pty.contains("SessionFilesAnchor(api, sessionId, snapshot?.cwd, onOpen = onOpenFiles)"))
        assertTrue(pty.contains("onOpenFiles = {\n                    keyboardRequested = false"))
        assertTrue(structured.contains("filesEnabled = filesEnabled, onFiles = openFiles"))
        assertTrue(structured.contains("onGit = { resources.dismiss(); quickCommit.openPanel() }"))
        val controls = source("FloatingChatControls")
        assertEquals(FloatingControlsBackInset, FloatingControlsActionsInset)
        val companion = source("TopChatCompanion")
        assertTrue(companion.contains("maxWidth - FloatingControlsActionsInset"))
        assertFalse(companion.contains("112.dp"))
        assertTrue(controls.contains("SessionIconAction(\"会话文件\", WandIcons.folder, enabled = filesEnabled, onClick = onFiles)"))
        assertTrue(controls.contains("SessionIconAction(\"变更与提交\", WandIcons.commit, enabled = gitEnabled, onClick = onGit)"))
        assertFalse(controls.contains("DropdownMenuItem("))
        val ptyActions = pty.substringAfterLast("actions = {")
        assertTrue(ptyActions.indexOf("SessionFilesAnchor(") < ptyActions.indexOf("DropdownMenu(moreOpen"))
        assertFalse(ptyActions.contains("GitChangesButton("))
        val icons = source("SessionIconActions")
        assertTrue(icons.contains("Modifier.size(48.dp)"))
        assertTrue(icons.contains("PlainTooltip { Text(action.label) }"))
        assertTrue(icons.contains("contentDescription = action.label"))
        assertTrue(icons.contains("onClick = { onDismiss(); action.onClick() }"))
        assertTrue(icons.contains("enabled = action.enabled"))
        val panel = source("SessionFilesPanel")
        assertTrue(panel.contains("remember(sessionId, api)"))
        assertTrue(panel.contains("PopupProperties(focusable = true)"))
        assertTrue(panel.contains("files::requestClose"))
        assertTrue(panel.contains("reduceMotionEnabled()"))
        assertTrue(panel.contains("keyboardController?.hide()"))
        assertTrue(panel.contains("focusRequester(editorFocus)"))
        assertFalse(panel.contains("ModalBottomSheet"))
        assertFalse(panel.contains("Toast"))
    }
}
