package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

private data class PtyGuideStep(
    val title: String,
    val body: String,
    val tip: String,
    val icon: ImageVector,
    val previewKeys: List<String>,
)

private val PtyGuideSteps = listOf(
    PtyGuideStep(
        title = "先写好，再发送",
        body = "底部输入栏适合完整的提示词和命令。内容会先留在本机草稿里，点发送后才写入当前 PTY。",
        tip = "适合长文本，也能继续使用附件和语音输入。",
        icon = WandIcons.terminal,
        previewKeys = listOf("输入…", "↑"),
    ),
    PtyGuideStep(
        title = "快捷键直接控制 PTY",
        body = "快捷键条会绕过草稿，立即把终端控制序列写入 PTY。方向键可移动光标、切换历史或选择 TUI 菜单。",
        tip = "按住方向键、退格、删除、Home 或 End 可以连续触发。",
        icon = WandIcons.keyboard,
        previewKeys = listOf("Esc", "Tab", "←", "↑", "↓", "→"),
    ),
    PtyGuideStep(
        title = "按你的习惯映射",
        body = "在系统设置的“终端快捷键”里，可以隐藏内置键，并组合 Ctrl、Alt、Shift 与字符或特殊键。",
        tip = "外接键盘的方向键、Esc、Tab、编辑键和 Ctrl 组合也会直接传给 PTY。",
        icon = WandIcons.tune,
        previewKeys = listOf("Ctrl+C", "Alt+←", "Shift+Tab"),
    ),
)

@Composable
internal fun PtyQuickStartGuideDialog(
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val step = PtyGuideSteps[stepIndex]
    val lastStep = stepIndex == PtyGuideSteps.lastIndex

    WandDialog(
        title = if (stepIndex == 0) "PTY 快速上手" else step.title,
        icon = step.icon,
        onDismissRequest = onDismiss,
        dismiss = WandDialogAction(
            label = if (stepIndex == 0) "稍后再看" else "关闭",
            onClick = onDismiss,
        ),
        confirm = WandDialogAction(
            label = if (lastStep) "开始使用" else "下一步",
            onClick = {
                if (lastStep) onFinished() else stepIndex += 1
            },
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (stepIndex == 0) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = WandColors.textPrimary,
                )
            }
            Text(
                step.body,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = WandColors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                step.previewKeys.forEach { label -> PtyGuideKeycap(label) }
            }
            Text(
                step.tip,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = WandColors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WandColors.surfaceSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PtyGuideSteps.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (index == stepIndex) 7.dp else 5.dp)
                            .background(
                                if (index == stepIndex) WandColors.brand else WandColors.borderStrong,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.PtyGuideKeycap(label: String) {
    val widthModifier = if (label == "输入…") Modifier.weight(1f) else Modifier
    Box(
        modifier = widthModifier
            .background(WandColors.surface, RoundedCornerShape(9.dp))
            .border(0.7.dp, WandColors.borderStrong, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = WandColors.textPrimary,
            maxLines = 1,
        )
    }
}
