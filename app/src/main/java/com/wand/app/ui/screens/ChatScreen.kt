package com.wand.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.SessionWatcher
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.WandApi
import com.wand.app.data.WandApiException
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.ChatStore
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 原生聊天视图 —— 对称 iOS ChatView.swift：
 * 结构化消息渲染 + 原生输入栏 + 权限审批卡片。
 * 输入栏跟随 imePadding()，键盘升降由系统接管 ——
 * 这正是 WebView 方案里键盘重叠/状态栏错位问题的根治点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    api: WandApi,
    sessionId: String,
    isHapticEnabled: () -> Boolean,
    onBack: () -> Unit,
) {
    val store = remember(sessionId) { ChatStore(sessionId, api) }
    val quickCommit = remember(sessionId) {
        QuickCommitStore(sessionId, api) { msg -> store.toast = msg }
    }
    DisposableEffect(store) {
        store.start()
        onDispose { store.shutdown() }
    }
    DisposableEffect(quickCommit) {
        onDispose { quickCommit.shutdown() }
    }
    // 注册「正在看」的会话：通知中枢据此抑制当前会话的打扰通知（对齐网页
    // skipWhenSelectedSessionId —— 正盯着的会话不需要系统通知再吵一遍）。
    DisposableEffect(sessionId) {
        SessionWatcher.activeChatSessionId = sessionId
        onDispose {
            if (SessionWatcher.activeChatSessionId == sessionId) {
                SessionWatcher.activeChatSessionId = null
            }
        }
    }
    // 进屏加载一次 git 状态（决定顶栏徽标显隐）；每回合结束后刷新（agent 可能改了文件）。
    LaunchedEffect(store.isResponding) {
        if (!store.isResponding) quickCommit.loadStatus(force = true)
    }

    var draft by remember { mutableStateOf("") }
    var followsLatest by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // 按住说话：端侧语音识别控制器（sherpa 本地模型优先，系统识别器兜底）。
    val context = LocalContext.current
    val voice = remember { VoiceInputController(context) }
    DisposableEffect(voice) {
        voice.onToast = { store.toast = it }
        onDispose { voice.destroy() }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        store.toast = if (granted) "已获得麦克风权限，按住麦克风说话" else "需要麦克风权限才能语音输入"
    }
    val onMicDown: () -> Unit = {
        if (voice.hasMicPermission()) {
            if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            voice.beginPress { text -> draft = appendVoiceText(draft, text) }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 仅用户明确向下拖动、准备查看更早消息时暂停跟随（阈值 18dp，对齐 iOS）。
    // 轻微触摸或收键盘不会误关跟随，新回复仍自动贴底。
    val density = LocalDensity.current
    val followPauseConnection = remember(density) {
        val thresholdPx = with(density) { 18.dp.toPx() }
        object : NestedScrollConnection {
            private var pulledDown = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y > 0f) {
                        pulledDown += available.y
                        if (pulledDown > thresholdPx) followsLatest = false
                    } else if (available.y < 0f) {
                        pulledDown = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    // 探索类工具跨消息合并成「探索上下文」紧凑卡（对齐 iOS groupExplorationTurns）。
    val displayItems = remember(store.messages) { groupExplorationTurns(store.messages) }

    // 附件上传：+ 菜单 → 系统文件选择器（多选 ≤5 个 / 单个 ≤10MB）→ savedPath 回填输入框。
    var uploadingAttachments by remember { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        uploadingAttachments = true
        scrollScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.take(5).map { uri -> readAttachment(context, uri) }
                }
                val uploaded = api.uploadAttachments(sessionId, files)
                val paths = uploaded.joinToString("\n") { it.savedPath }
                draft = "[附件已上传，请查看以下文件:\n$paths\n]\n\n" + draft
                store.toast = "已上传 ${uploaded.size} 个附件"
            } catch (e: Exception) {
                store.toast = e.message ?: "附件上传失败"
            } finally {
                uploadingAttachments = false
            }
        }
    }

    // 监听完整消息列表而不是 size：流式回复会原地替换最后一条消息，数量不变。
    // 列表末尾有独立锚点，确保长消息增长时滚到真正底部而非最后一项顶部。
    val bottomIndex = displayItems.size + if (store.isResponding) 1 else 0
    LaunchedEffect(store.messages, store.isResponding, store.loading) {
        if (!store.loading && followsLatest) {
            listState.scrollToItem(bottomIndex)
        }
    }

    // Toast 自动消失。
    LaunchedEffect(store.toast) {
        if (store.toast != null) {
            delay(2_600)
            store.toast = null
        }
    }

    Scaffold(
        containerColor = WandColors.bgPrimary,
        topBar = {
            // 顶栏对齐 iOS navigationStatus：居中显示最新一条用户消息 + 完整工作目录，
            // 右侧是 Git 变更统计 + 会话设置菜单（仅结构化会话）。
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            latestUserMessage(store.messages)
                                ?: store.snapshot?.displayTitle ?: "对话详情",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 190.dp),
                        )
                        Text(
                            middleTruncate(store.snapshot?.cwd ?: "未设置工作目录", 44),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 190.dp),
                        )
                    }
                },
                navigationIcon = {
                    // 返回箭头 + 当前 provider 小徽标（紧贴 title 起始侧）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = WandColors.textSecondary,
                            )
                        }
                        ChatProviderBadge(store.snapshot?.provider)
                    }
                },
                actions = {
                    GitChangesButton(quickCommit) { quickCommit.openPanel() }
                    if (store.isStructured) {
                        SessionSettingsMenu(store)
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = WandColors.bgPrimary,
                ),
            )
        },
        bottomBar = { BottomBar(
            store = store,
            draft = draft,
            onDraftChange = { draft = it },
            voice = voice,
            onMicDown = onMicDown,
            uploading = uploadingAttachments,
            onUpload = { attachmentPicker.launch(arrayOf("*/*")) },
        ) {
            // 发送回调（带触感反馈）；发送后立即恢复贴底跟随（对齐 iOS sendDraft）。
            if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val text = draft
            draft = ""
            followsLatest = true
            store.send(text)
        } },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                store.loading -> LoadingState("正在加载会话…")
                store.loadError != null -> ErrorState(store.loadError ?: "加载失败")
                store.isStructured && store.messages.isEmpty() && !store.isResponding ->
                    SessionLaunchPanel(store.snapshot?.providerLabel ?: "结构化会话")
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(followPauseConnection),
                        contentPadding = PaddingValues(
                            start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // key = index：流式原地替换最后一条时 key 不变不触发动画，
                        // 只有真正新增的消息才走 animateItem 淡入。
                        itemsIndexed(displayItems, key = { index, _ -> index }) { _, item ->
                            Box(modifier = Modifier.animateItem()) {
                                when (item) {
                                    is MessageDisplayItem.Turn -> TurnView(
                                        item.turn,
                                        isLastTurn = item.index == store.messages.lastIndex,
                                        isResponding = store.isResponding,
                                        askSelections = store.askUserSelections,
                                        onAskToggle = { toolUseId, qIdx, optIdx, multi ->
                                            store.toggleAskOption(toolUseId, qIdx, optIdx, multi)
                                        },
                                        onAskSubmit = { toolUseId, answerText ->
                                            followsLatest = true
                                            store.submitAskUser(toolUseId, answerText)
                                        },
                                    )
                                    is MessageDisplayItem.Exploration -> ExplorationGroupCard(
                                        tools = item.tools,
                                        running = store.isResponding &&
                                            item.lastTurnIndex == store.messages.lastIndex &&
                                            item.tools.any { it.result == null },
                                    )
                                }
                            }
                        }
                        if (store.isResponding) {
                            item(key = "responding") {
                                Box(modifier = Modifier.animateItem()) {
                                    RespondingIndicator(store.currentTaskTitle)
                                }
                            }
                        }
                        item(key = "chat-bottom") {
                            Spacer(modifier = Modifier.size(1.dp))
                        }
                    }
                }
            }

            // 回到底部按钮：淡入 + 缩放，替换硬切显隐。
            AnimatedVisibility(
                visible = !store.loading && store.loadError == null && !followsLatest,
                enter = fadeIn(WandMotion.tweenFast()) +
                    scaleIn(initialScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                exit = fadeOut(WandMotion.tweenFast()) +
                    scaleOut(targetScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        followsLatest = true
                        scrollScope.launch { listState.animateScrollToItem(bottomIndex) }
                    },
                    containerColor = WandColors.brand,
                    contentColor = Color.White,
                    shape = CircleShape,
                ) {
                    Icon(WandIcons.expand, contentDescription = "回到底部")
                }
            }

            // 端侧语音模型下载对话框（无可用识别引擎时由麦克风按钮触发）。
            if (voice.showModelDialog) {
                SttModelDownloadDialog(onDismiss = { voice.showModelDialog = false })
            }

            // Git 快捷提交弹层（磁吸气泡 dock，对齐网页版交互）。
            if (quickCommit.panelOpen) {
                QuickCommitSheet(
                    qc = quickCommit,
                    isHapticEnabled = isHapticEnabled,
                    onDismiss = { quickCommit.closePanel() },
                )
            }

            // Toast：顶部居中胶囊（淡入淡出 + 缓存文案避免退场闪空）。
            var lastToast by remember { mutableStateOf("") }
            LaunchedEffect(store.toast) {
                if (store.toast != null) lastToast = store.toast ?: lastToast
            }
            AnimatedVisibility(
                visible = store.toast != null,
                enter = fadeIn(WandMotion.tweenFast()) +
                    slideInVertically(WandMotion.tweenNormal()) { -it / 2 },
                exit = fadeOut(WandMotion.tweenNormal()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Text(
                    store.toast ?: lastToast,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 顶栏左侧 provider 小徽标：品牌色弱底圆角方块 + 品牌 logo，标明当前 Claude / Codex。 */
@Composable
private fun ChatProviderBadge(provider: String?) {
    val isCodex = provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val background = if (isCodex) WandColors.infoSoft else WandColors.brandSoft
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape(8.dp)),
    ) {
        Icon(
            if (isCodex) BrandLogos.codex else BrandLogos.claude,
            contentDescription = if (isCodex) "Codex" else "Claude",
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 最新一条非空用户消息（顶栏标题，对齐 iOS latestUserMessage）。 */
private fun latestUserMessage(messages: List<ConversationTurn>): String? {
    for (turn in messages.asReversed()) {
        if (turn.role != "user") continue
        val text = turn.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (text.isNotEmpty()) return text
    }
    return null
}

/** 中间截断（对齐 iOS .truncationMode(.middle)，Compose 没有内置实现）。 */
private fun middleTruncate(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val head = (maxChars - 1) / 2
    val tail = maxChars - 1 - head
    return text.take(head) + "…" + text.takeLast(tail)
}

/** 空结构化会话的居中欢迎卡（对齐 iOS sessionLaunchPanel）。 */
@Composable
private fun SessionLaunchPanel(providerLabel: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(WandColors.surface)
                .border(1.dp, WandColors.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            WandBrandMark(size = 52)
            Text(
                providerLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WandColors.textPrimary,
            )
        }
    }
}

/** 思考深度档位（对齐 iOS thinkingLevels / 服务端 thinking-effort 端点）。 */
private val THINKING_LEVELS = listOf(
    "off" to "off",
    "standard" to "think",
    "deep" to "think hard",
    "max" to "ultrathink",
)

private fun thinkingLabel(id: String): String =
    THINKING_LEVELS.firstOrNull { it.first == id }?.second ?: "off"

/**
 * 会话设置菜单（对齐 iOS sessionSettingsMenu）：
 * 顶栏齿轮图标 → 「模型 / 思考深度」两级菜单，实时切换进行中的会话参数。
 */
@Composable
private fun SessionSettingsMenu(store: ChatStore) {
    var menu by remember { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { menu = "root" }) {
            Icon(
                WandIcons.tune,
                contentDescription = "会话设置",
                tint = WandColors.brand,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = menu != null,
            onDismissRequest = { menu = null },
            containerColor = WandColors.surface,
        ) {
            when (menu) {
                "model" -> {
                    SettingsMenuOption("默认", selected = store.selectedModel == null) {
                        store.setModel(null)
                        menu = null
                    }
                    store.availableModels.filter { it.id != "default" }.forEach { model ->
                        SettingsMenuOption(model.label, selected = store.selectedModel == model.id) {
                            store.setModel(model.id)
                            menu = null
                        }
                    }
                }
                "thinking" -> THINKING_LEVELS.forEach { (id, label) ->
                    SettingsMenuOption(label, selected = store.thinkingEffort == id) {
                        store.chooseThinkingEffort(id)
                        menu = null
                    }
                }
                else -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "模型 · ${store.selectedModel ?: "默认"}",
                                fontSize = 13.sp,
                                color = WandColors.textPrimary,
                            )
                        },
                        trailingIcon = {
                            Icon(
                                WandIcons.chevronRight,
                                contentDescription = null,
                                tint = WandColors.textMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { menu = "model" },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "思考深度 · ${thinkingLabel(store.thinkingEffort)}",
                                fontSize = 13.sp,
                                color = WandColors.textPrimary,
                            )
                        },
                        trailingIcon = {
                            Icon(
                                WandIcons.chevronRight,
                                contentDescription = null,
                                tint = WandColors.textMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = { menu = "thinking" },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = WandColors.textPrimary) },
        leadingIcon = {
            if (selected) {
                Icon(
                    WandIcons.check,
                    contentDescription = "当前选中",
                    tint = WandColors.brand,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(16.dp))
            }
        },
        onClick = onClick,
    )
}

/**
 * Git 变更统计按钮（对齐 iOS gitChangesButton）：~修改 -删除 +新增，
 * 点击打开快速提交面板。
 */
@Composable
private fun GitChangesButton(quickCommit: QuickCommitStore, onClick: () -> Unit) {
    var modified = 0
    var deleted = 0
    var added = 0
    quickCommit.status?.files.orEmpty().forEach { file ->
        val status = file.status.uppercase()
        when {
            status.contains("?") || status.contains("A") -> added++
            status.contains("D") -> deleted++
            else -> modified++
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Icon(
            WandIcons.commit,
            contentDescription = "Git 变更",
            tint = WandColors.textSecondary,
            modifier = Modifier.size(15.dp),
        )
        val countStyle = TextStyle(
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Text("~$modified", style = countStyle, color = WandColors.textSecondary)
        Text("-$deleted", style = countStyle, color = WandColors.danger)
        Text("+$added", style = countStyle, color = WandColors.success)
    }
}

/** 从 content Uri 读出 (文件名, 字节)，供 multipart 上传。 */
private fun readAttachment(context: Context, uri: Uri): Pair<String, ByteArray> {
    var name = "attachment"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)?.takeIf { it.isNotEmpty() }?.let { name = it }
        }
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw WandApiException(null, "无法读取 $name")
    return name to bytes
}

@Composable
private fun RespondingIndicator(taskTitle: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        StatusDot("running")
        Text(
            taskTitle ?: "正在思考…",
            fontSize = 13.sp,
            color = WandColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - 底部栏（权限卡 + 队列 + 输入框）

@Composable
private fun BottomBar(
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    uploading: Boolean,
    onUpload: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WandColors.bgPrimary.copy(alpha = 0.97f))
            // 先垫系统导航栏，再垫输入法：键盘弹起时输入栏精确贴在键盘上方 ——
            // 这就是 WebView 时代键盘重叠问题的原生解法。
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 4.dp),
    ) {
        // 待办进度条：当前 turn 有未完成 todos 时悬浮在输入栏上方（对齐 Web todo-progress）。
        val todos = remember(store.messages) { currentTodos(store.messages) }
        if (todos.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TodoProgressBar(todos)
            }
        }
        // 权限审批卡：底部滑入 + 淡入；退场期间用缓存内容避免闪空。
        val hasPermission = store.pendingEscalation != null || store.legacyPermissionPrompt != null
        var cachedEscalation by remember { mutableStateOf<EscalationRequest?>(null) }
        var cachedLegacy by remember { mutableStateOf<PermissionRequestInfo?>(null) }
        LaunchedEffect(store.pendingEscalation, store.legacyPermissionPrompt) {
            if (store.pendingEscalation != null) cachedEscalation = store.pendingEscalation
            if (store.legacyPermissionPrompt != null) cachedLegacy = store.legacyPermissionPrompt
        }
        AnimatedVisibility(
            visible = hasPermission,
            enter = fadeIn(WandMotion.tweenNormal()) +
                slideInVertically(WandMotion.tweenNormal()) { it / 2 },
            exit = fadeOut(WandMotion.tweenFast()) +
                slideOutVertically(WandMotion.tweenFast()) { it / 2 },
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                PermissionCard(
                    escalation = store.pendingEscalation ?: cachedEscalation,
                    legacy = store.legacyPermissionPrompt ?: cachedLegacy,
                    onResolve = { store.resolvePermission(it) },
                )
            }
        }
        if (store.queuedMessages.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Icon(
                    WandIcons.history,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    "已排队 ${store.queuedMessages.size} 条消息",
                    fontSize = 12.sp,
                    color = WandColors.textMuted,
                )
            }
        }
        if (store.sessionEnded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(
                    "会话已结束",
                    fontSize = 12.sp,
                    color = WandColors.textMuted,
                )
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { store.resume() }) {
                    Text("恢复会话", fontSize = 13.sp, color = WandColors.brand)
                }
            }
        }
        // 按住说话实时转写气泡（按住期间悬浮在输入栏上方）。
        if (voice.pressed) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                VoiceTranscriptBubble(voice)
            }
        }
        // 语音输入模式：轻点话筒进入，整个输入框变成「按住说话」面板（转屏后保留）。
        var voiceMode by rememberSaveable { mutableStateOf(false) }
        InputBar(
            store = store,
            draft = draft,
            onDraftChange = onDraftChange,
            voice = voice,
            voiceMode = voiceMode,
            onVoiceModeChange = { voiceMode = it },
            onMicDown = onMicDown,
            uploading = uploading,
            onUpload = onUpload,
            onSend = onSend,
        )
    }
}

