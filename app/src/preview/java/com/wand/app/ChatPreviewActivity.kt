package com.wand.app

import android.os.Bundle
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import com.wand.app.ui.ChatComposer
import com.wand.app.ui.SessionDraftStore
import com.wand.app.data.UploadedFile
import com.wand.app.data.TurnUsage
import com.wand.app.data.WandApiException
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.ui.SendPhase
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.components.*
import com.wand.app.ui.screens.*
import com.wand.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opt-in fixture only. No API, connection credentials or AI calls. */
class ChatPreviewActivity : ComponentActivity() {
    var sendCount = 0
    var stopCount = 0
    var runningForTest = false
    var editorVisible = false
    var modelSheetOpen = false
    var modelForTest = "model-a"
    var composerForTest: ChatComposer? = null
    var messageViewportForTest: Rect? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
        )
        val scale = intent.getFloatExtra("fontScale", 1f)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                WandTheme(if (dark) WandAppearanceMode.Dark else WandAppearanceMode.Light) {
                    CompositionLocalProvider(LocalReduceMotion provides intent.getBooleanExtra("reduceMotion", false)) {
                        Preview(intent.getStringExtra("mode") ?: "idle", this@ChatPreviewActivity)
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(mode: String, fixture: ChatPreviewActivity) {
    var activity by remember { mutableStateOf(AvatarFileActivity.Idle) }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(mode in setOf("stop", "running-empty", "running-unknown", "thinking", "reading", "writing", "streaming", "progress")) }
    val drafts = remember { SessionDraftStore(mapOf("fixture" to if (mode in setOf("send", "stop", "failure", "unknown", "draft", "running-unknown")) "继续检查键盘遮挡" else "")) }
    val sentTurns = remember { mutableStateListOf<ConversationTurn>() }
    val composer = remember {
        ChatComposer("fixture", drafts, scope, { true }, send = { prompt ->
            fixture.sendCount++
            delay(1000)
            if (mode == "failure") throw WandApiException(422, "示例：明确拒绝，草稿与附件保留")
            if (mode == "unknown" || mode == "running-unknown") throw WandApiException(500, "示例：送达未知，请核对，不能自动重发")
            if (mode == "viewport") sentTurns.add(ConversationTurn("user", listOf(ContentBlock.Text(prompt, null)), createdAt = "2026-10-12T09:00:00Z"))
            running = true
        }, notice = { notice = it })
    }
    val observedRunning = running
    SideEffect { fixture.composerForTest = composer; fixture.runningForTest = observedRunning }
    DisposableEffect(composer) { onDispose { composer.shutdown() } }
    LaunchedEffect(mode) {
        if (mode in setOf("attachment", "failure", "unknown")) composer.upload { listOf(UploadedFile("设计说明.txt", "/mock/design.txt", 128, "text/plain")) }
    }
    val progressTodos = if (mode == "progress") listOf(
        TodoEntry("排查安卓输入框与模型切换", "completed", null),
        TodoEntry("修复输入焦点与重复入口，保留模型搜索、附件选择和原生文字编辑", "in_progress", "修复输入焦点与重复入口"),
        TodoEntry("验证安卓交互并交付 Beta", "pending", null),
    ) else emptyList()
    val draft = composer.draft
    val delivery = composer.sendPhase
    val presentation = remember { ChatInputPresentation() }
    var settingsOpen by remember { mutableStateOf<String?>(null) }
    var attachmentsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("model-a") }
    val nativePickers = rememberAttachmentPickerActions { /* Cancel/selection never uploads in this fixture. */ }
    val observedModelOpen = modelOpen
    val observedModel = model
    SideEffect { fixture.modelSheetOpen = observedModelOpen; fixture.modelForTest = observedModel }
    var contextOpen by remember { mutableStateOf(false) }
    val orange = mode == "orange"
    val id = if (orange) "e_wand_ops" else "e_wand_default"
    val name = if (orange) "勤劳的初二" else "赛博虎妞"
    LaunchedEffect(mode) {
        if (mode == "reading") activity = AvatarFileActivity.Reading
        if (mode == "writing") activity = AvatarFileActivity.Writing
        if (mode == "motion") {
            while (true) {
                delay(2500); activity = AvatarFileActivity.Reading
                delay(5000); activity = AvatarFileActivity.Writing
                delay(5000); activity = AvatarFileActivity.Idle
            }
        }
    }
    val exampleTurns = remember { listOf(
        ConversationTurn("user", listOf(ContentBlock.Text("帮我整理这次聊天页面调整，保留现有功能，并核对键盘遮挡。", null)), createdAt = "2026-10-11T08:30:00Z"),
        ConversationTurn("assistant", listOf(ContentBlock.Text("已整理页面布局。消息内容保持清晰，附件和模型设置继续沿用现有入口。\n\n### 当前检查\n- 长回复和代码块可以正常阅读、复制。\n- 键盘打开后，输入区随系统上移。\n- 执行过程按真实工具状态展示。\n\n```typescript\nconst status = tool.finished ? \"idle\" : \"reading\";\n// 横向滚动保留完整的长代码：doNotTruncateImportantOutput();\n```\n\n这是原生 Compose 预览的示例内容，没有调用 AI，也没有修改任何用户会话。", null)), createdAt = "2026-10-11T08:30:12Z", completedAt = "2026-10-11T08:30:20Z")
    ) }
    val turns = if (mode == "viewport") sentTurns.toList() else exampleTurns
    val topClearance = chatTopIdentityClearance()
    Scaffold(containerColor = WandColors.bgPrimary,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().widthIn(max = 840.dp).fillMaxWidth()) {
                if (progressTodos.isNotEmpty()) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    TodoProgressBar(progressTodos, usage = TurnUsage(inputTokens = 469, outputTokens = 85, cacheReadInputTokens = null,
                        cacheCreationInputTokens = null, reasoningOutputTokens = null, totalCostUsd = 0.13, estimated = false),
                        detailsInSheet = true, onDetailsOpenChange = { presentation.overlayOpen = it })
                }
                SharedMessageComposer(backdrop = null, sessionKey = composer, draft = draft, onDraftChange = composer::editDraft,
                    attachments = composer.attachments, baseUrl = "", onRemoveAttachment = composer::removeAttachment, uploading = composer.uploading,
                    attachOpen = attachmentsOpen, onAttachOpenChange = { attachmentsOpen = it },
                    onPickPhoto = nativePickers.pickPhoto,
                    onPickFile = if (mode == "composer-focus") nativePickers.pickFile else {
                        { composer.upload { listOf(UploadedFile("设计说明.txt", "/mock/design.txt", 128, "text/plain")) }; Unit }
                    },
                    editorOverlayOpen = modelOpen || settingsOpen != null || nativePickers.open,
                    canSubmit = composer.canSubmit, onSend = { composer.submit() }, compactAfterAcknowledgement = true, deliveryPhase = delivery,
                    allowRefocus = true, onExpandedChange = { fixture.editorVisible = it },
                    presentation = presentation,
                    actionCount = composerActionPresentation(delivery, running, draft.isNotBlank() || composer.attachments.isNotEmpty(), composer.canSubmit).slotCount,
                    exteriorAction = { send -> ComposerSendStopActions(
                        presentation = composerActionPresentation(delivery, running, draft.isNotBlank() || composer.attachments.isNotEmpty(), composer.canSubmit),
                        stopDescription = "停止当前任务", sendDescription = if (running) "继续发送（排队）" else if (draft.isBlank() && composer.attachments.isNotEmpty()) "发送附件" else "发送消息",
                        onSend = send, onStop = { fixture.stopCount++; running = false }, canSubmit = composer.canSubmit) },
                    menuContent = {
                        if (running) WandInlinePanelAction(WandIcons.stop, "停止当前任务",
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = { attachmentsOpen = false; fixture.stopCount++; running = false })
                    },
                    trailingActions = { _, _ -> },
                    controls = {
                        ComposerSettingsRow(if (model == "model-a") "示例模型 A" else "示例模型 B", "标准", "自动",
                            onModel = { attachmentsOpen = false; modelOpen = true },
                            onMode = { settingsOpen = "权限" }, onThinking = { settingsOpen = "思考" })
                    })
                if (settingsOpen != null) ComposerChoiceSheet(settingsOpen!!,
                    listOf("default" to "当前档位（离线示例）", "alternative" to "另一档（离线示例，不更改会话）"), "default",
                    onSelect = { settingsOpen = null }, onDismiss = { settingsOpen = null })
                // Same stable owner as production InputBar, outside the conditionally mounted editor.
                if (modelOpen) ComposerChoiceSheet("模型", listOf("model-a" to "示例模型 A", "model-b" to "示例模型 B"),
                    model, onSelect = { model = it; modelOpen = false }, onDismiss = { modelOpen = false }, searchable = true)
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight()
                .padding(top = topClearance).clipToBounds().onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    fixture.messageViewportForTest = Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                }.pointerInput(presentation) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); presentation.dismiss() } }, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (mode == "empty") item { Text("向员工描述你想完成的事情", style = MaterialTheme.typography.bodyLarge) }
                else if (mode == "error") item { Text("示例：网络暂不可用，输入保留", color = WandColors.danger); TextButton(onClick = {}) { Text("重新加载") } }
                else if (mode == "loading") item { CircularProgressIndicator(Modifier.size(24.dp)) }
                else items(turns) { TurnView(it, employeeId = id, employeeName = name, employeeAvatar = "", isLastTurn = it.role == "assistant", showIdentity = false) }
            }
            ChatTopIdentityBar(
                identity = chatTopIdentity("整理聊天页面调整", "会话标题", name, "/workspace/demo"),
                attention = chatTopAttention(connected = true, blocked = false,
                    sending = delivery == SendPhase.Sending, responding = running, failed = delivery == SendPhase.Failed),
                statusText = when {
                    delivery == SendPhase.Sending -> "正在发送"
                    delivery == SendPhase.Failed -> "请核对发送结果，草稿已保留"
                    notice.isNotBlank() -> notice
                    mode == "thinking" -> "思考中"
                    activity == AvatarFileActivity.Reading -> "阅读中"
                    activity == AvatarFileActivity.Writing -> "编写中"
                    mode == "streaming" -> "回复中"
                    running -> companionWorkStatus(true, activity, progressTodos.isNotEmpty())
                    else -> ""
                },
            )
            FloatingChatControls(showBack = true, onBack = {},
                identity = name, cwd = "/workspace/demo", onTarget = { contextOpen = true },
                gitEnabled = true, onGit = {}, onStop = { running = false }.takeIf { running }, modifier = Modifier.align(Alignment.TopCenter),
                files = { FloatingChatIconButton(WandIcons.folder, "会话文件", {}) })
            if (contextOpen) AlertDialog(onDismissRequest = { contextOpen = false },
                title = { Text("会话对象与目录") }, text = { Text("$name\n/workspace/demo\n本地示例，不切换真实会话") },
                confirmButton = { TextButton(onClick = { contextOpen = false }) { Text("完成") } })

        }
    }
}
