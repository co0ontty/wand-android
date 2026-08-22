package com.wand.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wand.app.data.WandHttp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 聊天里服务器文本文件的应用内预览：走 /api/file-raw（带鉴权）拉取文本，
 * 超长截断展示。替代旧流程的「下载到公开 Downloads + 外部应用打开」——
 * 常见代码/文本文件不再落盘、不再跳出去。
 */
object WandTextPreview {

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "mdx", "rst", "log", "json", "jsonl", "ndjson",
        "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env",
        "xml", "html", "htm", "css", "scss", "less",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts",
        "py", "rb", "go", "rs", "java", "kt", "kts", "scala", "groovy",
        "c", "h", "cpp", "hpp", "cc", "hh", "cs", "swift", "m", "mm",
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
        "sql", "csv", "tsv", "gradle", "lock", "diff", "patch",
        "vue", "svelte", "astro", "proto", "graphql", "gql", "dockerfile", "makefile",
    )

    /** 预览拉取上限：超过即截断，避免把终端日志整个读进内存。 */
    const val MAX_BYTES = 512 * 1024

    /** 是否值得做应用内文本预览（按扩展名，大小写不敏感）。 */
    fun isPreviewableText(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val clean = path.trim().substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/').lowercase()
        if (name in setOf("dockerfile", "makefile", "gemfile", "rakefile", "license", "readme")) return true
        val extension = name.substringAfterLast('.', "")
        return extension in TEXT_EXTENSIONS
    }

    /** 拉取文本，返回 (内容, 是否被截断)；非 2xx 或空 body 抛异常。 */
    suspend fun fetchText(baseUrl: String, path: String): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(WandImage.fileRawUrl(baseUrl, path)).get().build()
            WandHttp.clientFor(baseUrl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("服务端返回 HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("服务端没有返回文件内容")
                val source = body.source()
                val truncated = source.request(MAX_BYTES + 1L) && source.buffer.size > MAX_BYTES
                val text = source.readUtf8(minOf(source.buffer.size, MAX_BYTES.toLong()))
                text to truncated
            }
        }
}

@Composable
fun TextPreviewDialog(
    path: String,
    baseUrl: String,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    var content by remember(path) { mutableStateOf<String?>(null) }
    var truncated by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path, baseUrl) {
        runCatching { WandTextPreview.fetchText(baseUrl, path) }
            .onSuccess { (text, wasTruncated) ->
                content = text
                truncated = wasTruncated
            }
            .onFailure { error = it.message ?: "未知错误" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(WandShapes.lg)
                .background(WandColors.bgElevated)
                .border(1.dp, WandColors.border, WandShapes.lg)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    path.substringAfterLast('/').ifEmpty { path },
                    style = MaterialTheme.typography.titleSmall,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(WandColors.surfaceSoft)
                        .clickableWithoutRipple { onDismiss() },
                ) {
                    androidx.compose.material3.Icon(
                        WandIcons.close,
                        contentDescription = "关闭",
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(min = 120.dp, max = 420.dp)
                    .clip(WandShapes.sm)
                    .background(WandColors.surfaceSoft.copy(alpha = 0.5f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                when {
                    content != null -> SelectionContainer {
                        Text(
                            content.orEmpty() + if (truncated) "\n\n…（内容过长，已截断）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    error != null -> Text(
                        "加载失败：$error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.danger,
                    )
                    else -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).align(Alignment.Center),
                        color = WandColors.brand,
                        strokeWidth = 2.dp,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onOpenExternally) {
                    Text("用其他应用打开", color = WandColors.textSecondary, fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = WandColors.brand, fontSize = 13.sp)
                }
            }
        }
    }
}
