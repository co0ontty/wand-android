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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.wand.app.R
import com.wand.app.data.WandApi
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.ui.HomeActions
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
    actions: HomeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            SettingsMotionSection(
                title = "常用",
                index = 0,
                motionEnabled = motionEnabled,
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    AppearanceModePicker(
                        selected = appearanceMode,
                        onSelected = { mode ->
                            appearanceMode = mode
                            actions.setAppearanceMode(mode)
                        },
                    )
                }
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
                SttModelSection()
            }

            SettingsMotionSection(
                title = "连接",
                index = 1,
                motionEnabled = motionEnabled,
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    InfoRow("当前服务器", actions.serverUrl, icon = WandIcons.web, mono = true)
                    RowDivider()
                    InfoRow(
                        "连接状态",
                        if (actions.hasToken) "连接码已绑定" else "未绑定连接码",
                        icon = WandIcons.permission,
                    )
                    RowDivider()
                    ActionRow("打开网页版", WandIcons.web) { actions.openWeb() }
                    RowDivider()
                    ActionRow("切换服务器", WandIcons.swapServer) { actions.switchServer() }
                }
            }

            SettingsMotionSection(
                title = "运行与更新",
                index = 2,
                motionEnabled = motionEnabled,
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    SwitchRow("后台保活", keepAlive, WandIcons.refresh) { enabled ->
                        keepAlive = enabled
                        if (enabled && Build.VERSION.SDK_INT >= 33) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        actions.setKeepAlive(enabled)
                    }
                    RowDivider()
                    ActionRow("检查更新", WandIcons.update) { actions.manualCheckUpdate() }
                    RowDivider()
                    SwitchRow("Beta 通道", betaChannel, WandIcons.update) {
                        betaChannel = it
                        actions.setBetaChannel(it)
                    }
                }
            }

            SettingsMotionSection(
                title = "个性化",
                index = 3,
                motionEnabled = motionEnabled,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppIconCard(
                        name = "赛博虎妞",
                        backgroundRes = R.drawable.ic_launcher_background,
                        foregroundRes = R.drawable.ic_launcher_foreground,
                        selected = appIcon == "shorthair",
                        motionEnabled = motionEnabled,
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
                        motionEnabled = motionEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        appIcon = "garfield"
                        actions.setAppIcon("garfield")
                    }
                }
            }

            SettingsMotionSection(
                title = "关于",
                index = 4,
                motionEnabled = motionEnabled,
            ) {
                SettingsAboutSection(
                    appVersion = actions.appVersion,
                    serverVersion = serverVersion,
                )
            }

            SettingsMotionSection(
                title = "危险操作",
                index = 5,
                motionEnabled = motionEnabled,
            ) {
                SettingsCard(modifier = Modifier.fillMaxWidth()) {
                    ActionRow("断开连接", WandIcons.logout, danger = true) {
                        showDisconnectConfirm = true
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
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
        val horizontalPadding = if (wide) 22.dp else 14.dp
        val maxContentWidth = if (wide) 720.dp else 560.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxContentWidth)
                .align(Alignment.TopCenter)
                .padding(horizontal = horizontalPadding)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsRowIcon(icon = WandIcons.settings)
            Text(
                "界面主题",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        val options = listOf(
            WandAppearanceMode.Light to "明亮",
            WandAppearanceMode.Dark to "黑暗",
            WandAppearanceMode.System to "跟随系统",
        )
        WandChoiceStrip(
            options = options,
            selected = selected,
            onSelect = onSelected,
            minHeight = 34.dp,
            labelFontSize = 12.sp,
        )
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
private fun SettingsMotionSection(
    title: String,
    index: Int,
    motionEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsChapterHeader(title = title)
        content()
    }
}

@Composable
private fun SettingsChapterHeader(
    title: String,
) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = WandColors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp, start = 2.dp),
    )
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
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "提示音",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
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
                minHeight = 34.dp,
                labelFontSize = 12.sp,
            )
        }
        RowDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
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
                    .padding(start = 10.dp),
            )
            Text(
                "${volume.toInt()}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        RowDivider()
        SwitchRow("振动反馈", hapticEnabled, WandIcons.settings, onHapticChange)
    }
}

@Composable
private fun SettingsSheetHeader(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(WandColors.textMuted.copy(alpha = 0.48f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
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
            .clickable(onClick = onClick),
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WandBrandMark(size = 34)
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
        RowDivider()
        InfoRow(
            "Android",
            "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}",
            icon = WandIcons.permission,
        )
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
            val isSelected = selectedId == model.id
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
            .background(WandColors.surface.copy(alpha = 0.98f))
            .border(0.5.dp, WandColors.border.copy(alpha = if (dark) 0.16f else 0.08f), shape),
        content = content,
    )
}

/** 卡片内行间分割线：0.5dp border 色，左右与行内边距对齐。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = WandColors.border,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** 信息行：标签 + 右对齐值（textSecondary，可选 mono）。 */
@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector? = null, mono: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        if (icon != null) {
            SettingsRowIcon(icon = icon)
            Spacer(modifier = Modifier.size(10.dp))
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
            modifier = Modifier.padding(start = 12.dp),
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
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.08f))
            .border(0.55.dp, tint.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** 操作行：左侧场景图标 + 标签 + 行尾右箭头，行高 ≥52dp，danger 时图标与标签同色。 */
@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) WandColors.danger else WandColors.textSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
    ) {
        SettingsRowIcon(icon = icon, tint = tint)
        Text(
            label,
            fontSize = 14.sp,
            color = if (danger) WandColors.danger else WandColors.textPrimary,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
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
            .heightIn(min = 50.dp)
            .padding(horizontal = 12.dp),
    ) {
        SettingsRowIcon(icon = icon)
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textPrimary,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = WandColors.brand,
            ),
        )
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
    val shape = RoundedCornerShape(14.dp)
    val dark = isWandDarkTheme()
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) WandColors.brandSoft else WandColors.surface.copy(alpha = 0.98f))
            .border(
                0.55.dp,
                if (selected) WandColors.brand.copy(alpha = 0.32f) else WandColors.border.copy(alpha = if (dark) 0.16f else 0.08f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(WandColors.surfaceSoft),
                contentAlignment = Alignment.Center,
            ) {
                // 自适应图标可视区约为画布中央 2/3，放大到 82dp 再裁 54dp 圆角方形，贴近桌面观感。
                Image(
                    painterResource(backgroundRes),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(82.dp),
                )
                Image(
                    painterResource(foregroundRes),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(82.dp),
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
                        .padding(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WandColors.brand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            WandIcons.check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) WandColors.brand else WandColors.textPrimary,
            )
    }
}
