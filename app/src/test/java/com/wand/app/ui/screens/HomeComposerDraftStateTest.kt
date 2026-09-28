package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeComposerDraftStateTest {
    @Test
    fun dialogEditsReturnToHomeWhenCreationIsCancelled() {
        val draft = HomeComposerDraftState()
        draft.edit("第一行\n第二行")
        draft.beginHandoff(1)
        draft.editInDialog(1, "第一行\n第二行\n第三行")

        draft.finishHandoff(1)

        assertEquals("第一行\n第二行\n第三行", draft.text)
        assertNull(draft.revisionFor(1))
    }

    @Test
    fun successfulCreationClearsOnlyTheSubmittedRevision() {
        val draft = HomeComposerDraftState()
        draft.edit("原任务")
        draft.beginHandoff(1)
        // 创建或派发失败时不调用 finishHandoff；用户仍可改写并重试。
        draft.editInDialog(1, "原任务，补充限制")
        assertEquals("原任务，补充限制", draft.text)
        val retryRevision = draft.revisionFor(1)
        assertNotNull(retryRevision)
        draft.finishHandoff(1, retryRevision)

        assertEquals("", draft.text)
    }

    @Test
    fun lateCompletionDoesNotClearNewInputOrAnotherDialog() {
        val draft = HomeComposerDraftState()
        draft.edit("原任务")
        draft.beginHandoff(1)
        val submittedRevision = draft.revisionFor(1)
        draft.edit("新的首页输入")
        draft.finishHandoff(1, submittedRevision)
        assertEquals("新的首页输入", draft.text)

        draft.beginHandoff(2)
        draft.editInDialog(1, "过期结果")
        draft.finishHandoff(1, draft.revisionFor(2))
        assertEquals("新的首页输入", draft.text)
        assertNotNull(draft.revisionFor(2))
    }
}
