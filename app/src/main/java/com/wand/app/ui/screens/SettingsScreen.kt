package com.wand.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.wand.app.R
import com.wand.app.data.WandApi
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.ui.HomeActions
import com.wand.app.ui.components.SectionHeader
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandChromeIconButton
import com.wand.app.ui.components.WandIcons
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandAppearanceMode
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop

/**
 * 原生设置页 —— 对称 iOS SettingsView，并把原 WebView 桥（WandNative）的
 * Android 特有能力迁到原生：提示音/音量/振动、应用图标切换、后台保活、检查更新。
 * 视觉对齐重设计规范 v1 第 3.4 节：区块卡片化 + 图标化 ActionRow + 图标预览卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: WandApi,
    actions: HomeActions,
    onBack: () -> Unit,
) {
    var serverVersion by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    var selectedSound by remember { mutableStateOf(actions.getNotificationSound()) }
    var volume by remember { mutableFloatStateOf(actions.getNotificationVolume().toFloat()) }
    var hapticEnabled by remember { mutableStateOf(actions.isHapticEnabled()) }
    var appIcon by remember { mutableStateOf(actions.getAppIcon()) }
    var keepAlive by remember { mutableStateOf(false) }
    var betaChannel by remember { mutableStateOf(actions.isBetaChannel()) }
    var appearanceMode by remember { mutableStateOf(actions.getAppearanceMode()) }

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
                    actions.disconnect()
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

    // 液态玻璃：设置页内容从玻璃顶栏下滚过。
    val glassBackdrop = rememberGlassBackdrop()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SettingsTopBar(
                backdrop = glassBackdrop,
                onBack = onBack,
                onOpenWeb = actions.openWeb,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // padding 放在 verticalScroll 之后：顶部留白随内容滚动，
                    // 内容从玻璃顶栏下面滑过。
                    .padding(padding)
                    .padding(horizontal = 14.dp),
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // —— 高频偏好 ——
                SectionHeader("外观调整")
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    AppearanceModePicker(
                        selected = appearanceMode,
                        onSelected = { mode ->
                            appearanceMode = mode
                            actions.setAppearanceMode(mode)
                        },
                    )
                }

                // —— 通知与反馈 ——
                SectionHeader("通知与反馈")
                NotificationFeedbackSection(
                    selectedSound = selectedSound,
                    volume = volume,
                    hapticEnabled = hapticEnabled,
                    onSoundSelected = { id ->
                        selectedSound = id
                        actions.setNotificationSound(id)
                        actions.previewSound(id)
                    },
                    onVolumeChange = { volume = it },
                    onVolumeCommit = {
                        actions.setNotificationVolume(volume.toInt())
                        actions.previewSound(selectedSound)
                    },
                    onHapticChange = {
                        hapticEnabled = it
                        actions.setHapticEnabled(it)
                    },
                )

                // —— 语音输入 ——
                SectionHeader("语音输入")
                SttModelSection()

                // —— 后台 ——
                SectionHeader("后台运行")
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow("后台保活", keepAlive, WandIcons.refresh) { enabled ->
                        keepAlive = enabled
                        if (enabled && Build.VERSION.SDK_INT >= 33) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        actions.setKeepAlive(enabled)
                    }
                }

                // —— 更新 ——
                SectionHeader("更新")
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    ActionRow("检查更新", WandIcons.update) { actions.manualCheckUpdate() }
                    RowDivider()
                    SwitchRow("Beta 通道", betaChannel, WandIcons.update) {
                        betaChannel = it
                        actions.setBetaChannel(it)
                    }
                }

                // —— 连接 ——
                SectionHeader("连接与服务器")
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    InfoRow("服务器地址", actions.serverUrl, icon = WandIcons.web, mono = true)
                    RowDivider()
                    InfoRow("连接码", if (actions.hasToken) "已绑定" else "未绑定", icon = WandIcons.permission)
                    RowDivider()
                    ActionRow("打开网页版", WandIcons.web) { actions.openWeb() }
                    RowDivider()
                    ActionRow("切换服务器", WandIcons.swapServer) { actions.switchServer() }
                    RowDivider()
                    ActionRow("断开连接", WandIcons.logout, danger = true) {
                        showDisconnectConfirm = true
                    }
                }

                // —— 低频个性化 ——
                SectionHeader("应用图标")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIconCard(
                        name = "赛博虎妞",
                        backgroundRes = R.drawable.ic_launcher_background,
                        foregroundRes = R.drawable.ic_launcher_foreground,
                        selected = appIcon == "shorthair",
                        modifier = Modifier.weight(1f),
                    ) {
                        appIcon = "shorthair"
                        actions.setAppIcon("shorthair")
                    }
                    AppIconCard(
                        name = "勤劳初二",
                        backgroundRes = R.drawable.ic_launcher_background_garfield,
                        foregroundRes = R.drawable.ic_launcher_foreground_garfield,
                        selected = appIcon == "garfield",
                        modifier = Modifier.weight(1f),
                    ) {
                        appIcon = "garfield"
                        actions.setAppIcon("garfield")
                    }
                }

                // —— 关于 ——
                SectionHeader("关于")
                SettingsAboutSection(
                    appVersion = actions.appVersion,
                    serverVersion = serverVersion,
                )

                Spacer(modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun AppearanceModePicker(
    selected: WandAppearanceMode,
    onSelected: (WandAppearanceMode) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                WandAppearanceMode.Light to "明亮",
                WandAppearanceMode.Dark to "黑暗",
                WandAppearanceMode.System to "跟随系统",
            )
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WandColors.brandSoft,
                        activeContentColor = WandColors.brand,
                        activeBorderColor = WandColors.brand,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = WandColors.textSecondary,
                        inactiveBorderColor = WandColors.border,
                    ),
                ) { Text(label, fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun NotificationFeedbackSection(
    selectedSound: String,
    volume: Float,
    hapticEnabled: Boolean,
    onSoundSelected: (String) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeCommit: () -> Unit,
    onHapticChange: (Boolean) -> Unit,
) {
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "提示音",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                // 与 NotificationHelper.SOUND_PRESETS 对齐。
                val presets = listOf(
                    "chime" to "叮咚",
                    "bubble" to "气泡",
                    "meow" to "喵~",
                    "bell" to "铃声",
                )
                presets.forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = selectedSound == id,
                        onClick = { onSoundSelected(id) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = presets.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = WandColors.brandSoft,
                            activeContentColor = WandColors.brand,
                            activeBorderColor = WandColors.brand,
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = WandColors.textSecondary,
                            inactiveBorderColor = WandColors.border,
                        ),
                    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
        RowDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                "音量",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
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
                    .padding(start = 12.dp),
            )
            Text(
                "${volume.toInt()}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        RowDivider()
        SwitchRow("振动反馈", hapticEnabled, WandIcons.settings, onHapticChange)
    }
}

@Composable
private fun SettingsTopBar(
    backdrop: GlassBackdrop,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop,
                RoundedCornerShape(0.dp),
                WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp),
                edgeToEdge = true,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WandChromeIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "设置",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                )
            }
            WandChromeIconButton(
                icon = WandIcons.web,
                contentDescription = "打开网页版",
                tint = WandColors.brand,
                onClick = onOpenWeb,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
    }
}

@Composable
private fun SettingsAboutSection(
    appVersion: String,
    serverVersion: String?,
) {
    SettingsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    "远程 CLI 控制台",
                    fontSize = 12.sp,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RowDivider()
        InfoRow("App 版本", "v$appVersion", icon = WandIcons.settings)
        serverVersion?.let {
            RowDivider()
            InfoRow("Server 版本", "v$it", icon = WandIcons.update)
        }
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
            val status = when {
                downloading && sttState is SttModelManager.State.Downloading ->
                    "下载中 ${sttState.percent}%"
                ready -> "已就绪 · 离线运行"
                sttState is SttModelManager.State.Failed && selectedId == model.id ->
                    "下载失败，点击重试"
                else -> "未下载 · ${model.sizeLabel}"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedId = model.id
                        SttModelManager.setSelectedModel(context, model.id)
                        if (SttModelManager.isReady(context, model)) {
                            SherpaSpeechEngine.warmUp(context)
                        } else {
                            SttModelManager.startDownload(context, model)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            model.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = WandColors.textPrimary,
                        )
                        Text(
                            status,
                            fontSize = 11.sp,
                            color = when {
                                downloading -> WandColors.brand
                                status.startsWith("下载失败") -> WandColors.danger
                                else -> WandColors.textMuted
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (selectedId == model.id) {
                        Icon(
                            WandIcons.check,
                            contentDescription = "使用中",
                            tint = WandColors.brand,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
    WandCard(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        containerColor = WandColors.surface.copy(alpha = 0.92f),
        content = content,
    )
}

/** 卡片内行间分割线：0.5dp border 色，左右与行内边距对齐。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = WandColors.border,
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

/** 信息行：标签 + 右对齐值（textSecondary，可选 mono）。 */
@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector? = null, mono: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            SettingsRowIcon(icon = icon)
            Spacer(modifier = Modifier.size(12.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = WandColors.textPrimary)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = if (mono) 12.sp else 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
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
            .border(1.dp, tint.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
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
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
}

/** 操作行：左侧场景图标 + 标签 + 行尾右箭头，行高 ≥52dp，danger 时图标与标签同色。 */
@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) WandColors.danger else WandColors.brand
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
    ) {
        SettingsRowIcon(icon = icon, tint = tint)
        Text(
            label,
            fontSize = 14.sp,
            color = if (danger) WandColors.danger else WandColors.textPrimary,
            modifier = Modifier.padding(start = 14.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            WandIcons.chevronRight,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 开关行：标签 + 行尾 brand 色 Switch，行高 ≥52dp。 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    icon: ImageVector,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp),
    ) {
        SettingsRowIcon(icon = icon)
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textPrimary,
            modifier = Modifier.padding(start = 14.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = WandColors.brand,
            ),
        )
    }
}

/**
 * 应用图标预览卡：launcher 前景/背景矢量按自适应图标安全区放大裁圆，
 * 模拟桌面实际效果；选中态走 WandCard(selected=true) 的 mode-card 规范。
 */
@Composable
private fun AppIconCard(
    name: String,
    backgroundRes: Int,
    foregroundRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    WandCard(
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(WandColors.surfaceSoft),
                contentAlignment = Alignment.Center,
            ) {
                // 自适应图标可视区约为画布中央 2/3，放大到 72dp 再裁 48dp 圆，贴近桌面观感。
                Image(
                    painterResource(backgroundRes),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(72.dp),
                )
                Image(
                    painterResource(foregroundRes),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(72.dp),
                )
            }
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) WandColors.brand else WandColors.textPrimary,
            )
        }
    }
}
