package com.wand.app

import android.content.Context
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wand.app.R
import com.wand.app.ui.components.WORKSPACE_REVEAL_END
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandConnectionScene
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandMorphingIcon
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.revealFraction
import com.wand.app.data.ServerProfile
import com.wand.app.data.ServerProfiles
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandSpacing
import com.wand.app.ui.theme.WandTheme
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.wandSelectedSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

interface ConnectUiListener {
    fun onOpeningComplete()
    fun onConnect()
    fun onScanQr()
    fun onCancelAutoConnect()
    fun onSwitchServer()
    fun onPickServer(serverId: String)
    fun onRenameServer(serverId: String, name: String)
    fun onRemoveServer(serverId: String)
    fun onClearServers()
}

/** Java 连接流程的 Compose 显示 adapter。业务状态仍由 ConnectActivity 掌管。 */
class ConnectComposeView(context: Context, private val playOpening: Boolean) : AbstractComposeView(context) {
    private var uiInputValue by mutableStateOf("")
    private var uiServerAlias by mutableStateOf("")
    private var uiAddServerExpanded by mutableStateOf(false)
    private var uiAutoConnecting by mutableStateOf(false)
    private var uiAutoStatus by mutableStateOf(context.getString(R.string.auto_connecting))
    private var uiConnecting by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var statusIsError by mutableStateOf(true)
    private var statusServerId by mutableStateOf<String?>(null)
    private var uiServerProfiles by mutableStateOf(emptyList<ServerProfile>())
    private var uiActiveServerId by mutableStateOf<String?>(null)
    private var uiConnectingServerId by mutableStateOf<String?>(null)
    private var openingVisible by mutableStateOf(playOpening)
    private var listener: ConnectUiListener? = null

    fun setListener(value: ConnectUiListener) {
        listener = value
    }

    fun setIncomingConnection(value: String) {
        uiInputValue = value
        uiServerAlias = ""
        uiAddServerExpanded = true
    }

    fun setScannedConnection(value: String) {
        if (uiInputValue.isNotBlank() && uiInputValue.trim() != value.trim()) {
            uiServerAlias = ""
        }
        uiInputValue = value
        uiAddServerExpanded = true
    }

    fun clearConnectionDraft() {
        uiInputValue = ""
        uiServerAlias = ""
        uiAddServerExpanded = false
    }

    fun getInputValue(): String = uiInputValue

    fun getServerAlias(): String = uiServerAlias

    fun showAutoConnecting(status: String) {
        uiAutoStatus = status
        uiAutoConnecting = true
        statusMessage = null
    }

    fun setAutoStatus(status: String) {
        uiAutoStatus = status
    }

    fun showForm() {
        uiAutoConnecting = false
    }

    fun isAutoConnectVisible(): Boolean = uiAutoConnecting

    fun setConnecting(value: Boolean) {
        uiConnecting = value
        if (value) {
            statusMessage = null
            if (uiConnectingServerId == null) statusServerId = null
        }
        if (!value) uiConnectingServerId = null
    }

    fun showStatus(message: String, isError: Boolean) {
        statusMessage = message
        statusIsError = isError
    }

    fun setConnectingServer(serverId: String?) {
        uiConnectingServerId = serverId
        statusServerId = serverId
        setConnecting(serverId != null)
    }

    fun setServerProfiles(profiles: List<ServerProfile>, activeServerId: String?) {
        uiServerProfiles = profiles.toList()
        uiActiveServerId = activeServerId
    }

