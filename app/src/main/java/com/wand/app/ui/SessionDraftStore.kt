package com.wand.app.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import com.wand.app.data.UploadedFile
import org.json.JSONArray
import org.json.JSONObject

/** An adoption offer contains identities/revisions only; pages never copy the draft payload. */
data class ComposerDraftAdoption internal constructor(
    val sourceId: String, val targetId: String, val sourceRevision: Long, val targetRevision: Long,
)

/** Queue originals remain session-owned in memory, including an interrupted/unknown removal. */
enum class QueueRecoveryPhase { Retrieving, Ready, Unconfirmed, Rejected }

data class QueueRecovery(val text: String, val phase: QueueRecoveryPhase, val message: String)

/** Keeps unsent composer text isolated by session while detail screens are replaced. */
class SessionDraftStore(initialDrafts: Map<String, String> = emptyMap()) {
    private val drafts = mutableStateMapOf<String, String>().apply {
        putAll(initialDrafts.filterValues(String::isNotEmpty))
    }
    private val attachmentDrafts = mutableStateMapOf<String, List<UploadedFile>>()
    private val stagedAttachmentDrafts = mutableStateMapOf<String, List<StagedComposerAttachment>>()
    private val queueRecoveries = mutableStateMapOf<String, QueueRecovery>()
    private val pendingCreatedSessions = mutableStateMapOf<String, String>()
    internal fun pendingCreatedSession(draftKey: String): String? = pendingCreatedSessions[draftKey]
    internal fun setPendingCreatedSession(draftKey: String, sessionId: String?) {
        if (sessionId == null) pendingCreatedSessions.remove(draftKey) else pendingCreatedSessions[draftKey] = sessionId
    }
    private val pendingCreatedTasks = mutableStateMapOf<String, String>()
    internal fun pendingCreatedTask(draftKey: String): String? = pendingCreatedTasks[draftKey]
    internal fun setPendingCreatedTask(draftKey: String, taskId: String?) {
        if (taskId == null) pendingCreatedTasks.remove(draftKey) else pendingCreatedTasks[draftKey] = taskId
    }
    private val pendingTaskWindows = mutableStateMapOf<String, String>()
    internal fun pendingTaskWindow(draftKey: String): String? = pendingTaskWindows[draftKey]
    internal fun setPendingTaskWindow(draftKey: String, sessionId: String?) {
        if (sessionId == null) pendingTaskWindows.remove(draftKey) else pendingTaskWindows[draftKey] = sessionId
    }
    private val unconfirmedPendingCreations = mutableStateMapOf<String, Boolean>()
    internal fun pendingCreationUnconfirmed(draftKey: String): Boolean = unconfirmedPendingCreations[draftKey] == true
    internal fun setPendingCreationUnconfirmed(draftKey: String, unconfirmed: Boolean) {
        if (unconfirmed) unconfirmedPendingCreations[draftKey] = true else unconfirmedPendingCreations.remove(draftKey)
    }
    internal fun clearPendingCreation(draftKey: String) {
        pendingCreatedSessions.remove(draftKey)
        pendingCreatedTasks.remove(draftKey)
        pendingTaskWindows.remove(draftKey)
        unconfirmedPendingCreations.remove(draftKey)
    }
    internal fun queueRecovery(sessionId: String): QueueRecovery? = queueRecoveries[sessionId]
    internal fun setQueueRecovery(sessionId: String, recovery: QueueRecovery?) {
        if (recovery == null) queueRecoveries.remove(sessionId) else queueRecoveries[sessionId] = recovery
    }
    private val revisions = mutableMapOf<String, Long>()
    private val unconfirmedTextRevisions = mutableMapOf<String, Long>()
    private val unconfirmedAttachmentPaths = mutableMapOf<String, Set<String>>()
    private val unconfirmedStagedUris = mutableMapOf<String, Set<String>>()
    private val unconfirmedPendingUploads = mutableMapOf<String, Set<String>>()
    /** Unknown delivery must continue to block the exact input even after the user edits the draft. */
    private val unconfirmedInputs = mutableMapOf<String, MutableSet<String>>()

