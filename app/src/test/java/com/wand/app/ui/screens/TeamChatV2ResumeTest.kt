package com.wand.app.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.TurnAuthor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2/R3 双端合成输入；不以本地句柄冒充服务端消息 ID。 */
class TeamChatV2ResumeTest {
    private val color = Color(0xFFC5653D)
    private val soft = color.copy(alpha = 0.14f)
    private val style = SpanStyle(color = color, background = soft, fontWeight = FontWeight.Medium)
    private val stamp = "2026-09-29T10:00:00.000Z"

    /** 与 output/team-chat-v2-resume/presentation-cases.json 的八组输入/预期逐例同值。 */
    private val embeddedPresentationCases = """
        {
          "fixtures": {
            "a":{"role":"assistant","createdAt":"2026-09-29T10:00:00.000Z","author":{"id":"m-a","name":"甲"},"content":[{"type":"text","text":"同一前缀一二三四五六七八九十甲报告后半持续保持共同的二十四字前缀 A"}]},
            "b":{"role":"assistant","createdAt":"2026-09-29T10:00:00.000Z","author":{"id":"m-a","name":"甲"},"content":[{"type":"text","text":"同一前缀一二三四五六七八九十甲报告后半持续保持共同的二十四字前缀 B"}]},
            "c":{"role":"assistant","createdAt":"2026-09-29T10:00:00.000Z","author":{"id":"m-b","name":"乙"},"content":[{"type":"text","text":"同一前缀一二三四五六七八九十甲报告后半持续保持共同的二十四字前缀 A"}]},
            "tail":{"role":"assistant","createdAt":"2026-09-29T10:00:01.000Z","author":{"id":"m-a","name":"甲"},"content":[{"type":"text","text":"新尾"}]},
            "unknownTime":{"role":"assistant","author":{"id":"m-a","name":"甲"},"content":[{"type":"text","text":"时刻缺失"}]},
            "unrelated":{"role":"user","createdAt":"2026-09-29T10:00:02.000Z","content":[{"type":"text","text":"不重叠"}]}
          },
          "cases": [
            {"id":"repeat-reload","scope":"run-1/chat-1","initial":["a","b"],"openOwner":0,"steps":[{"successful":true,"next":["a","b"],"expect":{"retained":[0,1],"rebound":[],"candidates":[],"closeOwner":false}}]},
            {"id":"same-millisecond-different-full-text-author","scope":"run-1/chat-1","initial":["a"],"steps":[{"successful":true,"next":["a","b","c"],"expect":{"retained":[0],"rebound":[1,2],"candidates":[1,2],"closeOwner":false}}]},
            {"id":"exact-duplicates-no-dedup","scope":"run-1/chat-1","initial":["a","a","b"],"openOwner":0,"steps":[{"successful":true,"next":["a","b","tail"],"expect":{"retained":[1],"rebound":[0,2],"candidates":[2],"closeOwner":true}}]},
            {"id":"window-200-to-200","scope":"run-1/chat-1","initial":{"generate":200},"openOwner":100,"steps":[{"successful":true,"next":{"dropHead":1,"append":"tail"},"expect":{"retained":[0,198],"rebound":[199],"candidates":[199],"closeOwner":false}}]},
            {"id":"no-overlap-reconnect","scope":"run-1/chat-1","initial":["a","b"],"openOwner":0,"steps":[{"successful":true,"next":["unrelated","tail"],"expect":{"retained":[],"rebound":[0,1],"candidates":[],"closeOwner":true}}]},
            {"id":"failed-snapshot-does-not-clear","scope":"run-1/chat-1","initial":["a","b"],"openOwner":0,"steps":[{"successful":false,"next":[],"expect":{"retained":[0,1],"rebound":[],"candidates":[],"closeOwner":false}},{"successful":true,"next":["a","b"],"expect":{"retained":[0,1],"rebound":[],"candidates":[],"closeOwner":false}}]},
            {"id":"scope-switch-run-and-chat","scope":"run-1/chat-1","initial":["a","b"],"openOwner":0,"steps":[{"successful":true,"scope":"run-2/chat-2","next":["a","b"],"expect":{"retained":[],"rebound":[0,1],"candidates":[],"closeOwner":true}}]},
            {"id":"missing-time-tail-static","scope":"run-1/chat-1","initial":["a","b"],"steps":[{"successful":true,"next":["a","b","unknownTime"],"expect":{"retained":[0,1],"rebound":[2],"candidates":[],"closeOwner":false}}]}
          ]
        }
    """.trimIndent()

    private fun turn(text: String, createdAt: String? = stamp): ConversationTurn = ConversationTurn(
        role = "assistant", createdAt = createdAt,
        author = TurnAuthor(id = "m-a", name = "甲"),
        content = listOf(ContentBlock.Text(text, null)),
    )

