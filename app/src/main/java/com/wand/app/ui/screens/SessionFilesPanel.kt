package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.wand.app.ByteSizeFormatter
import com.wand.app.data.WandApi
import com.wand.app.ui.FileActionPhase
import com.wand.app.ui.SessionFilesController
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandListItem
import com.wand.app.ui.components.WandListItemIconSlot
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled

/** Anchored below the session toolbar, not a new page or a bottom sheet. */
@Composable
internal fun SessionFilesButton(api: WandApi, sessionId: String, cwd: String?, onOpen: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val files = remember(sessionId, api) { SessionFilesController(api, scope) }
    DisposableEffect(files) { onDispose { files.shutdown() } }
    val visibility = remember(files) { MutableTransitionState(false) }
    LaunchedEffect(files.open) { visibility.targetState = files.open }
    val motion = !reduceMotionEnabled()
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val panelHeight = (config.screenHeightDp.dp - 100.dp - keyboard).coerceIn(160.dp, 600.dp)
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Box {
        WandMorphIconButton(
            expanded = files.open,
            collapsedIcon = WandIcons.folder,
            expandedIcon = WandIcons.close,
            contentDescription = if (files.open) "关闭会话文件" else "会话文件",
            touchSize = 48.dp,
            enabled = !cwd.isNullOrBlank(),
            onClick = {
                if (files.open) files.requestClose() else {
                    keyboardController?.hide()
                    onOpen()
                    files.open(cwd.orEmpty())
                }
            },
        )
        if (files.open || visibility.currentState || !visibility.isIdle) {
            Popup(
                popupPositionProvider = remember(density) { SessionFilesPosition(with(density) { 12.dp.roundToPx() }) },
                onDismissRequest = files::requestClose,
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = if (motion) expandVertically(WandMotion.tweenEnter(), expandFrom = Alignment.Top) +
                        fadeIn(WandMotion.tweenFast()) else EnterTransition.None,
                    exit = if (motion) shrinkVertically(WandMotion.tweenExit(), shrinkTowards = Alignment.Top) +
                        fadeOut(WandMotion.tweenFast()) else ExitTransition.None,
                ) {
                    SessionFilesPanel(
                        files, api.baseUrl,
                        onDownload = { files.download { WandServerFileLink.download(context, api.baseUrl, it, api.token) } },
                        modifier = Modifier.width((config.screenWidthDp - 24).coerceIn(1, 560).dp).height(panelHeight),
                    )
                }
            }
        }
    }
}

internal class SessionFilesPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(margin,
            (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
        anchorBounds.bottom.coerceIn(margin,
            (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
    )
}

@Composable
internal fun SessionFilesPanel(files: SessionFilesController, baseUrl: String,
    onDownload: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.semantics { paneTitle = "会话文件" }, shape = WandShapes.lg,
        color = WandColors.bgElevated, tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("会话文件", style = MaterialTheme.typography.titleMedium,
                    color = WandColors.textPrimary, modifier = Modifier.weight(1f))
                WandIconButton(WandIcons.refresh, "重新读取", files::requestReload, enabled = !files.busy && !files.loading)
                WandIconButton(WandIcons.close, "关闭文件面板", files::requestClose,
                    enabled = files.savePhase != FileActionPhase.Running)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                WandIconButton(WandIcons.back, if (files.filePath == null) "上级目录" else "返回目录",
                    { if (files.filePath != null) files.requestBack()
                      else files.requestDirectory(parentWorkspaceDirectory(files.directory)) },
                    enabled = files.savePhase != FileActionPhase.Running && (files.filePath != null || files.directory != "/"))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(files.filePath ?: files.directory, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                }
                if (files.filePath == null && files.directory != files.root) {
                    WandIconButton(WandIcons.folder, "返回会话目录", { files.requestDirectory(files.root) }, enabled = !files.busy)
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (files.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = WandColors.brand)
            }
            WandInPlaceSwap(contentKey = files.filePath != null, modifier = Modifier.weight(1f).fillMaxWidth(),
                enterScale = 1f, exitScale = 1f) { showingFile ->
                if (showingFile == true) FileContent(files, baseUrl, onDownload)
                else DirectoryContent(files)
            }
        }
    }
    if (files.pendingDiscard != null) {
        WandDialog(
            title = "放弃未保存的修改？", onDismissRequest = files::cancelDiscard,
            confirm = WandDialogAction("放弃修改", files::confirmDiscard, destructive = true),
            dismiss = WandDialogAction("继续编辑", files::cancelDiscard),
        ) { Text("修改尚未保存到服务器。继续编辑可以保留当前草稿。", color = WandColors.textSecondary) }
    }
}