    operator fun get(sessionId: String): String = drafts[sessionId].orEmpty()

    operator fun set(sessionId: String, value: String) {
        revisions[sessionId] = revision(sessionId) + 1
        unconfirmedTextRevisions.remove(sessionId)
        if (value.isEmpty()) {
            drafts.remove(sessionId)
        } else {
            drafts[sessionId] = value
        }
    }

    internal fun attachments(sessionId: String): List<UploadedFile> = attachmentDrafts[sessionId].orEmpty()

    internal fun stagedAttachments(sessionId: String): List<StagedComposerAttachment> =
        stagedAttachmentDrafts[sessionId].orEmpty()

    internal fun setStagedAttachments(sessionId: String, files: List<StagedComposerAttachment>) {
        if (stagedAttachments(sessionId) != files) revisions[sessionId] = revision(sessionId) + 1
        if (files.isEmpty()) stagedAttachmentDrafts.remove(sessionId) else stagedAttachmentDrafts[sessionId] = files
        val retainedUris = files.map { it.uri }.toSet()
        val unconfirmed = unconfirmedPendingUploads[sessionId].orEmpty().intersect(retainedUris)
        if (unconfirmed.isEmpty()) unconfirmedPendingUploads.remove(sessionId)
        else unconfirmedPendingUploads[sessionId] = unconfirmed
    }

    internal fun pendingUploadUnconfirmed(sessionId: String, files: List<StagedComposerAttachment>): Boolean =
        files.any { it.uri in unconfirmedPendingUploads[sessionId].orEmpty() }

    internal fun markPendingUploadUnconfirmed(sessionId: String, files: List<StagedComposerAttachment>) {
        if (files.isEmpty()) return
        unconfirmedPendingUploads[sessionId] = unconfirmedPendingUploads[sessionId].orEmpty() + files.map { it.uri }
    }

    internal fun releasePendingUploadAfterDefiniteRejection(sessionId: String, files: List<StagedComposerAttachment>) {
        val remaining = unconfirmedPendingUploads[sessionId].orEmpty() - files.map { it.uri }.toSet()
        if (remaining.isEmpty()) unconfirmedPendingUploads.remove(sessionId)
        else unconfirmedPendingUploads[sessionId] = remaining
    }

    internal fun setAttachments(sessionId: String, files: List<UploadedFile>) {
        if (attachments(sessionId) != files) revisions[sessionId] = revision(sessionId) + 1
        if (files.isEmpty()) attachmentDrafts.remove(sessionId) else attachmentDrafts[sessionId] = files
    }

    internal fun discardScope(prefix: String) {
        (drafts.keys + attachmentDrafts.keys + stagedAttachmentDrafts.keys + revisions.keys + pendingCreatedSessions.keys +
            pendingCreatedTasks.keys + pendingTaskWindows.keys + unconfirmedPendingCreations.keys + unconfirmedPendingUploads.keys)
            .filter { it.startsWith(prefix) }.toSet().forEach { id ->
            revisions[id] = revision(id) + 1
            drafts.remove(id); attachmentDrafts.remove(id); stagedAttachmentDrafts.remove(id); queueRecoveries.remove(id)
            unconfirmedInputs.remove(id); unconfirmedTextRevisions.remove(id); unconfirmedAttachmentPaths.remove(id); unconfirmedStagedUris.remove(id); unconfirmedPendingUploads.remove(id)
            pendingCreatedSessions.remove(id)
            pendingCreatedTasks.remove(id)
            pendingTaskWindows.remove(id)
            unconfirmedPendingCreations.remove(id)
        }
    }

