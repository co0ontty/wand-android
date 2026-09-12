package com.wand.app.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class WandAppearanceTest {
    @Test
    fun nightModeFollowsAppearanceSelection() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, WandAppearanceMode.Light.toNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, WandAppearanceMode.Dark.toNightMode())
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            WandAppearanceMode.System.toNightMode(),
        )
    }

    @Test
    fun storageRoundTripDefaultsToSystem() {
        assertEquals(WandAppearanceMode.System, WandAppearanceMode.fromStorageValue(null))
        assertEquals(WandAppearanceMode.System, WandAppearanceMode.fromStorageValue("unknown"))
        assertEquals(WandAppearanceMode.Light, WandAppearanceMode.fromStorageValue("light"))
        assertEquals(WandAppearanceMode.Dark, WandAppearanceMode.fromStorageValue("dark"))
        assertEquals(WandAppearanceMode.System, WandAppearanceMode.fromStorageValue("system"))
    }
}
