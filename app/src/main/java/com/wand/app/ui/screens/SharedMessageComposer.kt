package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.wand.app.data.UploadedFile
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.theme.GlassBackdrop

/** The native chat input layout shared by a session and its AI Team group chat. */
@Composable
internal fun SharedMessageComposer(
    backdrop: GlassBackdrop?,
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<UploadedFile>,
    baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit,
    uploading: Boolean,
    attachOpen: Boolean,
    onAttachOpenChange: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    canSubmit: Boolean,
    onSend: () -> Unit,
    allowRefocus: Boolean,
    voicePressed: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    trailingActions: @Composable RowScope.(requestFocus: () -> Unit, sendAndRefocus: () -> Unit) -> Unit,
    controls: @Composable RowScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var refocusAfterSend by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var draftNeedsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(refocusAfterSend, allowRefocus) {
        if (refocusAfterSend && allowRefocus) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    val expanded = shouldComposerExpand(
        isFocused = isFocused,
        voicePressed = voicePressed,
        draftNeedsExpanded = draftNeedsExpanded,
        hasAttachments = attachments.isNotEmpty(),
    )
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    val requestFocus: () -> Unit = { runCatching { focusRequester.requestFocus() } }
    BackHandler(enabled = attachOpen) { onAttachOpenChange(false) }
    LaunchedEffect(voicePressed) {
        if (voicePressed) onAttachOpenChange(false)
    }
    val sendAndRefocus: () -> Unit = {
        if (canSubmit) {
            onAttachOpenChange(false)
            onSend()
            refocusAfterSend = true
        }
    }
    NativeComposerSurface(
        backdrop = backdrop,
        focused = isFocused,
        inputContent = {
            Column(modifier = Modifier.weight(1f).heightIn(min = 34.dp)) {
                if (expanded && attachments.isNotEmpty()) {
                    PendingAttachmentsPreview(
                        attachments = attachments,
                        baseUrl = baseUrl,
                        onRemove = onRemoveAttachment,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
                    )
                }
                ComposerInputField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = "输入消息",
                    isFocused = isFocused,
                    onFocusChanged = { isFocused = it },
                    focusRequester = focusRequester,
                    expanded = expanded,
                    maxLines = if (expanded) 6 else 1,
                    maxHeight = composerInputMaxHeight(expanded),
                    onTextLayout = { layout ->
                        draftNeedsExpanded = draft.isNotEmpty() &&
                            (layout.lineCount > 1 || layout.hasVisualOverflow)
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendAndRefocus() }),
                )
            }
        },
        panelVisible = attachOpen,
        panelContent = {
            WandInlinePanelAction(
                icon = WandIcons.image,
                label = "从相册选择",
                onClick = {
                    onAttachOpenChange(false)
                    onPickPhoto()
                },
            )
            WandInlinePanelAction(
                icon = WandIcons.attach,
                label = "从文件选择",
                onClick = {
                    onAttachOpenChange(false)
                    onPickFile()
                },
            )
        },
        controls = {
            ComposerActionsMenu(
                backdrop = backdrop,
                uploading = uploading,
                attachOpen = attachOpen,
                onAttachOpenChange = onAttachOpenChange,
            )
            controls()
            trailingActions(requestFocus, sendAndRefocus)
        },
    )
}
