package com.wand.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.SessionCompletion
import com.wand.app.data.WandApi
import com.wand.app.ui.SessionTitleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** Acknowledge only a loaded, visible conversation/terminal, never a background list refresh. */
@Composable
fun SessionCompletionViewEffect(
    api: WandApi,
    sessionId: String,
    ready: Boolean,
    completionRevision: Int?,
    viewedCompletionRevision: Int?,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val completion = SessionCompletion(completionRevision ?: 0, viewedCompletionRevision ?: 0)
        .merge(SessionTitleStore.completionOf(sessionId) ?: SessionCompletion())
    LaunchedEffect(api, sessionId, ready, completion, lifecycle) {
        if (!ready || !completion.unread) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val confirmed = api.markSessionCompletionViewed(sessionId, completion.completionRevision)
                SessionTitleStore.apply(sessionId,
                    completionRevision = confirmed.completionRevision,
                    viewedCompletionRevision = confirmed.viewedCompletionRevision,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Keep the result unread. Reopening/resuming the visible screen can retry.
            }
            awaitCancellation()
        }
    }
}
