package com.wand.app.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver

/** Keeps unsent composer text isolated by session while detail screens are replaced. */
class SessionDraftStore(initialDrafts: Map<String, String> = emptyMap()) {
    private val drafts = mutableStateMapOf<String, String>().apply {
        putAll(initialDrafts.filterValues(String::isNotEmpty))
    }

    operator fun get(sessionId: String): String = drafts[sessionId].orEmpty()

    operator fun set(sessionId: String, value: String) {
        if (value.isEmpty()) {
            drafts.remove(sessionId)
        } else {
            drafts[sessionId] = value
        }
    }

    internal fun savedDrafts(): Map<String, String> = drafts.toMap()

    companion object {
        val Saver: Saver<SessionDraftStore, Any> = mapSaver(
            save = { store -> store.savedDrafts() },
            restore = { saved ->
                SessionDraftStore(
                    saved.mapNotNull { (sessionId, value) ->
                        (value as? String)?.let { sessionId to it }
                    }.toMap(),
                )
            },
        )
    }
}
