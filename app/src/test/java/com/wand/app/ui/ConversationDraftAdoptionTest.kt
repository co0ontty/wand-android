package com.wand.app.ui

import com.wand.app.data.UploadedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDraftAdoptionTest {
    private val sourceId = "conversation:::talk"
    private val aId = "conversation:dm_a::talk"
    private val bId = "conversation:dm_b::talk"
    private val file = UploadedFile("draft.txt", "/uploads/draft.txt", 3, "text/plain")
    private fun TestScope.composer(store: SessionDraftStore, id: String) =
        ChatComposer(id, store, backgroundScope, ready = { id != sourceId }, send = {}, notice = {})

    @Test fun explicitAdoptionMovesTextAndAttachmentsExactlyOnceAndNeverSends() = runTest {
        val store = SessionDraftStore(mapOf(sourceId to "待接收正文"))
        store.setAttachments(sourceId, listOf(file))
        val source = composer(store, sourceId); val target = composer(store, aId)
        val offer = target.pendingDraftAdoption(source)!!
        assertEquals("", target.draft) // Merely choosing or reading the employee does not adopt.
        assertTrue(target.adoptDraftFrom(source, offer))
        assertEquals("待接收正文", target.draft); assertEquals(listOf(file), target.attachments)
        assertEquals("", source.draft); assertTrue(source.attachments.isEmpty())
        assertFalse(target.adoptDraftFrom(source, offer)); assertEquals(SendPhase.Idle, target.sendPhase)
        assertEquals(mapOf(aId to "待接收正文"), store.savedDrafts())
        assertEquals(listOf(file), store.savedAttachments()[aId])
    }

    @Test fun attachmentsOnlyDraftHasAnExplicitOffer() = runTest {
        val store = SessionDraftStore(); store.setAttachments(sourceId, listOf(file))
        val source = composer(store, sourceId); val target = composer(store, aId)
        assertTrue(target.adoptDraftFrom(source, target.pendingDraftAdoption(source)!!))
        assertEquals(listOf(file), target.attachments); assertTrue(source.attachments.isEmpty())
    }

    @Test fun staleSourceAndTargetRevisionsEvenAfterClearCannotOverwrite() = runTest {
        val store = SessionDraftStore(mapOf(sourceId to "first"))
        val source = composer(store, sourceId); val target = composer(store, aId)
        val staleSource = target.pendingDraftAdoption(source)!!
        source.editDraft("new source")
        assertFalse(target.adoptDraftFrom(source, staleSource))
        val staleTarget = target.pendingDraftAdoption(source)!!
        target.editDraft("target edited"); target.editDraft("")
        assertFalse(target.adoptDraftFrom(source, staleTarget))
        assertEquals("new source", source.draft); assertEquals("", target.draft)
    }

    @Test fun existingTextOrAttachmentsAlwaysWinOverPendingDraft() = runTest {
        val store = SessionDraftStore(mapOf(sourceId to "pending", aId to "own draft"))
        val source = composer(store, sourceId); val target = composer(store, aId)
        assertNull(target.pendingDraftAdoption(source)); assertEquals("own draft", target.draft)
        target.editDraft(""); store.setAttachments(aId, listOf(file))
        assertNull(target.pendingDraftAdoption(source)); assertEquals(listOf(file), target.attachments)
        assertEquals("pending", source.draft)
    }

    @Test fun switchingPeerAndAsyncDefaultPartnerReadCannotUseAnotherTargetsOffer() = runTest {
        val store = SessionDraftStore(mapOf(sourceId to "pending"))
        val source = composer(store, sourceId); val a = composer(store, aId); val b = composer(store, bId)
        val offerForA = a.pendingDraftAdoption(source)!!
        assertFalse(b.adoptDraftFrom(source, offerForA))
        assertEquals("pending", source.draft); assertEquals("", a.draft); assertEquals("", b.draft)
        assertTrue(b.adoptDraftFrom(source, b.pendingDraftAdoption(source)!!))
        assertFalse(a.adoptDraftFrom(source, offerForA)); assertEquals("pending", b.draft)
    }

    @Test fun inFlightUploadAndLateResultCannotCrossAnAdoptionRevision() = runTest {
        val store = SessionDraftStore(mapOf(sourceId to "pending"))
        val source = composer(store, sourceId); val target = composer(store, aId)
        val offer = target.pendingDraftAdoption(source)!!
        val upload = CompletableDeferred<List<UploadedFile>>()
        source.upload { upload.await() }; runCurrent()
        assertFalse(target.adoptDraftFrom(source, offer))
        source.editDraft("changed during upload")
        upload.complete(listOf(file)); runCurrent()
        assertTrue(source.attachments.isEmpty())
        assertFalse(target.adoptDraftFrom(source, offer)); assertEquals("", target.draft)
        assertTrue(target.adoptDraftFrom(source, target.pendingDraftAdoption(source)!!))
        assertEquals("changed during upload", target.draft)
    }

    @Test fun asyncDefaultPartnerReadOnlySelectsAndLateOfferCannotMoveToAnotherPeer() = runTest {
        var preferences = "{}"
        val drafts = SessionDraftStore()
        val state = ConversationStore(com.wand.app.data.WandApi("http://127.0.0.1:1", null), drafts, backgroundScope,
            { preferences }, { preferences = it })
        val unaddressed = state.composer("", null)
        val directoryRead = CompletableDeferred<String>()
        val reading = backgroundScope.launch { state.select(directoryRead.await()) }
        runCurrent()
        unaddressed.editDraft("联系人读取期间的草稿")
        drafts.setAttachments(unaddressed.sessionId, listOf(file))
        directoryRead.complete("dm_e_wand_default"); runCurrent()
        val default = state.composer(state.selectedId, null)
        assertEquals("", default.draft); assertTrue(default.attachments.isEmpty())
        assertEquals("联系人读取期间的草稿", unaddressed.draft)
        val oldOffer = default.pendingDraftAdoption(unaddressed)!!
        state.select("dm_other")
        val current = state.composer(state.selectedId, null)
        assertFalse(current.adoptDraftFrom(unaddressed, oldOffer))
        assertTrue(current.adoptDraftFrom(unaddressed, current.pendingDraftAdoption(unaddressed)!!))
        assertEquals("联系人读取期间的草稿", current.draft); assertEquals(listOf(file), current.attachments)
        assertFalse(default.adoptDraftFrom(unaddressed, oldOffer))
        reading.cancel(); state.shutdown()
    }

    @Test fun uncertainContentAndDisposedOwnersCannotBeAdopted() = runTest {
        val store = SessionDraftStore(mapOf(sourceId to "unknown"))
        val source = composer(store, sourceId); val target = composer(store, aId)
        val offer = target.pendingDraftAdoption(source)!!
        val capture = store.submission(sourceId)
        assertNull(target.pendingDraftAdoption(source)); assertFalse(target.adoptDraftFrom(source, offer))
        store.rejected(sourceId, capture)
        val current = target.pendingDraftAdoption(source)!!
        source.shutdown()
        assertFalse(target.adoptDraftFrom(source, current)); assertEquals("unknown", store[sourceId])
    }
}
