package com.wand.app.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import com.wand.app.data.UploadedFile
import org.json.JSONArray
import org.json.JSONObject

/** Keeps unsent composer text isolated by session while detail screens are replaced. */
class SessionDraftStore(initialDrafts: Map<String, String> = emptyMap()) {
    private val drafts = mutableStateMapOf<String, String>().apply {
        putAll(initialDrafts.filterValues(String::isNotEmpty))
    }
    private val attachmentDrafts = mutableStateMapOf<String, List<UploadedFile>>()
    private val revisions = mutableMapOf<String, Long>()
    private val unconfirmedTextRevisions = mutableMapOf<String, Long>()
    private val unconfirmedAttachmentPaths = mutableMapOf<String, Set<String>>()

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

    internal fun setAttachments(sessionId: String, files: List<UploadedFile>) {
        if (files.isEmpty()) attachmentDrafts.remove(sessionId) else attachmentDrafts[sessionId] = files
    }

    internal fun submission(sessionId: String): ComposerSubmission {
        val submission = ComposerSubmission(this[sessionId], attachments(sessionId), revision(sessionId))
        unconfirmedTextRevisions[sessionId] = submission.textRevision
        unconfirmedAttachmentPaths[sessionId] = unconfirmedAttachmentPaths[sessionId].orEmpty() +
            submission.attachments.map { it.savedPath }
        return submission
    }

    internal fun accepted(sessionId: String, submission: ComposerSubmission) {
        if (revision(sessionId) == submission.textRevision) this[sessionId] = ""
        val submittedPaths = submission.attachments.map { it.savedPath }.toSet()
        setAttachments(sessionId, attachments(sessionId).filterNot { it.savedPath in submittedPaths })
        confirmAttachments(sessionId, submittedPaths)
    }

    internal fun rejected(sessionId: String, submission: ComposerSubmission) {
        if (unconfirmedTextRevisions[sessionId] == submission.textRevision) {
            unconfirmedTextRevisions.remove(sessionId)
        }
        confirmAttachments(sessionId, submission.attachments.map { it.savedPath }.toSet())
    }

    private fun confirmAttachments(sessionId: String, paths: Set<String>) {
        val remaining = unconfirmedAttachmentPaths[sessionId].orEmpty() - paths
        if (remaining.isEmpty()) unconfirmedAttachmentPaths.remove(sessionId)
        else unconfirmedAttachmentPaths[sessionId] = remaining
    }

    internal fun revision(sessionId: String): Long = revisions[sessionId] ?: 0L

    // An interrupted/uncertain send stays available in this process, but must not become
    // a restored draft after process death and accidentally send the same input twice.
    internal fun savedDrafts(): Map<String, String> = drafts.filterKeys {
        unconfirmedTextRevisions[it] != revision(it)
    }

    internal fun savedAttachments(): Map<String, List<UploadedFile>> = attachmentDrafts.mapValues {
        (sessionId, files) -> files.filterNot { it.savedPath in unconfirmedAttachmentPaths[sessionId].orEmpty() }
    }.filterValues { it.isNotEmpty() }

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
        val Saver: Saver<SessionDraftStore, Any> = mapSaver(
            save = { store -> store.savedDrafts() + (ATTACHMENTS_KEY to store.savedAttachmentJson()) },
            restore = { saved ->
                SessionDraftStore(
                    saved.filterKeys { it != ATTACHMENTS_KEY }.mapNotNull { (sessionId, value) ->
                        (value as? String)?.let { sessionId to it }
                    }.toMap(),
                ).apply {
                    val json = runCatching { JSONObject(saved[ATTACHMENTS_KEY] as? String ?: "{}") }
                        .getOrNull()
                    json?.keys()?.forEach { sessionId ->
                        val files = UploadedFile.parseList(JSONObject().put("files", json.optJSONArray(sessionId)))
                        setAttachments(sessionId, files)
                    }
                }
            },
        )
    }
}
