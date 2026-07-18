package com.wand.app.ui.screens

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.wand.app.R
import com.wand.app.data.WandApi
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.ui.HomeConnectionInfo
import com.wand.app.ui.HomeNavigationActions
import com.wand.app.ui.HomeSettingsActions
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandChoiceStrip
import com.wand.app.ui.components.WandIcons
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wand.app.ui.theme.WandAppearanceMode
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.isWandDarkTheme

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
) {
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    var selectedSound by remember { mutableStateOf(settings.getNotificationSound()) }
    var volume by remember { mutableFloatStateOf(settings.getNotificationVolume().toFloat()) }
    var hapticEnabled by remember { mutableStateOf(settings.isHapticEnabled()) }
    var appIcon by remember { mutableStateOf(settings.getAppIcon()) }
    var keepAlive by remember { mutableStateOf(false) }
    var betaChannel by remember { mutableStateOf(settings.isBetaChannel()) }
    var appearanceMode by remember { mutableStateOf(settings.getAppearanceMode()) }
    val motionEnabled = rememberSettingsMotionEnabled()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授不授权都继续，前台服务无通知也能运行 */ }

    LaunchedEffect(Unit) {
        serverVersion = try {
            api.serverConfig().currentVersion
        } catch (_: Exception) {
            null
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            containerColor = WandColors.bgElevated,
            shape = RoundedCornerShape(20.dp),
            icon = {
                SettingsIconBadge(icon = WandIcons.logout, tint = WandColors.danger)
            },
            title = {
                Text(
                    "断开连接",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                )
            },
            text = {
                Text(
                    "清除本机保存的服务器地址和连接码，并返回连接页。",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = WandColors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    navigation.disconnect()
                }) {
                    Text(
                        "断开连接",
                        color = WandColors.danger,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text("取消", color = WandColors.textSecondary)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        SettingsSheetHeader(
            onBack = onBack,
        )
        SettingsContentLayout(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                title = "外观与反馈",
                description = "调整这台设备上的主题、声音和触感。",
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
                        selectedSound = selectedSound,
                        volume = volume,
                        hapticEnabled = hapticEnabled,
                        onSoundSelected = { id ->
                            selectedSound = id
                            settings.setNotificationSound(id)
                            settings.previewSound(id)
                        },
                        onVolumeChange = { volume = it },
                        onVolumeCommit = {
                            settings.setNotificationVolume(volume.toInt())
                            settings.previewSound(selectedSound)
                        },
                        onHapticChange = {
                            hapticEnabled = it
                            settings.setHapticEnabled(it)
                        },
                    )
                }
            }

            SettingsSection(
                title = "语音输入",
                description = "选择离线识别模型；缺少的模型会在选中后下载。",
            ) {
                SttModelSection()
            }

            SettingsSection(
                title = "连接",
                description = "查看当前服务器，或切换到其他 Wand 服务。",
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    ServerConnectionRow(
                        serverUrl = connection.serverUrl,
                        hasConnectionCode = connection.hasToken,
                    )
                    RowDivider()
                    ConnectionActionsRow(
                        onOpenWeb = navigation.openWeb,
                        onSwitchServer = navigation.switchServer,
                    )
                    RowDivider()
                    ActionRow(
                        label = "断开连接",
                        icon = WandIcons.logout,
                        danger = true,
                    ) {
                        showDisconnectConfirm = true
                    }
                }
            }

            SettingsSection(
                title = "应用与更新",
                description = "控制后台运行方式和更新通道。",
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow(
                        label = "后台保活",
                        checked = keepAlive,
                        icon = WandIcons.keepAlive,
                        iconTint = WandColors.success,
                    ) { enabled ->
                        keepAlive = enabled
                        if (enabled) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        settings.setKeepAlive(enabled)
                    }
                    RowDivider()
                    ActionRow(
                        label = "检查更新",
                        icon = WandIcons.update,
                        trailingText = "v${settings.appVersion}",
                        iconTint = WandColors.success,
                    ) { settings.manualCheckUpdate() }
                    RowDivider()
                    SwitchRow(
                        label = "Beta 通道",
                        checked = betaChannel,
                        icon = WandIcons.beta,
                        iconTint = WandColors.warning,
                    ) {
                        betaChannel = it
                        settings.setBetaChannel(it)
                    }
                }
            }

            SettingsSection(
                title = "应用图标",
                description = "选择显示在桌面上的 Wand 图标。",
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    AppIconPickerContent(
                        appIcon = appIcon,
                        motionEnabled = motionEnabled,
                        onSelect = { icon ->
                            appIcon = icon
                            settings.setAppIcon(icon)
                        },
                    )
                }
            }

            SettingsSection(
                title = "关于",
                description = "版本与运行环境信息。",
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsAboutContent(
                        appVersion = settings.appVersion,
                        serverVersion = serverVersion,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsContentLayout(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= 720.dp
        val horizontalPadding = if (wide) 24.dp else 16.dp
        val maxContentWidth = if (wide) 680.dp else 560.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxContentWidth)
                .align(Alignment.TopCenter)
                .padding(horizontal = horizontalPadding)
                .padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

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
        WandChoiceStrip(
            options = listOf(
                WandAppearanceMode.Light to "明亮",
                WandAppearanceMode.Dark to "黑暗",
                WandAppearanceMode.System to "跟随系统",
            ),
            selected = selected,
            onSelect = onSelected,
            minHeight = 44.dp,
            labelFontSize = 13.sp,
            activeTextColor = WandColors.textPrimary,
            flat = true,
        )
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

@Composable
private fun rememberSettingsMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
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
        SettingsChapterHeader(title = title, description = description)
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
            .padding(top = 14.dp, bottom = 5.dp, start = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
            letterSpacing = 0.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = WandColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotificationFeedbackContent(
    selectedSound: String,
    volume: Float,
    hapticEnabled: Boolean,
    onSoundSelected: (String) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeCommit: () -> Unit,
    onHapticChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingBlockHeader(
            title = "提示音",
            icon = WandIcons.notification,
            tint = WandColors.info,
        )
        // 与 NotificationHelper.SOUND_PRESETS 对齐。
        WandChoiceStrip(
            options = listOf(
                "chime" to "叮咚",
                "bubble" to "气泡",
                "meow" to "喵~",
                "bell" to "铃声",
            ),
            selected = selectedSound,
            onSelect = onSoundSelected,
            minHeight = 44.dp,
            labelFontSize = 13.sp,
            activeTextColor = WandColors.textPrimary,
            flat = true,
        )
    }
    RowDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowIcon(icon = WandIcons.volume, tint = WandColors.info)
        Text(
            "音量",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textPrimary,
            modifier = Modifier.padding(start = 11.dp),
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            onValueChangeFinished = onVolumeCommit,
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = WandColors.brand,
                activeTrackColor = WandColors.brand,
                inactiveTrackColor = WandColors.brandSoft,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            "${volume.toInt()}%",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = WandColors.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 38.dp),
        )
    }
    RowDivider()
    SwitchRow(
        label = "振动反馈",
        checked = hapticEnabled,
        icon = WandIcons.haptic,
        iconTint = WandColors.info,
        onChange = onHapticChange,
    )
}

