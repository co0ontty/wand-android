package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.wand.app.ui.theme.WandColors
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal val LocalConversationPanelHeight = androidx.compose.runtime.compositionLocalOf { 480.dp }

@Composable
internal fun ConversationInsetDivider(start: Dp = 76.dp) {
    HorizontalDivider(Modifier.padding(start = start, end = 16.dp), thickness = 0.5.dp, color = WandColors.border)
}

/** Read-only outside observer: form inputs do not dismiss, and the clicked target keeps its focus. */
internal class ConversationOutsideLayer {
    private var host by mutableStateOf(Rect.Zero)
    private val regions = mutableMapOf<String, Rect>()
    fun region(name: String): Modifier = Modifier.onGloballyPositioned { regions[name] = it.boundsInRoot() }
    fun host(enabled: Boolean, names: Set<String>, dismiss: () -> Unit): Modifier = Modifier
        .onGloballyPositioned { host = it.boundsInRoot() }
        .pointerInput(enabled, names) {
            if (enabled) awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                    val position = down.position + host.topLeft
                    if (names.none { regions[it]?.contains(position) == true }) dismiss()
                }
            }
        }
}

@Composable
internal fun ConversationLayerBackHandler(enabled: Boolean, onClose: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled) { if (imeVisible) keyboard?.hide() else onClose() }
}
