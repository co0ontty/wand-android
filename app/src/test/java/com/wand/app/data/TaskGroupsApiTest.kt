package com.wand.app.data

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskGroupsApiTest {
    private val payload = """{"revision":"rev/2","groups":[{
        "workspaceId":"w","workspaceName":"Workspace","tasks":[
            {"id":"archived","workspaceId":"w","archived":true,"boardTaskStatus":"doing",
             "sessions":[{"id":"inside-archived","archived":false}]},
            {"id":"active","workspaceId":"w","boardTaskStatus":"archived","totalSessions":2,
             "sessions":[{"id":"visible"},{"id":"hidden","archived":true}]}
        ],"standaloneSessions":[{"id":"standalone"},{"id":"standalone-archived","archived":true}]
    }]}"""

    @Test fun sharedRevisionReadIncludesArchivesButLegacyListKeepsActiveScope() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val requests = LinkedBlockingQueue<String>()
        val responses = listOf(payload, """{"unchanged":true,"revision":"rev/2","groups":[]}""", payload)
        val worker = thread(isDaemon = true) {
            responses.forEach { response ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    requests.put(input.readLine())
                    while (!input.readLine().isNullOrEmpty()) { /* consume headers */ }
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                    }
                }
            }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            val page = api.listTaskGroupsPage()
            assertEquals("rev/2", page.revision)
            assertEquals(2, page.groups.single().tasks.size)
            assertTrue(page.groups.single().tasks.first().archived)
            assertEquals("doing", page.groups.single().tasks.first().boardTaskStatus)
            assertNull(page.groups.single().tasks.first().boardTaskId)
            assertTrue(api.listTaskGroupsPage(page.revision).unchanged)
            val legacy = api.listTaskGroups().single()
            assertEquals(listOf("active"), legacy.tasks.map { it.id })
            assertEquals("archived", legacy.tasks.single().boardTaskStatus)
            assertEquals(listOf("visible"), legacy.tasks.single().sessions.map { it.id })
            assertEquals(1, legacy.tasks.single().totalSessions)
            assertEquals(listOf("standalone"), legacy.standaloneSessions.map { it.id })
            assertEquals(listOf(
                "GET /api/tasks?includeArchived=1&revision= HTTP/1.1",
                "GET /api/tasks?includeArchived=1&revision=rev%2F2 HTTP/1.1",
                "GET /api/tasks?includeArchived=1&revision= HTTP/1.1",
            ), (0..2).map { requests.poll(2, TimeUnit.SECONDS) })
        } finally {
            server.close()
            worker.join(2000)
        }
    }

    @Test fun activeProjectionRetainsArchivedAncestorsByIdWithCycleGuardAndPreservesCounts() {
        val groups = TaskGroupsPage.parse("""{"groups":[
            {"workspaceId":"w","tasks":[
                {"id":"child","workspaceId":"w","parentWorkspaceTaskId":"parent","totalSessions":20,
                 "sessions":[{"id":"visible"},{"id":"hidden","archived":true}]},
                {"id":"missing-parent","workspaceId":"w","parentWorkspaceTaskId":"missing","totalSessions":7},
                {"id":"unneeded","workspaceId":"w","archived":true,"parentWorkspaceTaskId":"unneeded"}
            ]},
            {"workspaceId":"ancestors","synthetic":true,"tasks":[
                {"id":"parent","workspaceId":"w","archived":true,"parentWorkspaceTaskId":"grandparent",
                 "totalSessions":9,"sessions":[{"id":"parent-visible"}]},
                {"id":"grandparent","workspaceId":"w","archived":true,"parentWorkspaceTaskId":"parent","totalSessions":12}
            ]}
        ]}""").groups
        val active = activeTaskDirectoryGroups(groups)
        assertEquals(listOf("w", "ancestors"), active.map { it.id })
        assertEquals(listOf("child", "missing-parent"), active.first().tasks.map { it.id })
        assertEquals(listOf("parent", "grandparent"), active.last().tasks.map { it.id })
        val child = active.first().tasks.first()
        assertEquals("parent", child.parentWorkspaceTaskId)
        assertEquals(19, child.totalSessions)
        assertEquals(listOf("visible"), child.sessions.map { it.id })
        assertEquals(20, groups.first().tasks.first().totalSessions)
        assertSame(groups.first().tasks[1], active.first().tasks[1])
        assertEquals(7, active.first().tasks[1].totalSessions)
        assertSame(groups.last().tasks.first(), active.last().tasks.first())
        assertEquals(9, active.last().tasks.first().totalSessions)
        assertTrue(active.last().tasks.first().archived)
        assertEquals("grandparent", active.last().tasks.first().copy(sessions = emptyList()).parentWorkspaceTaskId)
        assertSame(groups.last().tasks.last(), active.last().tasks.last())
        assertEquals(12, active.last().tasks.last().totalSessions)
    }

    @Test fun activeProjectionDropsArchiveOnlySyntheticAndGlobalGroupsButKeepsRealEmptyWorkspaces() {
        val full = TaskGroupsPage.parse(payload).groups.single()
        val archiveOnly = full.copy(tasks = full.tasks.filter { it.archived }, standaloneSessions = emptyList())
        val groups = listOf(archiveOnly, archiveOnly.copy(workspaceId = "synthetic", synthetic = true),
            archiveOnly.copy(workspaceId = "wand-global"), archiveOnly.copy(workspaceId = "global", global = true))
        val active = activeTaskDirectoryGroups(groups)
        assertEquals(listOf("w"), active.map { it.id })
        assertTrue(active.single().tasks.isEmpty())
        assertEquals(1, groups.first().tasks.size)
        assertTrue(groups.first().tasks.single().archived)
    }
}
