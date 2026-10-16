package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TaskComment
import com.wand.app.data.loadTaskComments
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Inline, read-only native rows. No empty panel and no additional comment editing surface. */
@Composable
internal fun TaskComments(api: TaskBoardPort, taskId: String) {
    var comments by remember(api, taskId) { mutableStateOf<List<TaskComment>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(api, taskId, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                try {
                    comments = loadTaskComments(api, taskId)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // A transient failure keeps existing rows without disrupting task operations.
                }
                delay(6_000)
            }
        }
    }
    if (comments.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("评论", style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
            comments.forEach { comment ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOf(comment.authorName, comment.createdAt).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                    SelectionContainer {
                        Text(comment.body, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                    }
                }
            }
        }
    }
}
