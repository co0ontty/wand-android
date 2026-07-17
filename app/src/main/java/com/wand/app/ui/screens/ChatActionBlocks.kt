package com.wand.app.ui.screens

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.CardExpandDefaults
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SubagentMeta
import com.wand.app.data.ToolUseSemantic
import com.wand.app.data.TurnUsage
import com.wand.app.data.WandApi
import com.wand.app.data.arrayField
import com.wand.app.data.int
import com.wand.app.data.str
import com.wand.app.data.summaryText
import com.wand.app.ui.AskUserSelectionState
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.components.toolIcon
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassCard
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.tinted
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** AskUser、Diff、终端与 Todo 等可操作聊天卡片。 */

// MARK: - AskUserQuestion 交互卡片（对齐 Web 端 ask-user 卡）

/** AskUserQuestion 的一道题（tool_use input.questions[i]），字段对齐 Web 端 chat-render.ts。 */
data class AskUserQuestionData(
    val question: String,
    val header: String?,
    val multiSelect: Boolean,
    val options: List<Option>,
) {
    data class Option(val label: String, val description: String?)

    companion object {
        /** 从 tool_use 的 input 解析 questions 数组；形状不符返回空列表（上层回落通用工具卡）。 */
        fun parse(input: JSONObject): List<AskUserQuestionData> {
            val items = input.arrayField("questions") ?: return emptyList()
            val result = mutableListOf<AskUserQuestionData>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val optionsArr = obj.optJSONArray("options") ?: continue
                val options = mutableListOf<Option>()
                for (j in 0 until optionsArr.length()) {
                    val opt = optionsArr.optJSONObject(j) ?: continue
                    val label = opt.str("label") ?: ""
                    options.add(
                        Option(
                            label = label.ifEmpty { "选项 ${options.size + 1}" },
                            description = opt.str("description"),
                        )
                    )
                }
                if (options.isEmpty()) continue
                result.add(
                    AskUserQuestionData(
                        question = obj.str("question") ?: "",
                        header = obj.str("header"),
                        multiSelect = obj.optBoolean("multiSelect", false),
                        options = options,
                    )
                )
            }
            return result
        }
    }
}

/**
 * 提问卡：头部「? 提问 · header」，body 是题目 + 选项列表 + 确认提交。
 * 未答可交互（单选/多选），已答（配对到 tool_result）转只读并高亮用户选过的项。
 */