    @Test fun mentionMatchingIsLosslessEvenWithLongChineseNamesAndInsideSpaces() {
        val names = listOf("设计", "设计师", "张 三", "甲".repeat(40))
        for (mark in listOf("**", "__", "*", "_", "~~")) {
            val source = "前文 $mark@设计师$mark 和 @张 三、@${"甲".repeat(40)}。"
            val ranges = mentionRanges(source, names)
            assertEquals(listOf("@设计师", "@张 三", "@${"甲".repeat(40)}"), ranges.map { source.substring(it) })
            assertEquals(source, mentionAnnotatedText(source, names, style).text)
            assertFalse(mentionAnnotatedText(source, names, style).text.contains('\u2009'))
            assertTrue(mentionRanges("前文 $mark@设计师 未闭合", names).isEmpty())
        }
        assertTrue(mentionRanges("a@设计师", names).isEmpty())
        assertTrue(mentionRanges("@陌生人", names).isEmpty())
        assertTrue(mentionRanges("@设计师", emptyList()).isEmpty())
        val source = "字".repeat(417) + " @设计师 后半还有更多正文"
        val preview = collapsedPreview(source)
        assertEquals("字".repeat(417) + " @设…", preview)
        assertTrue("不能把截断的设计师认作设计", mentionRanges(preview, names, source).isEmpty())
        assertEquals(source, mentionAnnotatedText(source, names, style).text)
        assertEquals(1, mentionRanges(source, names).size)
        val wrapped = "字".repeat(414) + " **@设计师** 尾文"
        assertTrue(mentionRanges(collapsedPreview(wrapped), names, wrapped).isEmpty())
    }

    @Test fun realInlineParserPreservesBoldCodeEscapedAtAndLinkAnnotations() {
        val raw = "依据 **@设计师** 及 *@张 三*，`@设计师` \\@设计师 [@设计师](https://example.test/a)"
        val names = listOf("设计", "设计师", "张 三")
        val link = LinkAnnotation.Url("https://example.test/a")
        val normal = parseInlineMarkdown(raw, Color.Blue, linkFactory = { link })
        var protected: List<IntRange>? = null
        val decorated = parseInlineMarkdown(raw, Color.Blue, { original, ranges ->
            protected = ranges.toList()
            decorateTeamMarkdown(original, names, ranges, style)
        }, linkFactory = { link })
        assertEquals(normal.text, decorated.text)
        assertEquals(2, decorated.spanStyles.count { it.item.background == soft })
        assertTrue(decorated.text.contains("@张 三"))
        val boldAt = decorated.text.indexOf("@设计师")
        assertTrue(decorated.spanStyles.any {
            it.item.fontWeight == FontWeight.Bold && it.start <= boldAt && it.end >= boldAt + "@设计师".length
        })
        assertEquals(3, protected?.size) // inline code, escaped @, link label
        assertEquals(listOf(link), decorated.getLinkAnnotations(0, decorated.length).map { it.item })
        assertTrue(decorated.getLinkAnnotations(0, decorated.length).single().item === link)
        assertEquals(normal, parseInlineMarkdown(raw, Color.Blue, linkFactory = { link }))
        assertEquals("标题 @设计师", parseInlineMarkdown("标题 @设计师", Color.Blue).text)
    }

    @Test fun documentAndNoticeWiringUseOnlyTheOptionalSharedMarkdownDecorator() {
        fun source(path: String): String = File("src/main/java/com/wand/app/ui/$path").readText()
        val markdown = source("screens/ChatMarkdown.kt")
        val screen = source("screens/AiTeamChatScreen.kt")
        val sheet = source("components/TeamMessageDocSheet.kt")
        assertTrue(markdown.contains("fun MarkdownText(text: String, inlineDecoration: InlineMarkdownDecoration? = null)"))
        assertTrue(markdown.contains("remember(raw, linkColor, codeColor, context, baseUrl, scope, uriHandler, inlineDecoration)"))
        assertEquals("段落、标题、列表、引用各一处", 4,
            Regex("inlineMarkdown\\(block\\.text, inlineDecoration\\)").findAll(markdown).count())
        assertTrue(markdown.contains("MarkdownTable(block.headers, block.rows, inlineDecoration)"))
        assertTrue(markdown.contains("inlineMarkdown(cell, inlineDecoration)"))
        assertTrue(markdown.contains("is MarkdownBlock.Code -> MarkdownCodeBlock(block, subtleInset)"))
        assertTrue(screen.contains("TeamChatTurnKind.Notice -> TeamNoticeRow(turn)"))
        assertTrue(screen.contains("names = rosterNames"))
        assertTrue(screen.contains("document -> MarkdownText(text, inlineDecoration = teamChatMarkdownDecoration(names))"))
        assertTrue(sheet.contains("MarkdownText(doc.text, inlineDecoration = teamChatMarkdownDecoration(doc.mentionNames))"))
        assertFalse(screen.contains("item.wait"))
        assertFalse(screen.contains(".animateItem("))
        assertFalse(screen.contains("teamChatTurnKey"))
        assertTrue(screen.contains("TeamChatTurnArrival(") || screen.contains("TeamTurnArrival("))
        assertTrue(screen.contains("val frame = teamArrivalFrame(progress.value, motion, travelPx)"))
        assertTrue(screen.contains("translationY = frame.translationY"))
        assertTrue(screen.contains("progress.animateTo(1f, WandMotion.respectMotion(motion, WandMotion.tweenEnter()))"))
        assertTrue(screen.contains("presentedTurns.forEach"))
        assertTrue(screen.contains("settledProjection = batch"))
        assertTrue(screen.contains("arrivalState = teamArrivalAdmitted(arrivalState, batch.scope, eligible,"))
        assertTrue(screen.contains("val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()"))
        assertTrue(screen.contains("if (doc.scope != projectionScope || (projection != null && !owned)) docClosing = true"))
        assertTrue(sheet.contains("if (sheetState.isVisible) sheetState.hide()"))
        assertTrue(sheet.contains("onDismissed()"))
        assertTrue("群公告仍使用默认 MarkdownText", screen.contains("MarkdownText(\n            if (expanded) text else collapsedPreview(text)"))
        assertTrue("默认字号/compact 必须原样", markdown.contains("if (compact) 12.sp else 15.sp")
            && markdown.contains("if (compact) 18.sp else 22.sp"))
    }

