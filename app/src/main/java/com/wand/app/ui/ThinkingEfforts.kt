package com.wand.app.ui

import com.wand.app.data.ModelInfo

data class ThinkingEffortOption(
    val id: String,
    val label: String,
    val shortLabel: String,
    val menuLabel: String,
)

private val LEGACY_THINKING_LEVELS = listOf(
    ThinkingEffortOption("off", "自动", "自", "自动（模型默认）"),
    ThinkingEffortOption("standard", "低", "低", "低（low）"),
    ThinkingEffortOption("deep", "中", "中", "中（medium）"),
    ThinkingEffortOption("max", "高", "高", "高（max）"),
)

fun thinkingEffortOptions(
    provider: String,
    selectedModel: String?,
    defaultModel: String?,
    models: List<ModelInfo>,
): List<ThinkingEffortOption> {
    if (provider != "codex") return LEGACY_THINKING_LEVELS
    val modelId = selectedModel?.takeIf { it.isNotBlank() && it != "default" }
        ?: defaultModel?.takeIf { it.isNotBlank() && it != "default" }
        ?: "default"
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