@Composable
fun AskUserQuestionCard(
    toolUseId: String,
    questions: List<AskUserQuestionData>,
    result: ContentBlock.ToolResult?,
    selection: AskUserSelectionState,
    onToggle: (Int, Int, Boolean) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val isAnswered = result != null
    // 已答时按行拆答案：每道题一行，行内 ", " 分隔多选 label（对齐 Web 的解析）。
    val answerLines = remember(result?.text) {
        result?.text?.trim()?.takeIf { it.isNotEmpty() }?.split("\n") ?: emptyList()
    }
    var expanded by remember { mutableStateOf(!isAnswered) }
    // 回答送达后自动折叠（对齐 Web 已答默认折叠）。
    LaunchedEffect(isAnswered) { if (isAnswered) expanded = false }
    val allAnswered = questions.indices.all { !selection.selected[it].isNullOrEmpty() }

    // 状态色通过 glassCard 的 rimTint 表达（已答绿 / 待答品牌），
    // 不再叠手写 background + border —— 那会与 glassCard 自带的描边/底色撞成双重描边。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(WandShapes.md, rimTint = if (isAnswered) WandColors.success else WandColors.brand)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        // 头部
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                if (isAnswered) WandIcons.check else WandIcons.question,
                contentDescription = null,
                tint = if (isAnswered) WandColors.success else WandColors.brand,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "提问",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
            val headerLabel = questions.firstOrNull { !it.header.isNullOrEmpty() }?.header
            if (!headerLabel.isNullOrEmpty()) {
                Text(
                    headerLabel,
                    fontSize = 12.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isAnswered && answerLines.isNotEmpty()) {
                Text(
                    answerLines.joinToString(", "),
                    fontSize = 12.sp,
                    color = WandColors.success,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textMuted,
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                questions.forEachIndexed { qIdx, question ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (question.question.isNotEmpty()) {
                            Text(
                                question.question,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp,
                                color = WandColors.textPrimary,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            question.options.forEachIndexed { optIdx, option ->
                                AskUserOptionRow(
                                    option = option,
                                    multiSelect = question.multiSelect,
                                    isAnswered = isAnswered,
                                    chosen = if (isAnswered) {
                                        // 只读态：答案第 qIdx 行（缺行回落第一行），按 "," 拆出已选 label。
                                        val line = answerLines.getOrNull(qIdx)
                                            ?: answerLines.firstOrNull() ?: ""
                                        option.label in line.split(",").map { it.trim() }
                                    } else {
                                        optIdx in (selection.selected[qIdx] ?: emptySet())
                                    },
                                    enabled = !isAnswered && !selection.submitted,
                                    onClick = { onToggle(qIdx, optIdx, question.multiSelect) },
                                )
                            }
                        }
                    }
                }
                if (!isAnswered) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                val lines = questions.mapIndexed { qIdx, question ->
                                    (selection.selected[qIdx] ?: emptySet())
                                        .sorted()
                                        .joinToString(", ") { question.options[it].label }
                                }
                                onSubmit(lines.joinToString("\n"))
                            },
                            enabled = allAnswered && !selection.submitted,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WandColors.brand,
                                contentColor = Color.White,
                            ),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text(
                                if (selection.submitted) "已提交…" else "确认提交",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 提问卡单个选项：单选圆形 / 多选圆角方形 indicator，选中实底白点/白勾（对齐 Web）。 */
@Composable
private fun AskUserOptionRow(
    option: AskUserQuestionData.Option,
    multiSelect: Boolean,
    isAnswered: Boolean,
    chosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isAnswered) WandColors.success else WandColors.brand
    val fill = when {
        isAnswered && chosen -> WandColors.successSoft
        isAnswered -> WandColors.surface
        chosen -> WandColors.brand.copy(alpha = 0.16f)
        else -> WandColors.surface
    }
    val border = if (chosen) tint else WandColors.border
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .background(fill)
            .border(if (chosen) 1.dp else 0.55.dp, border.copy(alpha = if (chosen) 0.72f else 0.58f), WandShapes.sm)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .graphicsLayer { alpha = if (isAnswered && !chosen) 0.55f else 1f }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(if (multiSelect) RoundedCornerShape(3.dp) else CircleShape)
                .background(if (chosen) tint else Color.Transparent)
                .border(
                    1.5.dp,
                    if (chosen) tint.copy(alpha = 0.86f) else WandColors.borderStrong.copy(alpha = 0.72f),
                    if (multiSelect) RoundedCornerShape(3.dp) else CircleShape,
                ),
        ) {
            if (chosen) {
                if (multiSelect) {
                    Icon(
                        WandIcons.check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                option.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                color = WandColors.textPrimary,
            )
            if (!option.description.isNullOrEmpty()) {
                Text(
                    option.description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = WandColors.textSecondary,
                )
            }
        }
    }
}

// MARK: - Diff 卡片（Edit / Write / MultiEdit，对齐 Web 端 inline-diff）

@Composable
fun DiffCard(
    toolName: String,
    input: JSONObject,
    result: ContentBlock.ToolResult?,
    running: Boolean = false,
    initiallyExpanded: Boolean = false,
) {
    val path = input.str("file_path") ?: input.str("path") ?: ""
    val fileName = path.substringAfterLast('/').ifEmpty { "未命名文件" }
    val isWrite = toolName == "Write" || toolName == "MultiEdit"
    val oldText = input.str("old_string") ?: ""
    val newText = input.str("new_string") ?: input.str("content") ?: ""
    val unifiedDiff = input.str("unified_diff") ?: ""
    val kind = (input.str("kind") ?: if (isWrite) "add" else "update").lowercase()
    val movePath = input.str("move_path") ?: ""
    val diffUnavailableReason = input.str("diff_unavailable_reason")
    val hasDiffBody = oldText.isNotEmpty() || newText.isNotEmpty() || unifiedDiff.isNotEmpty() || movePath.isNotEmpty()

    val statusText = when {
        running -> "执行中"
        result == null -> "无结果"
        result.isError ->
            if (result.text.contains("haven't granted") || result.text.contains("permission")) {
                "等待授权"
            } else {
                "失败"
            }
        kind == "add" -> "已新增"
        kind == "delete" -> "已删除"
        movePath.isNotEmpty() -> "已移动"
        else -> "已修改"
    }
    val statusColor = when {
        running -> WandColors.brand
        result == null -> WandColors.textMuted
        result.isError -> WandColors.danger
        else -> WandColors.success
    }

    // 命令/文件详情默认不展示；运行状态只体现在摘要行，用户点开后保持展开。
    var expanded by remember(toolName, path, initiallyExpanded) { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Icon(
                WandIcons.edit,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                fileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                path,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                statusText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor,
                modifier = Modifier
                    .clip(WandShapes.full)
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textMuted,
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                if (!isWrite && oldText.isNotEmpty()) {
                    DiffColumn(label = "旧", text = oldText, prefix = "- ", tint = WandColors.danger)
                }
                if (newText.isNotEmpty()) {
                    DiffColumn(
                        label = if (isWrite) "" else "新",
                        text = newText,
                        prefix = "+ ",
                        tint = WandColors.success,
                    )
                }
                if (unifiedDiff.isNotEmpty()) {
                    UnifiedDiffBlock(unifiedDiff)
                }
                if (movePath.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("移动到", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = WandColors.textMuted)
                        SelectionContainer {
                            Text(
                                movePath,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                color = WandColors.info,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(WandShapes.sm)
                                    .background(WandColors.infoSoft)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                if (!hasDiffBody && result != null && !result.isError) {
                    Text(
                        diffUnavailableReason
                            ?: "Codex 已返回文件变更状态，但本次事件未包含差异正文。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = WandColors.textMuted,
                    )
                }
                if (result != null && result.isError && result.text.isNotEmpty()) {
                    Text(
                        if (result.text.length > 600) result.text.take(600) + "…" else result.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.danger,
                    )
                }
            }
        }
    }
}

/** Codex patch_apply_end 的 unified_diff 专用渲染：保留代码横向滚动并区分增删/区块。 */
@Composable
private fun UnifiedDiffBlock(diff: String) {
    val clipped = remember(diff) {
        if (diff.length > 16_000) diff.take(16_000) + "\n…（差异已截断）" else diff
    }
    val lines = remember(clipped) { clipped.lines() }
    val annotated = buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            val color = when {
                line.startsWith("@@") -> WandColors.info
                line.startsWith("+++") || line.startsWith("---") -> WandColors.textMuted
                line.startsWith("+") -> WandColors.success
                line.startsWith("-") -> WandColors.danger
                else -> WandColors.textSecondary
            }
            withStyle(SpanStyle(color = color)) { append(line) }
            if (index < lines.lastIndex) append('\n')
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "统一差异",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textMuted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.sm)
                .background(WandColors.textPrimary.copy(alpha = 0.045f))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(
                    annotated,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun DiffColumn(label: String, text: String, prefix: String, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label.isNotEmpty()) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textMuted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.sm)
                .background(tint.copy(alpha = 0.08f))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(
                    prefix + if (text.length > 2000) text.take(2000) + "\n…（已截断）" else text,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = tint,
                )
            }
        }
    }
}

