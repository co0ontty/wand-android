package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.wandCardSurface
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassSurface
import java.io.File
import kotlin.math.roundToInt

/** 更新接口提供的一个可安装版本；UI 不依赖 UpdateManager 的 Java 细节。 */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String,
    val fileName: String,
    val size: Long,
    val source: String,
    val releaseNotes: String,
    val channel: String,
)

/**
 * 更新是一个连续旅程而非若干 AlertDialog：检查 → 可用 → 下载 → 就绪/失败。
 * 只有宿主 Activity 触碰网络和安装权限，这个状态仅负责 Compose 呈现。
 */
sealed interface UpdatePresentation {
    data object Hidden : UpdatePresentation
    data object Checking : UpdatePresentation
    data class Available(val update: AppUpdateInfo) : UpdatePresentation
    data class Downloading(
        val update: AppUpdateInfo,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val bytesPerSecond: Long = 0,
    ) : UpdatePresentation
    data class Ready(val update: AppUpdateInfo, val apkFile: File) : UpdatePresentation
    data class Failed(val update: AppUpdateInfo, val message: String) : UpdatePresentation
    data class UpToDate(val message: String) : UpdatePresentation
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    presentation: UpdatePresentation,
    onDismiss: () -> Unit,
    onDownload: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onSkipVersion: (AppUpdateInfo) -> Unit,
) {
    if (presentation is UpdatePresentation.Hidden) return

    val downloading = presentation is UpdatePresentation.Downloading
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    WandBottomSheet(
        onDismissRequest = { if (!downloading) onDismiss() },
        sheetState = sheetState,
        gesturesEnabled = !downloading,
        showDragHandle = false,
        transparent = true,
    ) {
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                // 独立 Dialog window 无法采样主窗口内容；这里使用玻璃引擎的高对比降级配方，
                // 保留折射边缘、层次和阅读性，而不是伪造一层普通 Material 卡片。
                .glassSurface(
                    backdrop = null,
                    shape = shape,
                    style = WandGlass.regular.copy(
                        fallbackAlpha = 0.965f,
                        shadowElevation = 4.dp,
                    ),
                ),
        ) {
            AmbientBackground(Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UpdateSheetHandle()
                UpdateSheetHeader(
                    presentation = presentation,
                    onDismiss = if (downloading) null else onDismiss,
                )
                AnimatedContent(
                    targetState = presentation.contentKey(),
                    transitionSpec = {
                        (fadeIn(tween(WandMotion.normal)) togetherWith fadeOut(tween(WandMotion.fast)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "updateSheetContent",
                ) {
                    when (presentation) {
                        UpdatePresentation.Checking -> UpdateCheckingContent()
                        is UpdatePresentation.Available -> UpdateAvailableContent(
                            update = presentation.update,
                            onDownload = { onDownload(presentation.update) },
                            onDismiss = onDismiss,
                            onSkip = { onSkipVersion(presentation.update) },
                        )
                        is UpdatePresentation.Downloading -> UpdateDownloadingContent(
                            state = presentation,
                            onCancel = onCancelDownload,
                        )
                        is UpdatePresentation.Ready -> UpdateReadyContent(
                            state = presentation,
                            onInstall = { onInstall(presentation.apkFile) },
                            onDismiss = onDismiss,
                        )
                        is UpdatePresentation.Failed -> UpdateFailureContent(
                            state = presentation,
                            onRetry = { onDownload(presentation.update) },
                            onDismiss = onDismiss,
                        )
                        is UpdatePresentation.UpToDate -> UpdateUpToDateContent(
                            message = presentation.message,
                            onDismiss = onDismiss,
                        )
                        UpdatePresentation.Hidden -> Unit
                    }
                }
            }
        }
    }
}

private fun UpdatePresentation.contentKey(): String = when (this) {
    UpdatePresentation.Hidden -> "hidden"
    UpdatePresentation.Checking -> "checking"
    is UpdatePresentation.Available -> "available"
    is UpdatePresentation.Downloading -> "downloading"
    is UpdatePresentation.Ready -> "ready"
    is UpdatePresentation.Failed -> "failed"
    is UpdatePresentation.UpToDate -> "up-to-date"
}

@Composable
private fun UpdateSheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(WandColors.textMuted.copy(alpha = 0.30f)),
        )
    }
}

