package com.wand.app.data

import org.json.JSONObject

/** Server-owned completion generations; normal snapshots cannot move acknowledgement backwards. */
data class SessionCompletion(
    val completionRevision: Int = 0,
    val viewedCompletionRevision: Int = 0,
) {
    val unread: Boolean get() = completionRevision > viewedCompletionRevision

    fun merge(other: SessionCompletion): SessionCompletion = SessionCompletion(
        completionRevision = maxOf(completionRevision, other.completionRevision),
        viewedCompletionRevision = maxOf(viewedCompletionRevision, other.viewedCompletionRevision),
    )

    companion object {
        fun parse(o: JSONObject): SessionCompletion = SessionCompletion(
            completionRevision = o.int("completionRevision") ?: 0,
            viewedCompletionRevision = o.int("viewedCompletionRevision") ?: 0,
        )
    }
}