// MARK: - 终端卡片（Bash，对齐 Web 端 inline-terminal）

/** 终端卡固定深色，亮暗主题一致（对齐 Web）。 */
private val TermBg = Color(0xFF1E1E1E)
private val TermText = Color(0xFFD9D9D4)
private val TermErrorText = Color(0xFFF28C82)

@Composable
fun TerminalCard(
    input: JSONObject,
    result: ContentBlock.ToolResult?,
    running: Boolean = false,
    initiallyExpanded: Boolean = false,
) {
    val command = input.str("command") ?: input.str("cmd") ?: ""
    val workdir = input.str("workdir") ?: ""
    val upstreamStatus = input.str("status") ?: ""
    val exitCode = input.int("exit_code")
    val statusColor = when {
        running -> WandColors.brand
        result == null -> WandColors.textMuted
        result.isError -> WandColors.danger
        else -> WandColors.success
    }
    val statusText = when {
        running -> "运行中"
        result?.isError == true && upstreamStatus == "declined" -> "已拒绝"
        result?.isError == true && exitCode != null -> "失败 · $exitCode"
        result?.isError == true -> "失败"
        result != null -> "完成"
        else -> "待执行"
    }
    val api = LocalChatApi.current
    val sessionId = LocalChatSessionId.current
    val scope = rememberCoroutineScope()
    var outputText by remember(result?.toolUseId, result?.text) { mutableStateOf(result?.text.orEmpty()) }
    var truncated by remember(result?.toolUseId, result?.truncated) { mutableStateOf(result?.truncated == true) }
    var loadingFullOutput by remember(result?.toolUseId) { mutableStateOf(false) }
    var outputLoadError by remember(result?.toolUseId) { mutableStateOf<String?>(null) }
    var expanded by remember(command, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(TermBg)
            .border(0.55.dp, Color.White.copy(alpha = 0.09f), WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (running) {
                val spin = rememberInfiniteTransition(label = "termSpin")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900)),
                    label = "termSpinAngle",
                )
                Icon(
                    WandIcons.refresh,
                    contentDescription = "运行中",
                    tint = TermText,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = angle },
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Text(
                "$ " + if (command.length > 80) command.take(77) + "…" else command.ifBlank { "命令" },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TermText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                statusText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                maxLines = 1,
                modifier = Modifier
                    .clip(WandShapes.full)
                    .background(statusColor.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            ExpandChevron(
                expanded = expanded,
                tint = TermText.copy(alpha = 0.6f),
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                if (workdir.isNotEmpty()) {
                    Text(
                        "目录  $workdir",
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TermText.copy(alpha = 0.60f),
                    )
                }
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            "$ $command",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TermText,
                        )
                    }
                }
                if (result != null && outputText.isNotEmpty()) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                if (outputText.length > 12_000) {
                                    outputText.take(12_000) + "\n…（本页仅展示前 12000 字）"
                                } else {
                                    outputText
                                },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (result.isError) TermErrorText else TermText.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
                if (truncated && result != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            outputLoadError ?: "输出过长，服务端已省略部分内容",
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            color = if (outputLoadError != null) TermErrorText else TermText.copy(alpha = 0.60f),
                            modifier = Modifier.weight(1f),
                        )
                        if (api != null && sessionId.isNotBlank() && result.toolUseId.isNotBlank()) {
                            TextButton(
                                enabled = !loadingFullOutput,
                                onClick = {
                                    loadingFullOutput = true
                                    outputLoadError = null
                                    scope.launch {
                                        try {
                                            outputText = api.fetchToolContent(sessionId, result.toolUseId).text
                                            truncated = false
                                        } catch (error: Exception) {
                                            outputLoadError = error.message ?: "加载失败，请重试"
                                        } finally {
                                            loadingFullOutput = false
                                        }
                                    }
                                },
                            ) {
                                if (loadingFullOutput) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = TermText,
                                    )
                                } else {
                                    Text("加载完整输出", fontSize = 10.sp, color = TermText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 待办进度条（TodoWrite，对齐 Web 端 todo-progress）

/** TodoWrite 的一项待办（tool_use input.todos[i]）。 */
data class TodoEntry(
    val content: String,
    val status: String,
    val activeForm: String?,
)

/**
 * 当前 turn 的待办列表：只看最后一条 user 消息之后的 TodoWrite，
 * 对齐 Web 端 updateTodoProgress 的 scoping（上一轮的进度条不跨 turn 残留）。
 * 全部完成时返回空（对齐 Web allDone 隐藏）。
 */
fun currentTodos(messages: List<ConversationTurn>): List<TodoEntry> {
    var startIdx = 0
    for (i in messages.indices.reversed()) {
        if (messages[i].role == "user") {
            startIdx = i + 1
            break
        }
    }
    // Wand protocol v2：provider 的 TodoWrite / Task* 已由服务端归一化。
    for (i in messages.indices.reversed()) {
        if (i < startIdx) break
        for (block in messages[i].content.reversed()) {
            val semantic = (block as? ContentBlock.ToolUse)?.semantic as? ToolUseSemantic.TaskList ?: continue
            val todos = semantic.items.map { TodoEntry(it.content, it.status, it.activeForm) }
            if (todos.isEmpty()) continue
            return if (todos.all { it.status == "completed" }) emptyList() else todos
        }
    }
    // 旧 TodoWrite 协议：最后一次写入就是完整快照，倒序取最新即可。
    for (i in messages.indices.reversed()) {
        if (i < startIdx) break
        for (block in messages[i].content.reversed()) {
            if (block is ContentBlock.ToolUse && block.name == "TodoWrite") {
                val arr = block.input.arrayField("todos") ?: continue
                val todos = mutableListOf<TodoEntry>()
                for (j in 0 until arr.length()) {
                    val obj = arr.optJSONObject(j) ?: continue
                    todos.add(
                        TodoEntry(
                            content = obj.str("content") ?: "",
                            status = obj.str("status") ?: "pending",
                            activeForm = obj.str("activeForm"),
                        )
                    )
                }
                if (todos.isEmpty()) continue
                val completed = todos.count { it.status == "completed" }
                return if (completed == todos.size) emptyList() else todos
            }
        }
    }

    // 新 TaskCreate / TaskUpdate 协议：创建与状态更新是增量事件，需要按时间顺序归并。
    val tasks = linkedMapOf<String, TodoEntry>()
    val pendingCreates = mutableMapOf<String, ContentBlock.ToolUse>()
    for (i in startIdx until messages.size) {
        for (block in messages[i].content) {
            when {
                block is ContentBlock.ToolUse && block.name == "TaskCreate" -> {
                    pendingCreates[block.id] = block
                }
                block is ContentBlock.ToolResult && block.toolUseId in pendingCreates -> {
                    val create = pendingCreates.remove(block.toolUseId) ?: continue
                    val taskId = Regex("""Task #([^\s]+) created""")
                        .find(block.text)?.groupValues?.getOrNull(1) ?: create.id
                    tasks[taskId] = TodoEntry(
                        content = create.input.str("subject")
                            ?: create.input.str("description")
                            ?: "Task #$taskId",
                        status = "pending",
                        activeForm = create.input.str("activeForm"),
                    )
                }
                block is ContentBlock.ToolUse && block.name == "TaskUpdate" -> {
                    val taskId = block.input.str("taskId") ?: continue
                    val existing = tasks[taskId] ?: TodoEntry(
                        content = "Task #$taskId",
                        status = "pending",
                        activeForm = null,
                    )
                    tasks[taskId] = existing.copy(
                        content = block.input.str("subject") ?: existing.content,
                        status = block.input.str("status") ?: existing.status,
                        activeForm = block.input.str("activeForm") ?: existing.activeForm,
                    )
                }
            }
        }
    }
    val derived = tasks.values.toList()
    return if (derived.isNotEmpty() && derived.all { it.status == "completed" }) emptyList() else derived
}

/**
 * 当前执行项：协议明确标记 in_progress 时优先使用；Codex 只有 pending/completed
 * 二态时，把首个 pending 推导为正在执行，避免列表与实际运行状态相互矛盾。
 */
internal fun activeTodoIndex(todos: List<TodoEntry>): Int? {
    val explicit = todos.indexOfFirst { it.status == "in_progress" }
    if (explicit >= 0) return explicit
    val inferred = todos.indexOfFirst { it.status == "pending" }
    return inferred.takeIf { it >= 0 }
}

/** 输入栏上方的悬浮任务状态：执行中 + 第 N/M 步 + 当前任务，点击展开任务列表。 */
@Composable
fun TodoProgressBar(todos: List<TodoEntry>, backdrop: GlassBackdrop? = null) {
    if (todos.isEmpty()) return
    val completed = todos.count { it.status == "completed" }
    val activeIndex = activeTodoIndex(todos)
    val currentStep = activeIndex?.plus(1) ?: minOf(completed + 1, todos.size)
    fun TodoEntry.label(): String? =
        (activeForm?.ifEmpty { null } ?: content).ifEmpty { null }
    val activeTask = activeIndex?.let { todos[it].label() }
        ?: "准备中…"
    var expanded by remember { mutableStateOf(false) }
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val activityAlpha = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "todoActivityBreath")
        val alpha by transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "todoActivityAlpha",
        )
        alpha
    } else {
        1f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, WandShapes.lg, WandGlass.regular)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    stateDescription = "已完成 $completed 项，共 ${todos.size} 项；正在执行第 $currentStep 步：$activeTask"
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.graphicsLayer { alpha = activityAlpha },
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(WandColors.brand, CircleShape),
                )
                Text(
                    "执行中",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.brand,
                )
            }
            Text(
                "· 第 $currentStep/${todos.size} 步",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = WandColors.brand,
            )
            Text(
                activeTask,
                fontSize = 12.sp,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textMuted,
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                todos.forEachIndexed { index, todo ->
                    val isActive = index == activeIndex
                    val rowBackground by animateColorAsState(
                        targetValue = if (isActive) WandColors.brandSoft else Color.Transparent,
                        animationSpec = WandMotion.tweenFast(),
                        label = "todoRowBackground",
                    )
                    val rowTextColor by animateColorAsState(
                        targetValue = if (isActive) WandColors.textPrimary else WandColors.textSecondary,
                        animationSpec = WandMotion.tweenFast(),
                        label = "todoRowText",
                    )
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(rowBackground)
                            .semantics(mergeDescendants = true) {
                                stateDescription = when {
                                    todo.status == "completed" -> "已完成：${todo.content}"
                                    isActive -> "进行中：${todo.content}"
                                    else -> "待处理：${todo.content}"
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.width(14.dp).height(17.dp),
                        ) {
                            when {
                                todo.status == "completed" -> Icon(
                                    WandIcons.check,
                                    contentDescription = null,
                                    tint = WandColors.success,
                                    modifier = Modifier.size(14.dp),
                                )
                                isActive -> Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(WandColors.brand, CircleShape),
                                )
                                else -> Box(
                                    Modifier
                                        .size(7.dp)
                                        .border(1.dp, WandColors.textMuted, CircleShape),
                                )
                            }
                        }
                        Text(
                            todo.content,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = rowTextColor,
                            textDecoration = if (todo.status == "completed") {
                                TextDecoration.LineThrough
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}
