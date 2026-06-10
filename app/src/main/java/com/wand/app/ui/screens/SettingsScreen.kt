package com.wand.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.WandApi
import com.wand.app.ui.HomeActions

/**
 * 原生设置页 —— 对称 iOS SettingsView，并把原 WebView 桥（WandNative）的
 * Android 特有能力迁到原生：提示音/音量/振动、应用图标切换、后台保活、检查更新。
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
                }) { Text("断开", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // —— 服务器 ——
            SectionHeader("服务器")
            InfoRow("地址", actions.serverUrl, mono = true)
            InfoRow("认证方式", if (actions.hasToken) "连接码" else "无密码")
            serverVersion?.let { InfoRow("服务端版本", "v$it") }
            ActionRow("切换服务器") { actions.switchServer() }
            ActionRow("断开连接", tintDanger = true) { showDisconnectConfirm = true }

            SectionDivider()

            // —— 通知与反馈 ——
            SectionHeader("通知与反馈")
            Text(
                "提示音",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                // 与 NotificationHelper.SOUND_PRESETS 对齐。
                val presets = listOf("chime" to "叮咚", "bubble" to "气泡", "meow" to "喵~", "bell" to "铃声")
                presets.forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = selectedSound == id,
                        onClick = {
                            selectedSound = id
                            actions.setNotificationSound(id)
                            actions.previewSound(id)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", fontSize = 14.sp)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = {
                        actions.setNotificationVolume(volume.toInt())
                        actions.previewSound(selectedSound)
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Text(
                    "${volume.toInt()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            SwitchRow("振动反馈", hapticEnabled) {
                hapticEnabled = it
                actions.setHapticEnabled(it)
            }

            SectionDivider()

            // —— 应用图标 ——
            SectionHeader("应用图标")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val icons = listOf("shorthair" to "赛博虎妞", "garfield" to "勤劳初二")
                icons.forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = appIcon == id,
                        onClick = {
                            appIcon = id
                            actions.setAppIcon(id)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = icons.size),
                    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                }
            }
            Text(
                "切换后桌面图标会变化，部分启动器需要几秒生效。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // —— 后台 ——
            SectionHeader("后台")
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()

            // —— 更多 ——
            SectionHeader("更多")
            ActionRow("检查更新") { actions.manualCheckUpdate() }
            ActionRow("打开网页版（完整功能）") { actions.openWeb() }
            InfoRow("App 版本", "v${actions.appVersion}")

            Spacer(modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(label, fontSize = 14.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = if (mono) 12.sp else 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionRow(label: String, tintDanger: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (tintDanger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Spacer(modifier = Modifier.weight(1f))
        Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, fontSize = 14.sp)
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
