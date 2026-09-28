package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ComposerSubmission(
    val text: String,
    val attachments: List<UploadedFile>,
    val textRevision: Long,
) {
    val prompt: String get() = attachmentPrompt(attachments, text).trim()
}

/**
 * One session's unsent content, uploads, single-flight submit and inline feedback.
 * ChatStore still owns conversation/queue/protocol state; this module waits only for
 * its transport acknowledgement, never for the model's entire response.
 */
class ChatComposer(
    val sessionId: String,
    private val drafts: SessionDraftStore,
    parentScope: CoroutineScope,
    private val ready: () -> Boolean,
    private val send: suspend (String) -> Unit,
    private val notice: (String) -> Unit,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var active = true
    private var feedbackJob: Job? = null

    val draft: String get() = drafts[sessionId]
    val attachments: List<UploadedFile> get() = drafts.attachments(sessionId)
    var uploading by mutableStateOf(false)
        private set
    var sendPhase by mutableStateOf(SendPhase.Idle)
        private set
    val canSubmit: Boolean get() = active && ready() && !uploading && sendPhase != SendPhase.Sending &&
        (draft.isNotBlank() || attachments.isNotEmpty())

    fun editDraft(text: String) {
        if (active) drafts[sessionId] = text
    }

    fun appendVoice(text: String) {
        if (active && text.isNotBlank()) editDraft(appendComposerVoiceText(draft, text))
    }

    fun removeAttachment(file: UploadedFile) {
        if (active) drafts.setAttachments(sessionId, attachments.filterNot { it.savedPath == file.savedPath })
    }

    /** The upload adapter reads Android URIs and performs multipart I/O, for this fixed session. */
    fun upload(operation: suspend (remainingSlots: Int) -> List<UploadedFile>): Boolean {
        if (!active || uploading) return false
        val remainingSlots = MAX_COMPOSER_ATTACHMENTS - attachments.size
        if (remainingSlots <= 0) {
            notice("最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件，请先移除已有附件")
            return false
        }
        uploading = true
        scope.launch {
            try {
                val uploaded = operation(remainingSlots)
                coroutineContext.ensureActive()
                if (!active) return@launch
                drafts.setAttachments(sessionId, (attachments + uploaded).distinctBy { it.savedPath })
                notice("已上传 ${uploaded.size} 个附件")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) notice(e.message ?: "附件上传失败")
            } finally {
                uploading = false
            }
        }
        return true
    }

    /** Keep content/preview until a definite ack; typing during send belongs to the next draft. */
    fun submit(): Boolean {
        if (!canSubmit) return false
        val submission = drafts.submission(sessionId)
        feedbackJob?.cancel()
        sendPhase = SendPhase.Sending
        scope.launch {
            try {
                send(submission.prompt)
                coroutineContext.ensureActive()
                if (!active) return@launch
                drafts.accepted(sessionId, submission)
                finish(SendPhase.Sent, SEND_SENT_DWELL_MS)
            } catch (e: CancellationException) {
                // Leaving the screen cannot establish whether the server received the input.
                throw e
            } catch (e: Exception) {
                if (!active) return@launch
                if (isDefiniteComposerRejection(e)) drafts.rejected(sessionId, submission)
                finish(SendPhase.Failed, SEND_FAILED_DWELL_MS)
                notice(e.message ?: "发送失败")
            }
        }
        return true
    }

    private fun finish(phase: SendPhase, dwell: Long) {
        sendPhase = phase
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            delay(dwell)
            if (active && sendPhase == phase) sendPhase = SendPhase.Idle
        }
    }

    fun shutdown() {
        active = false
        scope.cancel()
    }
}

internal const val MAX_COMPOSER_ATTACHMENTS = 5

internal fun isDefiniteComposerRejection(error: Exception): Boolean {
    val status = (error as? WandApiException)?.status ?: return false
    // Timeouts/conflicts may describe an already accepted or duplicate idempotent request.
    return status in 400..499 && status != 408 && status != 409
}

internal fun appendComposerVoiceText(existing: String, text: String): String {
    val clean = text.trim()
    if (clean.isEmpty()) return existing
    val base = existing.trimEnd()
    return if (base.isEmpty()) clean else "$base $clean"
}

internal fun attachmentPrompt(attachments: List<UploadedFile>, body: String): String {
    if (attachments.isEmpty()) return body
    val paths = attachments.joinToString("\n") { it.savedPath }
    return "[附件已上传，请查看以下文件:\n$paths\n]\n\n$body"
}
