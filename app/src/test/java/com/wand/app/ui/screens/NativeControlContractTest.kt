package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeControlContractTest {
    private fun source(name: String) = File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()

    @Test fun skillDetentsUseTheNativeVisibleSliderAndCommitOnlyAfterRelease() {
        val skill = source("PiSkillSwitch")
        assertTrue(skill.contains("Slider("))
        assertTrue(skill.contains("steps = 1"))
        assertFalse(skill.contains("thumb = {}"))
        assertFalse(skill.contains("track = {}"))
        assertFalse(skill.contains("WandSegmentedTrack"))
        val preview = skill.substringAfter("onValueChange =").substringBefore("onValueChangeFinished")
        assertFalse(preview.contains("onModeChange"))
        val commit = skill.substringAfter("onValueChangeFinished =")
        assertTrue(commit.contains("if (enabled)"))
        assertTrue(commit.contains("if (next != mode) onModeChange(next)"))
    }

    @Test fun settingsBinarySwitchKeepsNativeMaterialThumbAndDisabledFeedback() {
        val screen = source("SettingsScreen").substringAfter("private fun SettingsSwitchRow(")
        assertTrue(source("SettingsScreen").contains("Switch("))
        assertFalse(screen.contains("checkedThumbColor = Color.White"))
        assertFalse(screen.contains("uncheckedThumbColor = WandColors.textMuted"))
    }
}
