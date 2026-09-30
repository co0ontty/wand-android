package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ContentBlock
import com.wand.app.data.ToolContentDetail
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled

internal data class ToolActivityCategory(
    val kind: String,
    val entries: List<ToolActivityEntry>,
) {
    val count: Int get() = entries.size

    val title: String
        get() = when (kind) {
            "edit_file" -> "修改了 $count 个文件"
            "read_file" -> "查看了 $count 个文件"
            "run_command" -> "运行了 $count 条命令"
            else -> "其他 $count 次调用"
        }

    val itemName: String
        get() = when (kind) {
            "edit_file" -> "文件修改"
            "read_file" -> "文件查看"
            "run_command" -> "命令"
            else -> "工具调用"
        }
}

/** 同一文件的一条菜单入口，可包含多次修改或查看；命令和其他调用各自独立。 */
internal data class ToolActivityEntry(val calls: List<DisplayItem.Tool>)

/** 摘要与菜单共用同一组入口；文件按匿名键合并，详情保留每个 tool id。 */
internal fun toolActivityCategories(items: List<DisplayItem>): List<ToolActivityCategory> {
    val order = listOf("edit_file", "read_file", "run_command", "other")
    val seenIds = mutableSetOf<String>()
    val calls = items.filterIsInstance<DisplayItem.Tool>().filter { item ->
        item.use.id.isBlank() || seenIds.add(item.use.id)
    }
    return order.mapNotNull { kind ->
        val matching = calls.filter { toolActivityKind(it.use) == kind }
        if (matching.isEmpty()) return@mapNotNull null
        val entries = if (kind == "edit_file" || kind == "read_file") {
            val byFile = linkedMapOf<String, MutableList<DisplayItem.Tool>>()
            matching.forEachIndexed { index, item ->
                val fileKey = item.use.activity?.fileKey?.takeIf { it.isNotBlank() }
                    ?: item.use.input.optString("file_path").takeIf { it.isNotBlank() }
                    ?: item.use.input.optString("path").takeIf { it.isNotBlank() }
                    ?: item.use.id.takeIf { it.isNotBlank() }
                    ?: "item-$index"
                byFile.getOrPut(fileKey) { mutableListOf() }.add(item)
            }
            byFile.values.map { ToolActivityEntry(it.toList()) }
        } else {
            matching.map { ToolActivityEntry(listOf(it)) }
        }
        ToolActivityCategory(kind, entries)
    }
}

private fun toolActivityKind(use: ContentBlock.ToolUse): String {
    use.activity?.kind?.takeIf { it in setOf("edit_file", "read_file", "run_command", "other") }
        ?.let { return it }
    val name = use.name.lowercase().substringAfterLast("__")
    val hasFilePath = listOf("file_path", "path", "notebook_path").any { key ->
        (use.input.opt(key) as? String)?.trim()?.isNotEmpty() == true
    }
    return when {
        listOf("bash", "exec", "command", "shell", "terminal").any { it in name } -> "run_command"
        hasFilePath && listOf("edit", "write", "replace", "notebookedit").any { it in name } -> "edit_file"
        hasFilePath && (name == "read" || name == "read_file") -> "read_file"
        else -> "other"
    }
}

/** 对话末尾的轻量活动摘要。分类展开只渲染占位条目，单条点开才请求完整内容。 */
@Composable
internal fun ToolActivitySummary(group: ActivityGroup, scope: String) {
    val categories = remember(group.items) { toolActivityCategories(group.items) }
    if (categories.isEmpty()) return
    val key = cardFoldKey(LocalChatSessionId.current, scope)
    var menuOpen by rememberSaveable(key) { mutableStateOf(false) }
    val motion = !reduceMotionEnabled()
    val pulse = if (group.running && motion) {
        val transition = rememberInfiniteTransition(label = "toolActivityRunning")
        val alpha by transition.animateFloat(
            initialValue = WandMotion.activityTextAlphaMin,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "toolActivityAlpha",
        )
        alpha
    } else {
        1f
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = if (menuOpen) "收起工具分类" else "查看工具分类") {
                    menuOpen = !menuOpen
                }
                .semantics { stateDescription = if (menuOpen) "已展开" else "已收起" }
                .padding(horizontal = 4.dp, vertical = 9.dp),
        ) {
            if (group.running) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(WandColors.brand)
                        .graphicsLayer { alpha = if (motion) {
                            WandMotion.breathAlphaMin +
                                (pulse - WandMotion.activityTextAlphaMin) /
                                (1f - WandMotion.activityTextAlphaMin) *
                                (1f - WandMotion.breathAlphaMin)
                        } else 1f },
                )
            }
            Text(
                categories.joinToString(" · ") { it.title },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = WandColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = pulse },
            )
            ExpandChevron(
                expanded = menuOpen,
                tint = WandColors.textMuted,
                size = 14.dp,
                contentDescription = null,
            )
        }
        ToolActivityReveal(menuOpen) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            ) {
                categories.forEach { category ->
                    ToolActivityCategorySection(category, "$key/${category.kind}", group.running)
                }
            }
        }
    }
}

