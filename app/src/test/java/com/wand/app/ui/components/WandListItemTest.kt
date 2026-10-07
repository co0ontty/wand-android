package com.wand.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contracts only: actual touch, font-scale bounds and IME require device acceptance. */
class WandListItemTest {
    private fun source(path: String) = File("src/main/java/com/wand/app/ui/$path.kt").readText()

    private fun body(path: String, name: String): String {
        val text = source(path)
        val start = text.indexOf('{', text.indexOf("fun $name("))
        var depth = 0
        for (index in start until text.length) {
            if (text[index] == '{') depth++
            if (text[index] == '}' && --depth == 0) return text.substring(start, index + 1)
        }
        error("Missing body: $path.$name")
    }

    /** Layout inside a Material slot is content; it must not replace the list item's measurement. */
    private fun slotBody(row: String, slot: String): String {
        val declaration = row.indexOf("$slot = {")
        check(declaration >= 0) { "Missing slot: $slot" }
        val start = row.indexOf('{', declaration)
        var depth = 0
        for (index in start until row.length) {
            if (row[index] == '{') depth++
            if (row[index] == '}' && --depth == 0) return row.substring(start, index + 1)
        }
        error("Unclosed slot: $slot")
    }

    @Test
    fun materialOwnsMeasurementAndTransparentThemeSlots() {
        val seam = body("components/WandListItem", "WandListItem")
        assertTrue(seam.contains("ListItem("))
        for (slot in listOf("headlineContent", "supportingContent", "leadingContent", "trailingContent")) {
            assertTrue(seam.contains("$slot = $slot"))
        }
        assertTrue(seam.contains("ListItemDefaults.colors("))
        assertTrue(seam.contains("containerColor = Color.Transparent"))
        for (forbidden in listOf("Row(", "Column(", "Layout(", "MeasurePolicy", "height(", "heightIn(", "remember")) {
            assertFalse(forbidden, seam.contains(forbidden))
        }
    }

    @Test
    fun allFiveRowsDelegateInsteadOfReimplementingDensity() {
        for ((path, name) in listOf(
            "SettingsScreen" to "ActionRow", "SettingsScreen" to "SwitchRow",
            "TaskListScreen" to "DirectoryPickerRow", "WorkspaceTargetSheet" to "WorkspaceTargetOption",
            "ChatScreen" to "ChoiceOptionsList",
        )) {
            val row = body("screens/$path", name)
            assertEquals("$name delegates to one shared list item", 1,
                Regex("\\bWandListItem\\(").findAll(row).count())
            for (forbidden in listOf("Column(", "Layout(", "MeasurePolicy", "height(", "heightIn(", "animateContentSize")) {
                assertFalse("$name: $forbidden", Regex("\\b${Regex.escape(forbidden)}").containsMatchIn(row))
            }
            // Settings metadata and chevron share a horizontal trailing slot; the surrounding
            // item still delegates all row height and slot placement to Material ListItem.
            val withoutTrailingContent = if (name == "ActionRow") {
                val trailing = slotBody(row, "trailingContent")
                assertEquals(1, Regex("\\bRow\\(").findAll(trailing).count())
                row.replace(trailing, "")
            } else row
            assertFalse("$name must not wrap the shared item in its own row",
                Regex("\\bRow\\(").containsMatchIn(withoutTrailingContent))
        }
    }

    @Test
    fun actionsKeepOneCallbackAndProjectExistingDiagnosticBusyState() {
        val row = body("screens/SettingsScreen", "ActionRow")
        assertEquals(1, Regex("onClick = onClick").findAll(row).count())
        assertEquals(1, Regex("\\.clickable\\(").findAll(row).count())
        assertTrue(row.contains("clickable(enabled = enabled, role = Role.Button"))
        assertTrue(row.contains("if (busy) stateDescription = \"正在导出\""))
        assertTrue(row.contains("else if (!enabled) stateDescription = \"不可操作\""))
        assertTrue(row.contains("Box {"))
        assertEquals(2, Regex("clearAndSetSemantics").findAll(row).count())
        val screen = source("screens/SettingsScreen")
        assertEquals(3, Regex("enabled = !exportingLogs").findAll(screen).count())
        assertEquals(3, Regex("if \\(exportingLogs\\) return@ActionRow").findAll(screen).count())
        assertEquals(2, Regex("busy = exportingLogs").findAll(screen).count())
        assertTrue(row.contains("supportingContent = supportingText?.takeUnless { inlineValue }?.let"))
        val trailing = slotBody(row, "trailingContent")
        assertTrue(trailing.contains("if (inlineValue && supportingText != null) Text(supportingText,"))
        assertTrue(trailing.contains("WandListItemIconSlot(WandIcons.chevronRight)"))
        assertTrue(trailing.contains("maxLines = 1"))
        assertTrue(trailing.contains("overflow = TextOverflow.Ellipsis"))
        assertFalse(trailing.contains("onClick"))
        assertFalse(trailing.contains("clickable"))
        assertTrue(screen.contains("inlineValue: Boolean = true"))
        assertTrue(screen.contains("inlineValue = false"))
    }

