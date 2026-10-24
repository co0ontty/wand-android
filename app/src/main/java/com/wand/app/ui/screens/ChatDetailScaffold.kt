package com.wand.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import com.wand.app.ui.theme.WandColors

/** Pending and running conversations share the same viewport, insets and composer dock. */
@Composable
internal fun ChatDetailScaffold(
    bottomBar: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = WandColors.bgPrimary,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        content = content,
    )
}