@Composable
private fun DirectoryContent(files: SessionFilesController) {
    var query by remember(files.directory) { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WandTextField(query, { query = it }, placeholder = "筛选当前目录", singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (files.listing?.truncated == true) {
            Text("当前显示前 ${files.listing?.items?.size} 项，共 ${files.listing?.total ?: "更多"} 项；筛选只查已加载项。",
                style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
        }
        val items = files.listing?.items.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
        when {
            files.loading -> PanelNotice("正在读取目录…", Modifier.weight(1f))
            files.error != null -> PanelNotice(files.error.orEmpty(), Modifier.weight(1f), error = true, retry = files::requestReload)
            items.isEmpty() -> PanelNotice(if (query.isEmpty()) "此目录没有文件" else "没有匹配的文件", Modifier.weight(1f))
            else -> LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.path }) { entry ->
                    WandListItem(
                        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { WandListItemIconSlot(if (entry.isDirectory) WandIcons.folder else WandIcons.attach) },
                        supportingContent = {
                            Text(if (entry.isDirectory) "文件夹" else entry.size?.let { ByteSizeFormatter.format(it) } ?: "文件")
                        },
                        trailingContent = if (entry.isDirectory) ({ WandListItemIconSlot(WandIcons.chevronRight) }) else null,
                        modifier = Modifier.clickable(role = Role.Button) {
                            if (entry.isDirectory) files.requestDirectory(entry.path) else files.requestFile(entry.path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun FileContent(files: SessionFilesController, baseUrl: String, onDownload: () -> Unit) {
    val file = files.file
    val clipboard = LocalClipboardManager.current
    var imageFailed by remember(file?.path, baseUrl) { mutableStateOf(false) }
    val editorFocus = remember(files) { FocusRequester() }
    LaunchedEffect(files.editing) { if (files.editing) editorFocus.requestFocus() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (files.editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WandButton("复制草稿", { clipboard.setText(AnnotatedString(files.draft)) }, variant = WandButtonVariant.Text)
                Spacer(Modifier.weight(1f))
                WandButton("取消编辑", files::toggleEditing, variant = WandButtonVariant.Text, enabled = !files.busy)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                files.loading -> PanelNotice("正在读取文件…", Modifier.fillMaxSize())
                files.error != null -> PanelNotice(files.error.orEmpty(), Modifier.fillMaxSize(), error = true,
                    retry = files::requestReload)
                files.editing -> WandTextField(files.draft, files::edit,
                    modifier = Modifier.fillMaxSize().focusRequester(editorFocus),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), enabled = !files.busy)
                file?.kind == "text" -> SelectionContainer {
                    Text(file.content.orEmpty().ifEmpty { "（空文件）" },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                        softWrap = false, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = WandColors.textPrimary)
                }
                file?.kind == "image" -> if (imageFailed) {
                    PanelNotice("图片加载失败，可下载原文件。", Modifier.fillMaxSize(), error = true)
                } else WandAsyncImage(file.path, baseUrl,
                    modifier = Modifier.align(Alignment.Center), maxWidth = 520, maxHeight = 360,
                    onFailure = { imageFailed = true })
                else -> PanelNotice("此格式不支持在线预览，可下载后用其他应用查看。", Modifier.fillMaxSize())
            }
        }
        Text(files.actionMessage ?: if (files.dirty) "有未保存的修改" else file?.size?.let {
            "${ByteSizeFormatter.format(it)} · ${if (file.canEdit) "文本文件，可编辑" else "只读预览"}"
        }.orEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (files.savePhase in listOf(FileActionPhase.Failed, FileActionPhase.Uncertain) ||
                files.downloadPhase == FileActionPhase.Failed) WandColors.dangerText else WandColors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WandButton(actionLabel(files.downloadPhase, "下载", "下载中", "已下载"), onDownload,
                modifier = Modifier.weight(1f).height(48.dp), icon = WandIcons.download,
                variant = WandButtonVariant.Secondary, loading = files.downloadPhase == FileActionPhase.Running,
                enabled = !files.busy && !files.loading)
            WandButton(if (files.saveConflict) "需重读" else if (files.editing) actionLabel(files.savePhase, "保存", "保存中", "已保存") else "编辑",
                { if (files.editing) files.save() else files.toggleEditing() },
                modifier = Modifier.weight(1f).height(48.dp),
                enabled = if (files.editing) files.canSave else file?.canEdit == true && !files.busy,
                loading = files.savePhase == FileActionPhase.Running)
        }
    }
}

private fun actionLabel(phase: FileActionPhase, idle: String, running: String, done: String): String = when (phase) {
    FileActionPhase.Idle -> idle
    FileActionPhase.Running -> running
    FileActionPhase.Done -> done
    FileActionPhase.Failed -> "重试$idle"
    FileActionPhase.Uncertain -> "未确认"
}

@Composable
private fun PanelNotice(text: String, modifier: Modifier, error: Boolean = false, retry: (() -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = if (error) WandColors.dangerText else WandColors.textSecondary)
        if (retry != null) WandButton("重试", retry, variant = WandButtonVariant.Text)
    }
}