    @Test fun wholePayloadIncludesBlockBoundariesButNotUsageOrShortPrefix() {
        val prefix = "相同的前二十四字".repeat(4)
        val a = turn("$prefix-A")
        val b = turn("$prefix-B")
        assertNotEquals(teamTurnFingerprint(a), teamTurnFingerprint(b))
        assertNotEquals(teamTurnFingerprint(a), teamTurnFingerprint(a.copy(author = TurnAuthor(id = "m-b", name = "乙"))))
        assertNotEquals(teamTurnFingerprint(a), teamTurnFingerprint(a.copy(
            content = listOf(ContentBlock.Text(prefix, null), ContentBlock.Text("-A", null)))))
        assertEquals(teamTurnFingerprint(a), teamTurnFingerprint(a.copy(usage = null)))
        assertEquals(null, teamTurnFingerprint(a.copy(content = listOf(ContentBlock.Unknown("future", "{}")))))
    }

    /** 与 Web tests/team-chat-presentation.test.ts 执行完全相同的 8 组 JSON 序列。 */
    @Test fun crossPlatformSnapshotCasesKeepAndInvalidateTheSameOwners() {
        val input = listOf("../tests/fixtures", "../../tests/fixtures")
            .map { File(it, "team-chat-presentation-cases.json") }
            .firstOrNull(File::isFile)
        // 集成检出时与主仓可跟踪 fixture 逐例对齐；Android 独立检出时运行同值嵌入样例。
        val suites = listOfNotNull(input?.let { JSONObject(it.readText()) }, JSONObject(embeddedPresentationCases))
        for (suite in suites) {
        val fixtures = suite.getJSONObject("fixtures")
        val cases = suite.getJSONArray("cases")
        fun fixture(name: String): ConversationTurn = ConversationTurn.parse(fixtures.getJSONObject(name))
        fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
        repeat(cases.length()) { caseIndex ->
            val scenario = cases.getJSONObject(caseIndex)
            val initial = scenario.get("initial")
            var turns = if (initial is JSONArray) strings(initial).map(::fixture)
                else (0 until (initial as JSONObject).getInt("generate")).map { turn("window-$it") }
            var current = projectTeamTurns(null, scenario.getString("scope"), turns)
            var owner: String? = if (scenario.has("openOwner")) {
                "${current.scope}/${current.rows[scenario.getInt("openOwner")].presentationId}"
            } else null
            val steps = scenario.getJSONArray("steps")
            repeat(steps.length()) { stepIndex ->
                val step = steps.getJSONObject(stepIndex)
                val before = current
                if (step.getBoolean("successful")) {
                    val next = step.get("next")
                    turns = if (next is JSONArray) strings(next).map(::fixture)
                        else (next as JSONObject).let {
                            turns.drop(it.getInt("dropHead")) + fixture(it.getString("append"))
                        }
                    current = projectTeamTurns(current, step.optString("scope", scenario.getString("scope")), turns)
                }
                val expect = step.getJSONObject("expect")
                val previousOwners = before.rows.map { "${before.scope}/${it.presentationId}" }.toSet()
                fun indexes(key: String) = (0 until expect.getJSONArray(key).length()).map(expect.getJSONArray(key)::getInt)
                indexes("retained").forEach { i ->
                    assertTrue("${scenario.getString("id")}: $i retained",
                        "${current.scope}/${current.rows[i].presentationId}" in previousOwners)
                }
                indexes("rebound").forEach { i ->
                    assertFalse("${scenario.getString("id")}: $i rebound",
                        "${current.scope}/${current.rows[i].presentationId}" in previousOwners)
                }
                val candidates = if (step.getBoolean("successful")) current.candidates.map { id ->
                    current.rows.indexOfFirst { it.presentationId == id }
                } else emptyList()
                assertEquals("${scenario.getString("id")}: candidates", indexes("candidates"), candidates)
                val closeOwner = owner != null && current.rows.none { "${current.scope}/${it.presentationId}" == owner }
                assertEquals("${scenario.getString("id")}: owner close", expect.getBoolean("closeOwner"), closeOwner)
                if (closeOwner) owner = null
            }
        }
        }
    }
}
