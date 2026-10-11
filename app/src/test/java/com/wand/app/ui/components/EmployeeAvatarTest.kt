package com.wand.app.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EmployeeAvatarTest {
    @Test fun plushIdentityMatchesWebIncludingUtf16AndNameFallback() {
        assertEquals("plush_round_lavender_ink_beret", PlushAvatarIdentity.asset("e1", "测试", ""))
        assertEquals("plush_round_blue_none_beret", PlushAvatarIdentity.asset("😀员工", "测试", ""))
        assertEquals("plush_square_blue_ink_none", PlushAvatarIdentity.asset("  stable-id  ", "测试", ""))
        assertEquals("plush_capsule_cream_ink_none", PlushAvatarIdentity.asset("", "测试", ""))
        assertEquals(PlushAvatarIdentity.asset("e1", "before", ""), PlushAvatarIdentity.asset("e1", "after", "future:v2"))
    }

    @Test fun plushContractPreservesUploadsAndLegacyCatsAndRejectsUnknownFields() {
        assertNull(PlushAvatarIdentity.asset("e1", "A", "data:image/png;base64,eA=="))
        assertNull(PlushAvatarIdentity.asset("e1", "A", "cat:7"))
        assertEquals("plush_cat_silver", PlushAvatarIdentity.asset("e1", "A", "plush-cat:v1:silver"))
        assertEquals("plush_cat_orange", PlushAvatarIdentity.asset("e1", "A", "plush-cat:v1:orange"))
        val default = PlushAvatarIdentity.asset("e1", "A", "")
        listOf("plush:v2:round:blue:none:none", "plush:v1:wrong:blue:none:none", "plush-cat:v1:unknown").forEach {
            assertEquals(default, PlushAvatarIdentity.asset("e1", "A", it))
        }
    }

    @Test fun everySupportedPlushConfigurationHasAnOfflinePortrait() {
        val assets = File("src/main/assets/plush")
        for (shape in PlushAvatarIdentity.shapes) for (color in PlushAvatarIdentity.colors)
            for (glasses in PlushAvatarIdentity.glasses) for (hat in PlushAvatarIdentity.hats) {
                val asset = PlushAvatarIdentity.asset("e1", "A", "plush:v1:$shape:$color:$glasses:$hat")
                assertEquals("plush_${shape}_${color}_${glasses}_${hat}", asset)
                org.junit.Assert.assertTrue(File(assets, "$asset.png").isFile)
            }
        assertEquals(326, assets.listFiles()?.count { it.extension == "png" })
    }
    @Test
    fun eachSupportedCliKeepsItsOwnLogoIdentity() {
        listOf("claude", "codex", "opencode", "grok", "qoder", "pi", "gemini").forEach {
            assertEquals(it, employeeAvatarProvider(it))
        }
        assertEquals("pi", employeeAvatarProvider(" Pi "))
    }

    @Test
    fun unknownOrMissingCliNeverImpersonatesClaude() {
        listOf(null, "", "terminal", "future-cli").forEach {
            assertNull(employeeAvatarProvider(it))
        }
    }

    @Test
    fun badgeScalesWithAvatarWithoutChangingAvatarSize() {
        assertEquals(13.dp, employeeCliBadgeSize(24.dp))
        assertEquals(13.dp, employeeCliBadgeSize(26.dp))
        assertEquals(14.dp, employeeCliBadgeSize(28.dp))
        assertEquals(16.dp, employeeCliBadgeSize(32.dp))
        assertEquals(20.dp, employeeCliBadgeSize(44.dp))
    }
}