@Composable
private fun InputBar(
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    voice: VoiceInputController,
    voiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    onMicDown: () -> Unit,
    uploading: Boolean,
    onUpload: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !store.sessionEnded
    // 从语音模式轻点切回键盘时自动聚焦文本框，键盘直接弹起。
    val focusRequester = remember { FocusRequester() }
    var focusAfterExit by remember { mutableStateOf(false) }
    LaunchedEffect(voiceMode, focusAfterExit) {
        if (!voiceMode && focusAfterExit) {
            focusAfterExit = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    // 布局对齐 iOS inputBar：[+ 菜单] [输入框（麦克风嵌右下角）] [停止?] [发送]。
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ComposerActionsMenu(uploading = uploading, onUpload = onUpload)
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier.weight(1f),
        ) {
            if (voiceMode) {
                VoiceHoldField(
                    draft = draft,
                    voice = voice,
                    onMicDown = onMicDown,
                    onExitVoiceMode = {
                        focusAfterExit = true
                        onVoiceModeChange(false)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 0.dp),
                )
            } else {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        color = WandColors.textPrimary,
                    ),
                    cursorBrush = SolidColor(WandColors.brand),
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(WandColors.surface)
                                .border(1.dp, WandColors.border, RoundedCornerShape(20.dp))
                                .padding(start = 14.dp, end = 48.dp, top = 9.dp, bottom = 9.dp),
                        ) {
                            if (draft.isEmpty()) {
                                Text("发消息…", fontSize = 16.sp, color = WandColors.textMuted)
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp, max = 128.dp)
                        .focusRequester(focusRequester),
                )
            }
            VoiceMicButton(
                voice = voice,
                voiceMode = voiceMode,
                onToggleMode = { onVoiceModeChange(!voiceMode) },
                onMicDown = onMicDown,
                modifier = Modifier.padding(end = 4.dp, bottom = 3.dp),
            )
        }
        if (store.isResponding) {
            ComposerIconButton(
                background = WandColors.danger,
                enabled = true,
                onClick = { store.stopResponding() },
            ) {
                Icon(
                    WandIcons.stop,
                    contentDescription = "停止回复",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        ComposerIconButton(
            background = if (canSend) WandColors.brand else WandColors.brand.copy(alpha = 0.4f),
            enabled = canSend,
            onClick = onSend,
        ) {
            Icon(
                WandIcons.arrowUp,
                contentDescription = "发送",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 输入栏左侧「更多操作」按钮（对齐 iOS composerActionsMenu）：
 * 圆形 + 号，点开菜单首项「上传附件」；上传中显示转圈。
 */
@Composable
private fun ComposerActionsMenu(uploading: Boolean, onUpload: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(WandColors.surface)
                .border(1.dp, WandColors.border, CircleShape)
                .clickable(enabled = !uploading) { open = true },
        ) {
            if (uploading) {
                CircularProgressIndicator(
                    color = WandColors.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    WandIcons.add,
                    contentDescription = "更多操作",
                    tint = WandColors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = WandColors.surface,
        ) {
            DropdownMenuItem(
                text = { Text("上传附件", fontSize = 13.sp, color = WandColors.textPrimary) },
                leadingIcon = {
                    Icon(
                        WandIcons.attach,
                        contentDescription = null,
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    onUpload()
                },
            )
        }
    }
}

// MARK: - 按住说话（端侧语音识别）

/** 识别文本追加进草稿（不覆盖已有内容，对齐 Web commitVoiceTranscript / iOS appendTranscriptToDraft）。 */
private fun appendVoiceText(existing: String, text: String): String {
    val clean = text.trim()
    if (clean.isEmpty()) return existing
    val base = existing.trimEnd()
    return if (base.isEmpty()) clean else "$base $clean"
}

/** 轻点 vs 按住的分界：按住超过该时长进入录音，否则按轻点处理。 */
private const val VOICE_HOLD_THRESHOLD_MS = 300L

/**
 * 轻点 / 按住二分手势：
 * - 按住超过 [VOICE_HOLD_THRESHOLD_MS] → onHoldStart()（开始录音），
 *   之后移动驱动「上滑取消」，松手 endPress() 提交；
 * - 阈值内松手 → onTap()。
 * 录音的触感反馈在 onHoldStart（即 onMicDown）里触发，正好对应「真正开始聆听」。
 */
private suspend fun PointerInputScope.voiceTapOrHoldGesture(
    voice: VoiceInputController,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
) {
    val cancelThresholdPx = 60.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        var recording = false
        var elapsed = 0L
        while (true) {
            val event = if (recording) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(VOICE_HOLD_THRESHOLD_MS - elapsed) { awaitPointerEvent() }
            }
            if (event == null) {
                // 按满阈值仍未松手 → 进入按住录音（原有交互）。
                recording = true
                onHoldStart()
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            elapsed = change.uptimeMillis - down.uptimeMillis
            if (!change.pressed) {
                change.consume()
                if (!recording) onTap()
                break
            }
            change.consume()
            if (recording) {
                voice.updateCancel(down.position.y - change.position.y > cancelThresholdPx)
            }
        }
        if (recording) voice.endPress()
    }
}

/**
 * 麦克风按钮：
 * - 轻点 → 切换语音输入模式（整个输入框变成「按住说话」面板，图标变键盘）；
 * - 长按 → 立即按住说话（原交互）：按住录音、上滑取消、松手把识别文本追加进输入框。
 */
@Composable
private fun VoiceMicButton(
    voice: VoiceInputController,
    voiceMode: Boolean,
    onToggleMode: () -> Unit,
    onMicDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnToggle by rememberUpdatedState(onToggleMode)
    val currentOnMicDown by rememberUpdatedState(onMicDown)
    // 对齐 iOS micButton：32dp 圆形嵌输入框右下角，平时品牌弱底，按住实底（取消态红）。
    val background = when {
        voice.pressed && voice.canceling -> WandColors.danger
        voice.pressed -> WandColors.brand
        else -> WandColors.brand.copy(alpha = 0.12f)
    }
    val scale by animateFloatAsState(
        if (voice.pressed) 1.1f else 1f,
        WandMotion.tweenFast(),
        label = "micScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .pointerInput(voice) {
                voiceTapOrHoldGesture(
                    voice = voice,
                    onTap = { currentOnToggle() },
                    onHoldStart = { currentOnMicDown() },
                )
            },
    ) {
        Icon(
            if (voiceMode && !voice.pressed) WandIcons.keyboard else WandIcons.mic,
            contentDescription = if (voiceMode) "切回键盘输入" else "轻点切语音模式，长按说话",
            tint = if (voice.pressed) Color.White else WandColors.brand,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 语音模式下替换文本框的「按住说话」面板：
 * 按住录音（同话筒长按），轻点切回键盘输入；非录音时显示当前草稿，所见即所得。
 */
@Composable
private fun VoiceHoldField(
    draft: String,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    onExitVoiceMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnExit by rememberUpdatedState(onExitVoiceMode)
    val currentOnMicDown by rememberUpdatedState(onMicDown)
    val background by androidx.compose.animation.animateColorAsState(
        when {
            voice.pressed && voice.canceling -> WandColors.danger.copy(alpha = 0.16f)
            voice.pressed -> WandColors.brand.copy(alpha = 0.14f)
            else -> WandColors.surfaceSoft
        },
        WandMotion.tweenFast(),
        label = "voiceFieldBg",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, WandColors.border, RoundedCornerShape(20.dp))
            .pointerInput(voice) {
                voiceTapOrHoldGesture(
                    voice = voice,
                    onTap = { currentOnExit() },
                    onHoldStart = { currentOnMicDown() },
                )
            }
            .padding(start = 14.dp, end = 48.dp, top = 6.dp, bottom = 6.dp),
    ) {
        when {
            voice.pressed && voice.canceling -> Text(
                "松开手指，取消输入",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.danger,
            )
            voice.pressed -> Text(
                "松开结束 · 上滑取消",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.brand,
            )
            draft.isBlank() -> Text(
                "按住说话",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textSecondary,
            )
            else -> Text(
                draft,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = WandColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 按住期间的实时转写气泡：覆盖式文本 + 引擎标签 + 上滑取消提示。 */
@Composable
private fun VoiceTranscriptBubble(voice: VoiceInputController) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WandColors.surface)
            .border(
                1.dp,
                if (voice.canceling) WandColors.danger.copy(alpha = 0.55f) else WandColors.border,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (voice.canceling) WandIcons.close else WandIcons.mic,
                contentDescription = null,
                tint = if (voice.canceling) WandColors.danger else WandColors.brand,
                modifier = Modifier.size(16.dp),
            )
            Text(
                when {
                    voice.canceling -> "松开手指，取消输入"
                    voice.transcript.isEmpty() -> "正在聆听…"
                    else -> voice.transcript
                },
                fontSize = 14.sp,
                color = when {
                    voice.canceling -> WandColors.danger
                    voice.transcript.isEmpty() -> WandColors.textMuted
                    else -> WandColors.textPrimary
                },
            )
        }
        if (!voice.canceling) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    voice.engineLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(WandColors.brand.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(
                    "松开填入输入框 · 上滑取消",
                    fontSize = 11.sp,
                    color = WandColors.textMuted,
                )
            }
        }
    }
}

/** 端侧语音模型下载对话框：说明 → 下载进度 → 就绪/失败重试。 */
@Composable
private fun SttModelDownloadDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = SttModelManager.state
    val model = remember { SttModelManager.selectedModel(context) }
    // 下载完成立刻预热模型，让「下载完→按住即用」无加载等待。
    LaunchedEffect(state) {
        if (state is SttModelManager.State.Ready) SherpaSpeechEngine.warmUp(context)
    }
    AlertDialog(
        onDismissRequest = { if (state !is SttModelManager.State.Downloading) onDismiss() },
        containerColor = WandColors.surface,
        title = {
            Text(
                "下载本地语音模型",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is SttModelManager.State.Downloading -> {
                        Text(
                            "正在下载语音识别模型…",
                            fontSize = 13.sp,
                            color = WandColors.textSecondary,
                        )
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            color = WandColors.brand,
                            trackColor = WandColors.surfaceSoft,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.percent}%（${formatMb(state.downloadedBytes)} / ${formatMb(state.totalBytes)}）",
                            fontSize = 12.sp,
                            color = WandColors.textMuted,
                        )
                    }
                    is SttModelManager.State.Ready -> Text(
                        "模型已就绪，按住麦克风即可语音输入，识别完全在本机离线运行。",
                        fontSize = 13.sp,
                        color = WandColors.textSecondary,
                    )
                    is SttModelManager.State.Failed -> Text(
                        "${state.message}\n可重试，会自动切换镜像源。",
                        fontSize = 13.sp,
                        color = WandColors.danger,
                    )
                    else -> Text(
                        "此设备没有可用的系统语音识别服务。下载开源端侧模型" +
                            "（${model.label}，${model.sizeLabel}）后，" +
                            "语音识别完全在本机离线运行：不耗流量、语音内容不出设备。" +
                            "可在设置页切换识别模型。",
                        fontSize = 13.sp,
                        color = WandColors.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is SttModelManager.State.Downloading -> TextButton(onClick = { SttModelManager.cancelDownload() }) {
                    Text("取消下载", color = WandColors.danger, fontSize = 13.sp)
                }
                is SttModelManager.State.Ready -> TextButton(onClick = onDismiss) {
                    Text("知道了", color = WandColors.brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> TextButton(onClick = { SttModelManager.startDownload(context) }) {
                    Text(
                        if (state is SttModelManager.State.Failed) "重试" else "下载",
                        color = WandColors.brand,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            if (state is SttModelManager.State.Idle || state is SttModelManager.State.Failed) {
                TextButton(onClick = onDismiss) {
                    Text("暂不", color = WandColors.textMuted, fontSize = 13.sp)
                }
            }
        },
    )
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

/** 输入栏操作按钮：38dp 圆形（对齐 iOS 停止/发送圆钮），保留按压缩放反馈。 */
@Composable
private fun ComposerIconButton(
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        WandMotion.tweenFast(),
        label = "composerBtnScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        content()
    }
}
