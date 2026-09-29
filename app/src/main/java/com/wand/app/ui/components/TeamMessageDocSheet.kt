package com.wand.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.screens.CHAT_EMPTY_BODY
import com.wand.app.ui.screens.MarkdownText
import com.wand.app.ui.screens.TeamChatDoc
import com.wand.app.ui.screens.teamChatMarkdownDecoration
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.reduceMotionEnabled

/** 弹层正文的高度上限：视口 72%（与 Web `max-height: min(72vh, 640px)` 同比例）。 */
private const val TEAM_DOC_SHEET_HEIGHT_RATIO = 0.72f

/** 空正文也要撑住的滚动区高度（设计 §8：内容被清空时滚动区不塌陷）。 */
private val TeamDocSheetMinBodyHeight = 40.dp

/** 弹层里那一枚报告 chip 的宽度上限：超长标题让位给名字与关闭。 */
private val TeamDocChipMaxWidth = 190.dp

/**
 * 群聊全文弹层（设计 §6）：从屏幕底部向上长出的覆盖层，显示整条发言的**全文**。
 *
 * 为什么用弹层不算跳页（与 Web 同一套理由）：它是覆盖层、不换路由；关闭即回到原位，
 * 触发点自身只有一个形态（不换成「收起」、不位移、不重排）；一条几千字的成员报告
 * 就地展开会把下面的对话整体顶走，读起来反而更差。短/中内容仍然直接铺开、不折。
 *
 * 关闭路径：标题行「关闭」/ 系统返回键 / 点遮罩 / 下滑（sheet 原生）。
 * reduce-motion 下 `ModalBottomSheet` 的内建位移转场不受 `reduceMotionEnabled()` 控制（设计缺口 G2），
 * 所以按设计 §7.3 改用**无位移转场**的既有 `WandDialog` 呈现同一份内容：关闭按钮 / 返回键 /
 * 点遮罩仍然齐全，只是没有下滑手势（AlertDialog 不是 sheet）。
 */
@Composable
fun TeamMessageDocSheet(
    doc: TeamChatDoc,
    closing: Boolean,
    onRequestClose: () -> Unit,
    onDismissed: () -> Unit,
) {
    if (reduceMotionEnabled()) {
        // E3：无位移的对话框关闭瞬时归稳态，返回/遮罩/关闭按钮仍走同一请求。
        LaunchedEffect(closing) { if (closing) onDismissed() }
        WandDialog(
            title = doc.name,
            onDismissRequest = onRequestClose,
            confirm = WandDialogAction(label = "关闭", onClick = onRequestClose),
        ) {
            // 名字已经在弹窗标题上，这里只补头像 / 时刻 · 类型 / chip，避免同一行念两遍名字。
            TeamMessageDocIdentity(doc, withName = false)
            TeamMessageDocBody(doc)
        }
        return
    }
    TeamMessageDocBottomSheet(doc, closing, onRequestClose, onDismissed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamMessageDocBottomSheet(
    doc: TeamChatDoc,
    closing: Boolean,
    onRequestClose: () -> Unit,
    onDismissed: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(closing, sheetState) {
        if (!closing) return@LaunchedEffect
        if (sheetState.isVisible) sheetState.hide()
        // 先走 Material sheet 原生退场，再清快照；下滑已隐藏时只清一次。
        onDismissed()
    }
    WandBottomSheet(onDismissRequest = onRequestClose, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TeamMessageDocIdentity(doc, withName = true, modifier = Modifier.weight(1f))
                // 只留一个关闭入口（不并排两个 ✕），下拉手势与返回键由 sheet 原生承担。
                TextButton(onClick = onRequestClose) {
                    Text("关闭", style = MaterialTheme.typography.labelLarge, color = WandColors.brand)
                }
            }
            HorizontalDivider(color = WandColors.border.copy(alpha = 0.6f))
            TeamMessageDocBody(doc)
        }
    }
}

/** 头像 24dp + 名字（可选）+ 「时刻 · 类型」+ 报告 chip。 */
@Composable
private fun TeamMessageDocIdentity(
    doc: TeamChatDoc,
    withName: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TeamMessageAvatar(doc.avatar, size = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            if (withName) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = WandColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOf(doc.clock, doc.typeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (doc.chip.isNotBlank()) {
            Text(
                doc.chip,
                fontSize = 11.sp,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = TeamDocChipMaxWidth),
            )
        }
    }
}

/** 正文：全文（一定长于预览）+ 可滚动；空内容给一句话，滚动区不塌陷。 */
@Composable
private fun TeamMessageDocBody(doc: TeamChatDoc) {
    val maxBodyHeight = LocalConfiguration.current.screenHeightDp.dp * TEAM_DOC_SHEET_HEIGHT_RATIO
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TeamDocSheetMinBodyHeight, max = maxBodyHeight)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 4.dp),
    ) {
        if (doc.text.isBlank()) {
            Text(CHAT_EMPTY_BODY, style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
        } else {
            MarkdownText(doc.text, inlineDecoration = teamChatMarkdownDecoration(doc.mentionNames))
        }
    }
}
