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

    @Test fun `mention replacement consumes suffix and compacts repeated dispatch preamble`() {
        val name = "赛博虎妞"
        val middle = "👩🏽‍💻 请 @赛博虎妞 核对 #TASK-133"
        val inserted = TaskCommentMention.insert(middle, "👩🏽‍💻 请 @赛".length, name)
        assertEquals(middle, inserted.first)
        assertEquals("👩🏽‍💻 请 @赛博虎妞 ".length, inserted.second)
        assertNull(TaskCommentMention.query(inserted.first, inserted.second))
        assertEquals("@赛博虎妞 重跑本任务：保留邮箱 a@b.test 与 `@示例`",
            TaskCommentMention.submissionBody("@赛博虎妞 @ @赛博虎妞 @赛博虎妞 重跑本任务：保留邮箱 a@b.test 与 `@示例`", name))
        val chosen = TaskCommentMention.insert("@赛博虎妞 @ @", "@赛博虎妞 @ @".length, name)
        assertEquals("@赛博虎妞 ", chosen.first)
        assertEquals(chosen.first.length, chosen.second)
        assertEquals("@石一 ", TaskCommentMention.insert("@赛博虎妞 @", "@赛博虎妞 @".length, "石一", name).first)
        val continuing = "@赛博虎妞 请核对 #TASK-133 @"
        val repeated = TaskCommentMention.insert(continuing, continuing.length, name, name)
        assertEquals("@赛博虎妞 请核对 #TASK-133 ", repeated.first)
        assertEquals(repeated.first.length, repeated.second)
        val replaced = TaskCommentMention.insert(continuing, continuing.length, "石一", name)
        assertEquals("@石一 请核对 #TASK-133 ", replaced.first)
        assertEquals(replaced.first.length, replaced.second)
        assertEquals("@石一 ", TaskCommentMention.insert("@A B @", "@A B @".length, "石一", "A B").first)
        assertNull(TaskCommentMention.validationMessage("@A B 执行", "A B"))
        assertEquals("已写正文", TaskCommentMention.insert("已写正文", 4, name).first)
    }

    @Test fun `invalid mentions cannot become plain comments or empty instructions`() {
        listOf("@", "@ @", " \n@\t@ ", "@赛博虎妞", "@赛博虎妞 请执行").forEach {
            assertNotNull(it, TaskCommentMention.validationMessage(it, ""))
        }
        listOf("@赛博虎妞", "@赛博虎妞 @ @赛博虎妞", "@赛博虎妞 执行 @", "@赛博虎妞 执行 @赛博虎妞").forEach {
            assertNotNull(it, TaskCommentMention.validationMessage(it, "赛博虎妞"))
        }
        listOf("普通评论", "a@b.test 的问题", "`@注解` 示例").forEach {
            assertNull(it, TaskCommentMention.validationMessage(it, ""))
        }
        assertNull(TaskCommentMention.validationMessage("@赛博虎妞 @ @赛博虎妞 修复 #TASK-133 👨‍👩‍👧‍👦", "赛博虎妞"))
        assertNull(TaskCommentMention.validationMessage("👩🏽‍💻 请 @赛博虎妞 核对", "赛博虎妞"))
        assertEquals("普通评论\n保留正文  和空格", TaskCommentMention.submissionBody("普通评论\n保留正文  和空格", ""))
    }

    @Test fun `unselected mention is rejected before transport can send`() = runBlocking {
        val api = WandApi("http://127.0.0.1:1", null)
        listOf("@", "@ @", "@赛博虎妞 请执行").forEach { body ->
            try {
                api.postTaskComment("must-not-write", "request", body, null, null)
                fail("Invalid comment reached transport")
            } catch (error: WandApiException) {
                assertEquals(400, error.status)
                assertTrue(error.message.orEmpty().contains("选择员工"))
            }
        }
    }

    @Test fun `valid dispatch and ordinary comment still reach the wire with exact identities`() = runBlocking {
        for (employee in listOf<String?>(null, "stable-employee")) {
            val body = if (employee == null) "普通评论" else TaskCommentMention.submissionBody("@赛博虎妞 @ @赛博虎妞 核对 #TASK-133", "赛博虎妞")
            FeedbackTestServer { _ ->
                200 to JSONObject().put("comment", JSONObject().put("id", "saved").put("taskId", "task")
                    .put("authorName", "用户").put("body", body).put("createdAt", "now")).toString()
            }.use { server ->
                val saved = WandApi(server.baseUrl, null).postTaskComment("task", "stable-request", body, employee, "source")
                assertEquals("saved", saved.id)
                val (headers, raw) = server.next()
                assertTrue(headers.startsWith("POST /api/wand-tasks/task/comments "))
                val payload = JSONObject(raw)
                assertEquals(body, payload.getString("body"))
                assertEquals("stable-request", payload.getString("requestId"))
                assertEquals("source", payload.getString("sourceCommentId"))
                if (employee == null) assertFalse(payload.has("employeeId")) else assertEquals(employee, payload.getString("employeeId"))
            }
        }
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

    @Test fun `late comment page cannot apply after task switch`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var pending: Continuation<TaskCommentPage>? = null
        val api = Proxy.newProxyInstance(TaskBoardPort::class.java.classLoader, arrayOf(TaskBoardPort::class.java)) { _, method, args ->
            check(method.name == "listTaskCommentPage")
            @Suppress("UNCHECKED_CAST")
            pending = args.last() as Continuation<TaskCommentPage>
            started.complete(Unit)
            COROUTINE_SUSPENDED
        } as TaskBoardPort
        var applied = false
        val oldTask = launch { loadTaskCommentPage(api, "old"); applied = true }
        started.await()
        oldTask.cancel()
        pending!!.resume(TaskCommentPage(listOf(TaskComment("c", "old", "name", "late", "today")), 1, null))
        yield()
        oldTask.join()
        assertFalse(applied)
    }
}
