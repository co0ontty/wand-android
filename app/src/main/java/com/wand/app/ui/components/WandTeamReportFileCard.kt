package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.TeamReportFile
import com.wand.app.ui.TextPreviewDialog
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.screens.teamReportFileSize
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 与 IM 文件消息一致：文件身份与大小先显示，正文只在点击后读取。 */
@Composable
fun WandTeamReportFileCard(file: TeamReportFile, baseUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var preview by remember(file.stepId, file.path, baseUrl) { mutableStateOf(false) }
    var downloading by remember(file.stepId, file.path, baseUrl) { mutableStateOf(false) }
    var opened by remember(file.stepId, file.path, baseUrl) { mutableStateOf(false) }
    var error by remember(file.stepId, file.path, baseUrl) { mutableStateOf<String?>(null) }
    val title = file.preview?.title ?: file.name
    val excerpt = file.preview?.excerpt?.takeIf { it.isNotBlank() }
    fun download() {
        if (downloading || baseUrl.isBlank()) return
        downloading = true
        opened = false
        error = null
        coroutineScope.launch {
            try {
                WandServerFileLink.downloadAndOpen(context, baseUrl, file.path)
                opened = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "文件下载失败"
            } finally {
                downloading = false
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(WandShapes.md).border(0.55.dp, WandColors.border, WandShapes.md)
            .background(WandColors.surfaceSoft),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(
                enabled = baseUrl.isNotBlank(), role = Role.Button,
                onClickLabel = "查看完整报告：$title", onClick = { preview = true },
            ).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (excerpt != null) Text(excerpt, fontSize = 12.sp, lineHeight = 19.sp,
                    color = WandColors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.size(54.dp, 76.dp).clip(WandShapes.xs)
                .border(0.55.dp, WandColors.border, WandShapes.xs).background(WandColors.surface)
                .padding(6.dp).clearAndSetSemantics { contentDescription = "文档缩略图" },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(WandIcons.toolResult, contentDescription = null, tint = WandColors.brand,
                    modifier = Modifier.size(12.dp))
                Text(title, fontSize = 6.sp, lineHeight = 8.sp, fontWeight = FontWeight.SemiBold,
                    color = WandColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(file.preview?.excerpt.orEmpty(), fontSize = 6.sp, lineHeight = 8.sp,
                    color = WandColors.textMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, fontSize = 10.sp, color = WandColors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Markdown · ${teamReportFileSize(file.size)}", fontSize = 10.sp, color = WandColors.textMuted)
            }
            Text(when { downloading -> "下载中…"; error != null -> "重试下载"; opened -> "已打开"; else -> "下载" },
                fontSize = 12.sp, color = WandColors.brand, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 80.dp).heightIn(min = 40.dp)
                    .clip(WandShapes.xs).clickable(enabled = !downloading && baseUrl.isNotBlank(),
                        role = Role.Button, onClickLabel = "下载${file.name}", onClick = { download() })
                    .padding(vertical = 10.dp))
        }
        error?.let { Text(it, fontSize = 11.sp, color = WandColors.danger,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
    }
    if (preview) TextPreviewDialog(path = file.path, baseUrl = baseUrl,
        onDismiss = { preview = false }, onOpenExternally = { preview = false; download() })
}

