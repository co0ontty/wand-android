package com.wand.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.wand.app.ServerStore
import com.wand.app.WandDiagnostics
import com.wand.app.WandLog
import com.wand.app.WebSettingsActivity
import com.wand.app.data.WandApi
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SpeechNativeLibrary
import com.wand.app.speech.SttModelManager
import com.wand.app.ui.HomeConnectionInfo
import com.wand.app.ui.HomeNavigationActions
import com.wand.app.ui.HomeSettingsActions
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandListItem
import com.wand.app.ui.components.WandListItemIconSlot
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandSnackbarHost
import com.wand.app.ui.components.showWandNotice
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.FileProvider
import com.wand.app.ui.theme.WandAppearanceMode
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.rememberGlassBackdrop
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生设置页 —— 对称 iOS SettingsView，并把原 WebView 桥（WandNative）的
 * Android 特有能力迁到原生：提示音/音量/振动、应用图标切换、后台保活、检查更新。
 * 视觉对齐重设计规范 v1 第 3.4 节：区块卡片化 + 图标化 ActionRow + 图标预览卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: WandApi,
    connection: HomeConnectionInfo,
    navigation: HomeNavigationActions,
    settings: HomeSettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var showRemoveServerConfirm by remember { mutableStateOf(false) }

    var hapticEnabled by remember { mutableStateOf(settings.isHapticEnabled()) }
    var trafficTimelineMode by remember { mutableStateOf(settings.getTrafficTimelineMode()) }
    var keepAlive by remember { mutableStateOf(settings.isKeepAlive()) }
    var betaChannel by remember { mutableStateOf(settings.isBetaChannel()) }
    var appearanceMode by remember { mutableStateOf(settings.getAppearanceMode()) }
    var exportingLogs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val logScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授不授权都继续，前台服务无通知也能运行 */ }

    // 系统「另存为」：选完位置后再拼报表写进去。用户中途取消（uri == null）什么都不做。
    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        logScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val report = WandDiagnostics.buildReport(appContext)
                    WandDiagnostics.writeToUri(appContext, uri, report)
                }
            }
            result
                .onSuccess {
                    WandLog.i("ui", "用户另存运行日志到自选位置")
                    snackbarHostState.showWandNotice("已保存到所选位置")
                }
                .onFailure { error ->
                    snackbarHostState.showWandNotice(
                        "保存失败：${error.message ?: "未知错误"}",
                    )
                }
        }
    }

    LaunchedEffect(Unit) {
        serverVersion = try {
            api.serverConfig().currentVersion
        } catch (_: Exception) {
            null
        }
    }

    val glassBackdrop = rememberGlassBackdrop()
    val page = rememberSaveable(saver = SettingsNavigation.Saver) { SettingsNavigation() }
    val focusManager = LocalFocusManager.current
    fun open(destination: SettingsDestination) {
        focusManager.clearFocus(force = true)
        page.open(destination)
    }
    fun back() {
        focusManager.clearFocus(force = true)
        if (!page.back()) onBack()
    }
    BackHandler(enabled = page.current != SettingsDestination.Index) { back() }

    if (showRemoveServerConfirm) {
        WandDialog(
            title = "移除当前服务器？",
            onDismissRequest = { showRemoveServerConfirm = false },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "移除服务器",
                destructive = true,
                onClick = {
                    showRemoveServerConfirm = false
                    navigation.disconnect()
                },
            ),
            dismiss = WandDialogAction("取消", { showRemoveServerConfirm = false }),
        ) {
            Text(
                "仅从这台设备移除「${connection.serverDisplayName}」及其连接凭据，" +
                    "不会删除服务器上的会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { WandSnackbarHost(snackbarHostState) },
        topBar = {
            WandDetailTopBar(
                title = page.current.title,
                backdrop = glassBackdrop,
                leading = {
                    WandDetailBackButton(
                        onClick = ::back,
                        contentDescription = if (page.current == SettingsDestination.Index) "返回" else "返回设置",
                        icon = WandIcons.back,
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            page.visited.forEach { destination ->
                key(destination) {
                    RetainedSettingsPage(visible = page.current == destination) {
                        SettingsContentLayout(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(padding).imePadding(),
                        ) {
                            when (destination) {
                                SettingsDestination.Index -> SettingsDirectory(
                                    connection = connection,
                                    appearance = appearanceMode.settingsLabel(),
                                    appVersion = settings.appVersion,
                                    onOpen = ::open,
                                    onOpenWeb = {
                                        context.startActivity(Intent(context, WebSettingsActivity::class.java)
                                            .putExtra(WebSettingsActivity.EXTRA_SERVER_ID, connection.serverId))
                                    },
                                )
                                SettingsDestination.Appearance -> {
                                    SettingsSection(
                                        title = "",
                                    ) {
                                        SettingsCard(modifier = Modifier.fillMaxWidth()) {
                                            AppearanceModePicker(
                                                selected = appearanceMode,
                                                onSelected = { mode ->
                                                    appearanceMode = mode
                                                    settings.setAppearanceMode(mode)
                                                },
                                            )
                                            RowDivider()
                                            NotificationFeedbackContent(
                                                hapticEnabled = hapticEnabled,
                                                onHapticChange = {
                                                    hapticEnabled = it
                                                    settings.setHapticEnabled(it)
                                                },
                                            )
                                        }
                                    }
                                }
                                SettingsDestination.Traffic -> {
                                    SettingsSection(
                                        title = "",
                                        description = "选择当前工具活动自动展开的网络范围。历史详情始终可点击查看。",
                                    ) {
                                        SettingsCard(modifier = Modifier.fillMaxWidth()) {
                                            TrafficTimelineModePicker(
                                                selected = trafficTimelineMode,
                                                onSelected = {
                                                    trafficTimelineMode = it
                                                    settings.setTrafficTimelineMode(it)
                                                },
                                            )
                                        }
                                    }
                                }
                                SettingsDestination.Models -> {
                                    SettingsSection(
                                        title = "",
                                        description = "为每个工具配置多个分组，使用时直接选组；组内按首选、候选顺序排列。",
                                    ) {
                                        ModelGroupsSettingsPanel(api, initiallyExpanded = true)
                                    }
                                }
                                SettingsDestination.Voice -> {
                                    SpeechRecognitionSection(api) {
                                        context.startActivity(Intent(context, WebSettingsActivity::class.java)
                                            .putExtra(WebSettingsActivity.EXTRA_SERVER_ID, connection.serverId))
                                    }
                                }
                                SettingsDestination.Server -> {
                                    SettingsSection(
                                        title = "",
                                    ) {
                                        SettingsCard(modifier = Modifier.fillMaxWidth()) {
                                            ServerConnectionRow(
                                                displayName = connection.serverDisplayName,
                                                serverUrl = connection.serverUrl,
                                                hasConnectionCode = connection.hasToken,
                                            )
                                            RowDivider()
                                            ConnectionActionsRow(
                                                onSwitchServer = navigation.manageServers,
                                            )
                                            RowDivider()
                                            ActionRow(
                                                label = "移除当前服务器",
                                                icon = WandIcons.delete,
                                                danger = true,
                                            ) {
                                                showRemoveServerConfirm = true
                                            }
                                        }
                                    }
                                }
                                SettingsDestination.Updates -> {
                                    SettingsSection(
                                        title = "",
                                    ) {
                                        UpdateControlDeck(
                                            appVersion = settings.appVersion,
                                            betaChannel = betaChannel,
                                            keepAlive = keepAlive,
                                            onCheckUpdate = settings.manualCheckUpdate,
                                            onKeepAliveChange = { enabled ->
                                                keepAlive = enabled
                                                if (enabled) {
                                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                                settings.setKeepAlive(enabled)
                                            },
                                            onBetaChannelChange = {
                                                betaChannel = it
                                                settings.setBetaChannel(it)
                                            },
                                        )
                                    }
                                }
                                SettingsDestination.Diagnostics -> {
                                    SettingsSection(
                                        title = "",
                                        description = "导出运行与崩溃日志。",
                                    ) {
                                        SettingsCard(modifier = Modifier.fillMaxWidth()) {
                                            ActionRow(
                                                label = "保存到「下载」",
                                                busyLabel = "正在导出…",
                                                busy = exportingLogs,
                                                enabled = !exportingLogs,
                                                icon = WandIcons.download,
                                                iconTint = WandColors.info,
                                                supportingText = remember(exportingLogs) { logSizeLabel() },
                                                onClick = {
                                                    if (exportingLogs) return@ActionRow
                                                    exportingLogs = true
                                                    logScope.launch {
                                                        WandLog.i("ui", "用户保存运行日志到下载目录")
                                                        val result = runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                WandDiagnostics.saveToDownloads(appContext)
                                                            }
                                                        }
                                                        exportingLogs = false
                                                        result
                                                            .onSuccess { location ->
                                                                snackbarHostState.showWandNotice("已保存到 $location")
                                                            }
                                                            .onFailure { error ->
                                                                snackbarHostState.showWandNotice(
                                                                    "保存失败：${error.message ?: "未知错误"}",
                                                                )
                                                            }
                                                    }
                                                },
                                            )
                                            RowDivider()
                                            ActionRow(
                                                label = "另存到其他位置…",
                                                busyLabel = "正在导出…",
                                                busy = exportingLogs,
                                                enabled = !exportingLogs,
                                                icon = WandIcons.folder,
                                                iconTint = WandColors.textSecondary,
                                                onClick = {
                                                    if (exportingLogs) return@ActionRow
                                                    // 只在用户选完位置后再拼报表：拼一次 1MB 级文本，不必为没选中的弹窗付代价。
                                                    saveLogLauncher.launch(WandDiagnostics.exportFileName())
                                                },
                                            )
                                            RowDivider()
                                            ActionRow(
                                                label = "分享日志文件",
                                                enabled = !exportingLogs,
                                                icon = WandIcons.share,
                                                iconTint = WandColors.textSecondary,
                                                onClick = {
                                                    if (exportingLogs) return@ActionRow
                                                    exportingLogs = true
                                                    logScope.launch {
                                                        val result = runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                WandDiagnostics.exportToFile(appContext)
                                                            }
                                                        }
                                                        exportingLogs = false
                                                        result
                                                            .onSuccess { file ->
                                                                // 先弹系统分享面板：showWandNotice 会挂起到气泡消失，
                                                                // 放在前面会让面板延迟几秒才出现。
                                                                shareLogFile(appContext, file)
                                                            }
                                                            .onFailure { error ->
                                                                snackbarHostState.showWandNotice(
                                                                    "导出失败：${error.message ?: "未知错误"}",
                                                                )
                                                            }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                SettingsDestination.About -> {
                                    SettingsSection(
                                        title = "",
                                    ) {
                                        SettingsCard(modifier = Modifier.fillMaxWidth()) {
                                            SettingsAboutContent(
                                                appVersion = settings.appVersion,
                                                serverVersion = serverVersion,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsContentLayout(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val horizontalPadding = if (wide) 24.dp else 16.dp
    val maxContentWidth = if (wide) 680.dp else 560.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** Stable destinations preserve each visited editor and scroll position for this settings visit. */
internal enum class SettingsDestination(val title: String) {
    Index("设置"), Appearance("外观与反馈"), Traffic("流量与实时内容"),
    Voice("语音输入"), Models("模型分组"), Server("服务器"),
    Updates("应用与更新"), Diagnostics("诊断"), About("关于");
}

internal class SettingsNavigation(initial: SettingsDestination = SettingsDestination.Index) {
    var current by mutableStateOf(initial)
        private set
    val visited = mutableStateListOf(SettingsDestination.Index).apply {
        if (initial != SettingsDestination.Index) add(initial)
    }
    fun open(destination: SettingsDestination) {
        if (destination !in visited) visited.add(destination)
        current = destination
    }
    fun back(): Boolean {
        if (current == SettingsDestination.Index) return false
        current = SettingsDestination.Index
        return true
    }
    companion object {
        val Saver = listSaver<SettingsNavigation, String>(
            save = { listOf(it.current.name) + it.visited.map(SettingsDestination::name) },
            restore = { values -> SettingsNavigation(SettingsDestination.entries.firstOrNull { it.name == values.firstOrNull() }
                ?: SettingsDestination.Index).apply {
                visited.clear()
                visited.add(SettingsDestination.Index)
                values.drop(1).mapNotNull { name -> SettingsDestination.entries.firstOrNull { it.name == name } }
                    .forEach { if (it !in visited) visited.add(it) }
                if (current !in visited) visited.add(current)
            } },
        )
    }
}

/** Hidden pages retain draft owners but have no measured surface, hit target or accessibility node. */
@Composable
private fun RetainedSettingsPage(visible: Boolean, content: @Composable () -> Unit) {
    Layout(content = content, modifier = if (visible) Modifier.fillMaxSize() else Modifier.clearAndSetSemantics {}) { measurables, constraints ->
        if (!visible) layout(0, 0) {} else {
            val children = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) { children.forEach { it.placeRelative(0, 0) } }
        }
    }
}

@Composable
private fun SettingsDirectory(
    connection: HomeConnectionInfo,
    appearance: String,
    appVersion: String,
    onOpen: (SettingsDestination) -> Unit,
    onOpenWeb: () -> Unit,
) {
    SettingsCard {
        ActionRow(connection.serverDisplayName.ifBlank { "当前服务器" }, WandIcons.server,
            supportingText = connection.serverUrl.takeIf { it != connection.serverDisplayName }, inlineValue = false) { onOpen(SettingsDestination.Server) }
    }
    SettingsSection("此设备") {
        SettingsCard {
            ActionRow("外观与反馈", WandIcons.settings, appearance) { onOpen(SettingsDestination.Appearance) }
            RowDivider()
            ActionRow("流量与实时内容", WandIcons.web) { onOpen(SettingsDestination.Traffic) }
            RowDivider()
            ActionRow("语音输入", WandIcons.mic) { onOpen(SettingsDestination.Voice) }
            RowDivider()
            ActionRow("应用与更新", WandIcons.update, "v$appVersion") { onOpen(SettingsDestination.Updates) }
        }
    }
    SettingsSection("服务配置") {
        SettingsCard {
            ActionRow("模型分组", WandIcons.tune) { onOpen(SettingsDestination.Models) }
            RowDivider()
            ActionRow("完整 Web 设置", WandIcons.web, onClick = onOpenWeb)
        }
    }
    SettingsSection("帮助与关于") {
        SettingsCard {
            ActionRow("诊断", WandIcons.download) { onOpen(SettingsDestination.Diagnostics) }
            RowDivider()
            ActionRow("关于 Wand", WandIcons.question) { onOpen(SettingsDestination.About) }
        }
    }
}

private fun WandAppearanceMode.settingsLabel(): String = when (this) {
    WandAppearanceMode.Light -> "浅色外观"
    WandAppearanceMode.Dark -> "深色外观"
    WandAppearanceMode.System -> "跟随系统"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceModePicker(
    selected: WandAppearanceMode,
    onSelected: (WandAppearanceMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingBlockHeader(
            title = "界面主题",
            icon = WandIcons.appearance,
            tint = WandColors.brand,
        )
        val options = listOf(
            WandAppearanceMode.Light to "明亮",
            WandAppearanceMode.Dark to "黑暗",
            WandAppearanceMode.System to "跟随系统",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WandColors.selectedFill,
                        activeContentColor = WandColors.brand,
                        activeBorderColor = WandColors.brand,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = WandColors.textSecondary,
                        inactiveBorderColor = WandColors.border,
                    ),
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrafficTimelineModePicker(
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingBlockHeader(
            title = "实时内容范围",
            supportingText = "关闭：所有网络显示；仅 Wi-Fi：移动网络隐藏实时输出。",
            icon = WandIcons.usage,
            tint = WandColors.info,
        )
        val options = listOf(
            ServerStore.TRAFFIC_TIMELINE_MODE_ALL to "关闭",
            ServerStore.TRAFFIC_TIMELINE_MODE_WIFI to "仅 Wi-Fi",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WandColors.selectedFill,
                        activeContentColor = WandColors.brand,
                        activeBorderColor = WandColors.brand,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = WandColors.textSecondary,
                        inactiveBorderColor = WandColors.border,
                    ),
                ) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}
@Composable
private fun SettingBlockHeader(
    title: String,
    supportingText: String? = null,
    icon: ImageVector,
    tint: Color = WandColors.textMuted,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SettingsRowIcon(icon = icon, tint = tint)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = WandColors.textSecondary,
                )
            }
        }
    }
}

private fun logSizeLabel(): String {
    val bytes = WandLog.fileBytes()
    return when {
        bytes <= 0L -> "暂无"
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    }
}

/** 用系统分享面板把导出的日志文件发出去（FileProvider 授权 + 显式 ClipData）。 */
private fun shareLogFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Wand Android 运行日志")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 部分接收方（尤其国内 IM）只看 clipData 里的 URI，必须显式带上。
        send.clipData = android.content.ClipData.newUri(context.contentResolver, "wand-log", uri)
        context.startActivity(
            Intent.createChooser(send, "导出运行日志")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        WandLog.e("ui", "分享日志文件失败", e)
        android.widget.Toast.makeText(
            context,
            "无法打开分享面板，日志已保存在 ${file.name}",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title.isNotBlank() || !description.isNullOrBlank()) SettingsChapterHeader(title = title, description = description)
        content()
    }
}

@Composable
private fun SettingsChapterHeader(
    title: String,
    description: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (title.isNotBlank()) Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = WandColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotificationFeedbackContent(
    hapticEnabled: Boolean,
    onHapticChange: (Boolean) -> Unit,
) {
    SwitchRow(
        label = "振动反馈",
        checked = hapticEnabled,
        icon = WandIcons.haptic,
        iconTint = WandColors.info,
        onChange = onHapticChange,
    )
}

/** 更新与后台行为共用连续设置行，不占据额外的宣传区。 */
@Composable
private fun UpdateControlDeck(
    appVersion: String,
    betaChannel: Boolean,
    keepAlive: Boolean,
    onCheckUpdate: () -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onBetaChannelChange: (Boolean) -> Unit,
) {
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        ActionRow("检查更新", WandIcons.update, "v$appVersion", onClick = onCheckUpdate)
        RowDivider()
        SwitchRow(
            label = "后台保活", checked = keepAlive, icon = WandIcons.keepAlive,
            description = "在通知栏保留服务，减少后台断连。", onChange = onKeepAliveChange,
        )
        RowDivider()
        SwitchRow(
            label = "Beta 通道", checked = betaChannel, icon = WandIcons.beta,
            description = "接收测试版更新。", onChange = onBetaChannelChange,
        )
    }
}

@Composable
private fun SettingsAboutContent(
    appVersion: String,
    serverVersion: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WandBrandMark(size = 38)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "Wand",
                style = MaterialTheme.typography.titleMedium,
                color = WandColors.textPrimary,
            )
            Text(
                buildString {
                    append("v$appVersion · Android ${Build.VERSION.RELEASE}")
                    serverVersion?.let { append(" · Server v$it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerConnectionRow(
    displayName: String,
    serverUrl: String,
    hasConnectionCode: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowIcon(icon = WandIcons.server, tint = WandColors.info)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasConnectionCode) {
                    Box(
                        modifier = Modifier
                            .padding(start = 7.dp)
                            .size(22.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(WandColors.successSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            WandIcons.connectionCode,
                            contentDescription = "当前服务器已认证",
                            tint = WandColors.success,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionActionsRow(onSwitchServer: () -> Unit) {
    ActionRow("管理服务器", WandIcons.swapServer, onClick = onSwitchServer)
}

/**
 * 端侧语音识别模型选择卡：中文小模型 / 中英混合大模型。
 * 点选即切换；未下载的模型点选后立即开始下载（下载完成自动预热）。
 * 下载中的行内展示进度条；所选模型未就绪期间语音输入自动回退到已就绪模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechRecognitionSection(api: WandApi, onOpenWeb: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(com.wand.app.speech.SpeechPreferences.mode(context)) }
    var status by remember(api) { mutableStateOf<com.wand.app.data.ServerSpeechStatus?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    suspend fun refresh() {
        checking = true; error = null
        try { status = api.speechStatus() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法读取服务端语音状态" }
        finally { checking = false }
    }
    LaunchedEffect(api, mode) { if (mode == com.wand.app.speech.SpeechMode.SERVER) refresh() }
    SettingsSection(title = "识别方式", description = "选择只影响此设备，不会自动切换到第三方云识别。") {
        SettingsCard {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                val modes = listOf(com.wand.app.speech.SpeechMode.SERVER to "服务端识别", com.wand.app.speech.SpeechMode.LOCAL to "客户端本地识别")
                modes.forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = mode == value, onClick = { mode = value; com.wand.app.speech.SpeechPreferences.select(context, value) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size)) { Text(label) }
                }
            }
        }
    }
    if (mode == com.wand.app.speech.SpeechMode.SERVER) {
        SettingsSection(title = "当前服务器", description = "松手后把音频发送到当前 Wand 服务器，离线转写；最长 60 秒。") {
            SettingsCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error ?: if (checking) "检查中…" else status?.reason ?: if (status?.ready == true) "已就绪 · Whisper ${status?.model} · ${status?.backend?.uppercase()}" else "尚未读取状态",
                        color = if (error != null || status?.ready == false) WandColors.danger else WandColors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("服务端支持 CPU、Mac Metal 与可选 CUDA。模型由管理员在服务器下载，手机不需要安装本地引擎。", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                    androidx.compose.material3.TextButton(enabled = !checking, onClick = { scope.launch { refresh() } }) { Text("刷新状态") }
                }
                RowDivider()
                ActionRow("管理服务端语音模型", WandIcons.web, onClick = onOpenWeb)
            }
        }
    } else {
        SettingsSection(title = "客户端本地模型", description = "优先使用已下载的 sherpa 模型，其次系统端侧模型；不上传音频。") { SttModelSection() }
    }
}

@Composable
private fun SttModelSection() {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf(SttModelManager.selectedModel(context).id) }
    var pendingDownload by remember { mutableStateOf<SttModelManager.SttModel?>(null) }
    // 触发各行就绪状态重算的信号：下载状态变化时 +1。
    val sttState = SttModelManager.state
    val downloadingId = SttModelManager.downloadingModelId
    LaunchedEffect(Unit) { SttModelManager.refresh(context) }
    // 下载完成立刻预热，让「下载完→按住即用」无加载等待。
    LaunchedEffect(sttState) {
        if (sttState is SttModelManager.State.Ready) SherpaSpeechEngine.warmUp(context)
    }
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        SttModelManager.MODELS.forEachIndexed { index, model ->
            if (index > 0) RowDivider(leadingIcon = false)
            val ready = remember(sttState, downloadingId, model.id) {
                SttModelManager.isReady(context, model)
            }
            val downloading = downloadingId == model.id
            val isSelected = selectedId == model.id
            // 选中底跟其他侧栏行一样走淡入淡出，切换模型时不再整块硬闪。
            val rowFill by animateColorAsState(
                targetValue = if (isSelected) WandColors.brand.copy(alpha = 0.08f) else Color.Transparent,
                animationSpec = WandMotion.respectMotion(
                    !reduceMotionEnabled(),
                    WandMotion.tweenFast(),
                ),
                label = "sttModelRowFill",
            )
            val status = when {
                downloading && sttState is SttModelManager.State.Downloading ->
                    "${sttState.percent}%"
                ready -> "已就绪"
                sttState is SttModelManager.State.Failed && selectedId == model.id ->
                    "重试"
                SttModelManager.isModelDownloaded(context, model) -> "需下载引擎"
                else -> model.sizeLabel
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowFill)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                    ) {
                        selectedId = model.id
                        SttModelManager.setSelectedModel(context, model.id)
                        if (SttModelManager.isReady(context, model)) {
                            SherpaSpeechEngine.warmUp(context)
                        } else {
                            pendingDownload = model
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                model.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WandColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                status,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = when {
                                    downloading -> WandColors.brand
                                    sttState is SttModelManager.State.Failed && selectedId == model.id -> WandColors.danger
                                    ready -> WandColors.success
                                    else -> WandColors.textSecondary
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(
                            model.description,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = WandColors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = WandColors.brand,
                            unselectedColor = WandColors.textMuted,
                        ),
                    )
                }
                if (downloading && sttState is SttModelManager.State.Downloading) {
                    LinearProgressIndicator(
                        progress = { sttState.percent / 100f },
                        color = WandColors.brand,
                        trackColor = WandColors.surfaceSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
    pendingDownload?.let { model ->
        WandDialog(
            title = "启用本地语音识别？",
            onDismissRequest = { pendingDownload = null },
            icon = WandIcons.update,
            confirm = WandDialogAction(
                label = "开始下载",
                onClick = {
                    pendingDownload = null
                    SttModelManager.startDownload(context, model)
                },
            ),
            dismiss = WandDialogAction("取消", { pendingDownload = null }),
        ) {
            Text(
                (if (!SpeechNativeLibrary.isInstalled(context)) "将从官方 GitHub 下载语音引擎（约 38 MB）；" else "") +
                    (if (!SttModelManager.isModelDownloaded(context, model)) "下载「${model.label}」（${model.sizeLabel}）。" else "模型已在本机。") +
                    "建议在 Wi‑Fi 下进行，启用后识别完全在本机离线运行。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WandCard(
        modifier = modifier,
        shape = WandShapes.md,
        content = content,
    )
}

/** 分隔线从行文字起点开始，不切过左侧图标；无图标的模型行用正文边距。 */
@Composable
private fun RowDivider(leadingIcon: Boolean = true) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = WandColors.borderStrong.copy(alpha = 0.22f),
        modifier = Modifier.padding(start = if (leadingIcon) 56.dp else 14.dp, end = 12.dp),
    )
}

@Composable
private fun SettingsRowIcon(
    icon: ImageVector,
    tint: Color = WandColors.textMuted,
) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Standard action row; reserve both labels without owning an export/result lifecycle. */
@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    supportingText: String? = null,
    inlineValue: Boolean = true,
    iconTint: Color = WandColors.textSecondary,
    danger: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String? = null,
    onClick: () -> Unit,
) {
    val tint = if (danger) WandColors.danger else iconTint
    WandListItem(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (busy) stateDescription = "正在导出"
                else if (!enabled) stateDescription = "不可操作"
            },
        headlineColor = when {
            !enabled -> WandColors.textMuted
            danger -> WandColors.dangerText
            else -> WandColors.textPrimary
        },
        headlineContent = {
            Box {
                Text(
                    label,
                    modifier = if (busy) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
                )
                busyLabel?.let {
                    Text(
                        it,
                        modifier = if (busy) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
                    )
                }
            }
        },
        supportingColor = WandColors.textMuted,
        supportingContent = supportingText?.takeUnless { inlineValue }?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            SettingsRowIcon(icon = icon, tint = if (enabled) tint else WandColors.textMuted)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inlineValue && supportingText != null) Text(supportingText,
                    style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 144.dp))
                WandListItemIconSlot(WandIcons.chevronRight)
            }
        },
    )
}

/** The row owns the only toggle action; the same controlled Switch remains decorative. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    icon: ImageVector,
    iconTint: Color = WandColors.textMuted,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    WandListItem(
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it) } },
        leadingContent = { SettingsRowIcon(icon = icon, tint = iconTint) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = WandColors.brand,
                    uncheckedThumbColor = WandColors.textMuted,
                    uncheckedTrackColor = WandColors.surfaceSoft,
                ),
            )
        },
    )
}
