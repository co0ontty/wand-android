package com.wand.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.wand.app.data.ConversationReceipt
import com.wand.app.data.ConversationTask
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandSizes

internal fun conversationApprovalLabel(phase: String): String = when (phase) {
    "sending" -> "批准中"
    "sent" -> "已批准"
    "result" -> "批准已接受"
    "failed" -> "批准失败"
    "unknown" -> "批准未确认"
    else -> "明确批准本轮计划"
}

internal fun acceptedConversationTask(receipt: ConversationReceipt?, hasDraft: Boolean): Boolean =
    receipt?.state == "accepted" && receipt.conversationId.isNotBlank() && !receipt.taskId.isNullOrBlank() && !hasDraft

internal fun conversationStartupLabel(task: ConversationTask): String =
    if (task.startup?.state == "failed") "启动失败 · 已接受的原任务" else "待开工 · 已接受的原任务"

/** 按第2步 chat-tool-design.md §6–7：同一个批准按钮，仅标签按原提交owner阶段交叉切换。 */
@Composable
internal fun ConversationApprovalButton(phase: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val measurements = listOf("idle", "sending", "sent", "result", "failed", "unknown")
        .map { textMeasurer.measure(AnnotatedString(conversationApprovalLabel(it)), style = style) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val padding = ButtonDefaults.ContentPadding
    val labelWidth = with(density) { measurements.maxOf { it.size.width }.toDp() }
    val width = labelWidth + padding.calculateLeftPadding(direction) + padding.calculateRightPadding(direction)
    val labelHeight = with(density) { measurements.maxOf { it.size.height }.toDp() }
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        modifier = modifier.width(maxOf(96.dp, width)).heightIn(min = WandSizes.controlHeight)) {
        Box(Modifier.width(labelWidth).heightIn(min = labelHeight), contentAlignment = Alignment.Center) {
            WandInPlaceSwap(contentKey = phase, durationMillis = WandMotion.fast, enterScale = 1f, exitScale = 1f) { displayed ->
                Text(conversationApprovalLabel(displayed as? String ?: "idle"), style = style, maxLines = 1)
            }
        }
    }
}
