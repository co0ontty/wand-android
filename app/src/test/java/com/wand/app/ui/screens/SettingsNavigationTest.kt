package com.wand.app.ui.screens

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun backFromAnyDetailReturnsToDirectoryBeforeLeavingSettings() {
        val navigation = SettingsNavigation()
        assertFalse(navigation.back())
        SettingsDestination.entries.filter { it != SettingsDestination.Index }.forEach { page ->
            navigation.open(page)
            assertEquals(page, navigation.current)
            assertTrue(navigation.back())
            assertEquals(SettingsDestination.Index, navigation.current)
            assertFalse(navigation.back())
        }
    }

    @Test fun revisitingEditorsKeepsTheirStablePageOwnersWithoutMountingUnvisitedTools() {
        val navigation = SettingsNavigation()
        assertEquals(listOf(SettingsDestination.Index), navigation.visited.toList())
        navigation.open(SettingsDestination.Models)
        navigation.back()
        navigation.open(SettingsDestination.Appearance)
        navigation.back()
        navigation.open(SettingsDestination.Models)
        assertEquals(listOf(SettingsDestination.Index, SettingsDestination.Models, SettingsDestination.Appearance), navigation.visited.toList())
        assertFalse(SettingsDestination.Voice in navigation.visited)
        assertFalse(SettingsDestination.Diagnostics in navigation.visited)
    }

    @Test fun recreationRestoresCurrentDetailAndStableVisitedPageOrder() {
        val navigation = SettingsNavigation().apply {
            open(SettingsDestination.Models)
            back()
            open(SettingsDestination.Diagnostics)
        }
        val saved = with(SettingsNavigation.Saver) { saverScope.save(navigation) }!!
        val restored = SettingsNavigation.Saver.restore(saved)!!
        assertEquals(SettingsDestination.Diagnostics, restored.current)
        assertEquals(navigation.visited.toList(), restored.visited.toList())
        assertTrue(restored.back())
        assertEquals(SettingsDestination.Index, restored.current)
    }

    @Test fun staleSavedDestinationFallsBackToSettingsDirectory() {
        val restored = SettingsNavigation.Saver.restore(listOf("removed-page", "Index", "Models", "removed-page"))!!
        assertEquals(SettingsDestination.Index, restored.current)
        assertEquals(listOf(SettingsDestination.Index, SettingsDestination.Models), restored.visited.toList())
    }
}
