package com.wand.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

/** 结构化聊天里可复制的正文：只取文本块，工具调用元数据不进剪贴板。 */
internal fun conversationTurnCopyText(turn: ConversationTurn): String = turn.content
    .filterIsInstance<ContentBlock.Text>()
    .filter { it.subagent == null }
    .joinToString("\n\n") { it.text.trim() }
    .trim()

/** 消息级复制操作：给选择手势之外的明确入口，保证流式列表里也能一键复制。 */
@Composable
internal fun MessageCopyButton(
    copyText: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("wand message", copyText))
            Toast.makeText(context, "消息已复制", Toast.LENGTH_SHORT).show()
        },
        enabled = copyText.isNotBlank(),
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            WandIcons.copy,
            contentDescription = "复制消息",
            tint = if (copyText.isBlank()) {
                WandColors.textMuted.copy(alpha = 0.48f)
            } else {
                WandColors.textSecondary
            },
            modifier = Modifier.size(iconSize),
        )
    }
}