@Composable
private fun UpdateSheetHeader(
    presentation: UpdatePresentation,
    onDismiss: (() -> Unit)?,
) {
    val channel = presentation.updateOrNull()?.channel
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "软件更新",
                style = MaterialTheme.typography.titleLarge,
                color = WandColors.textPrimary,
            )
            Text(
                when (presentation) {
                    is UpdatePresentation.Downloading -> "请保持此页面打开以查看下载状态"
                    is UpdatePresentation.Ready -> "安装前已完成本地校验"
                    else -> "Wand Android"
                },
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
            )
        }
        if (channel != null) {
            Text(
                if (channel == "beta") "BETA" else "STABLE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                color = if (channel == "beta") WandColors.warning else WandColors.success,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        (if (channel == "beta") WandColors.warningSoft else WandColors.successSoft)
                            .copy(alpha = 0.78f),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (onDismiss != null) {
            Spacer(Modifier.width(4.dp))
            WandIconButton(
                icon = WandIcons.close,
                contentDescription = "关闭更新面板",
                onClick = onDismiss,
                variant = WandIconButtonVariant.Chrome,
            )
        }
    }
}

@Composable
private fun UpdateCheckingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp),
    ) {
        UpdateOrb(icon = WandIcons.update, tint = WandColors.info, active = true)
        Text("正在检查可用版本", style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
        Text(
            "正在连接当前更新通道。",
            style = MaterialTheme.typography.bodySmall,
            color = WandColors.textSecondary,
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            color = WandColors.info,
            trackColor = WandColors.infoSoft,
        )
    }
}

