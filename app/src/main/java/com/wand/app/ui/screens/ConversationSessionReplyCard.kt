package com.wand.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.ui.ChatStore
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/** IM owns this fixed viewport; ChatStore owns all actual input, questions and execution state. */
@Composable
internal fun ConversationSessionReplyCard(turn: ConversationTurn, protocol: ChatStore? = null, onOpen: () -> Unit) {
    val preview = turn.sessionPreview
    val scroll = rememberScrollState()
    var following by remember(turn.sessionLink?.sessionId) { mutableStateOf(true) }
    LaunchedEffect(scroll) { snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (position, moving) ->
        if (moving) following = scroll.maxValue - position < 24
    } }
    LaunchedEffect(preview?.text, scroll.maxValue) { if (following) scroll.scrollTo(scroll.maxValue) }
    Surface(shape = WandShapes.md, color = WandColors.surfaceSoft,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(232.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(turn.sessionLink?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                TurnView(ConversationTurn("assistant", listOf(ContentBlock.Text(preview?.text ?: "消息已接收，正在启动独立会话。", null))), showHeader = false)
                val last = protocol?.messages?.lastOrNull()
                val questions = last?.content?.filterIsInstance<ContentBlock.ToolUse>()?.filter { protocol?.canAnswerAskUser(it.id) == true }.orEmpty()
                if (questions.isNotEmpty() && last != null) TurnView(last.copy(content = questions), showHeader = false,
                    askSelections = protocol.askUserSelections,
                    onAskToggle = { tool, question, option, multi -> protocol.toggleAskOption(tool, question, option, multi) },
                    onAskSubmit = { tool, answer -> protocol.submitAskUser(tool, answer) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when (preview?.status) {
                    "running" -> "正在回复"
                    "waiting_user" -> "等待你确认"
                    "done" -> "已完成"
                    "failed" -> "执行失败"
                    "stopped" -> "已停止"
                    "unavailable" -> "会话不可用"
                    else -> "正在启动"
                }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = if (preview?.status == "failed") WandColors.danger else WandColors.textSecondary)
                if (protocol?.isResponding == true) TextButton(onClick = protocol::stopResponding) { Text("停止") }
                TextButton(enabled = preview?.status != "unavailable", onClick = onOpen) { Text("查看会话") }
            }
        }
    }
}