@Composable
private fun ToolActivityCategorySection(category: ToolActivityCategory, key: String, running: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            category.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textMuted,
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 3.dp),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            category.entries.forEachIndexed { index, entry ->
                ToolActivityEntryRow(
                    entry,
                    "${category.itemName} ${index + 1}",
                    "$key/${entry.calls.first().use.id.ifBlank { index.toString() }}",
                    running,
                )
            }
        }
    }
}

@Composable
private fun ToolActivityEntryRow(entry: ToolActivityEntry, label: String, key: String, running: Boolean) {
    val results = entry.calls.map { it.result }
    val status = when {
        results.any { it?.isError == true } -> "失败"
        results.all { it != null } -> "完成"
        running -> "运行中"
        else -> "未返回"
    }
    var open by rememberSaveable(key) { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = "查看$label") { open = !open }
                .semantics { stateDescription = if (open) "已展开" else "已收起" }
                .padding(horizontal = 8.dp, vertical = 9.dp),
        ) {
            Text(label, fontSize = 11.sp, color = WandColors.textSecondary, modifier = Modifier.weight(1f))
            Text(
                status,
                fontSize = 10.sp,
                color = if (status == "失败") WandColors.danger else WandColors.textMuted,
            )
            ExpandChevron(
                expanded = open,
                tint = WandColors.textMuted,
                modifier = Modifier.padding(start = 6.dp),
                size = 14.dp,
                contentDescription = null,
            )
        }
        ToolActivityReveal(open) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, bottom = 8.dp),
            ) {
                entry.calls.forEachIndexed { index, item ->
                    if (entry.calls.size > 1) {
                        Text(
                            "第 ${index + 1} 次调用",
                            fontSize = 10.sp,
                            color = WandColors.textMuted,
                        )
                    }
                    ToolActivitySingleDetail(
                        item = item,
                        key = "$key/${item.use.id.ifBlank { index.toString() }}",
                        open = open,
                        running = running && item.result == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolActivitySingleDetail(item: DisplayItem.Tool, key: String, open: Boolean, running: Boolean) {
    val use = item.use
    val result = item.result
    val sessionId = LocalChatSessionId.current
    val api = LocalChatApi.current
    var detail by remember(sessionId, use.id) { mutableStateOf<ToolContentDetail?>(null) }
    var loading by remember(sessionId, use.id) { mutableStateOf(false) }
    var error by remember(sessionId, use.id) { mutableStateOf<String?>(null) }
    var retry by remember(sessionId, use.id) { mutableIntStateOf(0) }

    LaunchedEffect(open, result != null, retry, sessionId, use.id) {
        if (!open || api == null || sessionId.isBlank() || use.id.isBlank()) return@LaunchedEffect
        if (detail != null && (result == null || detail?.result != null)) return@LaunchedEffect
        loading = true
        error = null
        try {
            detail = api.fetchToolDetail(sessionId, use.id)
        } catch (failure: Exception) {
            error = failure.message ?: "加载失败，请重试"
        } finally {
            loading = false
        }
    }

    when {
        loading -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = WandColors.brand,
        )
        error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error.orEmpty(), fontSize = 11.sp, color = WandColors.danger, modifier = Modifier.weight(1f))
            TextButton(onClick = { retry += 1 }) { Text("重试") }
        }
        detail != null -> ToolActivityLoadedCard(use, detail!!, key, running)
        else -> Text(
            "此调用暂时无法加载详情",
            fontSize = 11.sp,
            color = WandColors.textMuted,
        )
    }
}

@Composable
private fun ToolActivityLoadedCard(
    use: ContentBlock.ToolUse,
    detail: ToolContentDetail,
    key: String,
    running: Boolean,
) {
    val input = detail.input
    val result = detail.result
    CompositionLocalProvider(LocalActivityFoldCompact provides true) {
        when (use.name) {
            "Edit", "Write", "MultiEdit" -> DiffCard(
                toolName = use.name,
                input = input,
                result = result,
                running = running,
                expandDefault = true,
                foldKey = "$key/detail",
            )
            "Bash" -> TerminalCard(
                input = input,
                result = result,
                running = running,
                expandDefault = true,
                foldKey = "$key/detail",
            )
            else -> ToolCard(
                use = use.copy(input = input),
                result = result,
                running = running,
                expandDefault = true,
                foldKey = "$key/detail",
            )
        }
    }
}

@Composable
private fun ToolActivityReveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = !reduceMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (motion) fadeIn(WandMotion.tweenEnter()) + expandVertically(animationSpec = WandMotion.tweenEnter())
            else fadeIn(androidx.compose.animation.core.snap()),
        exit = if (motion) fadeOut(WandMotion.tweenExit()) + shrinkVertically(animationSpec = WandMotion.tweenExit())
            else fadeOut(androidx.compose.animation.core.snap()),
    ) { content() }
}
