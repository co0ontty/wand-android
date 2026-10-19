package com.wand.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class TaskCommentsTest {
    @Test fun `mention query follows the caret and only the chosen token stays bound`() {
        assertEquals("name", TaskCommentMention.query("literal @name", "literal @name".length)?.text)
        assertNull(TaskCommentMention.query("mail@host", 9))
        assertEquals("瑞", TaskCommentMention.query("@瑞辰 已写完", 2)?.text)
        assertNull(TaskCommentMention.query("@瑞辰 已写完", 4))
        assertNull(TaskCommentMention.query("@开头", 0))
        val inserted = TaskCommentMention.insert("请 @虎", 5, "赛博虎妞")
        assertEquals("请 @赛博虎妞 ", inserted.first)
        assertTrue(TaskCommentMention.holds(inserted.first, "赛博虎妞"))
        assertFalse(TaskCommentMention.holds("@赛博", "赛博虎妞"))
        assertFalse(TaskCommentMention.holds("@瑞辰同学", "瑞辰"))
    }

    @Test fun `parse read only comments preserves body and excludes other tasks and empty rows`() {
        val response = JSONObject("""{"comments":[
            {"id":"c1","taskId":"one","authorName":"小王","body":"原始评论\n下一行","createdAt":"2026-10-16T12:00:00Z"},
            {"id":"c2","taskId":"two","authorName":"other","body":"another task"},
            {"id":"c3","taskId":"one","body":""}] }""")
        val comments = TaskComment.parseList(response, "one")
        assertEquals(1, comments.size)
        assertEquals(TaskComment("c1", "one", "小王", "原始评论\n下一行", "2026-10-16T12:00:00Z"), comments.single())
        assertTrue(TaskComment.parseList(JSONObject(), "one").isEmpty())
    }

    @Test fun `GET comments uses task route and older server 404 is empty`() = runBlocking {
        FeedbackTestServer { headers ->
            if (headers.startsWith("GET /api/wand-tasks/old/comments")) 404 to """{"error":"not found"}"""
            else 200 to """{"comments":[{"id":"c","taskId":"new","authorName":"我","body":"same issue","createdAt":"today"}]}"""
        }.use { server ->
            val api = WandApi(server.baseUrl, null)
            assertTrue(api.listTaskComments("old").isEmpty())
            assertEquals("same issue", loadTaskComments(api, "new").single().body)
            assertTrue(server.next().first.startsWith("GET /api/wand-tasks/old/comments "))
            assertTrue(server.next().first.startsWith("GET /api/wand-tasks/new/comments "))
        }
    }

    @Test fun `late non cancellable transport cannot apply comments after task switch`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var pending: Continuation<List<TaskComment>>? = null
        val api = Proxy.newProxyInstance(TaskBoardPort::class.java.classLoader, arrayOf(TaskBoardPort::class.java)) { _, method, args ->
            check(method.name == "listTaskComments")
            @Suppress("UNCHECKED_CAST")
            pending = args.last() as Continuation<List<TaskComment>>
            started.complete(Unit)
            COROUTINE_SUSPENDED
        } as TaskBoardPort
        var applied = false
        val oldTask = launch { loadTaskComments(api, "old"); applied = true }
        started.await()
        oldTask.cancel()
        pending!!.resume(listOf(TaskComment("c", "old", "name", "late", "today")))
        yield()
        oldTask.join()
        assertFalse(applied)
    }
}
