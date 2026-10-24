package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.ui.ViewerImagePage
import com.wand.app.ui.conversationImagePages
import com.wand.app.ui.imageGallerySelection
import org.junit.Assert.*
import org.junit.Test

class MarkdownImagesTest {
    @Test fun ordinaryImageLinksPreviewByDestinationWithoutOptIn() {
        for ((label, source) in listOf("查看优化效果" to "/work/preview.png", "截图" to "/work/preview%2Epng",
            "下载" to "https://host.test/preview.JPG?size=2#view", "原图" to "/api/file-raw?path=%2Fwork%2Fpreview.png")) {
            assertEquals(listOf(MarkdownImagePiece.Text("前文 "), MarkdownImagePiece.Image(label, source), MarkdownImagePiece.Text(" 后文")),
                splitMarkdownImages("前文 [$label]($source) 后文"))
        }
        for (raw in listOf("[图片](/work/notes.md)", "[图片](https://host.test/page)", "[图片](javascript:bad.png)",
            "[图片](//host.test/a.png)", "[图片](../a.png)", "`[预览](/work/a.png)`", "\\![例](/a.png)", "\\[例](/a.png)")) {
            assertEquals(listOf(MarkdownImagePiece.Text(raw)), splitMarkdownImages(raw))
        }
        val delivery = "[查看优化效果](</work/a (2).png>)\n\n[记录](/work/log.md)\n\n```md\n[示例](/work/code.png)\n```"
        assertEquals(listOf(ViewerImagePage("/work/a (2).png", false)), markdownImagePages(delivery))
    }

    @Test fun imagesPreserveSurroundingTextAndOrder() {
        assertEquals(listOf(MarkdownImagePiece.Text("前文 "), MarkdownImagePiece.Image("聊天详情", "/Users/test/chat.png"),
            MarkdownImagePiece.Text(" 后文 "), MarkdownImagePiece.Image("任务", "https://host.test/tasks.png")),
            splitMarkdownImages("前文 ![聊天详情](/Users/test/chat.png) 后文 ![任务](https://host.test/tasks.png)"))
    }

    @Test fun destinationsSupportSpacesParenthesesEscapesAndTitles() {
        assertEquals(listOf(MarkdownImagePiece.Image("页[二]", "/work/a (2).png")),
            splitMarkdownImages("![页[二]](</work/a (2).png> \"截图\")"))
        assertEquals(listOf(MarkdownImagePiece.Image("A]", "/work/a(b).png")),
            splitMarkdownImages("![A\\]](/work/a(b).png '截图')"))
        assertEquals(listOf(MarkdownImagePiece.Image("", "/work/a b.png")), splitMarkdownImages("![](/work/a\\ b.png)"))
    }

    @Test fun codeEscapedAndIncompleteSyntaxStayText() {
        for (raw in listOf("`![例](/a.png)`", "`` `![例](/a.png)` ``", "\\![例](/a.png)", "![未结束](/a.png", "[普通链接](/a.txt)")) {
            assertEquals(listOf(MarkdownImagePiece.Text(raw)), splitMarkdownImages(raw))
        }
        assertEquals(1, splitMarkdownImages("`未配对 ![图](/a.png)").filterIsInstance<MarkdownImagePiece.Image>().size)
    }

    @Test fun unsafeOrUnboundSourcesAreNotFetched() {
        for (source in listOf("//other.test/a.png", "file:///tmp/a.png", "javascript:alert(1)", "content://a", "data:text/html;base64,AA", "../a.png", "a.png", "https://u:p@host.test/a.png", "/a\nb.png")) {
            assertNull(source, markdownImagePage(source))
        }
    }

    @Test fun sourceModesPreserveServerPathsAndQueries() {
        assertEquals(ViewerImagePage("/Users/test/a + b.png", false), markdownImagePage("/Users/test/a%20+%20b.png"))
        assertEquals(ViewerImagePage("https://host.test/a?size=2#preview", true), markdownImagePage("https://host.test/a?size=2#preview"))
        assertEquals(ViewerImagePage("/api/file-raw?path=%2Ftmp%2Fa.png", true), markdownImagePage("/api/file-raw?path=%2Ftmp%2Fa.png"))
        assertEquals(ViewerImagePage("data:image/png;base64,AA", true), markdownImagePage("data:image/png;base64,AA"))
    }

    @Test fun galleryUsesRenderedBlocksAndExcludesCode() {
        val text = "# 截图\n\n![聊天](/work/chat.png)\n\n- ![任务](/work/tasks.png)\n\n> ![设置](https://host.test/settings.png)\n\n```md\n![代码](/not.png)\n```\n\n`![例](/inline.png)`"
        val expected = listOf(ViewerImagePage("/work/chat.png", false), ViewerImagePage("/work/tasks.png", false), ViewerImagePage("https://host.test/settings.png", true))
        assertEquals(expected, markdownImagePages(text))
        val pages = conversationImagePages(listOf(ConversationTurn("assistant", listOf(ContentBlock.Text(text, null)))))
        assertEquals(expected, pages)
        assertEquals(1, imageGallerySelection(pages, emptyList(), "/work/tasks.png", false).startIndex)
    }

    @Test fun actualFivePageReplyHasFiveGalleryEntries() {
        val text = listOf("chat", "tasks", "workspace", "contacts", "settings").joinToString("\n\n") {
            "![$it](/Users/test/wand/output/architecture-stage2/duo-adaptation-analysis/key-pages/$it.png)"
        }
        assertEquals(5, markdownImagePages(text).size)
    }
}