    @Test
    fun switchHasOnlyTheRowToggleAndNoSecondControlCallback() {
        val row = body("screens/SettingsScreen", "SwitchRow")
        assertEquals(1, Regex("\\.toggleable\\(").findAll(row).count())
        assertEquals(1, Regex("onValueChange = onChange").findAll(row).count())
        assertTrue(row.contains("onCheckedChange = null"))
        assertFalse(row.contains(".clickable("))
        assertTrue(row.contains("supportingContent = description?.let { { Text(it) } }"))
    }

    @Test
    fun selectedAndDisabledOptionsKeepStableTextAndDecorativeCheckSlots() {
        val slot = body("components/WandListItem", "WandListItemIconSlot")
        assertTrue(slot.contains("Box(Modifier.size(24.dp)"))
        assertTrue(slot.indexOf("Box(") < slot.indexOf("if (icon != null)"))
        assertTrue(slot.contains("contentDescription = null"))
        val workspace = body("screens/WorkspaceTargetSheet", "WorkspaceTargetOption")
        assertEquals(1, Regex("onClick = onClick").findAll(workspace).count())
        assertTrue(workspace.contains("enabled = enabled"))
        assertTrue(workspace.contains("BrandLogos.tintForProvider"))
        assertTrue(workspace.contains("icon = if (isSelected) WandIcons.check else null"))
        val choice = body("screens/ChatScreen", "ChoiceOptionsList")
        assertEquals(1, Regex("onSelect\\(id\\)").findAll(choice).count())
        assertFalse(choice.contains("fontWeight"))
        assertTrue(choice.contains("WandListItemIconSlot(if (isSelected) WandIcons.check else null"))
    }

    @Test
    fun fullHeadlinesAndDescriptionsCanGrowWhileOnlyMetadataEllipsizes() {
        val directory = body("screens/TaskListScreen", "DirectoryPickerRow")
        assertTrue(directory.contains("headlineContent = { Text(title) }"))
        assertTrue(directory.contains("contentDescription = if (path == title) title else \"\$title \$path\""))
        assertTrue(directory.contains("maxLines = 1"))
        assertTrue(directory.contains("overflow = TextOverflow.Ellipsis"))
        for ((path, name) in listOf("SettingsScreen" to "SwitchRow", "WorkspaceTargetSheet" to "WorkspaceTargetOption", "ChatScreen" to "ChoiceOptionsList")) {
            val row = body("screens/$path", name).let {
                if (name == "ChoiceOptionsList") it.substringAfter("val isSelected = selected == id") else it
            }
            assertFalse(name, row.contains("maxLines"))
            assertFalse(name, row.contains("fontSize"))
        }
    }

    @Test
    fun virtualizedListsKeepStableKeysAndOriginalFilterAndCloseOwners() {
        val chat = source("screens/ChatScreen")
        assertTrue(body("screens/ChatScreen", "ChoiceOptionsList").contains("items(options, key = { it.first })"))
        assertTrue(chat.contains("prioritizeSelectedItem(filtered, selected) { it.first }"))
        assertTrue(chat.contains("matchesModelSearch(query, it.first, it.second)"))
        assertTrue(chat.contains("keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)"))
        assertTrue(chat.contains("store.chooseMode(id)\n            onDismiss()"))
        val directory = source("screens/TaskListScreen")
        assertTrue(directory.contains("filter { it.isDirectory }, key = { it.path }"))
        assertTrue(directory.contains("if (!state.mutationBusy) directoryPickerOpen = false"))
        assertTrue(directory.contains("enabled = !directoryLoading"))
        val workspace = source("screens/WorkspaceTargetSheet")
        assertTrue(workspace.contains("key = { it.raw }"))
        assertTrue(workspace.contains("enabled = !creating"))
    }
}
