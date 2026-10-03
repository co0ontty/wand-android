package com.wand.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.wand.app.data.ContentBlock
import com.wand.app.ui.theme.WandColors

/** The server owns factual previews; old history can still show its available text. */
internal fun toolInputCardPreview(use: ContentBlock.ToolUse): String =
    use.preview?.takeIf(String::isNotBlank) ?: listOf(
        "command", "cmd", "pattern", "query", "file_path", "path", "url", "subject", "task", "description",
    ).firstNotNullOfOrNull { key -> (use.input.opt(key) as? String)?.takeIf(String::isNotBlank) }
        ?.replace(Regex("\\s+"), " ")?.take(180).orEmpty()

internal fun toolResultCardPreview(result: ContentBlock.ToolResult?): String =
    result?.preview?.takeIf(String::isNotBlank) ?: result?.text?.lineSequence()
        ?.filter(String::isNotBlank)?.take(2)?.joinToString(" · ")?.take(180).orEmpty()

@Composable
internal fun ToolPreviewText(text: String, color: Color = WandColors.textSecondary) {
    if (text.isBlank()) return
    Text(text, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
        color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
