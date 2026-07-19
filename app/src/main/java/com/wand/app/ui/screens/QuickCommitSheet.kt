package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wand.app.ui.QuickCommitEntryPhase
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.components.wandCardSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Git 快捷提交面板 —— 交互对齐网页版 git-commit.ts 的「磁吸 dock」：
 * Commit / Tag / Push（+ 可选 Sub）四颗气泡散落在力场里，抓任意一颗拖动，
 * 途经其他气泡会被磁吸进队伍；丢进右侧发射区执行组合动作（commit 永远隐含），
 * 松手在别处则全员弹回原位；单击气泡直接执行该气泡自己的动作。
 */

// 网页版 qc-chip 配色（commit 用主题 accent，其余固定色）。
private val TagBlue = Color(0xFF4A6FA5)
private val PushGreen = Color(0xFF4F7A58)
private val SubTeal = Color(0xFF3A8A8F)

// MARK: - TopBar 入口徽标

/** 会话顶栏的 git 徽标：分支名 + 待提交数（对齐网页 topbar-git-badge）。 */
@Composable
fun GitTopBarBadge(qc: QuickCommitStore, onClick: () -> Unit) {
    val s = qc.status ?: return
    if (!s.isGit) return
    val branch = s.branch ?: "?"
    val count = s.modifiedCount ?: 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .padding(end = 10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.55.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.46f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        GitBranchIcon(tint = MaterialTheme.colorScheme.primary)
        Text(
            branch,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 88.dp),
        )
        if (count > 0) {
            Text(
                "·$count",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text("✓", fontSize = 11.sp, color = WandColors.running)
        }
    }
}

/**
 * Git 变更统计按钮（对齐 iOS gitChangesButton）：~修改 -删除 +新增，
 * 点击打开快速提交面板。
 */