@Composable
private fun SettingsSheetHeader(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsHeaderButton(
                icon = WandIcons.close,
                contentDescription = "关闭设置",
                onClick = onBack,
            )
            Text(
                "设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.size(44.dp))
        }
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
    }
}

@Composable
private fun SettingsHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = WandColors.textSecondary,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
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
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
            Text(
                buildString {
                    append("v$appVersion · Android ${Build.VERSION.RELEASE}")
                    serverVersion?.let { append(" · Server v$it") }
                },
                fontSize = 12.sp,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerConnectionRow(
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
                "服务器",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    serverUrl,
                    fontSize = 12.sp,
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
                            contentDescription = "已使用连接码连接",
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
private fun ConnectionActionsRow(
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactConnectionAction(
            label = "打开网页",
            icon = WandIcons.web,
            onClick = onOpenWeb,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(width = 0.5.dp, height = 24.dp)
                .background(WandColors.borderStrong.copy(alpha = 0.22f)),
        )
        CompactConnectionAction(
            label = "切换服务器",
            icon = WandIcons.swapServer,
            onClick = onSwitchServer,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompactConnectionAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = WandColors.info,
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

/**
 * 端侧语音识别模型选择卡：中文小模型 / 中英混合大模型。
 * 点选即切换；未下载的模型点选后立即开始下载（下载完成自动预热）。
 * 下载中的行内展示进度条；所选模型未就绪期间语音输入自动回退到已就绪模型。
 */
@Composable
private fun SttModelSection() {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf(SttModelManager.selectedModel(context).id) }
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
            if (index > 0) RowDivider()
            val ready = remember(sttState, downloadingId, model.id) {
                SttModelManager.isReady(context, model)
            }
            val downloading = downloadingId == model.id
            val isSelected = selectedId == model.id
            val status = when {
                downloading && sttState is SttModelManager.State.Downloading ->
                    "${sttState.percent}%"
                ready -> "已就绪"
                sttState is SttModelManager.State.Failed && selectedId == model.id ->
                    "重试"
                else -> model.sizeLabel
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isSelected) WandColors.brand.copy(alpha = 0.08f) else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                    ) {
                        selectedId = model.id
                        SttModelManager.setSelectedModel(context, model.id)
                        if (SttModelManager.isReady(context, model)) {
                            SherpaSpeechEngine.warmUp(context)
                        } else {
                            SttModelManager.startDownload(context, model)
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
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val dark = isWandDarkTheme()
    Column(
        modifier = modifier
            .clip(shape)
            .background(WandColors.surface)
            .border(
                0.75.dp,
                WandColors.borderStrong.copy(alpha = if (dark) 0.24f else 0.16f),
                shape,
            ),
        content = content,
    )
}

/** 卡片内行间分割线：0.5dp border 色，左右与行内边距对齐。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = WandColors.borderStrong.copy(alpha = 0.22f),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun SettingsIconBadge(
    icon: ImageVector,
    tint: Color = WandColors.brand,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.11f))
            .border(0.55.dp, tint.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SettingsRowIcon(
    icon: ImageVector,
    tint: Color = WandColors.textMuted,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.08f))
            .border(0.55.dp, tint.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** 操作行：左侧场景图标 + 标签 + 行尾右箭头，行高 ≥52dp，danger 时图标与标签同色。 */
@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    trailingText: String? = null,
    iconTint: Color = WandColors.textSecondary,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) WandColors.danger else iconTint
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 12.dp),
    ) {
        SettingsRowIcon(icon = icon, tint = tint)
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) WandColors.danger else WandColors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp),
        )
        if (trailingText != null) {
            Text(
                trailingText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textSecondary,
                modifier = Modifier.padding(end = 5.dp),
            )
        }
        Icon(
            WandIcons.chevronRight,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 开关行：标签 + 行尾 brand 色 Switch，行高 ≥52dp。 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    icon: ImageVector,
    iconTint: Color = WandColors.textMuted,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .heightIn(min = 54.dp)
            .padding(horizontal = 12.dp),
    ) {
        SettingsRowIcon(icon = icon, tint = iconTint)
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp, end = 10.dp),
        )
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
    }
}

