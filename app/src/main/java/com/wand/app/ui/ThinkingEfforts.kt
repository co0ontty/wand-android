package com.wand.app.ui

import com.wand.app.data.ModelInfo
import com.wand.app.data.ReasoningEffortInfo

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

private data class LegacyNativeEfforts(val standard: String, val deep: String, val max: String)

// 对齐 Web thinkingChoiceId；旧 ID 必须保持各 provider 的实际执行语义。
private val LEGACY_NATIVE = mapOf(
    "claude" to LegacyNativeEfforts("low", "medium", "max"),
    "codex" to LegacyNativeEfforts("low", "medium", "xhigh"),
    "opencode" to LegacyNativeEfforts("low", "high", "max"),
    "grok" to LegacyNativeEfforts("low", "high", "xhigh"),
    "qoder" to LegacyNativeEfforts("low", "high", "max"),
    "pi" to LegacyNativeEfforts("low", "high", "max"),
)

private val COMPACT_LABELS = mapOf(
    "auto" to "自动",
    "none" to "关闭",
    "minimal" to "最低",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "超高",
    "max" to "极高",
    "ultra" to "极限",
    "ultracode" to "极限代码",
)
private val NATIVE_EFFORT_PATTERN = Regex("[a-z][a-z0-9_-]{0,31}")

fun thinkingEffortOptions(
    provider: String,
    selectedModel: String?,
    defaultModel: String?,
    models: List<ModelInfo>,
    providerEfforts: List<ReasoningEffortInfo> = emptyList(),
): List<ThinkingEffortOption> =
    dynamicThinkingEffortOptions(provider, selectedModel, defaultModel, models, providerEfforts)
        ?: LEGACY_THINKING_LEVELS

/** 模型能力优先、provider 目录兜底；null 表示未知，不能据此覆盖会话的已选档位。 */
internal fun dynamicThinkingEffortOptions(
    provider: String,
    selectedModel: String?,
    defaultModel: String?,
    models: List<ModelInfo>,
    providerEfforts: List<ReasoningEffortInfo> = emptyList(),
): List<ThinkingEffortOption>? {
    val modelId = selectedModel?.takeIf { it.isNotBlank() && it != "default" }
        ?: defaultModel?.takeIf { it.isNotBlank() && it != "default" }
        ?: "default"
    val modelEfforts = (models.firstOrNull { it.id == modelId }
        ?: models.firstOrNull { it.id == "default" })?.reasoningEfforts.orEmpty()
    val levels = modelEfforts.ifEmpty { providerEfforts }
        .map { it.effort.trim().lowercase() }
        .filter { NATIVE_EFFORT_PATTERN.matches(it) }
        .distinct()
    if (levels.isEmpty()) return null
    val legacy = LEGACY_NATIVE[provider]
    return buildList {
        add(LEGACY_THINKING_LEVELS.first())
        for (effort in levels) {
            if (effort == "off") continue
            val id = when (effort) {
                legacy?.standard -> "standard"
                legacy?.deep -> "deep"
                legacy?.max -> "max"
                else -> "$provider:$effort"
            }
            val label = COMPACT_LABELS[effort] ?: effort
            add(ThinkingEffortOption(id, label, label, "$label（$effort）"))
        }
    }
}
