package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.speech.VoiceCallPhase
import com.wand.app.ui.theme.WandColors

@Composable
internal fun VoiceCallPanel(
    phase: VoiceCallPhase, target: String, notice: String, transcript: String,
    onStart: () -> Unit, onInterrupt: () -> Unit, onFinishUtterance: () -> Unit,
    onPause: () -> Unit, onEnd: () -> Unit, onChooseTarget: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(WandColors.surfaceSoft)
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("通话 · $target", style = MaterialTheme.typography.labelLarge, color = WandColors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (phase == VoiceCallPhase.Off) "识别后自动发送，回复使用系统端侧语音播报" else notice,
            style = MaterialTheme.typography.bodySmall, color = if (phase == VoiceCallPhase.Error) WandColors.danger else WandColors.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (transcript.isNotBlank()) Text(transcript, style = MaterialTheme.typography.bodyMedium,
            color = WandColors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (phase) {
                VoiceCallPhase.Off -> {
                    TextButton(onClick = onChooseTarget) { Text("员工与目录") }
                    TextButton(onClick = onStart) { Text("开始通话") }
                }
                VoiceCallPhase.Paused, VoiceCallPhase.Error -> TextButton(onClick = onStart) { Text("继续通话") }
                VoiceCallPhase.Speaking -> TextButton(onClick = onInterrupt) { Text("插话") }
                VoiceCallPhase.Listening -> TextButton(onClick = onFinishUtterance) { Text("说完了") }
                else -> Unit
            }
            if (phase !in listOf(VoiceCallPhase.Off, VoiceCallPhase.Paused, VoiceCallPhase.Error)) TextButton(onClick = onPause) { Text("暂停") }
            TextButton(onClick = onEnd) { Text(if (phase == VoiceCallPhase.Off) "关闭" else "结束通话") }
        }
    }
}
