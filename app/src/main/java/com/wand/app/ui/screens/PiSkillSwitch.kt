package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.wand.app.data.PiSkillMode
import com.wand.app.ui.theme.WandColors
import kotlin.math.roundToInt

/** Native three-detent slider; preview changes never save an intermediate On state. */
@Composable
internal fun PiSkillSwitch(name: String, mode: PiSkillMode, enabled: Boolean, saving: Boolean,
    onModeChange: (PiSkillMode) -> Unit) {
    var preview by remember { mutableFloatStateOf(mode.ordinal.toFloat()) }
    LaunchedEffect(mode, saving) { if (!saving) preview = mode.ordinal.toFloat() }
    val index = preview.roundToInt().coerceIn(0, 2)
    val description = when (index) { 0 -> "关闭"; 1 -> "开启"; else -> "开启并锁定" }
    Column(Modifier.width(108.dp).heightIn(min = 48.dp).alpha(if (enabled || saving) 1f else 0.48f)) {
        Text(description, style = MaterialTheme.typography.labelSmall,
            color = if (index == 0) WandColors.textSecondary else WandColors.brandText)
        Slider(
            value = preview, valueRange = 0f..2f, steps = 1, enabled = enabled,
            onValueChange = { preview = it },
            onValueChangeFinished = {
                if (enabled) {
                    val next = PiSkillMode.entries[preview.roundToInt().coerceIn(0, 2)]
                    if (next != mode) onModeChange(next)
                }
            },
            modifier = Modifier.semantics {
                contentDescription = "Skill $name 使用设置"
                stateDescription = description
            },
        )
    }
}
