package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ComposerStatusLayoutTest {
    private fun source(name: String) = File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()

    @Test fun progressOwnsItsUsageAndTheGenericStatusDoesNotCreateASecondDock() {
        val screen = source("ChatScreen")
        assertTrue(screen.contains("TodoProgressBar(progressTodos, backdrop, usage = lastAssistantUsage)"))
        assertTrue(screen.contains("visible = activityDockVisible && (subagentActivities.isNotEmpty() || progressTodos.isEmpty())"))
        assertTrue(screen.contains("showUsage = progressTodos.isEmpty()"))
        assertTrue(screen.contains("hasDetailedProgress = progressTodos.isNotEmpty() || subagentActivities.isNotEmpty()"))
    }

    @Test fun detailsGrowAboveTheStableSummaryAndLongTasksKeepTheirFullText() {
        val progress = source("ChatActionBlocks").substringAfter("fun TodoProgressBar(")
        assertTrue(progress.indexOf("WandInlinePanel(") < progress.indexOf(".clickable(role = Role.Button"))
        assertTrue(progress.contains("growFrom = Alignment.Bottom"))
        assertTrue(progress.contains(".heightIn(max = 240.dp)"))
        assertTrue(progress.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(progress.contains("Text(todo.content,"))
        assertTrue(progress.contains("UsageSummaryRow(usage, isLive = false)"))
        assertTrue(progress.contains("maxLines = 2"))
        assertFalse(progress.contains("rememberInfiniteTransition"))
    }

    @Test fun captionDoesNotChangeTheActionsCenterlineOrOwnTheReceiptIcon() {
        val dock = source("FloatingComposerCompanion")
        assertTrue(dock.contains("height(dockHeight)"))
        assertTrue(dock.contains("Modifier.align(Alignment.CenterStart).padding(start = 12.dp)"))
        assertFalse(dock.contains("height(16.dp + reservedHeadSize + statusHeight)"))
        assertTrue(dock.contains("SubmitMorphButton("))
        assertFalse(dock.contains("FilledComposerAction("))
        assertTrue(dock.contains("maxLines = if (onPauseSpeech == null) 4 else 2"))
        assertFalse(dock.contains("statusHeight"))
        assertTrue(dock.contains(".width(ComposerActionTouchSize * 3)"))
        assertTrue(dock.contains("val actionWidth = ComposerActionTouchSize * 3"))
    }
}