    internal fun hasContent(sessionId: String): Boolean = this[sessionId].isNotEmpty() ||
        attachments(sessionId).isNotEmpty() || stagedAttachments(sessionId).isNotEmpty()

    internal fun movePendingDraft(sourceId: String, targetId: String): Boolean {
        if (sourceId == targetId) return true
        if (!hasContent(sourceId)) return true
        val offer = adoption(sourceId, targetId) ?: return false
        return adopt(offer)
    }

    internal fun pendingCreationState(): Map<String, String> = buildMap {
        pendingCreatedSessions.forEach { (key, value) -> put("session:$key", value) }
        pendingCreatedTasks.forEach { (key, value) -> put("task:$key", value) }
        pendingTaskWindows.forEach { (key, value) -> put("window:$key", value) }
        unconfirmedPendingCreations.keys.forEach { key -> put("unknown:$key", "true") }
    }

    internal fun restorePendingCreationState(values: Map<String, String>) {
        values.forEach { (key, value) ->
            when {
                key.startsWith("session:") -> setPendingCreatedSession(key.removePrefix("session:"), value)
                key.startsWith("task:") -> setPendingCreatedTask(key.removePrefix("task:"), value)
                key.startsWith("window:") -> setPendingTaskWindow(key.removePrefix("window:"), value)
                key.startsWith("unknown:") && value == "true" -> setPendingCreationUnconfirmed(key.removePrefix("unknown:"), true)
            }
        }
    }

    internal fun adoption(from: String, to: String): ComposerDraftAdoption? {
        val targetHasContent = this[to].isNotEmpty() || attachments(to).isNotEmpty() || stagedAttachments(to).isNotEmpty()
        val sourceHasContent = this[from].isNotEmpty() || attachments(from).isNotEmpty() || stagedAttachments(from).isNotEmpty()
        if (from == to || !sourceHasContent || targetHasContent ||
            unconfirmedInputs[from].orEmpty().isNotEmpty() || unconfirmedInputs[to].orEmpty().isNotEmpty()) return null
        return ComposerDraftAdoption(from, to, revision(from), revision(to))
    }

    /** Explicit atomic move, not a merge and never an overwrite of a recipient's own content. */
    internal fun adopt(offer: ComposerDraftAdoption): Boolean {
        if (revision(offer.sourceId) != offer.sourceRevision || revision(offer.targetId) != offer.targetRevision ||
            adoption(offer.sourceId, offer.targetId) != offer) return false
        val text = this[offer.sourceId]
        val files = attachments(offer.sourceId)
        val staged = stagedAttachments(offer.sourceId)
        if (files.size + staged.size > MAX_COMPOSER_ATTACHMENTS - attachments(offer.targetId).size - stagedAttachments(offer.targetId).size) return false
        // Move captured state as one operation so target/source observers never see a partial adoption.
        revisions[offer.targetId] = revision(offer.targetId) + 1
        revisions[offer.sourceId] = revision(offer.sourceId) + 1
        if (text.isEmpty()) drafts.remove(offer.targetId) else drafts[offer.targetId] = text
        if (files.isEmpty()) attachmentDrafts.remove(offer.targetId) else attachmentDrafts[offer.targetId] = files
        if (staged.isEmpty()) stagedAttachmentDrafts.remove(offer.targetId) else stagedAttachmentDrafts[offer.targetId] = staged
        drafts.remove(offer.sourceId)
        attachmentDrafts.remove(offer.sourceId)
        stagedAttachmentDrafts.remove(offer.sourceId)
        queueRecoveries.remove(offer.sourceId)
        unconfirmedInputs.remove(offer.sourceId)
        unconfirmedTextRevisions.remove(offer.sourceId)
        unconfirmedAttachmentPaths.remove(offer.sourceId)
        unconfirmedStagedUris.remove(offer.sourceId)
        unconfirmedPendingUploads.remove(offer.sourceId)
        return true
    }

