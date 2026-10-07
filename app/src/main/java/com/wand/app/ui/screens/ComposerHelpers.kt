package com.wand.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApi
import com.wand.app.data.WandApiException
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.appendComposerVoiceText
import com.wand.app.ui.attachmentPrompt
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.isWandDarkTheme
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class VoiceInputHandle(
    val voice: VoiceInputController,
    val onMicDown: () -> Unit,
)

@Composable
internal fun rememberVoiceInputHandle(
    isHapticEnabled: () -> Boolean,
    onToast: (String) -> Unit,
    onCommit: (String) -> Unit,
    sessionKey: Any? = null,
    onCommitForPress: (() -> (String) -> Unit)? = null,
): VoiceInputHandle {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentIsHapticEnabled = rememberUpdatedState(isHapticEnabled)
    val currentOnToast = rememberUpdatedState(onToast)
    val currentOnCommit = rememberUpdatedState(onCommit)
    val currentOnCommitForPress = rememberUpdatedState(onCommitForPress)
    val voice = remember(context, sessionKey) { VoiceInputController(context) }

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
                // Capture the destination when recording begins, before navigation can
                // replace rememberUpdatedState with another session's composer.
                val commitForPress = currentOnCommitForPress.value?.invoke() ?: currentOnCommit.value
                voice.beginPress(commitForPress)
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

private class AttachmentPickerRequest {
    var onResult: ((List<Uri>) -> Unit)? = null
    fun finish(uris: List<Uri>) {
        val destination = onResult
        onResult = null
        destination?.invoke(uris)
    }
}

@Composable
internal fun rememberAttachmentPickerActions(
    onUris: (List<Uri>) -> Unit,
): AttachmentPickerActions {
    val currentOnUris = rememberUpdatedState(onUris)
    val documentRequest = remember { AttachmentPickerRequest() }
    val photoRequest = remember { AttachmentPickerRequest() }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> documentRequest.finish(uris.orEmpty()) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris -> photoRequest.finish(uris) }

    val pickPhoto = remember(photoPicker) {
        {
            photoRequest.onResult = currentOnUris.value
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
    val pickFile = remember(attachmentPicker) {
        {
            documentRequest.onResult = currentOnUris.value
            attachmentPicker.launch(arrayOf("*/*"))
        }
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
            val uploaded = uploadComposerAttachments(context, api, sessionId, uris, 5)
            onUploaded(uploaded)
            onToast("已上传 ${uploaded.size} 个附件")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onToast(e.message ?: "附件上传失败")
        } finally {
            onUploadingChange(false)
        }
    }
}

internal suspend fun uploadComposerAttachments(
    context: Context,
    api: WandApi,
    sessionId: String,
    uris: List<Uri>,
    limit: Int,
): List<UploadedFile> {
    val files = withContext(Dispatchers.IO) {
        uris.take(limit).map { uri -> readAttachment(context, uri) }
    }
    return api.uploadAttachments(sessionId, files)
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
    val bytes = context.contentResolver.openInputStream(uri)?.use { readBoundedAttachment(it, name) }
        ?: throw WandApiException(null, "无法读取 $name")
    return name to bytes
}

internal fun readBoundedAttachment(input: InputStream, name: String): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > MAX_ATTACHMENT_BYTES) {
            throw WandApiException(413, "$name 超过 10MB 附件上限")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** 识别文本追加进草稿（不覆盖已有内容，对齐 Web commitVoiceTranscript / iOS appendTranscriptToDraft）。 */
internal fun appendVoiceText(existing: String, text: String): String {
    return appendComposerVoiceText(existing, text)
}

internal fun buildAttachmentPrompt(attachments: List<UploadedFile>, body: String): String {
    return attachmentPrompt(attachments, body)
}

private const val MAX_ATTACHMENT_BYTES = 10 * 1_024 * 1_024

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
                        modifier = Modifier
                            .size(width = 96.dp, height = 72.dp)
                            .clip(WandShapes.sm)
                            .border(0.8.dp, WandColors.border.copy(alpha = 0.6f), WandShapes.sm),
                        maxWidth = 96,
                        maxHeight = 72,
                    )
                } else {
                    WandFileChip(
                        path = file.savedPath,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                }
                // 触控区扩到 44dp（可见圆点仍为 22dp），避免缩略图右上角难点中。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .clickable(onClickLabel = "移除附件") { onRemove(file) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(WandColors.surface.copy(alpha = 0.92f))
                            .border(1.dp, WandColors.border, CircleShape),
                    ) {
                        Icon(
                            WandIcons.close,
                            contentDescription = null,
                            tint = WandColors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 判断输入栏是否需要平滑展开为多行工作台态。 */
internal fun shouldComposerExpand(
    isFocused: Boolean,
    voicePressed: Boolean,
    draftNeedsExpanded: Boolean,
    hasAttachments: Boolean,
): Boolean {
    return isFocused || voicePressed || draftNeedsExpanded || hasAttachments
}

/**
 * 将已选中的项优先置顶（若存在），其余项保持原有相对顺序。
 * 方便用户点开模型或模式选择面板时第一眼即可辨认当前生效的配置项。
 */
internal fun <T> prioritizeSelectedItem(
    items: List<T>,
    selectedId: String?,
    idSelector: (T) -> String?,
): List<T> {
    if (selectedId == null || items.size <= 1) return items
    val index = items.indexOfFirst { idSelector(it) == selectedId }
    if (index <= 0) return items
    return buildList(items.size) {
        add(items[index])
        items.forEachIndexed { i, item ->
            if (i != index) add(item)
        }
    }
}

/**
 * 书写区保持固定的文字宽度与清空触控槽，焦点变化只改变颜色。
 * 聊天使用外层统一描边；独立 PTY 输入保留自身表面。
 */
@Composable
internal fun ComposerInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    compact: Boolean = false,
    showClearAction: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (expanded) 6 else 1,
    maxHeight: Dp = composerInputMaxHeight(expanded),
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Send,
    ),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val motionEnabled = !reduceMotionEnabled()
    val dark = isWandDarkTheme()
    val shape = WandShapes.md

    val animatedBg by animateColorAsState(
        targetValue = if (isFocused) {
            WandColors.surface.copy(alpha = if (dark) 0.88f else 0.96f)
        } else {
            WandColors.surfaceSoft.copy(alpha = if (dark) 0.40f else 0.52f)
        },
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "composerFieldBg",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isFocused) {
            WandColors.focusRing
        } else {
            WandColors.border
        },
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "composerFieldBorder",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (framed) Modifier.background(animatedBg).border(1.dp, animatedBorderColor, shape) else Modifier)
            .padding(start = if (compact) 0.dp else 10.dp, end = if (compact) 0.dp else 2.dp, top = if (compact) 0.dp else 4.dp, bottom = if (compact) 0.dp else 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = if (expanded && maxLines > 1) Alignment.Top else Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ComposerActionTouchSize),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = WandColors.textPrimary,
                    ),
                    cursorBrush = SolidColor(WandColors.brand),
                    minLines = minLines,
                    maxLines = maxLines,
                    onTextLayout = onTextLayout,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = WandColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (minLines > 1) maxHeight else 22.dp, max = maxHeight)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) },
                )
            }
            // 始终预留触控槽，清空按钮显隐不改变换行或光标位置。
            if (showClearAction) Box(modifier = Modifier.size(ComposerActionTouchSize), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = value.isNotEmpty() && isFocused,
                    enter = fadeIn(WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast())),
                    exit = fadeOut(WandMotion.respectMotion(motionEnabled, WandMotion.tweenExit())),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(ComposerActionTouchSize)
                            .clip(CircleShape)
                            .clickable(
                                enabled = value.isNotEmpty() && isFocused,
                                role = Role.Button,
                                onClickLabel = "清空输入",
                            ) { onValueChange("") },
                    ) {
                        Icon(
                            WandIcons.close,
                            contentDescription = "清空输入",
                            tint = WandColors.textSecondary,
                            modifier = Modifier
                                .size(24.dp)
                                .background(WandColors.textSecondary.copy(alpha = 0.10f), CircleShape)
                                .padding(5.dp),
                        )
                    }
                }
            }
        }
    }
}
