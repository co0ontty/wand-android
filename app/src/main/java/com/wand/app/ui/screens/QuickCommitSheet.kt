package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wand.app.data.GitStatusResult
import com.wand.app.ui.QuickCommitEntryPhase
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.GitBranchIcon
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.components.wandCardSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Git 快捷提交面板 —— 交互对齐网页版 git-commit.ts 的「磁吸 dock」：
 * Commit / Tag / Push（+ 可选 Sub）四颗气泡散落在力场里，抓任意一颗拖动，
 * 途经其他气泡会被磁吸进队伍；丢进右侧发射区执行组合动作（commit 永远隐含），
 * 松手在别处则全员弹回原位；单击气泡直接执行该气泡自己的动作。
 */

// MARK: - TopBar 入口徽标

@Composable
fun QuickCommitStatusRefreshEffect(
    quickCommit: QuickCommitStore,
    sessionId: String,
    enabled: Boolean,
) {
    LifecycleResumeEffect(sessionId, enabled) {
        if (enabled) quickCommit.loadStatus(force = true)
        onPauseOrDispose { }
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
    // 入口只在「确认不是 git 仓库」时缺席：工作区干净、状态还没取到都保留入口，
    // 面板自己解释状态并重试。之前干净就整块消失，等于 git 面板打不开。
    val status = quickCommit.status
    if (status != null && !status.isGit) return
    var modified = 0
    var deleted = 0
    var added = 0
    status?.files.orEmpty().forEach { file ->
        val fileStatus = file.status.uppercase()
        when {
            fileStatus.contains("?") || fileStatus.contains("A") -> added++
            fileStatus.contains("D") -> deleted++
            else -> modified++
        }
    }
    val total = modified + deleted + added
    val ahead = status?.ahead ?: 0
    val clean = total == 0 && ahead == 0 && (status?.worktreeCount ?: 0) == 0
    val label = when {
        status == null -> "…"
        total > 0 -> listOfNotNull(
            modified.takeIf { it > 0 }?.let { "~$it" },
            deleted.takeIf { it > 0 }?.let { "-$it" },
            added.takeIf { it > 0 }?.let { "+$it" },
        ).joinToString(" ")
        ahead > 0 -> "↑$ahead"
        else -> "干净"
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
    // 阶段切换（Idle ↔ Loading ↔ Done）时底色与图标色一起过渡，内部图标已由 AnimatedContent 交叠。
    val motionEnabled = !reduceMotionEnabled()
    val tintTone by animateColorAsState(
        targetValue = activeTint,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "quickCommitEntryTint",
    )
    val containerTone by animateColorAsState(
        targetValue = activeBackground,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "quickCommitEntryContainer",
    )
    val accessibilityState = when (quickCommit.entryPhase) {
        QuickCommitEntryPhase.Loading -> "正在准备快捷提交"
        QuickCommitEntryPhase.Done -> "快捷提交完成"
        QuickCommitEntryPhase.Idle -> when {
            status == null -> "正在读取 git 状态"
            total > 0 -> "共 $total 个文件变更，修改 $modified 个，删除 $deleted 个，新增 $added 个"
            ahead > 0 -> "$ahead 个提交待推送"
            else -> "工作区干净"
        }
    }

    if (compact) {
        CompactGitChangesButton(
            phase = quickCommit.entryPhase,
            enabled = !quickCommit.entryLocked,
            total = total,
            ahead = ahead,
            clean = clean || status == null,
            activeTint = tintTone,
            activeBackground = containerTone,
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
            .background(containerTone)
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
                        color = tintTone,
                        modifier = Modifier.size(16.dp),
                    )
                }

                QuickCommitEntryPhase.Done -> {
                    Icon(
                        WandIcons.check,
                        contentDescription = null,
                        tint = tintTone,
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
                            tint = tintTone,
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
    /** 无改动（或状态还没取到）：不挂计数徽标，图标本身仍是入口。 */
    clean: Boolean,
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

        if (phase == QuickCommitEntryPhase.Idle && !clean) {
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

// GitBranchIcon 已抽取为共享组件（ui/components/WandIcons.kt），供工作空间任务行复用。

// MARK: - 弹层

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCommitSheet(
    qc: QuickCommitStore,
    isHapticEnabled: () -> Boolean,
    onDismiss: () -> Unit,
) {
    // 打开即全展开（§2.22 S12）：半展开态是「上滑展开」手势与内层滚动争抢的根源，
    // 快速上滑会把面板关掉并丢掉已输入的提交信息。收起只保留 4 条路径：
    // 拖 handle 向下 / 点遮罩 / 右上关闭 / 返回键（外加「取消」与提交后自动关闭）。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    WandBottomSheet(
        // 关闭只隐藏面板，不会取消已经发出的 Git 请求。这样网络等待或服务端耗时
        // 较长时，用户仍能返回会话；完成/失败结果通过 toast 与入口状态反馈。
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // ModalBottomSheet 是独立 window，采样不到 app 的 backdrop 层。
        // 由内部 Liquid 容器走高对比降级样式，保证输入和主操作仍有清晰层次。
        transparent = true,
        showDragHandle = true,
    ) {
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .glassSurface(
                    backdrop = null,
                    shape = sheetShape,
                    style = WandGlass.regular.copy(fallbackAlpha = 0.965f, shadowElevation = 4.dp),
                ),
        ) {
            AmbientBackground(Modifier.matchParentSize())
            NoOverscroll {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 20.dp),
                ) {
                    SheetHeader(onDismiss)
                    if (qc.result != null) {
                        ResultPanel(qc, onDismiss)
                    } else {
                        FormPanel(qc, isHapticEnabled, onDismiss)
                    }
                }
            }
        }
    }
}

// 头部不再放分支/改动数摘要（§2.11 B5）：同一屏下面的 CommitWorkspaceLens 已经写了一遍，
// 两处同数据会互相打架。头部只留标题与关闭按钮，标题行高度随之减小。
@Composable
private fun SheetHeader(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "快捷提交",
            modifier = Modifier.weight(1f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                WandIcons.close,
                contentDescription = "关闭快捷提交",
                tint = WandColors.textMuted,
                modifier = Modifier.size(18.dp),
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

    CommitWorkspaceLens(commitLensState(status = s, loading = qc.statusLoading))

    // 状态没取到：给一个原地重试，不让面板变成死胡同。
    if (s == null && !qc.statusLoading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "暂时读不到 git 状态，可能是网络或服务端临时失败。",
                fontSize = 12.sp,
                color = WandColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { qc.loadStatus(force = true) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Text("重试", fontSize = 12.sp)
            }
        }
    }

    // 非 Git 目录：lens 报状态，表单区解释为什么下面的控件都用不了（§2.11「信息不丢失」）。
    if (s != null && !s.isGit) {
        Text(
            "非 Git 仓库，无法快捷提交。",
            fontSize = 12.sp,
            color = WandColors.textMuted,
        )
    }

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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.heightIn(min = 36.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            label = "新的 Commit 信息",
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PairOldLine(label = "Tag", old = s?.latestTag ?: "无 tag")
        WandTextField(
            value = qc.tagDraft,
            onValueChange = { qc.tagDraft = it },
            label = "Tag（可选）",
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
        busy = qc.inFlight || qc.generating,
        busyLabel = if (qc.mergingWorktrees) "合并并推送中…" else qc.worktreeFeedback ?: busyLabel,
        worktreeCount = s?.worktreeCount ?: 0,
        ahead = s?.ahead ?: 0,
        feedback = qc.worktreeFeedback,
        isHapticEnabled = isHapticEnabled,
        onAction = { action, sub, worktree ->
            if (action == "merge-worktrees" || (worktree && !hasChanges)) qc.mergeWorktrees()
            else if (action == "commit-push" && !hasChanges) qc.pushCommitsOnly()
            else qc.submit(action, sub, includeWorktrees = worktree)
        },
    )

    val hint = when {
        qc.inFlight -> ""
        qc.worktreeFeedback != null -> qc.worktreeFeedback.orEmpty()
        !hasChanges -> "工作区干净。Worktree：合并全部工作树到当前分支并推送。"
        else -> "拖动可用气泡互相吸附 · 含 Worktree 的组合先提交并推送所选操作，再合并全部工作树并推送" +
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


}

/** lens 的语义色档位；色值由 Composable 侧从 `WandColors` 取，纯函数不碰主题。 */
internal enum class CommitLensTone { Brand, Success, Neutral }

/**
 * lens 要显示的那一句分支/改动数（§2.11 B5）：分支、改动数、↑↓ 从头部摘要搬到这里，
 * 全 sheet 只在这一处出现。[statusLine] 永不为空 —— 加载/非 Git/取不到状态都不能留白行。
 */
internal data class CommitLensState(
    val branch: String?,
    val statusLine: String,
    /** [CommitLensTone.Neutral] = 无数据态（读取中 / 非 Git 仓库 / 未获取到仓库状态）：
     *  中性灰语义色 + 13sp `textMuted`，不装作“一切正常”。 */
    val tone: CommitLensTone,
    /** `↑2 ↓1`；两位都为 0、或状态行已经报过这个数时为 null（不占位）。 */
    val aheadBehindLine: String?,
)

internal fun commitLensState(status: GitStatusResult?, loading: Boolean): CommitLensState {
    if (status == null) {
        val line = if (loading) "读取中…" else "未获取到仓库状态"
        return CommitLensState(
            branch = null,
            statusLine = line,
            tone = CommitLensTone.Neutral,
            aheadBehindLine = null,
        )
    }
    if (!status.isGit) {
        return CommitLensState(
            branch = null,
            statusLine = "非 Git 仓库",
            tone = CommitLensTone.Neutral,
            aheadBehindLine = null,
        )
    }
    val changeCount = status.modifiedCount ?: 0
    val ahead = status.ahead ?: 0
    val behind = status.behind ?: 0
    return CommitLensState(
        branch = status.branch ?: "未识别分支",
        statusLine = when {
            changeCount > 0 -> "$changeCount 个改动待处理"
            ahead > 0 -> "$ahead 个 commit 待推送"
            else -> "工作区干净"
        },
        tone = if (changeCount > 0) CommitLensTone.Brand else CommitLensTone.Success,
        // 无改动时状态行本身就是「N 个 commit 待推送」，再挂一行 ↑N 就变成这一条目要消灭的
        // 「同屏同数据两遍」，所以 ↑ 只在状态行讲的是改动数时补；↓（落后远端）永远是新信息。
        aheadBehindLine = buildList {
            if (ahead > 0 && changeCount > 0) add("↑$ahead")
            if (behind > 0) add("↓$behind")
        }.takeIf { it.isNotEmpty() }?.joinToString(" "),
    )
}

/** 提交前的上下文透镜：先回答“我要在哪个分支处理多少改动”，再进入表单。 */
@Composable
private fun CommitWorkspaceLens(state: CommitLensState) {
    val tone = when (state.tone) {
        CommitLensTone.Brand -> WandColors.brand
        CommitLensTone.Success -> WandColors.success
        CommitLensTone.Neutral -> WandColors.textMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandCardSurface(WandShapes.lg, rimTint = tone)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tone.copy(alpha = 0.13f))
                .border(0.6.dp, tone.copy(alpha = 0.25f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WandIcons.commit,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            state.branch?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.tone == CommitLensTone.Neutral) {
                Text(
                    state.statusLine,
                    fontSize = 13.sp,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    state.statusLine,
                    fontSize = 12.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.aheadBehindLine?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.success,
                    maxLines = 1,
                )
            }
        }
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
                onClick = onDismiss,
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
    if (members == listOf("worktree")) return "merge-worktrees"
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
    "worktree" -> "merge-worktrees" to false
    else -> "commit" to false
}

@Composable
private fun MagneticDock(
    hasChanges: Boolean,
    hasSubmodule: Boolean,
    busy: Boolean,
    busyLabel: String,
    worktreeCount: Int,
    ahead: Int,
    feedback: String?,
    isHapticEnabled: () -> Boolean,
    onAction: (action: String, includeSubmodule: Boolean, includeWorktrees: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val allIds = remember(hasSubmodule) {
        ACTION_ORDER + (if (hasSubmodule) listOf("sub") else emptyList()) + "worktree"
    }
    val commitColor = WandColors.brand
    val tagColor = WandColors.info
    val pushColor = WandColors.success
    val subColor = lerp(WandColors.info, WandColors.success, 0.45f)
    val chipColors = remember(commitColor, tagColor, pushColor, subColor) {
        mapOf("commit" to commitColor, "tag" to tagColor, "push" to pushColor,
            "sub" to subColor, "worktree" to tagColor)
    }
    val chipLabels = mapOf("commit" to "Commit", "tag" to "Tag", "push" to "Push",
        "sub" to "Sub", "worktree" to "Worktree")

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
    val clusterPadPx = with(density) { 12.dp.toPx() }
    val defaultChipWPx = with(density) { 86.dp.toPx() }
    val defaultChipHPx = with(density) { 38.dp.toPx() }

    fun cw(id: String): Float = chipSizes[id]?.width?.toFloat() ?: defaultChipWPx
    fun chipH(): Float = chipSizes.values.firstOrNull()?.height?.toFloat() ?: defaultChipHPx

    fun homePositions(): Map<String, Offset> {
        val fw = fieldSize.width.toFloat()
        val fh = fieldSize.height.toFloat()
        val rows = (allIds.size + 1) / 2
        return allIds.mapIndexed { index, id ->
            val centerX = fw * (if (index % 2 == 0) 0.27f else 0.73f)
            id to Offset((centerX - cw(id) / 2f).coerceAtLeast(marginPx),
                ((index / 2 + 0.5f) * fh / rows - chipH() / 2f).coerceAtLeast(marginPx))
        }.toMap()
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

    val motionEnabled = !reduceMotionEnabled()
    val enabled = !busy && (hasChanges || worktreeCount > 0 || ahead > 0)
    fun chipEnabled(id: String) = !busy && if (id == "worktree") worktreeCount > 0 else if (id == "push") hasChanges || ahead > 0 else hasChanges
    // pointerInput 的协程跨重组保留；执行与禁用状态读取本轮最新值。
    val currentChipEnabled = rememberUpdatedState<(String) -> Boolean>(::chipEnabled)
    val currentOnAction = rememberUpdatedState(onAction)
    val composedAction = dragMembers?.let { composeAction(it) } ?: "commit"
    val launchTone = when (composedAction) {
        "commit-tag" -> tagColor
        "commit-push" -> pushColor
        "commit-tag-push" -> subColor
        else -> commitColor
    }

    Row(modifier = Modifier.fillMaxWidth().height(200.dp)) {
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
                    chipEnabled = { currentChipEnabled.value(it) },
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
                                    if (motionEnabled) animFor(id).animateTo(target, WandMotion.settleSpringSpec())
                                    else animFor(id).snapTo(target)
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
                    onAction = { action, sub, worktree -> currentOnAction.value(action, sub, worktree) },
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
                                !chipEnabled(id) -> 0.45f
                                else -> 1f
                            }
                            if (active && motionEnabled) {
                                scaleX = 1.06f
                                scaleY = 1.06f
                            }
                        }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = if (id == "worktree") "合并全部 Worktree 到当前分支并推送" else "${chipLabels[id]} 气泡"
                            if (!chipEnabled(id)) disabled()
                            onClick {
                                if (chipEnabled(id)) { val intent = tapIntent(id); onAction(intent.first, intent.second, id == "worktree"); true }
                                else false
                            }
                        }
                        .onSizeChanged { chipSizes[id] = it }
                        .background(
                            if (active) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                            CircleShape,
                        )
                        .border(
                            1.dp,
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

        Spacer(Modifier.width(12.dp))

        // —— 发射区 ——
        // 始终走 wandCardSurface，与左侧力场同源浮起（rim 带 launchTone）；热区（拖入命中）
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
                .clickable(enabled = !busy && hasChanges) { onAction("commit", false, false) },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "执行提交",
                tint = launchTone,
                modifier = Modifier.size(22.dp),
            )
            Text(
                if (busy) busyLabel else feedback ?: if (hot) "松手执行" else "提交",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (hot) launchTone else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * dock 手势：场地坐标系内 hit-test 气泡 → 跟手拖动 → 磁吸拾取（场地内任意气泡
 * 都可被吸进组合）→ 出场地右缘即「热」（悬在发射区上方）→
 * 单击直发；热区执行完整组合（含 Sub / Worktree）；其它落点全员弹回。
 */
private fun Modifier.pointerInputDock(
    enabled: Boolean,
    chipEnabled: (String) -> Boolean,
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
    onAction: (String, Boolean, Boolean) -> Unit,
): Modifier = pointerInput(enabled, allIds) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown()
        // 命中检测：按下点落在哪颗气泡上（场地坐标系 = pointerInput 坐标系）。
        val homesAtDown = homePositions()
        val h = chipH()
        val anchor = allIds.firstOrNull { id ->
            val p = animFor(id).value
            chipEnabled(id) && down.position.x >= p.x && down.position.x <= p.x + cw(id) &&
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

            // 所有可用胶囊都能双向吸附；未进入组合的 Sub 不扩大提交范围。
            for (id in allIds) {
                if (!chipEnabled(id) || id in members) continue
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

        if (!completed || endMembers.any { !chipEnabled(it) }) return@awaitEachGesture
        if (!moved) {
            val (action, sub) = tapIntent(anchor)
            onAction(action, sub, anchor == "worktree")
        } else if (endHot) {
            // 起手胶囊不改变组合含义，保留全部动作与范围。
            onAction(composeAction(endMembers), "sub" in endMembers, "worktree" in endMembers)
        }
        // 松手在别处：springHome() 已让全员弹回，不执行任何动作。
    }
}
