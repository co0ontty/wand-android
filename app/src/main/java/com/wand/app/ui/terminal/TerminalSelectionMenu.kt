package com.wand.app.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled

private val MenuShape = RoundedCornerShape(10.dp)

/**
 * In-place actions for a finished terminal selection. It grows from the top of
 * the terminal instead of opening another screen.
 */
@Composable
internal fun TerminalSelectionBar(
    visible: Boolean,
    showSelectLine: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectLine: () -> Unit,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = !reduceMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) {
            fadeIn(WandMotion.tweenEnter()) +
                expandVertically(WandMotion.tweenEnter(), expandFrom = Alignment.Top)
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            fadeOut(WandMotion.tweenExit()) +
                shrinkVertically(WandMotion.tweenExit(), shrinkTowards = Alignment.Top)
        } else {
            ExitTransition.None
        },
    ) {
        Row(
            modifier = Modifier
                .clip(MenuShape)
                .background(Color(TerminalPalette.capsuleArgb).copy(alpha = 0.96f))
                .border(0.6.dp, Color(TerminalPalette.hairlineArgb), MenuShape)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuAction("复制", "复制选中文字", onCopy)
            MenuAction("粘贴", "粘贴到终端", onPaste)
            if (showSelectLine) MenuAction("整行", "选中整行", onSelectLine)
            MenuAction("全部", "复制终端全部文字", onCopyAll)
            MenuAction("关闭", "取消选择", onDismiss)
        }
    }
}

@Composable
private fun MenuAction(label: String, description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(40.dp)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(label, color = Color(TerminalPalette.foregroundArgb), fontSize = 13.sp)
    }
}
