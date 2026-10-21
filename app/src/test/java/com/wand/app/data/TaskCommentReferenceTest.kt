package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `#TASK-xxx` 引用解析契约。口径与服务端 `src/task-comment-references.ts` 一致：
 * 同一套正则、同一套边界规则，三端不能各自解释。
 */
class TaskCommentReferenceTest {
    @Test fun `recognises a task reference inside prose`() {
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("先做 #TASK-133 的去重"))
        assertEquals(
            listOf("TASK-133", "TASK-134"),
            TaskCommentReference.parseTaskReferenceIds("先做 #TASK-133，再看 #TASK-134。"),
        )
        assertTrue(TaskCommentReference.hasTaskReference("引用 #TASK-133 。", "TASK-133"))
        assertFalse(TaskCommentReference.hasTaskReference("引用 #TASK-134 。", "TASK-133"))
        assertEquals(2, TaskCommentReference.countTaskReference("#TASK-133 与 #TASK-133", "TASK-133"))
        assertEquals(1, TaskCommentReference.countTaskReference("#TASK-133 与 #TASK-134", "TASK-133"))
    }

    @Test fun `markdown heading is not a reference`() {
        assertTrue(TaskCommentReference.parseTaskReferenceIds("# 任务标题").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("## 任务标题").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("### TASK-133").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("正文\n# 标题").isEmpty())
        // 标题写法后面紧跟编号也不会被当成引用：`#` 后面必须是编号本身。
        assertTrue(TaskCommentReference.parseTaskReferenceIds("# 标题 TASK-133").isEmpty())
    }

    @Test fun `hash inside an ordinary word is not a reference`() {
        assertTrue(TaskCommentReference.parseTaskReferenceIds("a#TASK-133").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("issue#TASK-133").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("C#TASK-133").isEmpty())
        // 相邻的两个引用只认前一个：中间的 `#` 不构成边界。
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("#TASK-133#TASK-134"))
    }

    @Test fun `insert writes the chosen identifier with a trailing space`() {
        val inserted = TaskCommentReference.insertCommentReference("先做 #TAS", 7, "TASK-133")
        assertEquals("先做 #TASK-133 ", inserted.first)
        assertEquals(13, inserted.second)
        // 没有正在编辑的引用 token 时，光标处直接插入。
        val plain = TaskCommentReference.insertCommentReference("先做", 2, "TASK-133")
        assertEquals("先做#TASK-133 ", plain.first)
        assertEquals(12, plain.second)
    }

    @Test fun `insertion replaces the typed token and keeps the caret after it`() {
        // 与 Web 端 `insertCommentReference` 同口径：末尾已经紧跟空白时不再补空格。
        val inserted = TaskCommentReference.insertCommentReference("写 #TASK-13 帮我看看", 10, "TASK-133")
        assertEquals("写 #TASK-133 帮我看看", inserted.first)
        assertEquals("写 #TASK-133".length, inserted.second)
        val fresh = TaskCommentReference.insertCommentReference("写 ", 2, "TASK-133")
        assertEquals("写 #TASK-133 ", fresh.first)
        assertEquals("写 #TASK-133 ".length, fresh.second)
    }

    @Test fun `a hash only starts a reference at start of string, whitespace or an opening bracket`() {
        // URL 片段不是引用：否则服务端会以「引用的任务不存在」拒收整条评论。
        assertTrue(TaskCommentReference.parseTaskReferenceIds("https://x/doc#TASK-5").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("http://x/#TASK-133").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("看 https://x/doc#TASK-5 继续").isEmpty())
        // 词内的 `#` 不是引用。
        assertTrue(TaskCommentReference.parseTaskReferenceIds("a#TASK-133").isEmpty())
        // 成对开括号是合法边界。
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("（#TASK-133）"))
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("(#TASK-133)"))
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("【#TASK-133】"))
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("「#TASK-133」"))
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("《#TASK-133》"))
        // 行首也是边界。
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("#TASK-133"))
        // 链接与真引用混排时只认后者。
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("看 https://x/doc#TASK-5 后写 #TASK-133"))
        // 计数与存在性判断同一条边界。
        assertFalse(TaskCommentReference.hasTaskReference("看 https://x/doc#TASK-5", "TASK-5"))
        assertEquals(0, TaskCommentReference.countTaskReference("看 https://x/doc#TASK-5", "TASK-5"))
        assertEquals(1, TaskCommentReference.countTaskReference("看 https://x/doc#TASK-5 后写 #TASK-133", "TASK-133"))
    }

    @Test fun `only a full task identifier counts as a reference`() {
        // `#` 后面必须是完整编号：不是标题、不是编号加后缀。
        assertTrue(TaskCommentReference.parseTaskReferenceIds("# 标题").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("#TASK 标题").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("#TASK-133x").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("#TASK-133-").isEmpty())
        assertTrue(TaskCommentReference.parseTaskReferenceIds("C# 和 issue #12 都不是").isEmpty())
        // 同一个编号只记一次，出现多次按首次出现顺序去重。
        assertEquals(listOf("TASK-133"), TaskCommentReference.parseTaskReferenceIds("重复 #TASK-133 再 #TASK-133"))
        assertEquals(
            listOf("TASK-133", "TASK-137"),
            TaskCommentReference.parseTaskReferenceIds("#TASK-133 和 #TASK-137 都要"),
        )
    }

    @Test fun `reference query only opens at a token boundary`() {
        assertEquals(0, TaskCommentReference.commentReferenceQuery("#TASK-133", 9)?.start)
        assertEquals("TASK-133", TaskCommentReference.commentReferenceQuery("#TASK-133", 9)?.query)
        assertNull(TaskCommentReference.commentReferenceQuery("a#TASK-133", 9))
        assertNull(TaskCommentReference.commentReferenceQuery("看 #TASK 133", 9))
        assertNull(TaskCommentReference.commentReferenceQuery("#TASK-133", 0))
        assertEquals(3, TaskCommentReference.commentReferenceQuery("先做 #TAS", 7)?.start)
        assertEquals("TAS", TaskCommentReference.commentReferenceQuery("先做 #TAS", 7)?.query)
        // 成对开括号也算边界。
        assertNotNull(TaskCommentReference.commentReferenceQuery("（#TAS", 4))
        // URL 片段不会在输入框里唤起选择器（`#` 前面必须是空白或成对开括号）。
        assertNull(TaskCommentReference.commentReferenceQuery("https://host/#TAS", 17))
        assertNull(TaskCommentReference.commentReferenceQuery("看 https://x/doc#TAS", 18))
        // 成对开括号后面可以唤起选择器。
        assertNotNull(TaskCommentReference.commentReferenceQuery("【#TAS", 4))
        assertNotNull(TaskCommentReference.commentReferenceQuery("「#TAS", 4))
        assertNotNull(TaskCommentReference.commentReferenceQuery("《#TAS", 4))
    }

    @Test fun `render splits the body into plain text and reference tokens`() {
        val segments = TaskCommentReference.taskReferenceSegments("看 #TASK-133 尾")
        assertEquals(3, segments.size)
        assertEquals("看 ", segments[0].text)
        assertNull(segments[0].identifier)
        assertEquals("#TASK-133", segments[1].text)
        assertEquals("TASK-133", segments[1].identifier)
        assertEquals(" 尾", segments[2].text)
        assertNull(segments[2].identifier)
        // 没有引用时整段是普通文本。
        val plain = TaskCommentReference.taskReferenceSegments("普通评论")
        assertEquals(1, plain.size)
        assertEquals("普通评论", plain.single().text)
        assertNull(plain.single().identifier)
        // URL 片段整段都是普通文本，不会被渲染成引用 token。
        val link = TaskCommentReference.taskReferenceSegments("看 https://x/doc#TASK-5 尾")
        assertEquals(1, link.size)
        assertEquals("看 https://x/doc#TASK-5 尾", link.single().text)
        assertNull(link.single().identifier)
        // 成对开括号在引用 token 之前，属于普通文本。
        val bracket = TaskCommentReference.taskReferenceSegments("（#TASK-133）")
        assertEquals(3, bracket.size)
        assertEquals("（", bracket[0].text)
        assertNull(bracket[0].identifier)
        assertEquals("#TASK-133", bracket[1].text)
        assertEquals("TASK-133", bracket[1].identifier)
        assertEquals("）", bracket[2].text)
        assertNull(bracket[2].identifier)
    }

    @Test fun `a reference identifier resolves to the child task id`() {
        val children = listOf(
            TaskCommentReference.Target("child-1", "TASK-133"),
            TaskCommentReference.Target("child-2", "TASK-134"),
        )
        // 正文里写的是编号，详情页按 id 打开：点 token 必须先把编号换成 id。
        assertEquals("child-1", TaskCommentReference.taskReferenceTargetId("TASK-133", children))
        assertEquals("child-2", TaskCommentReference.taskReferenceTargetId("TASK-134", children))
        // 编号不在本任务子任务里时不给目标，也不猜 id。
        assertNull(TaskCommentReference.taskReferenceTargetId("TASK-999", children))
        assertNull(TaskCommentReference.taskReferenceTargetId("", children))
        assertNull(TaskCommentReference.taskReferenceTargetId("TASK-133", emptyList()))
    }

    @Test fun `references resolve only against the current task children`() {
        val children = listOf(
            TaskCommentReference.Target("child-1", "TASK-133"),
            TaskCommentReference.Target("child-2", "TASK-134"),
        )
        assertEquals(
            listOf(TaskCommentReference.Target("child-1", "TASK-133")),
            TaskCommentReference.resolveTaskReferences("做 #TASK-133 和 #TASK-999", children),
        )
        assertEquals(listOf("TASK-999"), TaskCommentReference.unknownTaskReferenceIds("做 #TASK-133 和 #TASK-999", children))
        assertTrue(TaskCommentReference.unknownTaskReferenceIds("做 #TASK-133", children).isEmpty())
    }
}
