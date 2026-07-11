package com.wand.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ModelInfo
import com.wand.app.ui.theme.WandColors
import kotlin.math.roundToInt

data class ThinkingEffortOption(
    val id: String,
    val label: String,
    val shortLabel: String,
    val menuLabel: String,
)

private val LEGACY_THINKING_LEVELS = listOf(
    ThinkingEffortOption("off", "关闭", "关", "关闭"),
    ThinkingEffortOption("standard", "低", "低", "低（low）"),
    ThinkingEffortOption("deep", "中", "中", "中（medium）"),
    ThinkingEffortOption("max", "高", "高", "高（max）"),
)

fun thinkingEffortOptions(
    provider: String,
    selectedModel: String?,
    models: List<ModelInfo>,
): List<ThinkingEffortOption> {
    if (provider != "codex") return LEGACY_THINKING_LEVELS
    val modelId = selectedModel?.takeIf { it.isNotBlank() && it != "default" } ?: "default"
    val levels = (models.firstOrNull { it.id == modelId }
        ?: models.firstOrNull { it.id == "default" })?.reasoningEfforts.orEmpty()
    if (levels.isEmpty()) return LEGACY_THINKING_LEVELS
    return listOf(ThinkingEffortOption("off", "自动", "自", "自动（模型默认）")) + levels.mapNotNull { level ->
        val effort = level.effort.lowercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = when (effort) {
            "low" -> "standard"
            "medium" -> "deep"
            "xhigh" -> "max"
            else -> "codex:$effort"
        }
        val label = when (effort) {
            "low" -> "低"
            "medium" -> "中"
            "high" -> "高"
            "xhigh" -> "超高"
            "max" -> "极高"
            "ultra" -> "极限"
            else -> effort
        }
        ThinkingEffortOption(id, label, label, "$label（$effort）")
    }
}

@Composable
fun ThinkingEffortSlider(
    options: List<ThinkingEffortOption>,
    selection: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = options.indexOfFirst { it.id == selection }.coerceAtLeast(0)
    var preview by remember(options) { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selection, options) {
        if (!dragging) preview = selectedIndex.toFloat()
    }
    val currentIndex = preview.roundToInt().coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val current = options.getOrNull(currentIndex)

    Column(
        modifier = modifier
            .semantics {
                contentDescription = "思考深度"
                stateDescription = current?.menuLabel ?: "自动"
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("思考深度", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = WandColors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                current?.menuLabel ?: "自动",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.brand,
                maxLines = 1,
            )
        }
        Slider(
            value = preview,
            onValueChange = {
                dragging = true
                preview = it.roundToInt().toFloat()
            },
            onValueChangeFinished = {
                dragging = false
                options.getOrNull(currentIndex)?.let { onSelect(it.id) }
            },
            valueRange = 0f..(options.size - 1).coerceAtLeast(0).toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = WandColors.brand,
                activeTrackColor = WandColors.brand,
                activeTickColor = WandColors.surface,
                inactiveTickColor = WandColors.textSecondary,
                inactiveTrackColor = WandColors.border,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
