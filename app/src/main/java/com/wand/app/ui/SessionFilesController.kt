package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.DirectoryListing
import com.wand.app.data.ServerFilePreview
import com.wand.app.data.SessionFilesPort
import com.wand.app.data.WandApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class FileActionPhase { Idle, Running, Done, Failed, Uncertain }

/** One owner per session/endpoint. Navigation and late responses never replace an unsaved draft. */
internal class SessionFilesController(
    private val api: SessionFilesPort,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var revision = 0L
    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    var open by mutableStateOf(false); private set
    var root by mutableStateOf(""); private set
    var directory by mutableStateOf(""); private set
    var listing by mutableStateOf<DirectoryListing?>(null); private set
    var filePath by mutableStateOf<String?>(null); private set
    var file by mutableStateOf<ServerFilePreview?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var editing by mutableStateOf(false); private set
    var draft by mutableStateOf(""); private set
    var savePhase by mutableStateOf(FileActionPhase.Idle); private set
    var downloadPhase by mutableStateOf(FileActionPhase.Idle); private set
    var saveConflict by mutableStateOf(false); private set
    private var saveMessage by mutableStateOf<String?>(null)
    private var downloadMessage by mutableStateOf<String?>(null)
    val actionMessage: String? get() = if (savePhase in listOf(FileActionPhase.Failed, FileActionPhase.Uncertain)) {
        saveMessage
    } else downloadMessage ?: saveMessage
    var pendingDiscard by mutableStateOf<(() -> Unit)?>(null); private set
    val dirty: Boolean get() = editing && file != null && draft != file?.content
    val busy: Boolean get() = savePhase == FileActionPhase.Running || downloadPhase == FileActionPhase.Running
    val canSave: Boolean get() = dirty && file?.canEdit == true && !busy && !saveConflict && savePhase != FileActionPhase.Uncertain

    fun open(cwd: String) {
        if (cwd.isBlank() || open) return
        root = cwd
        open = true
        loadDirectory(cwd)
    }

    private fun guardNavigation(action: () -> Unit) {
        if (savePhase == FileActionPhase.Running) return
        val navigate = {
            downloadJob?.cancel()
            downloadPhase = FileActionPhase.Idle
            action()
        }
        if (dirty) pendingDiscard = navigate else navigate()
    }

    fun requestClose() = guardNavigation {
        open = false
        revision++
        loadJob?.cancel()
    }

    fun requestDirectory(path: String) = guardNavigation { loadDirectory(path) }
    fun requestFile(path: String) = guardNavigation { loadFile(path) }
    fun requestReload() = guardNavigation {
        filePath?.let(::loadFile) ?: loadDirectory(directory)
    }
    fun requestBack() {
        if (filePath != null) requestDirectory(directory) else requestClose()
    }
    fun cancelDiscard() { pendingDiscard = null }
    fun confirmDiscard() {
        val action = pendingDiscard ?: return
        pendingDiscard = null
        action()
    }

    private fun resetFile() {
        file = null
        editing = false
        draft = ""
        savePhase = FileActionPhase.Idle
        downloadPhase = FileActionPhase.Idle
        saveConflict = false
        saveMessage = null
        downloadMessage = null
        error = null
    }

    private fun loadDirectory(path: String) {
        val request = ++revision
        loadJob?.cancel()
        directory = path
        filePath = null
        listing = null
        resetFile()
        loading = true
        loadJob = scope.launch {
            try {
                val loaded = api.listDirectory(path)
                if (open && request == revision) listing = loaded
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (open && request == revision) error = failure.message ?: "目录读取失败，请重试"
            } finally {
                if (request == revision) loading = false
            }
        }
    }

    private fun loadFile(path: String) {
        val request = ++revision
        loadJob?.cancel()
        resetFile()
        filePath = path
        loading = true
        loadJob = scope.launch {
            try {
                val loaded = api.previewFile(path)
                if (open && request == revision) {
                    file = loaded
                    draft = loaded.content.orEmpty()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (open && request == revision) error = failure.message ?: "文件读取失败，请重试或下载"
            } finally {
                if (request == revision) loading = false
            }
        }
    }

    fun toggleEditing() {
        if (busy || file?.canEdit != true) return
        if (editing) guardNavigation {
            editing = false
            draft = file?.content.orEmpty()
        } else editing = true
    }

    fun edit(value: String) {
        if (!editing || busy) return
        draft = value
        // An unknown acknowledgement must be reconciled by reading, not another write.
        if (savePhase != FileActionPhase.Uncertain && !saveConflict) {
            savePhase = FileActionPhase.Idle
            saveMessage = null
        }
    }

    fun save() {
        if (!canSave) return
        val original = file ?: return
        val submitted = draft
        val request = revision
        savePhase = FileActionPhase.Running
        saveMessage = null
        downloadMessage = null
        scope.launch {
            try {
                val result = api.writeFile(original, submitted)
                if (open && request == revision) {
                    file = original.copy(content = submitted, size = result.size, mtime = result.mtime)
                    savePhase = FileActionPhase.Done
                    saveMessage = "已保存到服务器"
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (open && request == revision) {
                    val status = (failure as? WandApiException)?.status
                    savePhase = if (status == null || status == 408 || status >= 500) {
                        FileActionPhase.Uncertain
                    } else FileActionPhase.Failed
                    saveConflict = status == 409
                    saveMessage = when {
                        status == 409 -> "文件已被外部修改，草稿仍保留。请复制草稿后重新读取文件。"
                        savePhase == FileActionPhase.Uncertain -> "保存结果未确认，草稿仍保留。请复制草稿并重新读取核对，不要重复提交。"
                        else -> failure.message ?: "保存失败，草稿仍保留，请重试"
                    }
                }
            }
        }
    }

    fun download(downloader: suspend (String) -> String) {
        val path = filePath ?: return
        if (busy || loading) return
        val request = revision
        downloadPhase = FileActionPhase.Running
        downloadMessage = null
        downloadJob = scope.launch {
            try {
                val name = downloader(path)
                if (open && request == revision) {
                    downloadPhase = FileActionPhase.Done
                    downloadMessage = "已保存到 下载/Wand/$name"
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (open && request == revision) {
                    downloadPhase = FileActionPhase.Failed
                    downloadMessage = failure.message ?: "下载失败，请重试"
                }
            }
        }
    }

    fun shutdown() {
        open = false
        revision++
        scope.cancel()
    }
}
