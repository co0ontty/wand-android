package com.wand.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors

/** Action labels remain available to TalkBack and native long-press tooltips. */
internal data class SessionIconAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Content for the existing anchored Material menu; it owns no business state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionIconActions(actions: List<SessionIconAction>, onDismiss: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        val groups = listOf(actions.filterNot { it.destructive }, actions.filter { it.destructive })
            .filter { it.isNotEmpty() }
        groups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = WandColors.border)
            group.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { action ->
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = { PlainTooltip { Text(action.label) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(
                                enabled = action.enabled,
                                onClick = { onDismiss(); action.onClick() },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(action.icon, contentDescription = action.label,
                                    tint = when {
                                        !action.enabled -> WandColors.textMuted
                                        action.destructive -> WandColors.danger
                                        else -> WandColors.textPrimary
                                    }, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