    internal fun submission(sessionId: String): ComposerSubmission {
        val submission = ComposerSubmission(this[sessionId], attachments(sessionId), stagedAttachments(sessionId), revision(sessionId))
        unconfirmedTextRevisions[sessionId] = submission.textRevision
        unconfirmedAttachmentPaths[sessionId] = unconfirmedAttachmentPaths[sessionId].orEmpty() +
            submission.attachments.map { it.savedPath }
        unconfirmedStagedUris[sessionId] = unconfirmedStagedUris[sessionId].orEmpty() +
            submission.stagedAttachments.map { it.uri }
        unconfirmedInputs.getOrPut(sessionId) { mutableSetOf() }.add(submission.prompt)
        return submission
    }

    internal fun accepted(sessionId: String, submission: ComposerSubmission) {
        val ownsDraft = revision(sessionId) == submission.textRevision
        if (ownsDraft) this[sessionId] = ""
        val submittedStagedUris = submission.stagedAttachments.map { it.uri }.toSet()
        setStagedAttachments(sessionId, stagedAttachments(sessionId).filterNot { it.uri in submittedStagedUris })
        val submittedPaths = submission.attachments.map { it.savedPath }.toSet()
        setAttachments(sessionId, attachments(sessionId).filterNot { it.savedPath in submittedPaths })
        confirmAttachments(sessionId, submittedPaths)
        forgetUnconfirmed(sessionId, submission)
    }

    internal fun rejected(sessionId: String, submission: ComposerSubmission) {
        if (unconfirmedTextRevisions[sessionId] == submission.textRevision) {
            unconfirmedTextRevisions.remove(sessionId)
        }
        confirmAttachments(sessionId, submission.attachments.map { it.savedPath }.toSet())
        forgetUnconfirmed(sessionId, submission)
    }

    internal fun isUnconfirmed(sessionId: String, prompt: String): Boolean =
        prompt in unconfirmedInputs[sessionId].orEmpty()

    private fun forgetUnconfirmed(sessionId: String, submission: ComposerSubmission) {
        val pending = unconfirmedInputs[sessionId] ?: return
        pending.remove(submission.prompt)
        if (pending.isEmpty()) unconfirmedInputs.remove(sessionId)
    }

    private fun confirmAttachments(sessionId: String, paths: Set<String>) {
        val remaining = unconfirmedAttachmentPaths[sessionId].orEmpty() - paths
        if (remaining.isEmpty()) unconfirmedAttachmentPaths.remove(sessionId)
        else unconfirmedAttachmentPaths[sessionId] = remaining
    }

    internal fun revision(sessionId: String): Long = revisions[sessionId] ?: 0L

    // An interrupted/uncertain send stays available in this process, but must not become
    // a restored draft after process death and accidentally send the same input twice.
    internal fun savedDrafts(): Map<String, String> = drafts.filterKeys { sessionId ->
        unconfirmedTextRevisions[sessionId] != revision(sessionId) &&
            !isUnconfirmed(sessionId, attachmentPrompt(attachments(sessionId), this[sessionId]).trim())
    }

    internal fun savedStagedAttachments(): Map<String, List<StagedComposerAttachment>> =
        stagedAttachmentDrafts.mapValues { (sessionId, files) ->
            files.filterNot { it.uri in unconfirmedStagedUris[sessionId].orEmpty() }
        }.filterValues { it.isNotEmpty() }

    internal fun savedAttachments(): Map<String, List<UploadedFile>> = attachmentDrafts.mapValues {
        (sessionId, files) -> files.filterNot { it.savedPath in unconfirmedAttachmentPaths[sessionId].orEmpty() }
    }.filterValues { it.isNotEmpty() }

