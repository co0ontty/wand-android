package com.wand.app.ui

import com.wand.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KnowledgeControllerTest {
    private fun document(scope: KnowledgeScope = KnowledgeScope.Project, owner: String = "p", revision: Int = 1) =
        KnowledgeDocument("d-$owner", scope, owner, "协作方式", "# 工作\n保持输入", "约定", revision, "2026-10-27T00:00:00Z", "2026-10-27T00:00:00Z")
    private inner class Port : KnowledgePort {
        var reads = mutableListOf<Pair<KnowledgeScope, String>>()
        var saves = mutableListOf<Pair<String, KnowledgeDraft>>()
        var deletes = mutableListOf<KnowledgeDocument>()
        var failure: Exception? = null
        var pendingRead: CompletableDeferred<KnowledgeLibrary>? = null
        var pendingSave: CompletableDeferred<KnowledgeDocument>? = null
        var readOnly = false
        var wrongOwner = false
        var readRevision = 1
        override suspend fun knowledgeOwners() = KnowledgeOwners(listOf(KnowledgeOwner("a", "甲"), KnowledgeOwner("b", "乙")), listOf(KnowledgeOwner("p", "项目")))
        override suspend fun knowledgeLibrary(scope: KnowledgeScope, ownerId: String): KnowledgeLibrary {
            reads += scope to ownerId
            pendingRead?.let { return withContext(NonCancellable) { it.await() } }
            return KnowledgeLibrary(scope, ownerId, ownerId, readOnly, listOf(document(scope, ownerId, readRevision)), 1, 200)
        }
        override suspend fun createKnowledge(scope: KnowledgeScope, ownerId: String, draft: KnowledgeDraft): KnowledgeDocument {
            saves += ownerId to draft
            failure?.let { throw it }
            pendingSave?.let { return withContext(NonCancellable) { it.await() } }
            return document(scope, if (wrongOwner) "other" else ownerId).copy(title = draft.title, content = draft.content)
        }
        override suspend fun updateKnowledge(document: KnowledgeDocument, draft: KnowledgeDraft): KnowledgeDocument {
            saves += document.ownerId to draft
            failure?.let { throw it }
            pendingSave?.let { return withContext(NonCancellable) { it.await() } }
            return document.copy(title = draft.title, content = draft.content, folder = draft.folder, revision = document.revision + 1)
        }
        override suspend fun deleteKnowledge(document: KnowledgeDocument) { deletes += document; failure?.let { throw it } }
    }

    @Test fun ownershipScopeAndSearchAreIndependent() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        assertEquals("p", c.ownerId)
        c.selectScope(KnowledgeScope.Personal); runCurrent(); assertEquals("a", c.ownerId)
        c.selectOwner("b"); runCurrent(); assertEquals("b", c.library?.ownerId)
        c.updateQuery("保持输入"); assertEquals(1, c.visibleDocuments.size)
        c.updateQuery("没有内容"); assertTrue(c.visibleDocuments.isEmpty())
        c.updateQuery(""); assertEquals(1, c.visibleDocuments.size)
        assertEquals(listOf(KnowledgeScope.Project to "p", KnowledgeScope.Personal to "a", KnowledgeScope.Personal to "b"), port.reads)
    }

    @Test fun ownerAndRootNavigationRequireExplicitDiscard() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.selectScope(KnowledgeScope.Personal); runCurrent(); c.startNew()
        c.updateDraft(KnowledgeDraft("未保存", "正文")); c.selectOwner("b")
        assertTrue(c.confirmingDiscard); assertEquals("a", c.ownerId)
        c.keepEditing(); assertEquals("正文", c.draft.content)
        var left = false; c.guard { left = true }; assertFalse(left)
        c.discardAndContinue(); assertTrue(left); assertFalse(c.dirty)
    }

    @Test fun successfulEditReturnsToReadingTheServerRevision() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.selectDocument(c.library!!.entries.first()); c.edit(); c.updateDraft(c.draft.copy(content = "新正文"))
        c.save(); c.save(); runCurrent()
        assertEquals(1, port.saves.size); assertFalse(c.editing)
        assertEquals("新正文", c.selected?.content); assertEquals(2, c.selected?.revision); assertEquals("已保存", c.notice)
    }

    @Test fun conflictKeepsDraftAndRereadRequiresDiscard() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.selectDocument(c.library!!.entries.first()); c.edit(); c.updateDraft(c.draft.copy(content = "我的修改"))
        port.failure = WandApiException(409, "冲突"); c.save(); runCurrent()
        assertTrue(c.conflict); assertTrue(c.editing); assertEquals("我的修改", c.draft.content)
        c.save(); runCurrent(); assertEquals(1, port.saves.size)
        port.readRevision = 7; c.readLatest(); runCurrent()
        assertEquals("我的修改", c.draft.content); assertTrue(c.editing); assertTrue(c.latestRead)
        assertEquals(7, c.latestDocument?.revision); assertEquals(1, c.selected?.revision)
        c.reload(); assertTrue(c.confirmingDiscard); assertEquals(2, port.reads.size)
        c.discardAndContinue(); runCurrent(); assertFalse(c.editing); assertFalse(c.conflict); assertEquals(3, port.reads.size)
    }

    @Test fun networkFailureKeepsDraftAndNeverAutomaticallyRetries() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.startNew(); c.updateDraft(KnowledgeDraft("标题", "正文")); port.failure = WandApiException(null, "网络错误")
        c.save(); runCurrent(); assertTrue(c.editing); assertTrue(c.dirty); assertEquals(1, port.saves.size)
        assertEquals("正文", c.draft.content); assertNotNull(c.error)
    }

    @Test fun mismatchedCreateReceiptDoesNotClaimSaved() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.startNew(); c.updateDraft(KnowledgeDraft("标题", "正文")); port.wrongOwner = true
        c.save(); runCurrent(); assertTrue(c.editing); assertTrue(c.dirty); assertNull(c.notice)
        assertTrue(c.error!!.contains("未确认")); assertEquals("p", c.library?.ownerId)
    }

    @Test fun lateReadCannotReplaceAnotherEmployee() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        val pending = CompletableDeferred<KnowledgeLibrary>(); port.pendingRead = pending
        c.selectScope(KnowledgeScope.Personal); runCurrent()
        port.pendingRead = null; c.selectOwner("b"); runCurrent()
        pending.complete(KnowledgeLibrary(KnowledgeScope.Personal, "a", "甲", false, listOf(document(KnowledgeScope.Personal,"a")),1,200)); runCurrent()
        assertEquals("b", c.ownerId); assertEquals("b", c.library?.ownerId)
    }

    @Test fun serverDisposalInvalidatesLateSaveAndRetainsUnconfirmedDraft() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.startNew(); c.updateDraft(KnowledgeDraft("标题", "正文"))
        val pending = CompletableDeferred<KnowledgeDocument>(); port.pendingSave = pending
        c.save(); runCurrent(); c.dispose(); pending.complete(document()); runCurrent()
        assertTrue(c.editing); assertEquals("正文", c.draft.content); assertNull(c.notice)
    }

    @Test fun readonlyProjectAllowsConfirmedDeleteButNoCreateOrEdit() = runTest {
        val port = Port(); port.readOnly = true
        val c = KnowledgeController(port, this); c.open(); runCurrent(); assertFalse(c.canCreate)
        c.startNew(); assertFalse(c.editing)
        c.selectDocument(c.library!!.entries.first()); c.edit(); assertFalse(c.editing)
        c.deleteConfirmed(); assertTrue(port.deletes.isEmpty())
        c.requestDelete(); c.deleteConfirmed(); runCurrent()
        assertEquals("p", port.deletes.single().ownerId); assertEquals(1, port.deletes.single().revision)
        assertTrue(c.library!!.entries.isEmpty()); assertNull(c.selected)
    }

    @Test fun failedDeleteRetainsDocumentAndRevision() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.selectDocument(c.library!!.entries.first()); port.failure = WandApiException(409, "冲突")
        c.requestDelete(); c.deleteConfirmed(); runCurrent()
        assertEquals(1, c.library!!.entries.size); assertNotNull(c.selected); assertTrue(c.conflict)
    }

    @Test fun validatesDocumentBeforeAnyWrite() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent(); c.startNew()
        c.save(); runCurrent(); assertTrue(port.saves.isEmpty()); assertNotNull(c.error)
        c.updateDraft(KnowledgeDraft("标题", "文".repeat(12_001))); c.save(); runCurrent(); assertTrue(port.saves.isEmpty())
        c.updateDraft(KnowledgeDraft("标题", "文".repeat(12_000))); c.save(); runCurrent(); assertEquals(1, port.saves.size)
    }

    @Test fun returningFromConversationRefreshesKnowledgeWithoutReplacingAnOpenDraft() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        port.readRevision = 2; c.open(); runCurrent()
        assertEquals(2, c.library?.entries?.single()?.revision)
        c.selectDocument(c.library!!.entries.first()); c.edit(); c.updateDraft(c.draft.copy(content = "还未保存"))
        c.open(); runCurrent()
        assertEquals(2, port.reads.size); assertEquals("还未保存", c.draft.content)
    }

    @Test fun endingConflictedEditUsesLatestVersionAndNeverShowsItInAnotherDraft() = runTest {
        val port = Port(); val c = KnowledgeController(port, this); c.open(); runCurrent()
        c.selectDocument(c.library!!.entries.first()); c.edit(); c.updateDraft(c.draft.copy(content = "我的修改"))
        port.failure = WandApiException(409, "冲突"); c.save(); runCurrent()
        port.readRevision = 8; c.readLatest(); runCurrent()
        c.cancelEditing(); assertTrue(c.confirmingDiscard); c.discardAndContinue()
        assertEquals(8, c.selected?.revision); assertNull(c.latestDocument)
        c.startNew(); assertTrue(c.editing); assertNull(c.latestDocument); assertFalse(c.latestRead)
    }
}
