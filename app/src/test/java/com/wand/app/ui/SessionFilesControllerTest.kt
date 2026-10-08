package com.wand.app.ui

import com.wand.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFilesControllerTest {
    private val text = ServerFilePreview("/repo/笔记 + #?.md", "笔记 + #?.md", "text", 3, "mtime-1", "old")
    private class Port : SessionFilesPort {
        val directories = mutableListOf<String>()
        val previews = mutableListOf<String>()
        val writes = mutableListOf<Pair<ServerFilePreview, String>>()
        var listing = DirectoryListing(listOf(DirectoryItem("/repo/a", "a", "file", 3)), false)
        var preview = ServerFilePreview("/repo/a", "a", "text", 3, "mtime-1", "old")
        var directoryReader: (suspend (String) -> DirectoryListing)? = null
        var fileReader: (suspend (String) -> ServerFilePreview)? = null
        var writer: (suspend () -> ServerFileWriteResult)? = null
        var failure: Exception? = null
        override suspend fun listDirectory(path: String): DirectoryListing {
            directories += path
            return directoryReader?.invoke(path) ?: listing
        }
        override suspend fun previewFile(path: String): ServerFilePreview {
            previews += path
            failure?.let { throw it }
            return fileReader?.invoke(path) ?: preview.copy(path = path)
        }
        override suspend fun writeFile(file: ServerFilePreview, content: String): ServerFileWriteResult {
            writes += file to content
            failure?.let { throw it }
            return writer?.invoke() ?: ServerFileWriteResult(content.toByteArray().size.toLong(), "mtime-2")
        }
    }

    @Test fun opensAtSessionCwdWithoutGitAndSupportsParentAndRootNavigation() = runTest {
        val port = Port()
        val files = SessionFilesController(port, backgroundScope)
        files.open("")
        assertFalse(files.open)
        files.open("/repo")
        runCurrent()
        assertEquals(listOf("/repo"), port.directories)
        assertEquals("/repo", files.root)
        files.requestDirectory("/repo/sub")
        runCurrent()
        assertEquals("/repo/sub", files.directory)
        assertEquals("/repo", files.root)
        files.requestClose()
        files.open("/new-cwd")
        runCurrent()
        assertEquals("/new-cwd", files.directory)
    }

    @Test fun lateDirectoryCannotReplaceNewerLocation() = runTest {
        val stale = CompletableDeferred<DirectoryListing>()
        val port = Port().apply {
            directoryReader = { path -> if (path == "/old") withContext(NonCancellable) { stale.await() } else listing }
        }
        val files = SessionFilesController(port, backgroundScope)
        files.open("/old")
        runCurrent()
        files.requestDirectory("/new")
        runCurrent()
        stale.complete(DirectoryListing(listOf(DirectoryItem("/old/stale", "stale", "file")), false))
        runCurrent()
        assertEquals("/new", files.directory)
        assertEquals("a", files.listing!!.items.single().name)
        assertFalse(files.loading)
    }

    @Test fun lateFileCannotReplaceNewFileOrClosedSession() = runTest {
        val stale = CompletableDeferred<ServerFilePreview>()
        val port = Port().apply { fileReader = { path ->
            if (path == "/old") withContext(NonCancellable) { stale.await() } else preview.copy(path = path)
        } }
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile("/old"); runCurrent()
        files.requestFile("/new"); runCurrent()
        stale.complete(text.copy(path = "/old")); runCurrent()
        assertEquals("/new", files.file!!.path)
        files.shutdown()
        assertFalse(files.open)
    }

    @Test fun directoryAndFileErrorsRemainRetryableAndUnpreviewableFilesRemainDownloadable() = runTest {
        val port = Port().apply { directoryReader = { throw WandApiException(403, "目录无权限") } }
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        assertEquals("目录无权限", files.error)
        assertNull(files.listing)
        port.directoryReader = null
        files.requestReload(); runCurrent()
        assertNotNull(files.listing)
        port.failure = WandApiException(413, "文件太大")
        files.requestFile("/repo/large.log"); runCurrent()
        assertEquals("文件太大", files.error)
        assertEquals("/repo/large.log", files.filePath)
        files.download { "large.log" }; runCurrent()
        assertEquals(FileActionPhase.Done, files.downloadPhase)
        assertTrue(files.actionMessage!!.contains("下载/Wand/large.log"))
    }

    @Test fun dirtyNavigationRequiresExplicitDiscardAndCancelKeepsDraft() = runTest {
        val files = SessionFilesController(Port(), backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("new")
        files.requestBack()
        assertNotNull(files.pendingDiscard)
        assertEquals(text.path, files.filePath)
        files.cancelDiscard()
        assertEquals("new", files.draft)
        files.requestDirectory("/next")
        files.confirmDiscard(); runCurrent()
        assertEquals("/next", files.directory)
        assertFalse(files.editing)
        assertFalse(files.dirty)
    }

    @Test fun dirtyCloseReloadAndCancelEditAreAllGuarded() = runTest {
        val port = Port()
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("new")
        for (navigate in listOf(files::requestClose, files::requestReload, files::toggleEditing)) {
            navigate()
            assertNotNull(files.pendingDiscard)
            assertTrue(files.open)
            assertEquals("new", files.draft)
            files.cancelDiscard()
        }
        files.requestClose(); files.confirmDiscard()
        assertFalse(files.open)
    }

    @Test fun saveUsesPreviewSnapshotAndPreventsDuplicateWritesAndNavigationWhileSaving() = runTest {
        val ack = CompletableDeferred<ServerFileWriteResult>()
        val port = Port().apply { preview = text; writer = { ack.await() } }
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("你好\n")
        files.save(); files.save(); runCurrent()
        assertEquals(listOf(text to "你好\n"), port.writes)
        assertFalse(files.canSave)
        files.requestFile("/elsewhere")
        files.requestClose()
        files.edit("ignored while saving")
        assertEquals("你好\n", files.draft)
        assertTrue(files.open)
        ack.complete(ServerFileWriteResult(7, "mtime-2")); runCurrent()
        assertEquals("你好\n", files.file!!.content)
        assertEquals(7L, files.file!!.size)
        assertEquals("mtime-2", files.file!!.mtime)
        assertEquals(FileActionPhase.Done, files.savePhase)
        assertFalse(files.dirty)
        files.edit("next"); files.save(); runCurrent()
        assertEquals("mtime-2", port.writes.last().first.mtime)
    }

    @Test fun conflictDoesNotOverwriteDraftOrPermitBlindRetryAndDownloadCannotHideConflict() = runTest {
        val port = Port()
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("mine")
        port.failure = WandApiException(409, "FILE_CHANGED")
        files.save(); runCurrent()
        assertEquals("mine", files.draft)
        assertEquals("old", files.file!!.content)
        assertTrue(files.saveConflict)
        assertFalse(files.canSave)
        files.edit("still mine"); files.save(); runCurrent()
        assertEquals(1, port.writes.size)
        files.download { "copy.md" }; runCurrent()
        assertTrue(files.actionMessage!!.contains("外部修改"))
        port.failure = null
        files.requestReload()
        assertNotNull(files.pendingDiscard)
        files.confirmDiscard(); runCurrent()
        assertFalse(files.saveConflict)
    }

    @Test fun unknownSaveRequiresReadToReconcileEvenAfterMoreTyping() = runTest {
        for (status in listOf(null, 500, 408)) {
            val port = Port()
            val files = SessionFilesController(port, backgroundScope)
            files.open("/repo"); runCurrent()
            files.requestFile(text.path); runCurrent()
            files.toggleEditing(); files.edit("mine")
            port.failure = WandApiException(status, "unknown")
            files.save(); runCurrent()
            assertEquals(FileActionPhase.Uncertain, files.savePhase)
            files.edit("newer"); files.save(); runCurrent()
            assertEquals(1, port.writes.size)
            assertEquals("newer", files.draft)
            assertFalse(files.canSave)
            files.shutdown()
        }
    }

    @Test fun explicitRejectionKeepsDraftAndCanBeRetried() = runTest {
        val port = Port()
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("mine")
        port.failure = WandApiException(403, "只读")
        files.save(); runCurrent()
        assertEquals(FileActionPhase.Failed, files.savePhase)
        assertEquals("mine", files.draft)
        assertTrue(files.canSave)
        port.failure = null
        files.save(); runCurrent()
        assertEquals(FileActionPhase.Done, files.savePhase)
    }

    @Test fun readonlyFilesAndMissingMetadataCannotBeEdited() = runTest {
        val port = Port()
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        for (preview in listOf(text.copy(kind = "binary"), text.copy(size = null), text.copy(mtime = null), text.copy(content = null))) {
            port.preview = preview
            files.requestFile(preview.path); runCurrent()
            files.toggleEditing(); files.edit("new"); files.save(); runCurrent()
            assertFalse(files.editing)
            assertFalse(files.canSave)
        }
        assertTrue(port.writes.isEmpty())
    }

    @Test fun downloadIsSingleFlightKeepsDraftAndAllowsCancellationViaClose() = runTest {
        val files = SessionFilesController(Port(), backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        val pending = CompletableDeferred<String>()
        var downloads = 0
        val download: suspend (String) -> String = { downloads++; pending.await() }
        files.download(download); files.download(download); runCurrent()
        assertEquals(1, downloads)
        files.requestClose(); runCurrent()
        assertFalse(files.open)
        assertEquals(FileActionPhase.Idle, files.downloadPhase)
        pending.complete("ignored"); runCurrent()
        assertNull(files.actionMessage)
    }

    @Test fun failedDownloadIsRetryableAndDoesNotClearEditor() = runTest {
        val files = SessionFilesController(Port(), backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("mine")
        files.download { throw IllegalStateException("下载失败") }; runCurrent()
        assertEquals(FileActionPhase.Failed, files.downloadPhase)
        assertEquals("mine", files.draft)
        files.download { "a.md" }; runCurrent()
        assertEquals(FileActionPhase.Done, files.downloadPhase)
        assertEquals("mine", files.draft)
    }

    @Test fun shutdownDuringSaveNeverPublishesAnOldAcknowledgement() = runTest {
        val ack = CompletableDeferred<ServerFileWriteResult>()
        val port = Port().apply { writer = { withContext(NonCancellable) { ack.await() } } }
        val files = SessionFilesController(port, backgroundScope)
        files.open("/repo"); runCurrent()
        files.requestFile(text.path); runCurrent()
        files.toggleEditing(); files.edit("mine"); files.save(); runCurrent()
        files.shutdown()
        ack.complete(ServerFileWriteResult(4, "late")); runCurrent()
        assertFalse(files.open)
        assertEquals("mtime-1", files.file!!.mtime)
    }
}