    @Composable
    override fun Content() {
        WandTheme {
            ConnectScreen(
                inputValue = uiInputValue,
                serverAlias = uiServerAlias,
                addServerExpanded = uiAddServerExpanded,
                onInputValueChange = {
                    if (it.isBlank() && uiInputValue.isNotBlank()) uiServerAlias = ""
                    uiInputValue = it
                    statusMessage = null
                    statusServerId = null
                },
                onServerAliasChange = { uiServerAlias = ServerProfiles.normalizeCustomNameInput(it) },
                onAddServerExpandedChange = { uiAddServerExpanded = it },
                onPasteUnavailable = {
                    statusServerId = null
                    showStatus("剪贴板中没有可粘贴的连接码或地址", false)
                },
                autoConnecting = uiAutoConnecting,
                autoStatus = uiAutoStatus,
                connecting = uiConnecting,
                statusMessage = statusMessage,
                statusIsError = statusIsError,
                statusServerId = statusServerId,
                serverProfiles = uiServerProfiles,
                activeServerId = uiActiveServerId,
                connectingServerId = uiConnectingServerId,
                openingVisible = openingVisible,
                onOpeningComplete = {
                    openingVisible = false
                    listener?.onOpeningComplete()
                },
                listener = listener,
            )
        }
    }
}