@Composable
private fun UpdateAvailableContent(
    update: AppUpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            UpdateOrb(icon = WandIcons.update, tint = WandColors.brand)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("新版本已准备好", style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
                Text(
                    update.fileName,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        VersionBridge(update)
        UpdateNotes(update.releaseNotes, update.size, update.source)
        WandButton(
            label = "下载更新",
            onClick = onDownload,
            icon = WandIcons.update,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WandButton(label = "以后再说", onClick = onDismiss, variant = WandButtonVariant.Text)
            WandButton(label = "跳过此版本", onClick = onSkip, variant = WandButtonVariant.Text)
        }
    }
}

@Composable
private fun UpdateDownloadingContent(
    state: UpdatePresentation.Downloading,
    onCancel: () -> Unit,
) {
    val hasTotal = state.totalBytes > 0
    val ratio = if (hasTotal) {
        (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progressLabel = if (hasTotal) "${(ratio * 100).roundToInt()}%" else "…"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            UpdateOrb(icon = WandIcons.update, tint = WandColors.brand, active = true)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("正在下载 v${state.update.latestVersion}", style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
                Text(
                    if (hasTotal) "下载完成后会提示你安装。" else "正在确定更新包大小。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textSecondary,
                )
            }
            Text(
                progressLabel,
                fontSize = 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.brand,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wandCardSurface(WandShapes.lg, rimTint = WandColors.brand)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hasTotal) {
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = WandColors.brand,
                    trackColor = WandColors.brandSoft,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = WandColors.brand,
                    trackColor = WandColors.brandSoft,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (hasTotal) "${formatByteSize(state.downloadedBytes)} / ${formatByteSize(state.totalBytes)}" else formatByteSize(state.downloadedBytes),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                )
                Text(
                    if (state.bytesPerSecond > 0) "${formatByteSize(state.bytesPerSecond)}/s" else "连接中",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                )
            }
        }
        WandButton(
            label = "取消下载",
            onClick = onCancel,
            variant = WandButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UpdateReadyContent(
    state: UpdatePresentation.Ready,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            UpdateOrb(icon = WandIcons.check, tint = WandColors.success)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("v${state.update.latestVersion} 已下载", style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
                Text("Android 将在下一步完成安装。", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
            }
        }
        VersionBridge(state.update)
        WandButton(
            label = "安装更新",
            onClick = onInstall,
            icon = WandIcons.check,
            variant = WandButtonVariant.Success,
            modifier = Modifier.fillMaxWidth(),
        )
        WandButton(
            label = "稍后安装",
            onClick = onDismiss,
            variant = WandButtonVariant.Text,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun UpdateFailureContent(
    state: UpdatePresentation.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            UpdateOrb(icon = WandIcons.error, tint = WandColors.danger)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("下载未完成", style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
                Text("更新包没有被保留。请检查网络后重试。", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
            }
        }
        Text(
            state.message,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = WandColors.danger,
            modifier = Modifier
                .fillMaxWidth()
                .wandCardSurface(WandShapes.md, tint = WandColors.dangerSoft)
                .padding(14.dp),
        )
        WandButton(
            label = "重新下载",
            onClick = onRetry,
            icon = WandIcons.refresh,
            modifier = Modifier.fillMaxWidth(),
        )
        WandButton(
            label = "关闭",
            onClick = onDismiss,
            variant = WandButtonVariant.Text,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun UpdateUpToDateContent(message: String, onDismiss: () -> Unit) {
    val isLatest = message.startsWith("已是最新")
    val isSkipped = message.contains("已被跳过")
    val title = when {
        isLatest -> "已经是最新版本"
        isSkipped -> "这个版本已跳过"
        else -> "更新检查未完成"
    }
    val tint = when {
        isLatest -> WandColors.success
        isSkipped -> WandColors.warning
        else -> WandColors.danger
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
    ) {
        UpdateOrb(icon = if (isLatest) WandIcons.check else WandIcons.error, tint = tint)
        Text(title, style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = WandColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        WandButton(label = "完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun VersionBridge(update: AppUpdateInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandCardSurface(WandShapes.lg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionToken(label = "当前", version = update.currentVersion, modifier = Modifier.weight(1f))
        Icon(
            WandIcons.chevronRight,
            contentDescription = "更新至",
            tint = WandColors.brand,
            modifier = Modifier.padding(horizontal = 9.dp).size(20.dp),
        )
        VersionToken(label = "新版本", version = update.latestVersion, accent = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VersionToken(label: String, version: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 11.sp, color = WandColors.textMuted)
        Text(
            "v$version",
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (accent) WandColors.brand else WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UpdateNotes(notes: String, size: Long, source: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wandCardSurface(WandShapes.lg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("更新说明", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = WandColors.textPrimary)
            val metadata = listOfNotNull(
                size.takeIf { it > 0 }?.let(::formatByteSize),
                source.takeIf { it == "github" }?.let { "GitHub" },
            ).joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Text(metadata, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = WandColors.textMuted)
            }
        }
        Text(
            notes.ifBlank { "此版本没有提供更新说明。" },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = WandColors.textSecondary,
        )
    }
}

@Composable
private fun UpdateOrb(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    active: Boolean = false,
) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(tint.copy(alpha = if (active) 0.16f else 0.12f))
            .border(0.8.dp, tint.copy(alpha = 0.28f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(25.dp))
    }
}

private fun UpdatePresentation.updateOrNull(): AppUpdateInfo? = when (this) {
    is UpdatePresentation.Available -> update
    is UpdatePresentation.Downloading -> update
    is UpdatePresentation.Ready -> update
    is UpdatePresentation.Failed -> update
    UpdatePresentation.Hidden, UpdatePresentation.Checking, is UpdatePresentation.UpToDate -> null
}

private fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}
