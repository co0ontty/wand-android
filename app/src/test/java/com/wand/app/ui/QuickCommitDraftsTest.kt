package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷提交表单草稿的转换规则（设计规格 §2.22 S12）：关闭面板不是清空理由，
 * 只有提交成功才是；AI 生成只允许补空白位，不得覆写用户已输入的内容。
 *
 * `QuickCommitStore` 本身在 JVM 单测里构造不了（`openPanel()` 走 `SystemClock`，
 * scope 用 `Dispatchers.Main.immediate`），所以这套规则单独抽成 [QuickCommitDrafts]
 * 纯类型来覆盖；开合路径由真机验收。
 */
class QuickCommitDraftsTest {

    @Test
    fun typedDraftsSurviveAiRegeneration() {
        val drafts = QuickCommitDrafts()
            .onMessageTyped("修复快捷提交丢草稿")
            .onTagTyped("v1.2.3")
            .onAiGenerated("AI 又写了一份", "v9.9.9")

        assertEquals("修复快捷提交丢草稿", drafts.messageDraft)
        assertEquals("v1.2.3", drafts.tagDraft)
    }

    @Test
    fun aiFillsBlankSlotsOnlyAndDoesNotClaimAuthorship() {
        val drafts = QuickCommitDrafts().onAiGenerated("  自动文案  ", " v2.0.0 ")

        assertEquals("自动文案", drafts.messageDraft)
        assertEquals("v2.0.0", drafts.tagDraft)
        // AI 填进去的 tag 仍允许被下一次推荐覆盖：只有用户敲过的才算「改过」。
        assertFalse(drafts.tagEdited)
    }

    @Test
    fun userEditedTagIsNeverOverwrittenByAI() {
        val typed = QuickCommitDrafts().onTagTyped("v1.0.0-rc1")

        assertTrue(typed.tagEdited)
        assertEquals("v1.0.0-rc1", typed.onAiGenerated("自动文案", "v2.0.0").tagDraft)
    }

    @Test
    fun clearingTheTagByHandStillBlocksAI() {
        // 用户把 tag 删空 = 明确表达「这次不打 tag」，AI 推荐不能把它塞回去。
        val emptied = QuickCommitDrafts().onTagTyped("v1.0.0").onTagTyped("")

        assertTrue(emptied.tagEdited)
        assertEquals("", emptied.onAiGenerated("自动文案", "v2.0.0").tagDraft)
    }

    @Test
    fun aiWithoutContentKeepsTheExistingDraft() {
        val drafts = QuickCommitDrafts(messageDraft = "用户写的")
            .onAiGenerated("   ", "")

        assertEquals("用户写的", drafts.messageDraft)
        assertEquals("", drafts.tagDraft)
    }

    @Test
    fun commitSuccessIsTheOnlyTransitionThatEmptiesTheForm() {
        val filled = QuickCommitDrafts(
            messageDraft = "已落地的文案",
            tagDraft = "v1.2.3",
            tagEdited = true,
        )

        // 提交成功后重开必须是空表单（§2.22 验收 6），tagEdited 也一起归零，
        // 否则下一次推荐会被这一笔的编辑历史挡住。
        assertEquals(QuickCommitDrafts(), filled.onCommitSuccess())
    }
}
