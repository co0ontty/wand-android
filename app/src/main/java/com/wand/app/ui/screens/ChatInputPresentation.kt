package com.wand.app.ui.screens

import androidx.compose.runtime.*

/** UI intents only: text, attachments and delivery remain in ChatComposer. */
@Stable
internal class ChatInputPresentation {
    var editRevision by mutableIntStateOf(0)
        private set
    var dismissRevision by mutableIntStateOf(0)
        private set
    var overlayOpen by mutableStateOf(false)
    fun edit() { editRevision++ }
    fun dismiss() { dismissRevision++ }
}
