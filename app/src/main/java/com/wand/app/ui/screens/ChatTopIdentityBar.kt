package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.statusBreath
import com.wand.app.ui.components.WandCapsuleTitle
import com.wand.app.ui.components.AvatarFileActivity
import com.wand.app.ui.sessionCwdLeaf
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.GlassBackdrop

/**
 * 会话顶栏身份：主标题优先任务名，没有归属任务（独立会话）时回落会话标题；小字为
 * 「员工名 · 目录缩写」，缺项自动省掉分隔符。与 iOS `chatTopIdentity` 同语义。
 */
data class ChatTopIdentity(val title: String, val subtitle: String)

fun chatTopIdentity(taskTitle: String?, sessionTitle: String?, employeeName: String?, cwd: String?): ChatTopIdentity {
    fun clean(value: String?) = value?.trim().orEmpty()
    val task = clean(taskTitle)
    val title = task.ifEmpty { clean(sessionTitle) }
    val subtitle = listOf(clean(employeeName), sessionCwdLeaf(cwd).orEmpty())
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
    return ChatTopIdentity(title.ifEmpty { "当前会话" }, subtitle)
}

/** 顶栏状态点的语义档位，由会话的真实状态位判定，不从展示文案反推。 */
enum class ChatTopAttention { Quiet, Running, NeedsYou, Fault }

fun chatTopAttention(
    connected: Boolean,
    blocked: Boolean,
    sending: Boolean,
    responding: Boolean,
    failed: Boolean,
): ChatTopAttention = when {
    !connected || failed -> ChatTopAttention.Fault
    blocked -> ChatTopAttention.NeedsYou
    sending || responding -> ChatTopAttention.Running
    else -> ChatTopAttention.Quiet
}

/** 顶栏的读屏播报：视觉上只剩标题、小字和一个状态点，完整信息仍要能被读出来。 */
fun chatTopReadback(title: String, subtitle: String, status: String): String =
    listOf(title, subtitle, status).filter { it.isNotEmpty() }.joinToString("，")

/** 两行身份文字的真实高度；身份区高度与阅读视口上边界共用同一份计算，不再为头像占位。 */
@Composable
internal fun chatTopIdentityTextHeight(): Dp {
    val density = LocalDensity.current
    return with(density) {
        MaterialTheme.typography.labelMedium.lineHeight.toDp() + 2.dp +
            MaterialTheme.typography.labelSmall.lineHeight.toDp()
    }
}

@Composable
internal fun chatTopIdentityClearance(): Dp =
    maxOf(ChatFloatingControlsClearance, chatTopIdentityTextHeight() + 8.dp)

/**
 * 会话顶栏身份区：胶囊里左侧是圆圈头像，右侧是任务名主标题与「员工名 · 目录缩写」小字。
 * 运行与异常只以一个状态点呈现，具体文案交给权限卡、连接横幅和待办进度条，读屏仍播报完整状态。
 */
@Composable
internal fun ChatTopIdentityBar(
    identity: ChatTopIdentity,
    attention: ChatTopAttention,
    statusText: String,
    modifier: Modifier = Modifier,
    avatarId: String? = null,
    avatarName: String? = null,
    avatar: String? = null,
    fileActivity: AvatarFileActivity = AvatarFileActivity.Idle,
    thinking: Boolean = false,
    backdrop: GlassBackdrop? = null,
) {
    val dotColor = when (attention) {
        ChatTopAttention.Quiet -> null
        ChatTopAttention.Running -> WandColors.brand
        ChatTopAttention.NeedsYou -> WandColors.permission
        ChatTopAttention.Fault -> WandColors.danger
    }
    Box(modifier.fillMaxWidth().height(chatTopIdentityClearance()), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FloatingControlsBackInset, end = FloatingControlsActionsInset)
                .semantics {
                    contentDescription = chatTopReadback(identity.title, identity.subtitle, statusText)
                    if (statusText.isNotEmpty()) liveRegion = LiveRegionMode.Polite
                },
        ) {
            val tone = dotColor
            if (tone != null) {
                Box(
                    Modifier.size(6.dp)
                        .then(if (attention == ChatTopAttention.Running) Modifier.statusBreath() else Modifier)
                        .background(tone, CircleShape),
                )
            }
            WandCapsuleTitle(
                title = identity.title,
                subtitle = identity.subtitle,
                avatarId = avatarId?.takeIf { it.isNotBlank() } ?: "e_wand_default",
                avatarName = avatarName,
                avatar = avatar,
                fileActivity = fileActivity,
                thinking = thinking,
                backdrop = backdrop,
            )
        }
    }
}
