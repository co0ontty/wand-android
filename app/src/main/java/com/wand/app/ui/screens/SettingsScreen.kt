package com.wand.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

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
            title = { Text("断开连接") },
            text = { Text("将清除已保存的服务器与连接码，返回连接页。") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    actions.disconnect()
                }) { Text("断开", color = WandColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        containerColor = WandColors.bgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = WandColors.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WandColors.bgPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // —— 服务器 ——
            SectionHeader("服务器")
            WandCard(modifier = Modifier.fillMaxWidth()) {
                InfoRow("地址", actions.serverUrl, mono = true)
                RowDivider()
                InfoRow("认证方式", if (actions.hasToken) "连接码" else "无密码")
                serverVersion?.let {
                    RowDivider()
                    InfoRow("服务端版本", "v$it", mono = true)
                }
                RowDivider()
                ActionRow("切换服务器", WandIcons.swapServer) { actions.switchServer() }
                RowDivider()
                ActionRow("断开连接", WandIcons.logout, danger = true) {
                    showDisconnectConfirm = true
                }
            }

            // —— 通知与反馈 ——
            SectionHeader("通知与反馈")
            WandCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("提示音", fontSize = 14.sp, color = WandColors.textPrimary)
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
                                onClick = {
                                    selectedSound = id
                                    actions.setNotificationSound(id)
                                    actions.previewSound(id)
                                },
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
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 14.dp),
                ) {
                    Text("音量", fontSize = 14.sp, color = WandColors.textPrimary)
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = {
                            actions.setNotificationVolume(volume.toInt())
                            actions.previewSound(selectedSound)
                        },
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
                SwitchRow("振动反馈", hapticEnabled) {
                    hapticEnabled = it
                    actions.setHapticEnabled(it)
                }
            }

            // —— 应用图标 ——
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
            Text(
                "切换后桌面图标会变化，部分启动器需要几秒生效。",
                fontSize = 11.sp,
                color = WandColors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )

            // —— 后台 ——
            SectionHeader("后台")
            WandCard(modifier = Modifier.fillMaxWidth()) {
                SwitchRow("后台保活（常驻通知）", keepAlive) { enabled ->
                    keepAlive = enabled
                    if (enabled && Build.VERSION.SDK_INT >= 33) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    actions.setKeepAlive(enabled)
                }
                Text(
                    "保持与服务器的连接，便于任务在后台继续推进。",
                    fontSize = 11.sp,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }

            // —— 语音输入 ——
            SectionHeader("语音输入")
            SttModelSection()

            // —— 更新 ——
            SectionHeader("更新")
            WandCard(modifier = Modifier.fillMaxWidth()) {
                SwitchRow("Beta 通道", betaChannel) {
                    betaChannel = it
                    actions.setBetaChannel(it)
                }
                Text(
                    "开启后接收最新开发构建（-debug 版本，可能不稳定）；关闭只提示正式版。",
                    fontSize = 11.sp,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                )
                RowDivider()
                ActionRow("检查更新", WandIcons.update) { actions.manualCheckUpdate() }
            }

            // —— 更多 ——
            SectionHeader("更多")
            WandCard(modifier = Modifier.fillMaxWidth()) {
                ActionRow("打开网页版（完整功能）", WandIcons.web) { actions.openWeb() }
                RowDivider()
                InfoRow("App 版本", "v${actions.appVersion}", mono = true)
            }

            Spacer(modifier = Modifier.size(32.dp))
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
    WandCard(modifier = Modifier.fillMaxWidth()) {
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
                            model.description,
                            fontSize = 11.sp,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(top = 2.dp),
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
        Text(
            "识别完全在本机离线运行。中英混合模型针对中英夹杂口述（含编程词汇热词增强），" +
                "内存占用较高，建议 6 GB 以上内存的设备使用。",
            fontSize = 11.sp,
            color = WandColors.textMuted,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
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
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 14.sp, color = WandColors.textPrimary)
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
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            fontSize = 15.sp,
            color = if (danger) WandColors.danger else WandColors.textPrimary,
            modifier = Modifier.padding(start = 12.dp),
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
    ) {
        Text(label, fontSize = 14.sp, color = WandColors.textPrimary)
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
