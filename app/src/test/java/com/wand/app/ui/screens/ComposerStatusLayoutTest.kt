package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ComposerStatusLayoutTest {
    private fun source(name: String) = File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()

    @Test fun progressOwnsItsUsageAndTheGenericStatusDoesNotCreateASecondDock() {
        val screen = source("ChatScreen")
        assertTrue(screen.contains("TodoProgressBar(progressTodos, backdrop, usage = lastAssistantUsage, detailsInSheet = true"))
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

    @Test fun statusSitsOutsideTheWritingSurfaceAndActionsShareTheBottomToolbar() {
        val capsule = source("FocusCapsuleComposer")
        val surface = capsule.substringAfter("Surface(").substringBefore("// Panels are modal")
        assertFalse(surface.contains("accessory()"))
        assertTrue(capsule.indexOf("    accessory()") < capsule.indexOf("    Surface("))
        assertTrue(surface.contains("ComposerCapsuleToolbar(expanded, actionInset"))
        assertTrue(surface.contains("align(Alignment.BottomEnd)"))
        assertTrue(surface.contains("exteriorAction != null && actionCount > 0"))
        assertTrue(capsule.contains("ComposerActionTouchSize * actionCount"))
        assertTrue(capsule.contains("val actionInset = actionWidth"))
        assertTrue(capsule.contains("val stacked = expanded && width - inset - trailing < touch * 4"))
        assertFalse(surface.contains("if (expanded) Row(Modifier.fillMaxWidth()"))
        assertFalse(capsule.contains("horizontalArrangement = Arrangement.spacedBy(4.dp)"))
        assertEquals(1, Regex("ComposerInputField\\(").findAll(capsule).count())
    }

    @Test fun groupDraftAlsoSeparatesTextFromTheExteriorActionAndInlineIcons() {
        val shared = source("SharedMessageComposer")
        val surface = source("NativeComposerSurface")
        assertTrue(shared.contains("inlineControls && draft.isEmpty() && !hasAnyAttachments"))
        assertTrue(shared.contains("padding(end = if (compactVisual) exteriorInset else 0.dp)"))
        assertTrue(shared.contains("controlsEndPadding = if (compactVisual) 0.dp else exteriorInset"))
        assertTrue(shared.contains("showClearAction = !compactVisual && exteriorAction == null"))
        assertTrue(shared.contains("align(androidx.compose.ui.Alignment.BottomEnd)"))
        assertTrue(surface.contains("Modifier.fillMaxWidth().padding(end = controlsEndPadding)"))
    }

    @Test fun topSceneHasStableGeometryWhileTheCapsuleOwnsTheReceipt() {
        val scene = source("TopChatCompanion")
        val screen = source("ChatScreen")
        assertTrue(scene.contains("height(topCompanionClearance())"))
        assertFalse(scene.contains("CompanionStatusLabel"))
        assertTrue(screen.contains("padding.calculateTopPadding() + chatTopIdentityClearance()"))
        assertTrue(screen.contains(".padding(chatPadding)\n                            .clipToBounds()"))
        assertEquals(FloatingControlTouch + FloatingControlRowInset * 2, ChatFloatingControlsClearance)
        val capsule = source("FocusCapsuleComposer")
        assertTrue(capsule.contains("Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)"))
        assertFalse(capsule.contains("220.dp"))
        assertTrue(scene.contains("animated = true"))
        assertFalse(scene.contains("SendPhase"))
        assertFalse(scene.contains("SubmitMorphButton("))
        assertTrue(scene.contains("CustomAccessibilityAction"))
        assertTrue(capsule.contains("state = state.submit()"))
        assertTrue(capsule.contains("state.accepted(draft.isNotEmpty() || attachments.isNotEmpty())"))
        assertTrue(capsule.contains("state = state.failed()"))

    }
}
