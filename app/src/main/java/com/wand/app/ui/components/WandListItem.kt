package com.wand.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors

/** Standard Material3 slot measurement/density; actions and state stay with the caller. */
@Composable
fun WandListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    headlineColor: Color = WandColors.textPrimary,
    supportingColor: Color = WandColors.textSecondary,
) {
    ListItem(
        headlineContent = headlineContent,
        modifier = modifier.fillMaxWidth(),
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = headlineColor,
            supportingColor = supportingColor,
            leadingIconColor = WandColors.textSecondary,
            trailingIconColor = WandColors.textMuted,
        ),
    )
}

/** Keep the decorative slot measured even when its check is absent. */
@Composable
fun WandListItemIconSlot(
    icon: ImageVector?,
    tint: Color = WandColors.textMuted,
    iconSize: Dp = 18.dp,
) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
