package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import com.wand.app.ui.SendPhase
import com.wand.app.data.UploadedFile
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.theme.GlassBackdrop

internal data class CompanionComposerControls(
    val editing: Boolean, val hasText: Boolean, val hasAttachments: Boolean,
    val canSubmit: Boolean, val deliveryPhase: SendPhase,
    val onEdit: () -> Unit, val onSend: () -> Unit,
)
internal typealias MinimalCompanion = @Composable (CompanionComposerControls, @Composable () -> Unit) -> Unit

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
    voiceCommitSignal: Int = 0,
    voiceProcessing: Boolean = false,
    /** Owned selectors live above the conditional editor and temporarily borrow its focus. */
    editorOverlayOpen: Boolean = false,
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
    compactAfterAcknowledgement: Boolean = false,
    deliveryPhase: SendPhase = SendPhase.Idle,
    minimalCompanion: MinimalCompanion? = null,
    presentation: ChatInputPresentation? = null,
    capsuleMicrophone: @Composable () -> Unit = {},
    /** Fixed exterior slot, independent of the editor's visibility and send feedback. */
    exteriorAction: (@Composable (send: () -> Unit) -> Unit)? = null,
) {
    if (presentation != null) {
        FocusCapsuleComposer(presentation, sessionKey, draft, onDraftChange, attachments, baseUrl,
            onRemoveAttachment, uploading, attachOpen, onAttachOpenChange, onPickPhoto, onPickFile,
            canSubmit, onSend, deliveryPhase, voicePressed, voiceProcessing, voiceCommitSignal,
            editorOverlayOpen, resourcePanelOpen, onExpandedChange, trailingActions, controls,
            resourcePanel, menuContent, capsuleMicrophone, exteriorAction)
        return
    }
    val focusRequester = remember(sessionKey) { FocusRequester() }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var refocusAfterSend by remember(sessionKey) { mutableStateOf(false) }
    var isFocused by remember(sessionKey) { mutableStateOf(false) }
    var draftNeedsExpanded by remember(sessionKey) { mutableStateOf(false) }
    var acknowledged by remember(sessionKey) { mutableStateOf(false) }
    var recoveryExpanded by remember(sessionKey) { mutableStateOf(false) }
    var collapsePending by remember(sessionKey) { mutableStateOf(false) }
    var minimal by remember(sessionKey) { mutableStateOf(MinimalComposerState()) }
    var focusRequest by remember(sessionKey) { mutableStateOf(0) }
    var handledFocusRequest by remember(sessionKey) { mutableStateOf(0) }
    var restoreEditorFocus by remember(sessionKey) { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(deliveryPhase, draft, attachments.size) {
        if (deliveryPhase == SendPhase.Failed) { recoveryExpanded = true; collapsePending = false; minimal = minimal.failed() }
        if (deliveryPhase == SendPhase.Sent) {
            minimal = minimal.accepted(draft.isNotEmpty() || attachments.isNotEmpty())
            acknowledged = true
            recoveryExpanded = false
            if (compactAfterAcknowledgement && collapsePending && draft.isEmpty() && attachments.isEmpty()) {
                collapsePending = false
                focusManager.clearFocus()
                keyboard?.hide()
            }
            collapsePending = false
        }
    }
    val compactVisual = inlineControls || compactAfterAcknowledgement && acknowledged && !recoveryExpanded &&
        !isFocused && !keyboardVisible && draft.isEmpty() && attachments.isEmpty() && !uploading &&
        !voicePressed && !attachOpen && !resourcePanelOpen && !editorOverlayOpen && deliveryPhase != SendPhase.Sending
    LaunchedEffect(refocusAfterSend, allowRefocus) {
        if (refocusAfterSend && allowRefocus) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    val expanded = fixedViewport || editorOverlayOpen || shouldComposerExpand(
        isFocused = isFocused,
        voicePressed = voicePressed,
        draftNeedsExpanded = draftNeedsExpanded,
        hasAttachments = attachments.isNotEmpty(),
    )
    val editorVisible = !voicePressed && !voiceProcessing && (minimal.editing || minimal.recovery || attachOpen || resourcePanelOpen || uploading)
    val reportExpanded = if (minimalCompanion == null) expanded else editorVisible && expanded
    LaunchedEffect(reportExpanded) { onExpandedChange(reportExpanded) }
    val focusEditor: () -> Unit = {
        focusRequest++
        if (minimalCompanion == null) runCatching { focusRequester.requestFocus() }
    }
    val requestFocus: () -> Unit = {
        minimal = minimal.edit()
        focusEditor()
    }
    LaunchedEffect(editorOverlayOpen, windowFocused) {
        if (editorOverlayOpen && (isFocused || minimal.editing)) restoreEditorFocus = true
        if (!editorOverlayOpen && windowFocused && restoreEditorFocus) {
            restoreEditorFocus = false
            // Focus returning is not a new edit: preserve a pending ACK, and never reopen
            // an empty editor that was accepted while its selector was still open.
            if (minimalCompanion == null || minimal.editing || minimal.recovery) focusEditor()
        }
    }
    LaunchedEffect(focusRequest, editorVisible, windowFocused, editorOverlayOpen) {
        if (minimalCompanion != null && editorVisible && windowFocused && !editorOverlayOpen &&
            focusRequest > handledFocusRequest) {
            if (runCatching { focusRequester.requestFocus() }.getOrDefault(false)) {
                handledFocusRequest = focusRequest
                keyboard?.show()
            }
        }
    }
    val interactionSuspended = editorOverlayOpen || attachOpen || resourcePanelOpen || uploading ||
        voicePressed || voiceProcessing || !windowFocused
    LaunchedEffect(keyboardVisible, interactionSuspended) {
        if (minimalCompanion != null) {
            val wasEditing = minimal.editing
            minimal = minimal.observeKeyboard(keyboardVisible, interactionSuspended)
            if (wasEditing && !minimal.editing) focusManager.clearFocus()
        }
    }
    LaunchedEffect(voiceCommitSignal) {
        if (voiceCommitSignal > 0 && minimalCompanion != null) requestFocus()
    }
    ConversationLayerBackHandler(attachOpen) { onAttachOpenChange(false); onMenuDismissFocus() }
    LaunchedEffect(voicePressed) {
        if (voicePressed) onAttachOpenChange(false)
    }
    val sendAndRefocus: () -> Unit = {
        if (canSubmit) {
            onAttachOpenChange(false)
            if (minimalCompanion != null) minimal = minimal.submit()
            if (compactAfterAcknowledgement) collapsePending = true else refocusAfterSend = true
            onSend()
        }
    }
    val editor: @Composable () -> Unit = {
    NativeComposerSurface(
        backdrop = backdrop,
        focused = isFocused,
        inlineControls = if (minimalCompanion != null) false else compactVisual,
        inputTrailing = {
            // Text-free capsule has no third action. Uploaded files still have an explicit route.
            if (minimalCompanion != null && draft.isBlank() && attachments.isNotEmpty())
                TextButton(onClick = sendAndRefocus, enabled = canSubmit) { Text("发送附件") }
        },
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
                    onValueChange = { collapsePending = false; minimal = minimal.edit(); onDraftChange(it) },
                    placeholder = if (compactVisual) "继续输入" else "发送消息",
                    framed = false,
                    compact = compactVisual,
                    showClearAction = !compactVisual,
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
            if (!compactVisual) ComposerActionsMenu(
                backdrop = backdrop,
                uploading = uploading,
                allowDuringUpload = true,
                attachOpen = attachOpen || resourcePanelOpen,
                onAttachOpenChange = onAttachOpenChange,
                modifier = menuActionModifier,
            )
            if (!compactVisual) controls()
            if (minimalCompanion == null) trailingActions(requestFocus, sendAndRefocus)
        },
    )
    }
    if (minimalCompanion == null && exteriorAction != null) Row(Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { editor() }
        androidx.compose.foundation.layout.Box(Modifier.size(ComposerActionTouchSize)) { exteriorAction(sendAndRefocus) }
    }
    else if (minimalCompanion == null) editor()
    else minimalCompanion(CompanionComposerControls(editorVisible, draft.isNotBlank(), attachments.isNotEmpty(),
        canSubmit, deliveryPhase, requestFocus, sendAndRefocus)) { if (editorVisible) editor() }
}
