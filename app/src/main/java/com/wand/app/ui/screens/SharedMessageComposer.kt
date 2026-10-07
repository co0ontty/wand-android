package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import com.wand.app.data.UploadedFile
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.theme.GlassBackdrop

/** The native chat input layout shared by a session and its AI Team group chat. */
@Composable
internal fun SharedMessageComposer(
    backdrop: GlassBackdrop?,
    sessionKey: Any? = null,
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
    resourcePanel: @Composable () -> Unit = {},
    resourcePanelOpen: Boolean = false,
    menuActionModifier: Modifier = Modifier,
    menuContent: @Composable () -> Unit = {},
    menuPanelModifier: Modifier = Modifier,
    onMenuDismissFocus: () -> Unit = {},
    fixedViewport: Boolean = false,
    inlineControls: Boolean = false,
) {
    val focusRequester = remember(sessionKey) { FocusRequester() }
    var refocusAfterSend by remember(sessionKey) { mutableStateOf(false) }
    var isFocused by remember(sessionKey) { mutableStateOf(false) }
    var draftNeedsExpanded by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(refocusAfterSend, allowRefocus) {
        if (refocusAfterSend && allowRefocus) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    val expanded = fixedViewport || shouldComposerExpand(
        isFocused = isFocused,
        voicePressed = voicePressed,
        draftNeedsExpanded = draftNeedsExpanded,
        hasAttachments = attachments.isNotEmpty(),
    )
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    val requestFocus: () -> Unit = { runCatching { focusRequester.requestFocus() } }
    ConversationLayerBackHandler(attachOpen) { onAttachOpenChange(false); onMenuDismissFocus() }
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
        inlineControls = inlineControls,
        leadingControl = {
            ComposerActionsMenu(backdrop = backdrop, uploading = uploading, allowDuringUpload = true,
                attachOpen = attachOpen || resourcePanelOpen, onAttachOpenChange = onAttachOpenChange, modifier = menuActionModifier)
        },
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
                    placeholder = "发送消息",
                    framed = false,
                    compact = inlineControls,
                    showClearAction = !inlineControls,
                    isFocused = isFocused,
                    onFocusChanged = { isFocused = it },
                    focusRequester = focusRequester,
                    expanded = expanded,
                    minLines = if (fixedViewport) 3 else 1,
                    maxLines = if (fixedViewport) 3 else if (expanded) 6 else 1,
                    maxHeight = if (fixedViewport) with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() * 3 } else composerInputMaxHeight(expanded),
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
        resourcePanel = resourcePanel,
        panelVisible = attachOpen,
        panelModifier = menuPanelModifier,
        panelContent = {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WandInlinePanelAction(
                        icon = WandIcons.image,
                        label = "从相册选择",
                        enabled = !uploading,
                        modifier = Modifier.weight(1f).heightIn(min = ComposerActionTouchSize),
                        onClick = {
                            onAttachOpenChange(false)
                            onPickPhoto()
                        },
                    )
                    WandInlinePanelAction(
                        icon = WandIcons.attach,
                        label = "从文件选择",
                        enabled = !uploading,
                        modifier = Modifier.weight(1f).heightIn(min = ComposerActionTouchSize),
                        onClick = {
                            onAttachOpenChange(false)
                            onPickFile()
                        },
                    )
                }
                menuContent()
            }
        },
        controls = {
            if (!inlineControls) ComposerActionsMenu(
                backdrop = backdrop,
                uploading = uploading,
                allowDuringUpload = true,
                attachOpen = attachOpen || resourcePanelOpen,
                onAttachOpenChange = onAttachOpenChange,
                modifier = menuActionModifier,
            )
            controls()
            trailingActions(requestFocus, sendAndRefocus)
        },
    )
}
