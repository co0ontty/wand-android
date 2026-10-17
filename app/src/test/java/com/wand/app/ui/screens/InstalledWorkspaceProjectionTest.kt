package com.wand.app.ui.screens

import com.wand.app.data.WandApi
import com.wand.app.data.WandAuth
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 只读已安装服务；不发消息、不改归属、不执行模型。 */
class InstalledWorkspaceProjectionTest {
    @Test fun installedRunningSessionsRemainInEmployeeDirectoryAndSearch() = runBlocking {
        assumeTrue(System.getenv("WAND_ANDROID_INSTALLED_ACCEPTANCE") == "1")
        val connection = JSONObject(File(System.getenv("HOME"), ".wand/acceptance-connection.json").readText())
        val decoded = WandAuth.decodeConnectCode(connection.getString("connectionCode"))!!
        val api = WandApi(connection.getString("serverURL"), decoded.second)
        val source = directoryTreeGroups(api.listTaskGroups())
        val all = recentHomeConversations(source, Int.MAX_VALUE)
        assertTrue(all.isNotEmpty())
        val active = all.filter { it.session.inFlight == true || it.session.ptyBusy == true }
        assertTrue("Need a currently executing session", active.isNotEmpty())
        val groups = recentHomeGroups(all, limit = Int.MAX_VALUE)
        active.forEach { item ->
            assertTrue(sessionKeptByRunningFold(item.session))
            val identity = groups.single { it.key == homeGroupKeyOf(item.session) }
            assertTrue(homeGroupShowsHeader(identity))
            assertTrue(identity.conversations.any { it.session.id == item.session.id })
            val query = listOfNotNull(item.group.workspaceName, item.session.employeeName).joinToString(" ")
            val directory = searchHomeGroups(source, query, emptyList())
            assertTrue(recentHomeConversations(directory, Int.MAX_VALUE).any { it.session.id == item.session.id })
            val recent = searchHomeRecentGroups(groups, query, emptyList(), false)
            assertTrue(recent.flatMap { it.conversations }.any { it.session.id == item.session.id })
        }
        println("Installed workspace projection: ${all.size} sessions, ${active.size} executing verified")
    }
}
