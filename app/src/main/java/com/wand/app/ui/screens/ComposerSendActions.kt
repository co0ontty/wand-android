package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import com.wand.app.ui.components.WandMorphingIcon
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled

/** 运行中的停止操作始终可达：主按钮不是停止时，由加号面板承接。 */
internal fun composerMenuHasStop(running: Boolean, primary: SendActionVisual): Boolean =
    running && primary != SendActionVisual.Stop

/** 单聊与群聊共用固定的语音槽和主操作槽，发送时不再额外插入第三枚按钮。 */
@Composable
internal fun ComposerSendStopActions(
    voiceAction: @Composable () -> Unit,
    visual: SendActionVisual,
    stopDescription: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendDescription: String = "发送消息",
    busy: Boolean = false,
    canSubmit: Boolean = true,
    failureDescription: String = "发送失败，可重试",
) {
    voiceAction()
    SubmitMorphButton(
        visual = visual,
        contentDescription = when (visual) {
            SendActionVisual.Sending -> "发送中"
            SendActionVisual.Sent -> "已发送"
            SendActionVisual.Failed -> failureDescription
            SendActionVisual.Blocked -> "当前没有可发送内容"
            SendActionVisual.Stop -> stopDescription
            SendActionVisual.Send -> sendDescription
        },
        onClick = if (visual == SendActionVisual.Stop) onStop else onSend,
        enabled = !busy && (visual == SendActionVisual.Stop || (visual == SendActionVisual.Send && canSubmit)),
        fillColor = when (visual) {
            SendActionVisual.Send, SendActionVisual.Sending, SendActionVisual.Sent -> WandColors.brand
            SendActionVisual.Failed -> WandColors.dangerSoft
            SendActionVisual.Stop -> WandColors.brand
            SendActionVisual.Blocked -> WandColors.textSecondary.copy(alpha = 0.16f)
        },
        contentTint = when (visual) {
            SendActionVisual.Send, SendActionVisual.Sending, SendActionVisual.Sent -> Color.White
            SendActionVisual.Failed -> WandColors.danger
            SendActionVisual.Stop -> WandColors.onBrand
            SendActionVisual.Blocked -> WandColors.textSecondary
        },
    )
}

/**
 * 提交按钮：箭头 / 转圈 / 对勾 / 叉 / 停止在固定圆形底内交叉淡入 + 缩放。
 * 按钮本身不移动、不变大（规则 3「全程在同一位置完成」+ 规则 4「同构变形」）。
 */
@Composable
internal fun SubmitMorphButton(
    visual: SendActionVisual,
    contentDescription: String,
    onClick: () -> Unit,
    fillColor: Color,
    contentTint: Color,
    enabled: Boolean = true,
) {
    // 底色也在原地过渡：品牌色 → 送达/失败态，不会出现一帧生硬的换色。
    val animatedFill by animateColorAsState(
        targetValue = fillColor,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenFast()),
        label = "submitFill",
    )
    val morphProgress by animateFloatAsState(if (visual == SendActionVisual.Stop) 1f else 0f,
        WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.morph()), label = "submitActionMorph")
    val key: Any = if (visual in listOf(SendActionVisual.Send, SendActionVisual.Stop, SendActionVisual.Blocked)) "action" else visual
    FilledComposerAction(
        enabled = enabled,
        fillColor = animatedFill,
        contentDescription = contentDescription,
        onClick = onClick,
    ) {
        WandInPlaceSwap(contentKey = key, modifier = Modifier.size(ComposerActionIconSize), enterScale = .95f, exitScale = 1.05f) { state ->
            when (state) {
                "action" -> WandMorphingIcon(morphProgress, WandIcons.arrowUp, WandIcons.stop, contentTint, iconSize = ComposerActionIconSize, rotationDegrees = 18f)
                SendActionVisual.Sending -> if (reduceMotionEnabled()) Icon(WandIcons.refresh, null, tint = contentTint, modifier = Modifier.size(ComposerActionIconSize)) else CircularProgressIndicator(
                    color = contentTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
                SendActionVisual.Sent -> Icon(
                    WandIcons.check,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
                SendActionVisual.Failed -> Icon(
                    WandIcons.statusFail,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
                SendActionVisual.Stop -> Icon(
                    WandIcons.stop,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
                else -> Icon(
                    WandIcons.arrowUp,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
            }
        }
    }
}
