package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.UploadedFile
import com.wand.app.data.isDefiniteRequestRejection
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

/** Some input chunks reached the terminal, so a later rejection is not an unsent draft. */
internal class UnconfirmedComposerInputException(cause: Exception) : Exception(cause.message, cause)

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
    private var unconfirmedSubmission: ComposerSubmission? = null
    val draftRevision: Long get() = drafts.revision(sessionId)

    val draft: String get() = drafts[sessionId]
    val attachments: List<UploadedFile> get() = drafts.attachments(sessionId)
    var uploading by mutableStateOf(false)
        private set
    var sendPhase by mutableStateOf(SendPhase.Idle)
        private set
    private val currentPrompt: String
        get() = attachmentPrompt(attachments, draft).trim()
    val canSubmit: Boolean get() = active && ready() && !uploading && sendPhase != SendPhase.Sending &&
        (draft.isNotBlank() || attachments.isNotEmpty()) && !drafts.isUnconfirmed(sessionId, currentPrompt)

    fun editDraft(text: String) {
        if (active) drafts[sessionId] = text
    }

    /** Offer an unaddressed draft for explicit adoption; no selection/read path performs this move. */
    fun pendingDraftAdoption(source: ChatComposer): ComposerDraftAdoption? {
        if (!active || !source.active || !ready() || drafts !== source.drafts || uploading || source.uploading ||
            sendPhase == SendPhase.Sending || source.sendPhase == SendPhase.Sending) return null
        return drafts.adoption(source.sessionId, sessionId)
    }

    fun adoptDraftFrom(source: ChatComposer, offer: ComposerDraftAdoption): Boolean {
        if (offer.sourceId != source.sessionId || offer.targetId != sessionId || pendingDraftAdoption(source) != offer) return false
        return drafts.adopt(offer)
    }

    fun appendVoice(text: String) {
        if (active && text.isNotBlank()) editDraft(appendComposerVoiceText(draft, text))
    }

    /** Bind speech recognition to the draft that was present when recording began. */
    fun voiceCommitForCurrentDraft(): (String) -> Unit {
        val startedRevision = drafts.revision(sessionId)
        return { text ->
            if (active && drafts.revision(sessionId) == startedRevision) appendVoice(text)
        }
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
        val startedRevision = drafts.revision(sessionId)
        uploading = true
        scope.launch {
            try {
                val uploaded = operation(remainingSlots)
                coroutineContext.ensureActive()
                if (!active || drafts.revision(sessionId) != startedRevision) return@launch
                require(uploaded.size <= remainingSlots) { "上传附件数量超过剩余名额" }
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
    fun submit(deliver: (suspend (String) -> Unit)? = null, afterAccepted: (() -> Unit)? = null): Boolean {
        if (!active || sendPhase == SendPhase.Sending) return false
        if (drafts.isUnconfirmed(sessionId, currentPrompt)) {
            notice("上一条消息送达结果未知，请先核对会话，不要重复发送。")
            return false
        }
        if (!canSubmit) return false
        val submission = drafts.submission(sessionId)
        val transport = deliver ?: send
        feedbackJob?.cancel()
        sendPhase = SendPhase.Sending
        scope.launch {
            try {
                transport(submission.prompt)
                coroutineContext.ensureActive()
                if (!active) return@launch
                drafts.accepted(sessionId, submission)
                unconfirmedSubmission = null
                finish(SendPhase.Sent, SEND_SENT_DWELL_MS)
                if (afterAccepted != null) { delay(SEND_SENT_DWELL_MS); if (active) afterAccepted() }
            } catch (e: CancellationException) {
                // Leaving the screen cannot establish whether the server received the input.
                if (active) finish(SendPhase.Failed, SEND_FAILED_DWELL_MS)
                throw e
            } catch (e: Exception) {
                if (!active) return@launch
                if (isDefiniteRequestRejection(e)) drafts.rejected(sessionId, submission)
                else unconfirmedSubmission = submission
                finish(SendPhase.Failed, SEND_FAILED_DWELL_MS)
                notice(e.message ?: "发送失败")
            }
        }
        return true
    }

    /** Confirm only this owner's capture; later edits/attachments remain untouched. */
    fun reconcileSubmission(accepted: Boolean) {
        val capture = unconfirmedSubmission ?: return
        if (accepted) drafts.accepted(sessionId, capture) else drafts.rejected(sessionId, capture)
        unconfirmedSubmission = null
        finish(if (accepted) SendPhase.Sent else SendPhase.Failed,
            if (accepted) SEND_SENT_DWELL_MS else SEND_FAILED_DWELL_MS)
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