@Composable
private fun ConnectScreen(
    inputValue: String,
    serverAlias: String,
    addServerExpanded: Boolean,
    onInputValueChange: (String) -> Unit,
    onServerAliasChange: (String) -> Unit,
    onAddServerExpandedChange: (Boolean) -> Unit,
    onPasteUnavailable: () -> Unit,
    autoConnecting: Boolean,
    autoStatus: String,
    connecting: Boolean,
    statusMessage: String?,
    statusIsError: Boolean,
    statusServerId: String?,
    serverProfiles: List<ServerProfile>,
    activeServerId: String?,
    connectingServerId: String?,
    openingVisible: Boolean,
    onOpeningComplete: () -> Unit,
    listener: ConnectUiListener?,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var fieldValue by androidx.compose.runtime.remember { mutableStateOf(TextFieldValue(inputValue)) }
    androidx.compose.runtime.LaunchedEffect(inputValue) {
        if (inputValue != fieldValue.text) fieldValue = TextFieldValue(inputValue)
    }
    var pendingRemoval by androidx.compose.runtime.remember { mutableStateOf<ServerProfile?>(null) }
    var confirmClear by androidx.compose.runtime.remember { mutableStateOf(false) }
    val reduceMotion = reduceMotionEnabled()
    val hasConnectionCode = remember(inputValue) {
        inputValue.isNotBlank() && com.wand.app.data.WandAuth.decodeConnectCode(inputValue) != null
    }
    pendingRemoval?.let { profile ->
        WandDialog(
            title = "移除服务器？",
            onDismissRequest = { pendingRemoval = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "移除",
                destructive = true,
                onClick = {
                    pendingRemoval = null
                    listener?.onRemoveServer(profile.id)
                },
            ),
            dismiss = WandDialogAction("取消", { pendingRemoval = null }),
        ) {
            Text(
                "仅从这台设备移除「${profile.visibleName()}」及其连接凭据，不会删除服务器上的会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }
    if (confirmClear) {
        WandDialog(
            title = "移除所有服务器？",
            onDismissRequest = { confirmClear = false },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "全部移除",
                destructive = true,
                onClick = {
                    confirmClear = false
                    listener?.onClearServers()
                },
            ),
            dismiss = WandDialogAction("取消", { confirmClear = false }),
        ) {
            Text(
                "仅清除此设备保存的服务器地址和连接凭据，不会删除任何服务器上的会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(Modifier.fillMaxSize())
        if (!openingVisible) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = WandSpacing.lg, vertical = WandSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WandSpacing.sm),
                    ) {
                        WandBrandMark(size = 32)
                        Column {
                            Text(
                                "WAND",
                                style = MaterialTheme.typography.labelMedium,
                                color = WandColors.brand,
                            )
                            Text(
                                "远程工作台",
                                style = MaterialTheme.typography.bodySmall,
                                color = WandColors.textSecondary,
                            )
                        }
                    }
                    Text(
                        if (autoConnecting) "正在接通工作台" else if (serverProfiles.isEmpty())
                            "连接你的工作台" else "选择服务器",
                        style = MaterialTheme.typography.headlineSmall,
                        color = WandColors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WandSpacing.lg),
                    )
                    Text(
                        if (autoConnecting) "已保存的工作台正在恢复连接。" else if (serverProfiles.isEmpty())
                            "添加 Wand 服务器，终端、会话与任务即可在手机上继续。"
                        else "点按一台服务器，继续上次的工作。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WandSpacing.xs),
                    )
                    ConnectionFeedback(
                        if (!autoConnecting && serverProfiles.isNotEmpty() &&
                            !addServerExpanded && statusServerId == null
                        ) statusMessage else null,
                        statusIsError,
                        reduceMotion,
                    )
                    AnimatedVisibility(
                        visible = !autoConnecting && serverProfiles.isNotEmpty(),
                        enter = if (reduceMotion) EnterTransition.None else
                            fadeIn(WandMotion.tweenEnter()) + expandVertically(WandMotion.tweenEnter()),
                        exit = if (reduceMotion) ExitTransition.None else
                            fadeOut(WandMotion.tweenExit()) + shrinkVertically(WandMotion.tweenExit()),
                    ) {
                        SavedServerSection(
                            profiles = serverProfiles,
                            activeServerId = activeServerId,
                            connectingServerId = connectingServerId,
                            feedbackServerId = statusServerId,
                            statusMessage = statusMessage,
                            statusIsError = statusIsError,
                            enabled = !connecting,
                            onPickServer = { listener?.onPickServer(it) },
                            onRenameServer = { id, name -> listener?.onRenameServer(id, name) },
                            onRemoveServer = { pendingRemoval = it },
                            onClearServers = { confirmClear = true },
                        )
                    }
                    if (!autoConnecting && serverProfiles.isNotEmpty()) {
                        AddServerTrigger(
                            expanded = addServerExpanded,
                            enabled = !connecting,
                            onClick = { onAddServerExpandedChange(!addServerExpanded) },
                            modifier = Modifier.padding(top = WandSpacing.md),
                        )
                    }
                    WandInlinePanel(
                        visible = autoConnecting || serverProfiles.isEmpty() || addServerExpanded,
                        growFrom = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                    WandCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (autoConnecting || serverProfiles.isEmpty()) WandSpacing.lg else WandSpacing.xs),
                        contentPadding = PaddingValues(WandSpacing.lg),
                    ) {
                        AnimatedContent(
                            targetState = autoConnecting,
                            transitionSpec = {
                                val fade = if (reduceMotion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
                                }
                                fade.using(SizeTransform(clip = false) { _, _ ->
                                    WandMotion.respectMotion(!reduceMotion, WandMotion.tweenNormal())
                                })
                            },
                            label = "connectionMode",
                        ) { showingAutoConnect ->
                            if (showingAutoConnect) {
                                Column {
                                    Text(
                                        "恢复连接",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = WandColors.textPrimary,
                                        modifier = Modifier.padding(bottom = WandSpacing.lg),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(WandSpacing.md),
                                    ) {
                                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                            if (reduceMotion) {
                                                Icon(
                                                    WandIcons.refresh,
                                                    contentDescription = null,
                                                    tint = WandColors.brand,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            } else {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(28.dp),
                                                    color = WandColors.brand,
                                                    strokeWidth = 2.5.dp,
                                                )
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "正在恢复连接",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = WandColors.textPrimary,
                                            )
                                            AnimatedContent(
                                                targetState = autoStatus,
                                                transitionSpec = {
                                                    val fade = if (reduceMotion) {
                                                        EnterTransition.None togetherWith ExitTransition.None
                                                    } else {
                                                        fadeIn(WandMotion.tweenFast()) togetherWith
                                                            fadeOut(WandMotion.tweenExit())
                                                    }
                                                    fade.using(SizeTransform(clip = false) { _, _ ->
                                                        WandMotion.respectMotion(!reduceMotion, WandMotion.tweenNormal())
                                                    })
                                                },
                                                label = "connectionStatus",
                                            ) { status ->
                                                Text(
                                                    status,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = WandColors.textSecondary,
                                                    modifier = Modifier
                                                        .padding(top = WandSpacing.xxs)
                                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = WandSpacing.lg),
                                        horizontalArrangement = Arrangement.spacedBy(WandSpacing.xs, Alignment.End),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        WandButton(
                                            label = "取消",
                                            onClick = { listener?.onCancelAutoConnect() },
                                            variant = WandButtonVariant.Text,
                                        )
                                        WandButton(
                                            label = "管理服务器",
                                            onClick = { listener?.onSwitchServer() },
                                            variant = WandButtonVariant.Secondary,
                                        )
                                    }
                                }
                            } else {
                                Column {
                                    Text(
                                        if (serverProfiles.isEmpty()) "添加第一台服务器" else "连接新服务器",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = WandColors.textPrimary,
                                    )
                                    Text(
                                        "从 Web 设置复制 App 连接码，也可以输入服务器地址。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WandColors.textSecondary,
                                        modifier = Modifier.padding(top = WandSpacing.xs, bottom = WandSpacing.lg),
                                    )
                                    WandTextField(
                                        value = fieldValue,
                                        onValueChange = {
                                            fieldValue = it
                                            onInputValueChange(it.text)
                                        },
                                        label = "连接码或服务器地址",
                                        placeholder = "粘贴连接码，或输入服务器地址",
                                        singleLine = true,
                                        enabled = !connecting,
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.None,
                                            autoCorrectEnabled = false,
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Go,
                                        ),
                                        keyboardActions = KeyboardActions(onGo = { listener?.onConnect() }),
                                        trailingIcon = {
                                            WandButton(
                                                label = "粘贴",
                                                onClick = {
                                                    val clip = clipboard?.primaryClip
                                                    val pasted = if (clip != null && clip.itemCount > 0) {
                                                        clip.getItemAt(0).coerceToText(context).toString().trim()
                                                    } else {
                                                        ""
                                                    }
                                                    if (pasted.isEmpty()) {
                                                        onPasteUnavailable()
                                                    } else {
                                                        fieldValue = TextFieldValue(
                                                            pasted,
                                                            selection = TextRange(pasted.length),
                                                        )
                                                        onInputValueChange(pasted)
                                                    }
                                                },
                                                variant = WandButtonVariant.Text,
                                                enabled = !connecting,
                                                compact = true,
                                            )
                                        },
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    )
                                    AnimatedVisibility(
                                        visible = hasConnectionCode && statusMessage == null,
                                        enter = if (reduceMotion) EnterTransition.None else
                                            fadeIn(WandMotion.tweenFast()) + expandVertically(WandMotion.tweenEnter()),
                                        exit = if (reduceMotion) ExitTransition.None else
                                            fadeOut(WandMotion.tweenExit()) + shrinkVertically(WandMotion.tweenExit()),
                                    ) {
                                        Text(
                                            "已识别连接码",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = WandColors.success,
                                            modifier = Modifier.padding(top = WandSpacing.xs),
                                        )
                                    }
                                    WandTextField(
                                        value = serverAlias,
                                        onValueChange = onServerAliasChange,
                                        label = "服务器名称（可选）",
                                        placeholder = "例如：家里的工作台",
                                        singleLine = true,
                                        enabled = !connecting,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { listener?.onConnect() }),
                                        modifier = Modifier.fillMaxWidth().padding(top = WandSpacing.sm),
                                    )
                                    Text(
                                        "这个名称只在这台设备显示，之后可随时修改。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WandColors.textSecondary,
                                        modifier = Modifier.padding(top = WandSpacing.xs),
                                    )
                                    ConnectionFeedback(
                                        if (statusServerId == null) statusMessage else null,
                                        statusIsError,
                                        reduceMotion,
                                    )
                                    WandButton(
                                        label = if (connecting && connectingServerId == null) "连接中…" else "连接并保存",
                                        onClick = { listener?.onConnect() },
                                        loading = connecting && connectingServerId == null,
                                        enabled = inputValue.isNotBlank() && !connecting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = WandSpacing.md),
                                    )
                                    WandButton(
                                        label = "扫描二维码",
                                        icon = Icons.Outlined.QrCodeScanner,
                                        onClick = { listener?.onScanQr() },
                                        variant = WandButtonVariant.Secondary,
                                        enabled = !connecting,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = WandSpacing.xs),
                                    )
                                }
                            }
                        }
                    }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WandSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            WandIcons.permission,
                            contentDescription = null,
                            tint = WandColors.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "连接凭据仅发送给你选择的 Wand 服务器",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textSecondary,
                            modifier = Modifier.padding(start = WandSpacing.xs),
                        )
                    }
                    Spacer(Modifier.height(WandSpacing.md))
                }
            }
        }
        AnimatedVisibility(
            visible = openingVisible,
            enter = EnterTransition.None,
            exit = if (reduceMotion) ExitTransition.None else fadeOut(WandMotion.tweenExit()),
        ) {
            ConnectionOpening(onComplete = onOpeningComplete)
        }
    }
}

