package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import com.wand.app.data.UploadedFile
import com.wand.app.ui.SendPhase
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One mounted editor transforms into a compact entry; temporary owners borrow focus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusCapsuleComposer(
    presentation: ChatInputPresentation, sessionKey: Any?, draft: String,
    onDraftChange: (String) -> Unit, attachments: List<UploadedFile>, baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit, uploading: Boolean,
    attachOpen: Boolean, onAttachOpenChange: (Boolean) -> Unit,
    onPickPhoto: () -> Unit, onPickFile: () -> Unit,
    canSubmit: Boolean, onSend: () -> Unit, deliveryPhase: SendPhase,
    voicePressed: Boolean, voiceProcessing: Boolean, voiceCommitSignal: Int,
    editorOverlayOpen: Boolean, resourcePanelOpen: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    trailingActions: @Composable RowScope.(() -> Unit, () -> Unit) -> Unit,
    controls: @Composable RowScope.() -> Unit,
    resourcePanel: @Composable () -> Unit, menuContent: @Composable () -> Unit,
    microphone: @Composable () -> Unit,
    exteriorAction: (@Composable (send: () -> Unit) -> Unit)? = null,
    actionCount: Int = 0,
) {
    var state by remember(sessionKey) { mutableStateOf(MinimalComposerState()) }
    var focusRevision by remember(sessionKey) { mutableIntStateOf(0) }
    var focused by remember(sessionKey) { mutableStateOf(false) }
    var restoring by remember(sessionKey) { mutableStateOf(false) }
    var returnFocus by remember(sessionKey) { mutableStateOf(false) }
    var imeArmed by remember(sessionKey) { mutableStateOf(false) }
    val requester = remember(sessionKey) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val ime = WindowInsets.ime
    val imeVisible = ime.getBottom(density) > 0
    val temporaryOwner = editorOverlayOpen || presentation.overlayOpen || attachOpen || resourcePanelOpen ||
        voicePressed || voiceProcessing || uploading
    val borrowed = temporaryOwner || !windowFocused
    val expanded = state.editing || state.recovery || attachOpen || resourcePanelOpen || uploading
    val edit: () -> Unit = { state = state.edit(); focusRevision++ }
    val dismiss: () -> Unit = {
        state = state.dismissKeyboard(); imeArmed = false
        focusManager.clearFocus(); keyboard?.hide()
    }
    val send: () -> Unit = {
        if (canSubmit) { state = state.submit(); onAttachOpenChange(false); onSend() }
    }
    LaunchedEffect(presentation.editRevision) { if (presentation.editRevision > 0) edit() }
    LaunchedEffect(presentation.dismissRevision) { if (presentation.dismissRevision > 0 && !borrowed) dismiss() }
    LaunchedEffect(voiceCommitSignal) { if (voiceCommitSignal > 0) edit() }
    LaunchedEffect(deliveryPhase, draft, attachments.size) {
        when (deliveryPhase) {
            SendPhase.Failed -> state = state.failed()
            SendPhase.Sent -> {
                state = state.accepted(draft.isNotEmpty() || attachments.isNotEmpty())
                if (!state.editing) { imeArmed = false; focusManager.clearFocus(); keyboard?.hide() }
            }
            else -> Unit
        }
    }
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    LaunchedEffect(borrowed) {
        if (borrowed) {
            // A selector can clear focus before its window attaches. The explicit
            // owner preserves editing intent even if the IME has already disappeared.
            returnFocus = returnFocus || expanded && (focused || imeVisible || temporaryOwner)
            imeArmed = false
            keyboard?.hide()
        } else if (returnFocus && expanded) {
            returnFocus = false
            focusRevision++
        }
    }
    LaunchedEffect(focusRevision, expanded, borrowed) {
        if (focusRevision > 0 && expanded && !borrowed) {
            restoring = true; imeArmed = false
            try {
                // Wait for the old dialog window to detach before giving the editor its IME.
                repeat(2) { withFrameNanos { } }
                requester.requestFocus(); keyboard?.show()
                val opened = withTimeoutOrNull(1500) { snapshotFlow { ime.getBottom(density) > 0 }.first { it } }
                // Remember that the IME opened, even if Back hides it in this frame.
                if (opened == true) imeArmed = true
            } finally { restoring = false }
        }
    }
    LaunchedEffect(imeVisible, borrowed, restoring, expanded) {
        if (!borrowed) {
            if (imeVisible && expanded) imeArmed = true
            else if (imeArmed && !restoring) dismiss()
        }
    }
    BackHandler(expanded && !borrowed && !imeVisible) { dismiss() }
    ConversationLayerBackHandler(attachOpen) { onAttachOpenChange(false) }
    val actionWidth = if (exteriorAction != null) ComposerActionTouchSize * actionCount else 0.dp
    val actionInset = if (actionWidth > 0.dp) actionWidth + 4.dp else 0.dp
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    Surface(
            modifier = Modifier.fillMaxWidth().padding(end = if (expanded) 0.dp else actionInset),
            shape = RoundedCornerShape(if (expanded) 22.dp else 28.dp),
            color = WandColors.surface,
            tonalElevation = 0.dp,
        ) {
            Column {
                if (expanded && attachments.isNotEmpty()) PendingAttachmentsPreview(
                    attachments, baseUrl, onRemoveAttachment, Modifier.padding(8.dp))
                // Keep the original field mounted, including selection and IME composition.
                Box(if (expanded) Modifier else Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { }) {
                    ComposerInputField(draft, { state = state.edit(); onDraftChange(it) }, "发消息…", focused,
                        { focused = it }, requester, expanded = true, framed = false, showClearAction = false,
                        modifier = Modifier.focusProperties { canFocus = expanded },
                        maxLines = 4, maxHeight = 112.dp,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default))
                }
                ComposerCapsuleToolbar(expanded, if (expanded) actionInset else 0.dp,
                    add = {
                    ComposerActionsMenu(null, uploading, allowDuringUpload = true,
                        attachOpen = attachOpen || resourcePanelOpen,
                        onAttachOpenChange = { state = state.edit(); onAttachOpenChange(it) })
                    }, controls = { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = controls) },
                    entry = { Text(
                        when { attachments.isNotEmpty() -> "草稿 · ${attachments.size} 附件"; draft.isNotEmpty() -> "草稿 · ${draft.length} 字"; else -> "发消息…" },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "展开键盘输入", onClick = edit)
                            .wrapContentHeight(Alignment.CenterVertically),
                        color = WandColors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    ) }, voice = {
                    if (exteriorAction != null) microphone()
                    else if (expanded) Row { trailingActions(edit, send) } else microphone()
                    })
            }
        }
        if (exteriorAction != null && actionCount > 0) Box(Modifier.width(actionWidth).height(ComposerActionTouchSize)
            .align(Alignment.BottomEnd).offset(y = (-2).dp),
            contentAlignment = Alignment.Center) { exteriorAction(send) }
    }
    // Panels are modal focus owners, never stacked above the input and IME.
    if (attachOpen) WandBottomSheet(onDismissRequest = { onAttachOpenChange(false) }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                WandInlinePanelAction(WandIcons.image, "从相册选择", enabled = !uploading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = { onPickPhoto(); onAttachOpenChange(false) })
                WandInlinePanelAction(WandIcons.attach, "从文件选择", enabled = !uploading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onClick = { onPickFile(); onAttachOpenChange(false) })
            }
            menuContent()
        }
    }
    resourcePanel()
}

