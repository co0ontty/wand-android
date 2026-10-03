package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring guards: JVM tests do not mount Compose; chat and PTY use list/sidebar navigation. */
class StructuredChatNavigationTest {
    @Test
    fun structuredChatDoesNotRenderTaskOrStandaloneSessionStrips() {
        val screen = source("screens/ChatScreen.kt")
        assertFalse(screen.contains("TaskSessionTabStrip("))
        assertFalse(screen.contains("StandaloneSessionTabStrip("))
        assertTrue(screen.contains("WandDetailTopBar("))
        assertTrue(screen.contains("WandDetailBackButton("))
    }

    @Test
    fun structuredChatDoesNotHandleSiblingSessionSwipes() {
        val screen = source("screens/ChatScreen.kt")
        assertFalse(screen.contains("taskSessionSwipe("))
        assertFalse(screen.contains("siblingSessions"))
        assertFalse(screen.contains("onSwitchSession"))
        assertFalse(screen.contains("onCreateTaskSession"))
        assertFalse(screen.contains("onDeleteTaskSession"))
        assertTrue(screen.contains("ChatComposer("))
        assertTrue(screen.contains("LazyColumn("))
    }

    @Test
    fun structuredRouteKeepsDraftsAndBackWithoutQuickSwitchCallbacks() {
        val app = source("WandApp.kt")
        val start = "is Screen.Chat -> ChatScreen("
        val end = "is Screen.PtyTerminal -> PtyTerminalScreen("
        assertTrue(app.contains(start))
        assertTrue(app.contains(end))
        val route = app.substringAfter(start).substringBefore(end)
        listOf("taskId =", "siblingSessions =", "onSwitchSession =",
            "onCreateTaskSession =", "onDeleteTaskSession =").forEach {
            assertFalse("Structured route still wires $it", route.contains(it))
        }
        assertTrue(route.contains("drafts = sessionDrafts"))
        assertTrue(route.contains("showBack = showBack"))
        assertTrue(route.contains("onBack = { nav.pop() }"))
    }

    @Test
    fun ptyDoesNotRenderTaskOrStandaloneSessionStrips() {
        val screen = source("screens/PtyTerminalScreen.kt")
        assertFalse(screen.contains("TaskSessionTabStrip("))
        assertFalse(screen.contains("StandaloneSessionTabStrip("))
        assertTrue(screen.contains("PtyTopBar("))
        assertTrue(screen.contains("NativePtyTerminalSurface("))
    }

    @Test
    fun ptyDoesNotHandleSiblingSwipesAndKeepsTerminalInput() {
        val screen = source("screens/PtyTerminalScreen.kt")
        listOf("taskSessionSwipe(", "siblingSessions", "onSwitchSession",
            "onCreateTaskSession", "onDeleteTaskSession").forEach {
            assertFalse("PTY still wires $it", screen.contains(it))
        }
        assertTrue(screen.contains("ptyComposerSubmitChunks(text, \"terminal\")"))
        assertTrue(screen.contains("terminal.send(chunk.input, chunk.shortcutKey)"))
    }

    @Test
    fun ptyRouteKeepsBackAndContextWithoutQuickSwitchCallbacks() {
        val app = source("WandApp.kt")
        val start = "is Screen.PtyTerminal -> PtyTerminalScreen("
        val end = "is Screen.Missions ->"
        assertTrue(app.contains(start))
        assertTrue(app.contains(end))
        val route = app.substringAfter(start).substringBefore(end)
        listOf("taskId =", "siblingSessions =", "onSwitchSession =",
            "onCreateTaskSession =", "onDeleteTaskSession =").forEach {
            assertFalse("PTY route still wires $it", route.contains(it))
        }
        assertTrue(route.contains("taskName = screen.taskName"))
        assertTrue(route.contains("showBack = showBack"))
        assertTrue(route.contains("onBack = { nav.pop() }"))
        assertTrue(app.contains("onSessionClosed = nav::closeSession"))
    }

    private fun source(path: String): String =
        File("src/main/java/com/wand/app/ui", path).readText()
}