@Composable
private fun ConnectionOpening(onComplete: () -> Unit) {
    val reduceMotion = reduceMotionEnabled()
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    // 场景里工作区标的落点（窗口坐标）。layout 后才量得到，但飞行标首帧就要画在屏幕中心，
    // 所以起点不依赖测量（用窗口中心），落地偏移测到之前一直是 0。
    var landingBounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, WandMotion.openingJourney())
            delay(WandMotion.openingSettle.toLong())
        }
        onComplete()
    }
    val journey = progress.value
    // 没测到落点就先不动（否则会拿 0 尺寸当终点、把标缩没）。
    val travel = if (reduceMotion || landingBounds == null) 0f else openingMarkTravel(journey)
    val density = LocalDensity.current
    val markSize = OpeningMarkSize
    val markSizePx = with(density) { markSize.toPx() }
    val landing = landingBounds
    // 起点：窗口中心 = 系统 splash 图标的位置（实测 splash 图标就在屏幕正中）。
    // 用 containerSize 而不是 onGloballyPositioned：首帧还没有测量结果，而这一帧恰好
    // 是与 splash 接上的那一帧。
    val windowCenter = LocalWindowInfo.current.containerSize.let {
        Offset(it.width / 2f, it.height / 2f)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .statusBarsPadding()
                .navigationBarsPadding()
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(horizontal = WandSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "WAND  /  CONNECT",
                style = MaterialTheme.typography.labelMedium,
                color = WandColors.brand,
            )
            WandConnectionScene(
                progress = journey,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = WandSpacing.xxl),
                // 飞行标就是场景里的那只标（同一个实例从中心飞过去），所以场景那只先不画。
                // 测不到落点（layout 没报）时退回场景自画，插图不会少一只标。
                markAlpha = if (reduceMotion || landingBounds == null) null else 0f,
                onMarkPlaced = { landingBounds = it },
            )
            Text(
                "把工作，接到手边",
                style = MaterialTheme.typography.headlineSmall,
                color = WandColors.textPrimary,
            )
            Text(
                "终端、会话与任务，在同一个地方继续。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
                modifier = Modifier.padding(top = WandSpacing.xs),
            )
        }
        if (!reduceMotion) {
            val delta = landing?.let { it.center - windowCenter } ?: Offset.Zero
            val landingSizePx = landing?.width ?: markSizePx
            OpeningBrandMark(
                diameter = markSize,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            (delta.x * travel).roundToInt(),
                            (delta.y * travel).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        val scale =
                            openingMarkSize(markSizePx, landingSizePx, travel) / markSizePx
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/** 开屏品牌标的起始直径。取系统 splash 图标里那只猫的实测尺寸（Pixel 8 / API 36 / 420dpi：162dp），
 *  换图标或换平台渲染后要重测：抓 splash 首帧量猫的像素宽 ÷ density。 */
private val OpeningMarkSize = 162.dp

/** 猫在 `ic_launcher_foreground`（108 viewport）里只占中间 60，放大后标盒直径就等于猫的实际直径。 */
private const val BRAND_CAT_ZOOM = 108f / 60f

/**
 * 只有猫、没有底板的品牌标，直径按猫的实际尺寸计算。
 * 用 `ic_launcher_foreground` 同一份形状，所以它和系统 splash 上那只猫逐像素一致。
 */
@Composable
private fun OpeningBrandMark(diameter: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(diameter)) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = BRAND_CAT_ZOOM
                    scaleY = BRAND_CAT_ZOOM
                },
        )
    }
}

