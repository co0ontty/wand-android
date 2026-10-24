package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors

/** Closed options show only their current values; full labels belong to the picker. */
@Composable
internal fun ComposerSettingsRow(
    model: String, mode: String, thinking: String, enabled: Boolean = true,
    modeLabel: String = "权限",
    onModel: () -> Unit, onMode: () -> Unit, onThinking: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("模型", model, onModel), Triple(modeLabel, mode, onMode), Triple("思考", thinking, onThinking)).forEach { (label, value, action) ->
            val open = { focus.clearFocus(); keyboard?.hide(); action() }
            Box(Modifier.weight(if (label == "模型") 1.35f else 1f).heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = open)
                .clearAndSetSemantics {
                    contentDescription = "$label：$value"
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick("选择$label") { if (enabled) { open(); true } else false }
                }
                .padding(horizontal = 4.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(value, color = WandColors.textSecondary.copy(alpha = if (enabled) 1f else .4f),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
