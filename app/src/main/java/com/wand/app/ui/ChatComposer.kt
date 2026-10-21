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

data class StagedComposerAttachment(val uri: String, val name: String, val mimeType: String = "")

internal data class ComposerSubmission(
    val text: String,
    val attachments: List<UploadedFile>,
    val stagedAttachments: List<StagedComposerAttachment>,
    val textRevision: Long,
    val prompt: String = attachmentPrompt(attachments, text).trim(),
)

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
    private var stagedUploadInProgress = false
    private var discardGeneration = 0L
    private var uploadJob: Job? = null
    val draftRevision: Long get() = drafts.revision(sessionId)

    val draft: String get() = drafts[sessionId]
    val attachments: List<UploadedFile> get() = drafts.attachments(sessionId)
    val stagedAttachments: List<StagedComposerAttachment> get() = drafts.stagedAttachments(sessionId)
    var uploading by mutableStateOf(false)
        private set
    var sendPhase by mutableStateOf(SendPhase.Idle)
        private set
    private val currentPrompt: String
        get() = attachmentPrompt(attachments, draft).trim()
    val canSubmit: Boolean get() = active && ready() && !uploading && sendPhase != SendPhase.Sending &&
        (draft.isNotBlank() || attachments.isNotEmpty() || stagedAttachments.isNotEmpty()) && !drafts.isUnconfirmed(sessionId, currentPrompt)

    fun editDraft(text: String) {
        if (active) drafts[sessionId] = text
    }

    /** Cancel only unsent content; never hide an in-flight or unknown delivery. */
    fun discardUnsentDraft(): Boolean {
        if (!active || sendPhase == SendPhase.Sending || unconfirmedSubmission != null ||
            drafts.isUnconfirmed(sessionId, currentPrompt)) return false
        feedbackJob?.cancel()
        discardGeneration++
        uploadJob?.cancel()
        uploadJob = null
        uploading = false
        stagedUploadInProgress = false
        // Advance the revision even for an empty draft, invalidating late uploads.
        drafts[sessionId] = ""
        drafts.setAttachments(sessionId, emptyList())
        drafts.setStagedAttachments(sessionId, emptyList())
        sendPhase = SendPhase.Idle
        return true
    }

    val queueRecovery: QueueRecovery? get() = drafts.queueRecovery(sessionId)

    /** Capture the original before I/O; never PATCH or replay an uncertain DELETE. */
    fun retrieveQueued(index: Int, text: String, remove: suspend (Int, String) -> Unit): Boolean {
        if (!active || queueRecovery != null || sendPhase == SendPhase.Sending) return false
        val revision = draftRevision
        drafts.setQueueRecovery(sessionId, QueueRecovery(text, QueueRecoveryPhase.Retrieving, "正在取回原文…"))
        scope.launch {
            try {
                remove(index, text)
                coroutineContext.ensureActive()
                drafts.setQueueRecovery(sessionId, QueueRecovery(text, QueueRecoveryPhase.Ready, "已取回，可在输入框继续编辑"))
                // An existing or newly typed draft requires explicit adoption, never replacement.
                if (active && draftRevision == revision && draft.isEmpty()) restoreQueuedOriginal()
            } catch (e: Exception) {
                val rejected = isDefiniteRequestRejection(e)
                drafts.setQueueRecovery(sessionId, QueueRecovery(text,
                    if (rejected) QueueRecoveryPhase.Rejected else QueueRecoveryPhase.Unconfirmed,
                    if (rejected) e.message ?: "取回被拒绝，原文已保留"
                    else "取回结果未知，请核对队列和会话，勿重复发送；原文已保留"))
                if (e is CancellationException) throw e
            }
        }
        return true
    }

    /** Explicitly append to the latest draft without changing any attachments. */
    fun restoreQueuedOriginal(confirmedNotExecuted: Boolean = false): Boolean {
        val recovery = queueRecovery ?: return false
        if (!active || sendPhase == SendPhase.Sending ||
            (recovery.phase != QueueRecoveryPhase.Ready &&
                !(recovery.phase == QueueRecoveryPhase.Unconfirmed && confirmedNotExecuted))) return false
        editDraft(if (draft.isEmpty()) recovery.text else "$draft\n\n${recovery.text}")
        drafts.setQueueRecovery(sessionId, null)
        return true
    }

    fun dismissRejectedQueueRecovery() {
        if (active && queueRecovery?.phase == QueueRecoveryPhase.Rejected) drafts.setQueueRecovery(sessionId, null)
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

    fun stageAttachments(files: List<StagedComposerAttachment>): Boolean {
        if (!active || uploading || files.isEmpty()) return false
        val combined = (stagedAttachments + files).distinctBy { it.uri }
        if (combined.size > MAX_COMPOSER_ATTACHMENTS - attachments.size) {
            notice("最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件")
            return false
        }
        drafts.setStagedAttachments(sessionId, combined)
        return true
    }

    fun clearStagedAttachments() {
        if (active) drafts.setStagedAttachments(sessionId, emptyList())
    }

    fun acceptUploadedStagedAttachments(files: List<StagedComposerAttachment>, uploaded: List<UploadedFile>): Boolean {
        if (!active || files.isEmpty() || files.any { it !in stagedAttachments }) return false
        drafts.setStagedAttachments(sessionId, stagedAttachments.filterNot { it in files })
        drafts.setAttachments(sessionId, (attachments + uploaded).distinctBy { it.savedPath })
        return true
    }

    fun uploadStaged(operation: suspend (List<StagedComposerAttachment>) -> List<UploadedFile>): Boolean {
        val captured = stagedAttachments
        if (!active || uploading || stagedUploadInProgress || captured.isEmpty()) return false
        if (attachments.size + captured.size > MAX_COMPOSER_ATTACHMENTS) {
            notice("最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件")
            return false
        }
        val startedGeneration = discardGeneration
        stagedUploadInProgress = true
        uploading = true
        uploadJob = scope.launch {
            try {
                val uploaded = operation(captured)
                coroutineContext.ensureActive()
                if (!active || discardGeneration != startedGeneration) return@launch
                require(uploaded.size == captured.size) { "上传附件数量不匹配" }
                if (!acceptUploadedStagedAttachments(captured, uploaded)) {
                    notice("附件选择已变化，上传结果未加入草稿；请核对会话附件")
                    return@launch
                }
                notice("已上传 ${uploaded.size} 个附件")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) notice(e.message ?: "附件上传失败，已保留所选附件")
            } finally {
                if (discardGeneration == startedGeneration) {
                    stagedUploadInProgress = false
                    uploading = false
                }
            }
        }
        return true
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
        val startedGeneration = discardGeneration
        uploading = true
        uploadJob = scope.launch {
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
                if (discardGeneration == startedGeneration) uploading = false
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
        val submission = drafts.submission(sessionId).copy(
            prompt = attachmentPrompt(attachments, draft).trim(),
            stagedAttachments = stagedAttachments,
        )
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
        queueRecovery?.takeIf { it.phase == QueueRecoveryPhase.Retrieving }?.let {
            drafts.setQueueRecovery(sessionId, it.copy(phase = QueueRecoveryPhase.Unconfirmed,
                message = "取回结果未知，请核对队列和会话，勿重复发送；原文已保留"))
        }
        active = false
        scope.cancel()
    }
}

internal const val MAX_COMPOSER_ATTACHMENTS = 5

internal fun attachmentPrompt(attachments: List<UploadedFile>, body: String): String {
    if (attachments.isEmpty()) return body
    val paths = attachments.joinToString("\n") { it.savedPath }
    return "[附件已上传，请查看以下文件:\n$paths\n]\n\n$body"
}

internal fun attachmentPromptBody(attachments: List<UploadedFile>, prompt: String): String {
    if (attachments.isEmpty()) return prompt
    val prefix = attachmentPrompt(attachments, "")
    return prompt.removePrefix(prefix)
}
