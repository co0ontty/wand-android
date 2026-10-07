package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ContentBlock
import com.wand.app.ui.FullscreenImageViewer
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.TextPreviewDialog
import com.wand.app.ui.WandImage
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.WandTextPreview
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.theme.WandColors
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal fun toolActivityOpensFile(use: ContentBlock.ToolUse): Boolean =
    toolActivityKind(use) in setOf("read_file", "edit_file")

/** 路径只取真实参数，不从匿名 fileKey、展示标题或结果摘录猜测；相对路径按服务端会话 cwd 解析。 */
internal fun toolActivityFilePath(input: JSONObject, cwd: String?): String? {
    val raw = listOf("move_path", "file_path", "path", "filename", "file", "notebook_path")
        .firstNotNullOfOrNull { (input.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank) }
        ?: return null
    if (raw.startsWith("~") || raw.contains('\u0000')) return null
    return runCatching {
        val path = Paths.get(raw)
        if (path.isAbsolute) path.normalize().toString()
        else cwd?.takeIf { it.startsWith("/") }?.let { Paths.get(it).resolve(path).normalize().toString() }
    }.getOrNull()
}

/** 文件条目的详情只有一个入口；展开不取正文，点击「查看文件」才解析路径和打开公共预览。 */
@Composable
internal fun ToolActivityFileAction(use: ContentBlock.ToolUse, open: Boolean) {
    val sessionId = LocalChatSessionId.current
    val api = LocalChatApi.current
    val cwd = LocalChatWorkingDirectory.current
    val baseUrl = LocalServerBaseUrl.current
    val context = LocalContext.current
    var request by remember(sessionId, use.id, baseUrl) { mutableIntStateOf(0) }
    var loading by remember(sessionId, use.id, baseUrl) { mutableStateOf(false) }
    var openExternally by remember(sessionId, use.id, baseUrl) { mutableStateOf(false) }
    var error by remember(sessionId, use.id, baseUrl) { mutableStateOf<String?>(null) }
    var previewPath by remember(sessionId, use.id, baseUrl) { mutableStateOf<String?>(null) }
    var resolvedPath by remember(sessionId, use.id, baseUrl, cwd) { mutableStateOf<String?>(null) }

    LaunchedEffect(request, open, sessionId, use.id, baseUrl, cwd) {
        if (!open) {
            request = 0
            loading = false
            error = null
            previewPath = null
            return@LaunchedEffect
        }
        if (request == 0) return@LaunchedEffect
        error = null
        try {
            val path = resolvedPath ?: toolActivityFilePath(use.input, cwd) ?: run {
                if (api == null || sessionId.isBlank() || use.id.isBlank()) {
                    throw IllegalStateException("无法获取此调用的文件路径")
                }
                val detail = api.fetchToolDetail(sessionId, use.id)
                toolActivityFilePath(detail.input, cwd)
                    ?: throw IllegalStateException("此调用未提供可查看的文件路径")
            }
            resolvedPath = path
            if (!openExternally && (WandImage.isImagePath(path) || WandTextPreview.isPreviewableText(path))) {
                previewPath = path
            } else {
                WandServerFileLink.downloadAndOpen(context, baseUrl, path)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "文件加载失败，请重试"
        } finally {
            loading = false
            openExternally = false
            request = 0
        }
    }

    Column {
        TextButton(
            enabled = open && !loading && baseUrl.isNotBlank(),
            onClick = { loading = true; request += 1 },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.widthIn(min = 96.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WandStatusIconSlot(
                    indicatorColor = WandColors.brand, containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    running = loading, icon = WandIcons.read, boxSize = 18.dp, iconSize = 14.dp, cornerRadius = 4.dp,
                )
                Text("查看文件", fontSize = 11.5.sp, color = WandColors.brand)
            }
        }
        Text(error ?: if (loading) "正在打开…" else "查看文件当前版本",
            fontSize = 11.sp, color = if (error != null) WandColors.danger else WandColors.textMuted)
    }
    previewPath?.let { path ->
        if (WandImage.isImagePath(path)) {
            FullscreenImageViewer(path = path, baseUrl = baseUrl, onDismiss = { previewPath = null })
        } else {
            TextPreviewDialog(path = path, baseUrl = baseUrl, onDismiss = { previewPath = null },
                onOpenExternally = { previewPath = null; openExternally = true; loading = true; request += 1 })
        }
    }
}