/**
 * 开屏飞入进度：`0` = 屏幕中心（与系统 splash 上那只猫同一位置，先停一拍再动），
 * `1` = 场景里的工作区标位，与工作区成形（[WORKSPACE_REVEAL_END]）同一拍落地。
 * 时间窗内再套一层标准缓动：猫是「位置变化」，要落稳而不是匀速滑。
 */
internal fun openingMarkTravel(journey: Float): Float =
    WandMotion.easing.transform(
        revealFraction(journey, OPENING_MARK_HOLD, WORKSPACE_REVEAL_END),
    )

/** 标的实际直径：从 [startSize] 收到落点尺寸 [landingSize]。 */
internal fun openingMarkSize(startSize: Float, landingSize: Float, travel: Float): Float =
    startSize + (landingSize - startSize) * travel.coerceIn(0f, 1f)

/** 起飞前的停顿占整段旅程的比例：先让用户认出「还是刚才那只猫」，再飞（与工作区开始显现同一拍）。 */
internal const val OPENING_MARK_HOLD = 0.12f

@Composable
private fun ConnectionFeedback(message: String?, isError: Boolean, reduceMotion: Boolean) {
    AnimatedContent(
        targetState = message,
        transitionSpec = {
            val fade = if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
            }
            fade.using(SizeTransform(clip = false) { _, _ ->
                WandMotion.respectMotion(!reduceMotion, WandMotion.tweenNormal())
            })
        },
        label = "connectionFeedback",
    ) { feedback ->
        if (feedback != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WandSpacing.md)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isError) WandColors.dangerSoft else WandColors.infoSoft)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(WandSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(WandSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isError) {
                    Icon(
                        WandIcons.error,
                        contentDescription = null,
                        tint = WandColors.danger,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) WandColors.danger else WandColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AddServerTrigger(
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.morph()),
        label = "addServerIcon",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(WandColors.surface.copy(alpha = 0.84f))
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = WandSpacing.md, vertical = WandSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WandSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WandColors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            WandMorphingIcon(
                progress = iconProgress,
                from = WandIcons.add,
                to = WandIcons.close,
                tint = WandColors.brand,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "添加服务器",
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
            )
            Text(
                "连接码、地址或扫码",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
            )
        }
    }
}

