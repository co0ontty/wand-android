package com.wand.app.ui

import com.wand.app.data.WandApi
import com.wand.app.data.WandAuth
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 只读真实运行目录投影验收；不发送输入、不切目录、不打印会话或凭据。 */
class SessionWorkingDirectoryInstalledTest {
    @Test
    fun installedSessionDirectoriesProjectToTheirFinalSegment() = runBlocking {
        assumeTrue(System.getenv("WAND_ANDROID_INSTALLED_ACCEPTANCE") == "1")
        val connection = JSONObject(File(System.getenv("HOME"), ".wand/acceptance-connection.json").readText())
        val decoded = WandAuth.decodeConnectCode(connection.getString("connectionCode"))
        assertNotNull("Acceptance connection must decode", decoded)
        val api = WandApi(connection.getString("serverURL"), decoded!!.second)
        val sessions = api.listSessions().filter { !it.cwd.isNullOrBlank() }.take(8)
        assertTrue("Installed service must expose a session working directory", sessions.isNotEmpty())
        for (session in sessions) {
            val snapshot = api.getSession(session.id)
            val path = snapshot.cwd?.trim()?.replace('\\', '/')
            assertTrue("Readable session must retain its working directory", !path.isNullOrEmpty())
            val expected = path!!.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
            assertEquals(expected, sessionWorkingDirectoryLabel(snapshot.cwd))
        }
        println("Installed directory projection: ${sessions.size} real session snapshots verified")
    }
}
