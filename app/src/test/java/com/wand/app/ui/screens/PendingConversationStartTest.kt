package com.wand.app.ui.screens

import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SiliconEmployee
import org.junit.Assert.*
import org.junit.Test

class PendingConversationStartTest {
    @Test fun defaultPartnerUsesStableIdentityAndExplicitEmployeeWins() {
        assertEquals("e_wand_default", pendingInitialEmployeeId(null))
        assertEquals("e_wand_default", pendingInitialEmployeeId(""))
        assertEquals("employee-explicit", pendingInitialEmployeeId("employee-explicit"))
    }

    @Test fun missingOrFailedDefaultPartnerCannotSubmitButAnExplicitToolCan() {
        val partner = SiliconEmployee("e_wand_default", "赛博虎妞", "", "", "", listOf(BoardTaskAgent("claude", "default", "off")))
        assertTrue(pendingEmployeeIsReady("e_wand_default", false, null, partner))
        assertFalse(pendingEmployeeIsReady("e_wand_default", true, null, partner))
        assertFalse(pendingEmployeeIsReady("e_wand_default", false, "离线", partner))
        assertFalse(pendingEmployeeIsReady("e_wand_default", false, null, null))
        assertFalse(pendingEmployeeIsReady("other", false, null, partner))
        assertFalse(pendingEmployeeIsReady("e_wand_default", false, null, partner.copy(agents = emptyList())))
        assertTrue(pendingEmployeeIsReady(null, false, "离线", null))
    }

}