    private fun savedPendingCreationJson(): String = JSONObject().apply {
        pendingCreationState().forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun savedStagedAttachmentJson(): String = JSONObject().apply {
        savedStagedAttachments().forEach { (sessionId, files) ->
            put(sessionId, JSONArray().apply {
                files.forEach { file -> put(JSONObject().put("uri", file.uri).put("name", file.name).put("mimeType", file.mimeType)) }
            })
        }
    }.toString()

    private fun savedPendingUploadJson(): String = JSONObject().apply {
        unconfirmedPendingUploads.forEach { (sessionId, uris) -> put(sessionId, JSONArray(uris.toList())) }
    }.toString()

    private fun savedAttachmentJson(): String = JSONObject().apply {
        savedAttachments().forEach { (sessionId, files) ->
            put(sessionId, JSONArray().apply {
                files.forEach { file -> put(JSONObject().apply {
                    put("originalName", file.originalName)
                    put("savedPath", file.savedPath)
                    put("size", file.size)
                    put("mimeType", file.mimeType)
                }) }
            })
        }
    }.toString()

    companion object {
        private const val ATTACHMENTS_KEY = "wand:composer-attachments:v1"
        private const val STAGED_ATTACHMENTS_KEY = "wand:composer-staged-attachments:v1"
        private const val PENDING_CREATION_KEY = "wand:pending-creation:v1"
        private const val PENDING_UPLOAD_KEY = "wand:pending-upload:v1"
        val Saver: Saver<SessionDraftStore, Any> = mapSaver(
            save = { store -> store.savedDrafts() + mapOf(
                ATTACHMENTS_KEY to store.savedAttachmentJson(),
                STAGED_ATTACHMENTS_KEY to store.savedStagedAttachmentJson(),
                PENDING_CREATION_KEY to store.savedPendingCreationJson(),
                PENDING_UPLOAD_KEY to store.savedPendingUploadJson(),
            ) },
            restore = { saved ->
                SessionDraftStore(
                    saved.filterKeys { it != ATTACHMENTS_KEY && it != STAGED_ATTACHMENTS_KEY && it != PENDING_CREATION_KEY && it != PENDING_UPLOAD_KEY }.mapNotNull { (sessionId, value) ->
                        (value as? String)?.let { sessionId to it }
                    }.toMap(),
                ).apply {
                    val pending = runCatching { JSONObject(saved[PENDING_CREATION_KEY] as? String ?: "{}") }.getOrNull()
                    pending?.keys()?.forEach { key -> pending.optString(key).takeIf(String::isNotBlank)?.let { value -> restorePendingCreationState(mapOf(key to value)) } }
                    val json = runCatching { JSONObject(saved[ATTACHMENTS_KEY] as? String ?: "{}") }
                        .getOrNull()
                    json?.keys()?.forEach { sessionId ->
                        val files = UploadedFile.parseList(JSONObject().put("files", json.optJSONArray(sessionId)))
                        setAttachments(sessionId, files)
                    }
                    val staged = runCatching { JSONObject(saved[STAGED_ATTACHMENTS_KEY] as? String ?: "{}") }
                        .getOrNull()
                    staged?.keys()?.forEach { sessionId ->
                        val rows = staged.optJSONArray(sessionId) ?: return@forEach
                        val files = (0 until rows.length()).mapNotNull { index ->
                            val row = rows.optJSONObject(index) ?: return@mapNotNull null
                            val uri = row.optString("uri").takeIf(String::isNotBlank) ?: return@mapNotNull null
                            StagedComposerAttachment(uri, row.optString("name", "attachment"), row.optString("mimeType"))
                        }
                        setStagedAttachments(sessionId, files)
                    }
                    val pendingUploads = runCatching { JSONObject(saved[PENDING_UPLOAD_KEY] as? String ?: "{}") }.getOrNull()
                    pendingUploads?.keys()?.forEach { sessionId ->
                        val rows = pendingUploads.optJSONArray(sessionId) ?: return@forEach
                        val uris = (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }.toSet()
                        if (uris.isNotEmpty()) unconfirmedPendingUploads[sessionId] = uris
                    }
                }
            },

        )
    }
}