@Composable
private fun AppIconPickerContent(
    appIcon: String,
    motionEnabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingBlockHeader(
            title = "应用图标",
            icon = WandIcons.appearance,
            tint = WandColors.brand,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppIconCard(
                name = "赛博虎妞",
                backgroundRes = R.drawable.ic_launcher_background,
                foregroundRes = R.drawable.ic_launcher_foreground,
                selected = appIcon == "shorthair",
                motionEnabled = motionEnabled,
                modifier = Modifier.weight(1f),
            ) { onSelect("shorthair") }
            AppIconCard(
                name = "勤劳初二",
                backgroundRes = R.drawable.ic_launcher_background_garfield,
                foregroundRes = R.drawable.ic_launcher_foreground_garfield,
                selected = appIcon == "garfield",
                motionEnabled = motionEnabled,
                modifier = Modifier.weight(1f),
            ) { onSelect("garfield") }
        }
    }
}

/**
 * 应用图标预览卡：launcher 前景/背景矢量按自适应图标安全区放大裁圆，
 * 模拟桌面实际效果；选中态只保留轻量边框和浅背景。
 */
@Composable
private fun AppIconCard(
    name: String,
    backgroundRes: Int,
    foregroundRes: Int,
    selected: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val dark = isWandDarkTheme()
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) WandColors.brand.copy(alpha = 0.09f) else WandColors.surfaceSoft.copy(alpha = 0.38f))
            .border(
                0.7.dp,
                if (selected) {
                    WandColors.brand.copy(alpha = 0.38f)
                } else {
                    WandColors.borderStrong.copy(alpha = if (dark) 0.24f else 0.16f)
                },
                shape,
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .heightIn(min = 58.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WandColors.surfaceSoft),
                contentAlignment = Alignment.Center,
            ) {
                // 自适应图标可视区约为画布中央 2/3，放大到 82dp 再裁 54dp 圆角方形，贴近桌面观感。
                Image(
                    painterResource(backgroundRes),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(66.dp),
                )
                Image(
                    painterResource(foregroundRes),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(66.dp),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn(
                        animationSpec = if (motionEnabled) tween(160, easing = WandMotion.easing) else snap(),
                    ) + scaleIn(
                        initialScale = 0.88f,
                        animationSpec = if (motionEnabled) tween(180, easing = WandMotion.easing) else snap(),
                    ),
                    exit = fadeOut(
                        animationSpec = if (motionEnabled) tween(90, easing = WandMotion.easing) else snap(),
                    ) + scaleOut(
                        targetScale = 0.92f,
                        animationSpec = if (motionEnabled) tween(90, easing = WandMotion.easing) else snap(),
                    ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(WandColors.brand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            WandIcons.check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(9.dp),
                        )
                    }
                }
            }
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
    }
}
