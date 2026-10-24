package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

internal enum class ComposerAccessoryPanel { Todos, Queue }

/** A single expanded accessory shares this budget; IME space never counts as reading space. */
internal fun composerAccessoryHeight(availableHeight: Dp, writingSurfaceHeight: Dp, headerCount: Int): Dp {
    val remaining = (availableHeight - writingSurfaceHeight - ComposerActionTouchSize * headerCount -
        ChatFloatingControlsClearance - 8.dp).coerceAtLeast(0.dp)
    val normal = remaining - 64.dp
    return minOf(160.dp, remaining, maxOf(ComposerActionTouchSize, normal)).coerceAtLeast(0.dp)
}

internal fun composerAccessoryNeedsKeyboardDismiss(maxHeight: Dp, keyboardVisible: Boolean): Boolean =
    keyboardVisible && maxHeight <= ComposerActionTouchSize

internal enum class ComposerAccessoryKeyboardAction { Keep, CollapsePanel, DismissInput }

internal fun composerAccessoryKeyboardAction(
    wasVisible: Boolean, visible: Boolean, expanded: Boolean, maxHeight: Dp,
): ComposerAccessoryKeyboardAction = when {
    visible && !wasVisible -> ComposerAccessoryKeyboardAction.CollapsePanel
    expanded && (composerAccessoryNeedsKeyboardDismiss(maxHeight, visible) || maxHeight < ComposerActionTouchSize) ->
        ComposerAccessoryKeyboardAction.DismissInput
    else -> ComposerAccessoryKeyboardAction.Keep
}

@Composable
internal fun composerAccessoryMaxHeight(writingSurfaceHeight: Dp, headerCount: Int): Dp {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val occupied = with(density) {
        maxOf(WindowInsets.ime.getBottom(this), WindowInsets.systemBars.getBottom(this)).toDp() +
            WindowInsets.systemBars.getTop(this).toDp()
    }
    return composerAccessoryHeight((windowHeight - occupied).coerceAtLeast(0.dp), writingSurfaceHeight, headerCount)
}

/** Transparent, one-line summary above the writing surface. Full content lives in its bounded list. */
@Composable
internal fun ComposerAccessoryHeader(
    icon: ImageVector,
    title: String,
    preview: String,
    count: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = if (expanded) "收起$title" else "展开$title", onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = if (expanded) "已展开" else "已收起" }
            .heightIn(min = ComposerActionTouchSize).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = WandColors.textSecondary)
        Text(title, style = MaterialTheme.typography.labelMedium, color = WandColors.textSecondary)
        Text(preview, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
        if (count.isNotBlank()) Text(count, style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary)
        Icon(WandIcons.expand, null, Modifier.size(16.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f }, tint = WandColors.textSecondary)
    }
}
