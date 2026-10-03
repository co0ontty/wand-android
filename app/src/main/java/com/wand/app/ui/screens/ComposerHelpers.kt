package com.wand.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
 * 高质感输入槽（Field Capsule）：
 * - 紧凑外形配合微圆角（WandShapes.md 14dp）；
 * - 聚焦时背景提亮、边框过渡为品牌色微光聚焦环（Focus Ring）；
 * - 占位符与输入文字垂直居中严丝合缝；
 * - 输入内容且聚焦时右上角浮现微型快速清空按钮，单手一键重置草稿；
 * - 展开态与折叠态平滑适应，高度上限内自然滚动。
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
            WandColors.brand.copy(alpha = 0.72f)
        } else {
            WandColors.border.copy(alpha = if (dark) 0.45f else 0.58f)
        },
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "composerFieldBorder",
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (isFocused) 1.2.dp else 0.8.dp,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "composerFieldBorderWidth",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(animatedBg)
            .border(animatedBorderWidth, animatedBorderColor, shape)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = if (expanded && maxLines > 1) Alignment.Top else Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 22.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = WandColors.textPrimary,
                    ),
                    cursorBrush = SolidColor(WandColors.brand),
                    minLines = 1,
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
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = WandColors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 22.dp, max = maxHeight)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) },
                )
            }
            if (value.isNotEmpty() && isFocused) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(WandColors.textSecondary.copy(alpha = 0.12f))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "清空输入",
                        ) {
                            onValueChange("")
                        },
                ) {
                    Icon(
                        WandIcons.close,
                        contentDescription = "清空",
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}
