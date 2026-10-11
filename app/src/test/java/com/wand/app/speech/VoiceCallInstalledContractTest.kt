package com.wand.app.speech

import com.wand.app.data.WandApi
import com.wand.app.data.WandAuth
import com.wand.app.ui.components.PlushAvatarIdentity
import com.wand.app.ui.components.avatarFileActivity
import com.wand.app.ui.components.AvatarFileActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only installed API compatibility; never sends, records, creates or changes a session. */
class VoiceCallInstalledContractTest {
    @Test fun existingEmployeeSessionsSupplyTheSameTargetAndMessagesForCall() = runBlocking {
        assumeTrue(System.getenv("WAND_ANDROID_INSTALLED_ACCEPTANCE") == "1")
        val connection = JSONObject(File(System.getenv("HOME"), ".wand/acceptance-connection.json").readText())
        val decoded = WandAuth.decodeConnectCode(connection.getString("connectionCode"))
        assertNotNull(decoded)
        val api = WandApi(connection.getString("serverURL"), decoded!!.second)
        val sessions = api.listSessions().filter { it.isStructured && !it.employeeId.isNullOrBlank() }.take(4)
        assertTrue("Need an existing structured employee conversation for read-only acceptance", sessions.isNotEmpty())
        for (session in sessions) {
            val snap = api.getSession(session.id)
            assertEquals(session.id, snap.id)
            assertNotNull(snap.employeeId)
            assertTrue(!snap.cwd.isNullOrBlank())
            assertEquals(AvatarFileActivity.Idle, avatarFileActivity(snap.messages.orEmpty(), false))
            if (snap.employeeId == "e_wand_default") assertEquals("plush_cat_silver", PlushAvatarIdentity.asset(snap.employeeId, snap.employeeName, snap.employeeAvatar))
            if (snap.employeeId == "e_wand_ops") assertEquals("plush_cat_orange", PlushAvatarIdentity.asset(snap.employeeId, snap.employeeName, snap.employeeAvatar))
        }
        println("Installed call target/message contract: ${sessions.size} real employee sessions, read-only, zero model calls")
    }
}