@Composable
private fun SavedServerSection(
    profiles: List<ServerProfile>,
    activeServerId: String?,
    connectingServerId: String?,
    feedbackServerId: String?,
    statusMessage: String?,
    statusIsError: Boolean,
    enabled: Boolean,
    onPickServer: (String) -> Unit,
    onRenameServer: (String, String) -> Unit,
    onRemoveServer: (ServerProfile) -> Unit,
    onClearServers: () -> Unit,
) {
    var editingServerId by remember { mutableStateOf<String?>(null) }
    var editedName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    WandCard(
        modifier = Modifier.fillMaxWidth().padding(top = WandSpacing.lg),
        contentPadding = PaddingValues(WandSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = WandSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WandSpacing.xs),
        ) {
            Text(
                "你的服务器",
                style = MaterialTheme.typography.titleMedium,
                color = WandColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${profiles.size} 台",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textSecondary,
            )
        }
        profiles.forEachIndexed { index, profile ->
            if (index > 0) {
                HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
            }
            SavedServerRow(
                profile = profile,
                active = profile.id == activeServerId,
                connecting = profile.id == connectingServerId,
                enabled = enabled,
                onClick = { onPickServer(profile.id) },
                onRename = {
                    editedName = profile.customName.orEmpty()
                    editingServerId = if (editingServerId == profile.id) null else profile.id
                },
                onRemove = {
                    editingServerId = null
                    onRemoveServer(profile)
                },
            )
            WandInlinePanel(
                visible = editingServerId == profile.id,
                growFrom = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val focusRequester = remember(profile.id) { FocusRequester() }
                LaunchedEffect(editingServerId) {
                    if (editingServerId == profile.id) focusRequester.requestFocus()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(WandColors.surfaceSoft.copy(alpha = 0.55f))
                        .padding(WandSpacing.md),
                ) {
                    Text(
                        "修改显示名称",
                        style = MaterialTheme.typography.labelLarge,
                        color = WandColors.textPrimary,
                    )
                    WandTextField(
                        value = editedName,
                        onValueChange = { editedName = ServerProfiles.normalizeCustomNameInput(it) },
                        label = "服务器别名",
                        placeholder = profile.displayName,
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            onRenameServer(profile.id, editedName)
                            editingServerId = null
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    Text(
                        "留空后按保存，将恢复显示服务器地址。仅在这台设备上生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textSecondary,
                        modifier = Modifier.padding(top = WandSpacing.xs),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = WandSpacing.xs),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        WandButton(
                            label = "取消",
                            onClick = {
                                editingServerId = null
                                focusManager.clearFocus()
                            },
                            variant = WandButtonVariant.Text,
                            compact = true,
                        )
                        WandButton(
                            label = "保存名称",
                            onClick = {
                                onRenameServer(profile.id, editedName)
                                editingServerId = null
                                focusManager.clearFocus()
                            },
                            variant = WandButtonVariant.Secondary,
                            enabled = enabled,
                            compact = true,
                        )
                    }
                }
            }
            if (feedbackServerId == profile.id) {
                ConnectionFeedback(statusMessage, statusIsError, reduceMotionEnabled())
            }
        }
        WandButton(
            label = "移除所有服务器",
            onClick = onClearServers,
            variant = WandButtonVariant.DangerText,
            enabled = enabled,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun SavedServerRow(
    profile: ServerProfile,
    active: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val authenticationLabel = if (profile.hasToken) "已认证" else "直接连接"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .wandSelectedSurface(
                selected = active,
                shape = RoundedCornerShape(14.dp),
                unselectedFill = androidx.compose.ui.graphics.Color.Transparent,
                showUnselectedBorder = false,
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = WandSpacing.xs, end = 2.dp, top = WandSpacing.xs, bottom = WandSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WandSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (active) WandColors.brandSoft else WandColors.surfaceSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WandIcons.server,
                contentDescription = null,
                tint = if (active) WandColors.brand else WandColors.textSecondary,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    stateDescription = buildString {
                        append(authenticationLabel)
                        if (active) append("，当前服务器")
                        if (connecting) append("，正在连接")
                    }
                }
                .padding(vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    profile.visibleName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) WandColors.brand else WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    ServerStateBadge("当前", WandColors.brand, WandColors.brandSoft)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    profile.baseUrl,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                ServerStateBadge(
                    label = authenticationLabel,
                    tint = if (profile.hasToken) WandColors.success else WandColors.textSecondary,
                    background = if (profile.hasToken) WandColors.successSoft else WandColors.surfaceSoft,
                )
            }
        }
        WandIconButton(
            icon = WandIcons.rename,
            contentDescription = "重命名服务器 ${profile.visibleName()}",
            onClick = onRename,
            enabled = enabled,
            variant = WandIconButtonVariant.Quiet,
            tint = WandColors.textSecondary,
        )
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (connecting) {
                if (reduceMotionEnabled()) {
                    Icon(
                        WandIcons.refresh,
                        contentDescription = null,
                        tint = WandColors.brand,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = WandColors.brand,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                WandIconButton(
                    icon = WandIcons.delete,
                    contentDescription = "移除服务器 ${profile.visibleName()}",
                    onClick = onRemove,
                    enabled = enabled,
                    variant = WandIconButtonVariant.Quiet,
                    tint = WandColors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun ServerStateBadge(label: String, tint: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun ServerProfile.visibleName(): String = displayName.ifBlank { baseUrl }
