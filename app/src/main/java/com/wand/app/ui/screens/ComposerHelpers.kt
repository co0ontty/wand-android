package com.wand.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApi
import com.wand.app.data.WandApiException
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceInputHandle(
    val voice: VoiceInputController,
    val onMicDown: () -> Unit,
)

@Composable
internal fun rememberVoiceInputHandle(
    isHapticEnabled: () -> Boolean,
    onToast: (String) -> Unit,
    onCommit: (String) -> Unit,
): VoiceInputHandle {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentIsHapticEnabled = rememberUpdatedState(isHapticEnabled)
    val currentOnToast = rememberUpdatedState(onToast)
    val currentOnCommit = rememberUpdatedState(onCommit)
    val voice = remember(context) { VoiceInputController(context) }

    DisposableEffect(voice) {
        voice.onToast = { message -> currentOnToast.value(message) }
        onDispose {
            voice.onToast = null
            voice.destroy()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        currentOnToast.value(
            if (granted) "已获得麦克风权限，按住麦克风说话" else "需要麦克风权限才能语音输入",
        )
    }
    val onMicDown = remember(voice, micPermissionLauncher, haptic) {
        {
            if (voice.hasMicPermission()) {
                if (currentIsHapticEnabled.value()) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                voice.beginPress { text -> currentOnCommit.value(text) }
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    return remember(voice, onMicDown) { VoiceInputHandle(voice, onMicDown) }
}

internal data class AttachmentPickerActions(
    val pickPhoto: () -> Unit,
    val pickFile: () -> Unit,
)

@Composable
internal fun rememberAttachmentPickerActions(
    onUris: (List<Uri>) -> Unit,
): AttachmentPickerActions {
    val currentOnUris = rememberUpdatedState(onUris)
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> currentOnUris.value(uris.orEmpty()) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris -> currentOnUris.value(uris) }

    val pickPhoto = remember(photoPicker) {
        {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
    val pickFile = remember(attachmentPicker) {
        { attachmentPicker.launch(arrayOf("*/*")) }
    }

    return remember(pickPhoto, pickFile) { AttachmentPickerActions(pickPhoto, pickFile) }
}

internal fun CoroutineScope.launchAttachmentUpload(
    context: Context,
    api: WandApi,
    sessionId: String,
    uris: List<Uri>,
    onUploadingChange: (Boolean) -> Unit,
    onUploaded: (List<UploadedFile>) -> Unit,
    onToast: (String) -> Unit,
) {
    if (uris.isEmpty()) return
    onUploadingChange(true)
    launch {
        try {
            val files = withContext(Dispatchers.IO) {
                uris.take(5).map { uri -> readAttachment(context, uri) }
            }
            val uploaded = api.uploadAttachments(sessionId, files)
            onUploaded(uploaded)
            onToast("已上传 ${uploaded.size} 个附件")
        } catch (e: Exception) {
            onToast(e.message ?: "附件上传失败")
        } finally {
            onUploadingChange(false)
        }
    }
}

/** 从 content Uri 读出 (文件名, 字节)，供 multipart 上传。 */
internal fun readAttachment(context: Context, uri: Uri): Pair<String, ByteArray> {
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

/** 识别文本追加进草稿（不覆盖已有内容，对齐 Web commitVoiceTranscript / iOS appendTranscriptToDraft）。 */
internal fun appendVoiceText(existing: String, text: String): String {
    val clean = text.trim()
    if (clean.isEmpty()) return existing
    val base = existing.trimEnd()
    return if (base.isEmpty()) clean else "$base $clean"
}

internal fun buildAttachmentPrompt(attachments: List<UploadedFile>, body: String): String {
    if (attachments.isEmpty()) return body
    val paths = attachments.joinToString("\n") { it.savedPath }
    return "[附件已上传，请查看以下文件:\n$paths\n]\n\n$body"
}

@Composable
internal fun PendingAttachmentsPreview(
    attachments: List<UploadedFile>,
    baseUrl: String,
    onRemove: (UploadedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        attachments.forEach { file ->
            Box {
                if (baseUrl.isNotBlank() && WandImage.isImagePath(file.savedPath)) {
                    WandAsyncImage(
                        path = file.savedPath,
                        baseUrl = baseUrl,
                        modifier = Modifier.size(width = 96.dp, height = 72.dp),
                        maxWidth = 96,
                        maxHeight = 72,
                    )
                } else {
                    WandFileChip(
                        path = file.savedPath,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(WandColors.surface.copy(alpha = 0.92f))
                        .border(1.dp, WandColors.border, CircleShape)
                        .clickable { onRemove(file) },
                ) {
                    Icon(
                        WandIcons.close,
                        contentDescription = "移除附件",
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}