@Composable
fun GitChangesButton(
    quickCommit: QuickCommitStore,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val status = quickCommit.status ?: return
    if (!status.isGit) return
    var modified = 0
    var deleted = 0
    var added = 0
    status.files.orEmpty().forEach { file ->
        val fileStatus = file.status.uppercase()
        when {
            fileStatus.contains("?") || fileStatus.contains("A") -> added++
            fileStatus.contains("D") -> deleted++
            else -> modified++
        }
    }
    val total = modified + deleted + added
    val ahead = status.ahead ?: 0
    if (quickCommit.entryPhase == QuickCommitEntryPhase.Idle && total == 0 && ahead == 0) return
    val label = if (total > 0) {
        listOfNotNull(
            modified.takeIf { it > 0 }?.let { "~$it" },
            deleted.takeIf { it > 0 }?.let { "-$it" },
            added.takeIf { it > 0 }?.let { "+$it" },
        ).joinToString(" ")
    } else {
        "↑$ahead"
    }
    val activeTint = when (quickCommit.entryPhase) {
        QuickCommitEntryPhase.Loading -> WandColors.brand
        QuickCommitEntryPhase.Done -> WandColors.running
        QuickCommitEntryPhase.Idle -> if (total > 0) WandColors.brand else WandColors.textMuted
    }
    val activeBackground = when (quickCommit.entryPhase) {
        QuickCommitEntryPhase.Loading -> WandColors.brand.copy(alpha = 0.10f)
        QuickCommitEntryPhase.Done -> WandColors.running.copy(alpha = 0.12f)
        QuickCommitEntryPhase.Idle -> WandColors.surface.copy(alpha = 0.58f)
    }
    val accessibilityState = when (quickCommit.entryPhase) {
        QuickCommitEntryPhase.Loading -> "正在准备快捷提交"
        QuickCommitEntryPhase.Done -> "快捷提交完成"
        QuickCommitEntryPhase.Idle -> if (total > 0) {
            "共 $total 个文件变更，修改 $modified 个，删除 $deleted 个，新增 $added 个"
        } else {
            "$ahead 个提交待推送"
        }
    }

    if (compact) {
        CompactGitChangesButton(
            phase = quickCommit.entryPhase,
            enabled = !quickCommit.entryLocked,
            total = total,
            ahead = ahead,
            activeTint = activeTint,
            activeBackground = activeBackground,
            accessibilityState = accessibilityState,
            onClick = onClick,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .sizeIn(minWidth = 54.dp, minHeight = 44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Git 快捷提交"
                stateDescription = accessibilityState
            }
            .clip(RoundedCornerShape(14.dp))
            .background(activeBackground)
            .border(0.55.dp, WandColors.border.copy(alpha = 0.68f), RoundedCornerShape(14.dp))
            .clickable(
                enabled = !quickCommit.entryLocked,
                role = Role.Button,
                onClickLabel = "打开快捷提交",
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        AnimatedContent(
            targetState = quickCommit.entryPhase,
            transitionSpec = {
                (fadeIn(WandMotion.tweenFast()) + scaleIn(initialScale = 0.92f, animationSpec = WandMotion.tweenFast()))
                    .togetherWith(
                        fadeOut(WandMotion.tweenFast()) +
                            scaleOut(targetScale = 0.92f, animationSpec = WandMotion.tweenFast()),
                    )
                    .using(SizeTransform(clip = false))
            },
            label = "quickCommitEntry",
        ) { phase ->
            when (phase) {
                QuickCommitEntryPhase.Loading -> {
                    CircularProgressIndicator(
                        strokeWidth = 1.8.dp,
                        color = activeTint,
                        modifier = Modifier.size(16.dp),
                    )
                }

                QuickCommitEntryPhase.Done -> {
                    Icon(
                        WandIcons.check,
                        contentDescription = null,
                        tint = activeTint,
                        modifier = Modifier.size(17.dp),
                    )
                }

                QuickCommitEntryPhase.Idle -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            WandIcons.commit,
                            contentDescription = null,
                            tint = activeTint,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = if (total > 0) WandColors.textPrimary else WandColors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** 对话页紧凑入口：48dp 命中区内放 36dp 图标，计数徽标不挤占标题空间。 */
@Composable
private fun CompactGitChangesButton(
    phase: QuickCommitEntryPhase,
    enabled: Boolean,
    total: Int,
    ahead: Int,
    activeTint: Color,
    activeBackground: Color,
    accessibilityState: String,
    onClick: () -> Unit,
) {
    val pendingCount = if (total > 0) total else ahead
    val countLabel = if (pendingCount > 99) "99+" else pendingCount.toString()
    val badgeLabel = if (total > 0) countLabel else "↑$countLabel"

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Git 快捷提交"
                    stateDescription = accessibilityState
                },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(activeBackground),
            ) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(WandMotion.tweenFast()) +
                            scaleIn(initialScale = 0.92f, animationSpec = WandMotion.tweenFast()))
                            .togetherWith(
                                fadeOut(WandMotion.tweenFast()) +
                                    scaleOut(targetScale = 0.92f, animationSpec = WandMotion.tweenFast()),
                            )
                            .using(SizeTransform(clip = false))
                    },
                    label = "compactQuickCommitEntry",
                ) { currentPhase ->
                    when (currentPhase) {
                        QuickCommitEntryPhase.Loading -> {
                            CircularProgressIndicator(
                                strokeWidth = 1.8.dp,
                                color = activeTint,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        QuickCommitEntryPhase.Done -> {
                            Icon(
                                WandIcons.check,
                                contentDescription = null,
                                tint = activeTint,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        QuickCommitEntryPhase.Idle -> {
                            Icon(
                                WandIcons.commit,
                                contentDescription = null,
                                tint = activeTint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        if (phase == QuickCommitEntryPhase.Idle) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .height(17.dp)
                    .widthIn(min = 17.dp)
                    .clip(CircleShape)
                    .background(WandColors.textPrimary)
                    .border(1.dp, WandColors.bgElevated, CircleShape)
                    .clearAndSetSemantics { }
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = badgeLabel,
                    color = WandColors.bgElevated,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 极简 git branch 图标（对齐网页 svg：两个节点 + 分叉到右侧节点）。 */
@Composable
private fun GitBranchIcon(tint: Color) {
    Canvas(modifier = Modifier.size(13.dp)) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 2.2f * s, cap = StrokeCap.Round)
        fun c(x: Float, y: Float) = Offset(x * s, y * s)
        drawCircle(tint, radius = 2.4f * s, center = c(6f, 5f), style = stroke)
        drawCircle(tint, radius = 2.4f * s, center = c(6f, 19f), style = stroke)
        drawCircle(tint, radius = 2.4f * s, center = c(18f, 8f), style = stroke)
        drawLine(tint, c(6f, 7.8f), c(6f, 16.2f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(18f * s, 10.8f * s)
            lineTo(18f * s, 12f * s)
            quadraticTo(18f * s, 15f * s, 15f * s, 15f * s)
            lineTo(9.2f * s, 15f * s)
        }
        drawPath(path, tint, style = stroke)
    }
}

// MARK: - 弹层

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommitSheet(
    qc: QuickCommitStore,
    isHapticEnabled: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        // 提交 / 推送进行中禁止下滑收起，防止误关后丢失结果面板。
        confirmValueChange = { value -> value != SheetValue.Hidden || !qc.inFlight },
    )
    WandBottomSheet(
        onDismissRequest = { if (!qc.inFlight) onDismiss() },
        sheetState = sheetState,
        // ModalBottomSheet 是独立 window，采样不到 app 的 backdrop 层。
        // 用近实底 bgElevated 保证输入区与按钮对比度。
    ) {
        NoOverscroll {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 18.dp),
            ) {
                SheetHeader(qc)
                if (qc.result != null) {
                    ResultPanel(qc, onDismiss)
                } else {
                    FormPanel(qc, isHapticEnabled, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(qc: QuickCommitStore) {
    val s = qc.status
    val parts = mutableListOf<String>()
    if (s != null && s.isGit) {
        parts.add(s.branch ?: "(no branch)")
        val count = s.modifiedCount ?: 0
        parts.add(if (count > 0) "$count 个改动" else "工作区干净")
        s.ahead?.takeIf { it > 0 }?.let { parts.add("↑$it") }
        s.behind?.takeIf { it > 0 }?.let { parts.add("↓$it") }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "快捷提交",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        val summary = when {
            qc.statusLoading && s == null -> "读取中"
            s != null && !s.isGit -> "非 Git 仓库"
            parts.isNotEmpty() -> parts.joinToString(" · ")
            else -> null
        }
        summary?.let {
            Text(
                it,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(WandColors.surfaceSoft.copy(alpha = 0.72f))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
                    .widthIn(max = 190.dp),
            )
        }
    }
}

// MARK: - 表单面板

@Composable
private fun FormPanel(
    qc: QuickCommitStore,
    isHapticEnabled: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val s = qc.status
    val hasChanges = (s?.modifiedCount ?: 0) > 0
    val hasSubmodule = s?.hasSubmodule == true

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "提交信息",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { qc.generateAI() },
            enabled = !qc.generating && !qc.submitting && hasChanges,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            if (qc.generating) {
                CircularProgressIndicator(
                    strokeWidth = 1.6.dp,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Icon(
                    WandIcons.sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(if (qc.generating) "生成中…" else "AI", fontSize = 12.sp)
        }
    }

    // Commit：上一笔 → 新 message
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PairOldLine(
            label = "Commit",
            old = listOfNotNull(
                s?.lastCommitShortHash,
                s?.lastCommitSubject?.takeIf { it.isNotEmpty() },
            ).joinToString(" ").ifEmpty { "无 commit" },
        )
        WandTextField(
            value = qc.messageDraft,
            onValueChange = { qc.messageDraft = it },
            placeholder = "留空自动生成",
            minLines = 2,
            maxLines = 4,
            enabled = !qc.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 56.dp),
        )
    }

    // Tag：最新 tag → 新 tag
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PairOldLine(label = "Tag", old = s?.latestTag ?: "无 tag")
        WandTextField(
            value = qc.tagDraft,
            onValueChange = {
                qc.tagDraft = it
                qc.tagEdited = true
            },
            placeholder = "可选 tag",
            singleLine = true,
            enabled = !qc.submitting,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp),
        )
    }

    qc.error?.let {
        Text(it, fontSize = 12.sp, color = WandColors.danger)
    }

    val busyLabel = (if (qc.autoGenerating) "AI 生成 + 提交中…" else "执行中…") +
        (if (qc.submoduleIntent) "（含 submodule）" else "")
    MagneticDock(
        hasChanges = hasChanges,
        hasSubmodule = hasSubmodule,
        busy = qc.submitting,
        busyLabel = busyLabel,
        isHapticEnabled = isHapticEnabled,
        onAction = { action, sub -> qc.submit(action, sub) },
    )

    val hint = when {
        qc.submitting -> ""
        !hasChanges -> "工作区干净，无可提交"
        else -> "拖动磁吸组合 · 丢进提交区执行 · 单击直接执行该项" +
            (if (hasSubmodule) "\nSub 球可选，纳入后递归处理 submodule" else "")
    }
    if (hint.isNotEmpty()) {
        Text(
            hint,
            fontSize = 11.sp,
            color = WandColors.textHint,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 工作区干净但本地领先远端：dock 无事可做，给一个「仅推送」直达按钮。
    val ahead = s?.ahead ?: 0
    if (!hasChanges && ahead > 0 && !qc.submitting) {
        WandButton(
            label = if (qc.pushing) {
                "推送中…"
            } else {
                "推送 ↑$ahead 个待推 commit" + if (hasSubmodule) "（含子模块）" else ""
            },
            onClick = { qc.pushCommitsOnly() },
            enabled = !qc.pushing,
            loading = qc.pushing,
            variant = WandButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        qc.pushError?.let {
            Text(it, fontSize = 12.sp, color = WandColors.danger)
        }
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        WandButton(
            label = "取消",
            onClick = { if (!qc.inFlight) onDismiss() },
            enabled = !qc.inFlight,
            variant = WandButtonVariant.Text,
        )
    }
}

@Composable
private fun PairOldLine(label: String, old: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            old,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = WandColors.textHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - 结果面板

@Composable
private fun ResultPanel(qc: QuickCommitStore, onDismiss: () -> Unit) {
    val r = qc.result ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResultPair(
            label = "Commit",
            old = listOf(r.oldCommitHash, r.oldCommitSubject)
                .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "无" },
            new = listOf(r.commitHash, r.commitMessage)
                .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "无" },
        )
        ResultPair(
            label = "Tag",
            old = r.oldTag.ifEmpty { "无 tag" },
            new = r.tagName.ifEmpty { "未打 tag" },
        )
        if (r.submoduleCount > 0) {
            Text(
                "已先提交 ${r.submoduleCount} 个 submodule",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val pushErr = qc.pushError ?: r.pushError
        if (pushErr != null) {
            Text("push 失败:$pushErr", fontSize = 12.sp, color = WandColors.danger)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            WandButton(
                label = "关闭",
                onClick = { if (!qc.pushing) onDismiss() },
                enabled = !qc.pushing,
                variant = WandButtonVariant.Text,
            )
            Spacer(Modifier.weight(1f))
            if (r.pushed) {
                Text("已推送", fontSize = 13.sp, color = WandColors.running)
            } else {
                WandButton(
                    label = if (qc.pushing) "推送中…" else "Push & Close",
                    onClick = { qc.pushOnly() },
                    enabled = !qc.pushing,
                    loading = qc.pushing,
                )
            }
        }
    }
}

@Composable
private fun ResultPair(label: String, old: String, new: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                old,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textHint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("→", fontSize = 12.sp, color = WandColors.textHint)
            Text(
                new,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

// MARK: - 磁吸 dock

private val ACTION_ORDER = listOf("commit", "tag", "push")

private fun composeAction(members: List<String>): String {
    val hasTag = "tag" in members
    val hasPush = "push" in members
    return when {
        hasTag && hasPush -> "commit-tag-push"
        hasTag -> "commit-tag"
        hasPush -> "commit-push"
        else -> "commit"
    }
}

/** 单击气泡的直发动作（tag/push 隐含 commit；sub 是正交 scope 修饰符）。 */
private fun tapIntent(id: String): Pair<String, Boolean> = when (id) {
    "tag" -> "commit-tag" to false
    "push" -> "commit-push" to false
    "sub" -> "commit" to true
    else -> "commit" to false
}

@Composable
private fun MagneticDock(
    hasChanges: Boolean,
    hasSubmodule: Boolean,
    busy: Boolean,
    busyLabel: String,
    isHapticEnabled: () -> Boolean,
    onAction: (action: String, includeSubmodule: Boolean) -> Unit,
) {
    // 执行中：dock 整体替换为 busy 面板（对齐网页 qc-dock-busy）。
    if (busy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .wandCardSurface()
                .padding(horizontal = 16.dp),
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(busyLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val allIds = remember(hasSubmodule) {
        if (hasSubmodule) ACTION_ORDER + "sub" else ACTION_ORDER
    }
    val commitColor = MaterialTheme.colorScheme.primary
    val chipColors = remember(commitColor) {
        mapOf("commit" to commitColor, "tag" to TagBlue, "push" to PushGreen, "sub" to SubTeal)
    }
    val chipLabels = mapOf("commit" to "Commit", "tag" to "Tag", "push" to "Push", "sub" to "Sub")

    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val chipSizes = remember { mutableStateMapOf<String, IntSize>() }
    val anims = remember { mutableMapOf<String, Animatable<Offset, AnimationVector2D>>() }
    fun animFor(id: String) = anims.getOrPut(id) { Animatable(Offset.Zero, Offset.VectorConverter) }

    var placed by remember { mutableStateOf(false) }
    var dragMembers by remember { mutableStateOf<List<String>?>(null) }
    var hot by remember { mutableStateOf(false) }
    var clusterRect by remember { mutableStateOf<Rect?>(null) }

    val marginPx = with(density) { 8.dp.toPx() }
    val pickupRPx = with(density) { 58.dp.toPx() }
    val gapPx = with(density) { 5.dp.toPx() }
    val stackStepPx = with(density) { 24.dp.toPx() }
    val clusterPadPx = with(density) { 7.dp.toPx() }
    val defaultChipWPx = with(density) { 86.dp.toPx() }
    val defaultChipHPx = with(density) { 38.dp.toPx() }

    fun cw(id: String): Float = chipSizes[id]?.width?.toFloat() ?: defaultChipWPx
    fun chipH(): Float = chipSizes.values.firstOrNull()?.height?.toFloat() ?: defaultChipHPx

    // 原位布局（px）：无 Sub 时 ∧ 三角（Commit 顶中 / Tag 左下 / Push 右下），
    // 有 Sub 时 2×2 网格 —— 对齐网页窄屏 homePositions()。
    fun homePositions(): Map<String, Offset> {
        val fw = fieldSize.width.toFloat()
        val fh = fieldSize.height.toFloat()
        val h = chipH()
        val pos = mutableMapOf<String, Offset>()
        if (hasSubmodule) {
            val topY = (fh * 0.20f - h / 2f).coerceAtLeast(marginPx)
            val botY = (fh * 0.70f - h / 2f).coerceAtMost(fh - h - marginPx)
            fun colL(w: Float) = (fw * 0.27f - w / 2f).coerceAtLeast(marginPx)
            fun colR(w: Float) = (fw * 0.73f - w / 2f).coerceAtMost(fw - w - marginPx)
            pos["commit"] = Offset(colL(cw("commit")), topY)
            pos["tag"] = Offset(colR(cw("tag")), topY)
            pos["push"] = Offset(colL(cw("push")), botY)
            pos["sub"] = Offset(colR(cw("sub")), botY)
        } else {
            val topY = (fh * 0.18f - h / 2f).coerceAtLeast(marginPx)
            val botY = (fh * 0.72f - h / 2f).coerceAtMost(fh - h - marginPx)
            pos["commit"] = Offset(((fw - cw("commit")) / 2f).coerceAtLeast(marginPx), topY)
            pos["tag"] = Offset((fw * 0.24f - cw("tag") / 2f).coerceAtLeast(marginPx), botY)
            pos["push"] = Offset(
                (fw * 0.76f - cw("push") / 2f).coerceAtMost(fw - cw("push") - marginPx),
                botY,
            )
        }
        return pos
    }

    // 几何就绪（场地 + 所有气泡都量过尺寸）且不在拖动中 → 钉回原位。
    LaunchedEffect(fieldSize, chipSizes.size, allIds) {
        if (fieldSize.width == 0) return@LaunchedEffect
        if (allIds.any { chipSizes[it] == null }) return@LaunchedEffect
        if (dragMembers == null) {
            val home = homePositions()
            allIds.forEach { id -> home[id]?.let { animFor(id).snapTo(it) } }
            placed = true
        }
    }

    val enabled = hasChanges
    val composedAction = dragMembers?.let { composeAction(it) } ?: "commit"
    val launchTone = when (composedAction) {
        "commit-tag" -> TagBlue
        "commit-push" -> PushGreen
        "commit-tag-push" -> SubTeal
        else -> commitColor
    }

    Row(modifier = Modifier.fillMaxWidth().height(170.dp)) {
        // —— 力场 ——
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .zIndex(1f)
                .wandCardSurface()
                .onSizeChanged { fieldSize = it }
                .pointerInputDock(
                    enabled = enabled,
                    allIds = allIds,
                    pickupRPx = pickupRPx,
                    gapPx = gapPx,
                    stackStepPx = stackStepPx,
                    clusterPadPx = clusterPadPx,
                    cw = ::cw,
                    chipH = ::chipH,
                    homePositions = ::homePositions,
                    animFor = ::animFor,
                    setDragMembers = { dragMembers = it },
                    setHot = { hot = it },
                    setClusterRect = { clusterRect = it },
                    springHome = {
                        val home = homePositions()
                        allIds.forEach { id ->
                            home[id]?.let { target ->
                                scope.launch {
                                    animFor(id).animateTo(
                                        target,
                                        spring(
                                            dampingRatio = 0.62f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    snapTo = { id, target -> scope.launch { animFor(id).snapTo(target) } },
                    tick = {
                        if (isHapticEnabled()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    thud = {
                        if (isHapticEnabled()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onAction = onAction,
                ),
        ) {
            clusterRect?.let { r ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(
                            with(density) { r.width.toDp() },
                            with(density) { r.height.toDp() },
                        )
                        .background(launchTone.copy(alpha = 0.035f), RoundedCornerShape(12.dp))
                        .border(0.55.dp, launchTone.copy(alpha = 0.22f), RoundedCornerShape(12.dp)),
                )
            }
            // 气泡们
            allIds.forEach { id ->
                val anim = animFor(id)
                val active = dragMembers?.contains(id) == true
                val color = chipColors[id] ?: commitColor
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .offset {
                            IntOffset(anim.value.x.roundToInt(), anim.value.y.roundToInt())
                        }
                        .zIndex(if (active) 3f else 2f)
                        .graphicsLayer {
                            alpha = when {
                                !placed -> 0f
                                !enabled -> 0.45f
                                else -> 1f
                            }
                            if (active) {
                                scaleX = 1.06f
                                scaleY = 1.06f
                            }
                        }
                        .onSizeChanged { chipSizes[id] = it }
                        .background(
                            if (active) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                            CircleShape,
                        )
                        .border(
                            1.2.dp,
                            color.copy(alpha = if (active) 0.7f else 0.32f),
                            CircleShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color, CircleShape),
                    )
                    Text(
                        chipLabels[id] ?: id,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // —— 发射区 ——
        // 始终走 glassCard，与左侧力场同源浮起（rim 带 launchTone）；热区（拖入命中）
        // 再覆盖一层 launchTone 强调底 + 高亮描边，明确「松手即执行」的落点。
        val launchShape = RoundedCornerShape(14.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(76.dp)
                .fillMaxHeight()
                .wandCardSurface(launchShape, rimTint = launchTone)
                .then(
                    if (hot) {
                        Modifier
                            .background(launchTone.copy(alpha = 0.10f), launchShape)
                            .border(1.dp, launchTone.copy(alpha = 0.58f), launchShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = enabled) { onAction("commit", false) },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "执行提交",
                tint = launchTone,
                modifier = Modifier.size(22.dp),
            )
            Text(
                if (hot) "松手执行" else "提交",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (hot) launchTone else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * dock 手势：场地坐标系内 hit-test 气泡 → 跟手拖动 → 磁吸拾取（只吸主动作球，
 * Sub 永不被路过吸附）→ 出场地右缘即「热」（悬在发射区上方）→
 * 松手：未动=单击直发；热区=组合执行；否则全员弹回。
 */
private fun Modifier.pointerInputDock(
    enabled: Boolean,
    allIds: List<String>,
    pickupRPx: Float,
    gapPx: Float,
    stackStepPx: Float,
    clusterPadPx: Float,
    cw: (String) -> Float,
    chipH: () -> Float,
    homePositions: () -> Map<String, Offset>,
    animFor: (String) -> Animatable<Offset, AnimationVector2D>,
    setDragMembers: (List<String>?) -> Unit,
    setHot: (Boolean) -> Unit,
    setClusterRect: (Rect?) -> Unit,
    springHome: () -> Unit,
    snapTo: (String, Offset) -> Unit,
    tick: () -> Unit,
    thud: () -> Unit,
    onAction: (String, Boolean) -> Unit,
): Modifier = pointerInput(enabled, allIds) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown()
        // 命中检测：按下点落在哪颗气泡上（场地坐标系 = pointerInput 坐标系）。
        val homesAtDown = homePositions()
        val h = chipH()
        val anchor = allIds.firstOrNull { id ->
            val p = animFor(id).value
            down.position.x >= p.x && down.position.x <= p.x + cw(id) &&
                down.position.y >= p.y && down.position.y <= p.y + h
        } ?: return@awaitEachGesture
        down.consume()

        val slop = viewConfiguration.touchSlop
        var moved = false
        val members = mutableListOf(anchor)
        setDragMembers(members.toList())
        var pos = down.position
        var isHot = false

        val completed = drag(down.id) { change ->
            change.consume()
            pos = change.position
            if (!moved && (pos - down.position).getDistance() > slop) moved = true
            if (!moved) return@drag

            // 磁吸拾取：指尖扫过松散气泡的原位中心即入队。
            // 只遍历主动作球 —— Sub 永不被「路过吸附」（默认不纳入），
            // 但它可以作为锚点被显式抓起、反向吸附动作球。
            for (id in ACTION_ORDER) {
                if (id in members) continue
                val hp = homesAtDown[id] ?: continue
                val center = Offset(hp.x + cw(id) / 2f, hp.y + h / 2f)
                if ((pos - center).getDistance() < pickupRPx) {
                    members.add(id)
                    setDragMembers(members.toList())
                    tick()
                }
            }

            // 拖动中的队伍层叠显示；标签无需完整可读，露出的前缘足以表示已吸附。
            val ids = allIds.filter { it in members }
            val widest = ids.maxOfOrNull { cw(it) } ?: 0f
            val total = widest + stackStepPx * (ids.size - 1).coerceAtLeast(0)
            val fh = size.height.toFloat()
            val y = (pos.y - h / 2f).coerceIn(2f, (fh - h - 2f).coerceAtLeast(2f))
            var x = pos.x - total / 2f
            for (id in ids) {
                snapTo(id, Offset(x, y))
                x += stackStepPx
            }
            setClusterRect(
                if (ids.size > 1) {
                    Rect(
                        pos.x - total / 2f - clusterPadPx,
                        y - clusterPadPx,
                        pos.x + total / 2f + clusterPadPx,
                        y + h + clusterPadPx,
                    )
                } else {
                    null
                },
            )

            // 拖出场地右缘 → 悬在发射区上方（hot）。
            val nowHot = pos.x > size.width.toFloat() + gapPx
            if (nowHot && !isHot) thud()
            isHot = nowHot
            setHot(nowHot)
        }

        val endMembers = members.toList()
        val endHot = isHot
        setDragMembers(null)
        setHot(false)
        setClusterRect(null)
        springHome()

        if (!completed) return@awaitEachGesture
        if (!moved) {
            // 原地单击 → 直接执行该气泡自己的动作。
            val (action, sub) = tapIntent(anchor)
            onAction(action, sub)
        } else if (endHot) {
            // 丢进发射区 → 执行组合动作（commit 永远隐含）。
            onAction(composeAction(endMembers), "sub" in endMembers)
        }
        // 松手在别处：springHome() 已让全员弹回，不执行任何动作。
    }
}
