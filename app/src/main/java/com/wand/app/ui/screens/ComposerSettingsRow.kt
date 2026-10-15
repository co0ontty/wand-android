package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
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
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

/** Model gets the remaining room; compact settings retain full values in selectors and AX. */
@Composable
internal fun ComposerSettingsRow(
    model: String, mode: String, thinking: String, enabled: Boolean = true,
    onModel: () -> Unit, onMode: () -> Unit, onThinking: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showValues = maxWidth >= 300.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple("模型", model, onModel), Triple("权限", mode, onMode), Triple("思考", thinking, onThinking)).forEach { (label, value, action) ->
                val open = { focus.clearFocus(); keyboard?.hide(); action() }
                val target = if (label == "模型") Modifier.weight(1f) else if (showValues) Modifier.widthIn(min = 48.dp) else Modifier.width(48.dp)
                Row(target.heightIn(min = 48.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = open)
                    .clearAndSetSemantics {
                        contentDescription = "$label：$value"
                        role = Role.Button
                        if (!enabled) disabled()
                        onClick("选择$label") { if (enabled) { open(); true } else false }
                    }
                    .padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
                    val tint = WandColors.textSecondary.copy(alpha = if (enabled) 1f else .4f)
                    if (label != "模型") Icon(if (label == "权限") WandIcons.permission else WandIcons.thinking,
                        contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    if (label == "模型" || showValues) Text(value,
                        if (label == "模型") Modifier.weight(1f) else Modifier.widthIn(max = 88.dp),
                        color = tint, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (label == "模型") Icon(Icons.Outlined.KeyboardArrowDown, null, tint = tint, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
