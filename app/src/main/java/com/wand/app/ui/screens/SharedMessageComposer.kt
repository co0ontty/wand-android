package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.UploadedFile
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors

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
    expandedControls: @Composable RowScope.(controlsCompact: Boolean) -> Unit,
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
    val expanded = isFocused || voicePressed || draftNeedsExpanded || attachments.isNotEmpty()
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    val requestFocus: () -> Unit = { runCatching { focusRequester.requestFocus() } }
    val sendAndRefocus: () -> Unit = {
        if (canSubmit) {
            onSend()
            refocusAfterSend = true
        }
    }
    val plusMenu: @Composable RowScope.() -> Unit = {
        ComposerActionsMenu(
            backdrop = backdrop,
            uploading = uploading,
            attachOpen = attachOpen,
            onAttachOpenChange = onAttachOpenChange,
        )
    }

    NativeComposerSurface(
        backdrop = backdrop,
        expanded = expanded,
        collapsedLeading = { plusMenu() },
        inputContent = {
            Column(modifier = Modifier.weight(1f).heightIn(min = 34.dp)) {
                if (expanded && attachments.isNotEmpty()) {
                    PendingAttachmentsPreview(
                        attachments = attachments,
                        baseUrl = baseUrl,
                        onRemove = onRemoveAttachment,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    )
                }
                Box(contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            color = WandColors.textPrimary,
                        ),
                        cursorBrush = SolidColor(WandColors.brand),
                        minLines = 1,
                        maxLines = if (expanded) 6 else 1,
                        onTextLayout = { layout ->
                            draftNeedsExpanded = draft.isNotEmpty() &&
                                (layout.lineCount > 1 || layout.hasVisualOverflow)
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { sendAndRefocus() }),
                        decorationBox = { innerTextField ->
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp,
                                ),
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        "输入消息",
                                        fontSize = 16.sp,
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
                            .heightIn(min = 34.dp, max = composerInputMaxHeight(expanded))
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused },
                    )
                }
            }
        },
        collapsedTrailing = { trailingActions(requestFocus, sendAndRefocus) },
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
        expandedControls = { controlsCompact ->
            expandedControls(controlsCompact)
            trailingActions(requestFocus, sendAndRefocus)
        },
    )
}
