package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.isWandDarkTheme

@Composable
fun NativeComposerSurface(
    backdrop: GlassBackdrop,
    expanded: Boolean,
    onFocusInput: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedLeading: @Composable RowScope.() -> Unit = {},
    inputContent: @Composable RowScope.() -> Unit,
    collapsedTrailing: @Composable RowScope.() -> Unit = {},
    expandedControls: @Composable RowScope.(controlsCompact: Boolean) -> Unit = {},
) {
    val composerShape = RoundedCornerShape(if (expanded) 24.dp else 28.dp)
    val darkGlass = isWandDarkTheme()
    val composerGlass = if (expanded) {
        WandGlass.regular.copy(
            tintAlpha = if (darkGlass) 0.62f else 0.46f,
            fallbackAlpha = if (darkGlass) 0.92f else 0.84f,
            blurRadius = 20.dp,
            refractionHeight = 6.dp,
            refractionAmount = 10.dp,
            shadowElevation = 8.dp,
            shadowColor = Color.Black.copy(alpha = if (darkGlass) 0.34f else 0.14f),
        )
    } else {
        WandGlass.regular.copy(
            tintAlpha = if (darkGlass) 0.56f else 0.34f,
            fallbackAlpha = if (darkGlass) 0.88f else 0.78f,
            blurRadius = 18.dp,
            refractionHeight = 3.dp,
            refractionAmount = 8.dp,
            shadowElevation = 3.dp,
            shadowColor = Color.Black.copy(alpha = if (darkGlass) 0.28f else 0.10f),
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val controlsCompact = maxWidth < 360.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(backdrop, composerShape, composerGlass)
                .clickableWithoutRipple(onFocusInput)
                .padding(horizontal = if (expanded) 8.dp else 9.dp, vertical = if (expanded) 6.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 8.dp else 0.dp),
        ) {
            Row(
                verticalAlignment = if (expanded) Alignment.Bottom else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!expanded) {
                    collapsedLeading()
                }
                inputContent()
                if (!expanded) {
                    collapsedTrailing()
                }
            }
            if (expanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    expandedControls(controlsCompact)
                }
            }
        }
    }
}
