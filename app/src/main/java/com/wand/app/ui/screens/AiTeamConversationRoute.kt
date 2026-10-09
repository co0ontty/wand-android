package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.wand.app.data.AiTeamRun
import com.wand.app.data.ConversationInstance
import com.wand.app.data.WandApi
import com.wand.app.ui.components.*
import kotlinx.coroutines.CancellationException

/** Legacy run IDs are navigation aliases, never a second chat renderer. */
internal fun conversationForRun(run: AiTeamRun, conversations: List<ConversationInstance> = emptyList()): String? {
    run.conversationId?.takeIf { it.isNotBlank() }?.let { return it }
    return conversations.filter { item -> item.kind == "group" && (
        (run.chatSessionId != null && item.sessionId == run.chatSessionId) ||
            item.tasks.any { task -> task.runs.any { it.id == run.id } }
        ) }.singleOrNull()?.id
}

@Composable
internal fun AiTeamConversationRoute(api: WandApi, runId: String, showBack: Boolean, onBack: () -> Unit,
    onOpenSession: (String) -> Unit, onResolved: (AiTeamRun, String) -> Unit) {
    var error by remember(runId) { mutableStateOf<String?>(null) }
    var sessionId by remember(runId) { mutableStateOf<String?>(null) }
    var retry by remember(runId) { mutableIntStateOf(0) }
    val resolved by rememberUpdatedState(onResolved)
    LaunchedEffect(api, runId, retry) {
        error = null
        sessionId = null
        try {
            val run = api.aiTeamRunDetail(runId).run
            sessionId = run.chatSessionId
            val id = conversationForRun(run) ?: conversationForRun(run, api.conversations())
            if (id == null) error = "此运行尚未关联群对话，请核对服务版本后重试。"
            else resolved(run, id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "对话加载失败，请重试。" }
    }
    Column(Modifier.fillMaxSize()) {
        WandDetailTopBar("任务对话", leading = if (showBack) {
            { WandIconButton(WandIcons.back, "返回", onClick = onBack) }
        } else null)
        if (error != null) {
            ErrorState(error.orEmpty(), onRetry = { retry++ })
            sessionId?.let { id -> WandButton("查看原会话记录", { onOpenSession(id) }, variant = WandButtonVariant.Text) }
        } else LoadingState(text = "正在打开对话…")
    }
}
