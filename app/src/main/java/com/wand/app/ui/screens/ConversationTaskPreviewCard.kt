package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.ConversationTurn
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/** The same viewport is retained through startup, streaming and terminal states. */
@Composable
internal fun ConversationTaskPreviewCard(turn: ConversationTurn, onOpen: () -> Unit) {
    val preview = turn.taskPreview
    val unavailable = preview?.status == "unavailable"
    val scroll = rememberScrollState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) { snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (position, moving) ->
        if (moving) following = scroll.maxValue - position < 24
    } }
    LaunchedEffect(preview?.text, scroll.maxValue) { if (following) scroll.scrollTo(scroll.maxValue) }
    Surface(shape = WandShapes.md, color = WandColors.surfaceSoft,
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(196.dp)
            .clickable(enabled = !unavailable, role = Role.Button, onClickLabel = "打开任务群", onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(turn.conversationLink?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when (preview?.status) {
                "starting", null -> "正在启动"
                "unavailable" -> "任务不可用"
                else -> conversationRunLabel(preview.status)
            }, style = MaterialTheme.typography.labelSmall, color = if (preview?.status == "failed") WandColors.danger else WandColors.textSecondary)
            Text(preview?.text ?: "任务已接收，点击查看处理进展。", Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), style = MaterialTheme.typography.bodyMedium)
            Text(if (unavailable) "原消息已保留" else "点击进入任务群 · 查看完整过程", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
    }
}