/** Native layout keeps controls mounted when a narrow dual-action toolbar wraps. */
@Composable
private fun ComposerCapsuleToolbar(
    expanded: Boolean, actionInset: androidx.compose.ui.unit.Dp,
    add: @Composable () -> Unit, controls: @Composable () -> Unit,
    entry: @Composable () -> Unit, voice: @Composable () -> Unit,
) {
    Layout(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), content = {
        Box { add() }
        Box(Modifier.clipToBounds().then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })) { controls() }
        Box(Modifier.clipToBounds().then(if (expanded) Modifier.clearAndSetSemantics { } else Modifier)) { entry() }
        Box { voice() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val touch = ComposerActionTouchSize.roundToPx()
        val inset = actionInset.roundToPx()
        val stacked = expanded && width - inset < touch * 5
        val addPlace = children[0].measure(Constraints.fixed(touch, touch))
        val voicePlace = children[3].measure(Constraints.fixed(touch, touch))
        val settingsWidth = if (stacked) width else (width - inset - touch * 2).coerceAtLeast(0)
        val settingsPlace = children[1].measure(if (expanded) Constraints.fixedWidth(settingsWidth) else Constraints.fixed(0, 0))
        val entryPlace = children[2].measure(if (expanded) Constraints.fixed(0, 0) else Constraints.fixed((width - touch * 2).coerceAtLeast(0), touch))
        val rowHeight = maxOf(52.dp.roundToPx(), if (expanded && !stacked) settingsPlace.height else 0)
        val settingsHeight = if (stacked) settingsPlace.height else 0
        layout(width, settingsHeight + rowHeight) {
            addPlace.placeRelative(0, settingsHeight + (rowHeight - touch) / 2)
            settingsPlace.placeRelative(if (stacked) 0 else touch, if (stacked) 0 else (rowHeight - settingsPlace.height) / 2)
            entryPlace.placeRelative(touch, (rowHeight - touch) / 2)
            voicePlace.placeRelative(if (stacked) touch else width - inset - touch, settingsHeight + (rowHeight - touch) / 2)
        }
    }
}
