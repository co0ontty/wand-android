package com.wand.app.data

private val allSessionModeIds = linkedSetOf(
    "managed",
    "full-access",
    "auto-edit",
    "default",
    "native",
)

data class SessionModeOption(
    val id: String,
    val label: String,
    val description: String,
)

val SESSION_MODE_OPTIONS = listOf(
    SessionModeOption("managed", "托管", "全自动完成任务"),
    SessionModeOption("full-access", "全权限", "自动确认权限"),
    SessionModeOption("auto-edit", "自动编辑", "自动确认修改"),
    SessionModeOption("default", "标准", "逐步确认操作"),
    SessionModeOption("native", "原生", "原生结构化输出"),
)

fun sessionModeLabel(id: String): String =
    SESSION_MODE_OPTIONS.firstOrNull { it.id == id }?.label ?: "标准"

fun providerDisplayName(provider: String?): String = when (provider) {
    null, "terminal" -> "终端"
    "codex" -> "Codex"
    "opencode" -> "OpenCode"
    "grok" -> "Grok"
    "qoder" -> "Qoder"
    "pi" -> "Pi"
    else -> "Claude"
}

fun modelsForProvider(
    provider: String?,
    claude: List<ModelInfo>,
    codex: List<ModelInfo>,
    opencode: List<ModelInfo>,
    qoder: List<ModelInfo> = emptyList(),
    grok: List<ModelInfo> = emptyList(),
    pi: List<ModelInfo> = emptyList(),
): List<ModelInfo> = when (provider) {
    "codex" -> codex
    "opencode" -> opencode
    "grok" -> grok
    "qoder" -> qoder
    "pi" -> pi
    else -> claude
}

fun ProviderDefaultModels.defaultFor(provider: String?): String? = when (provider) {
    "codex" -> codex
    "opencode" -> opencode
    "grok" -> grok
    "qoder" -> qoder
    "pi" -> pi
    else -> claude
}

fun supportedSessionModeIds(provider: String?): Set<String> = when (provider) {
    "codex" -> setOf("full-access")
    "opencode", "grok", "pi" -> setOf("default", "full-access", "managed")
    "qoder" -> setOf("default", "full-access", "auto-edit", "managed")
    else -> allSessionModeIds
}

fun clampSessionMode(mode: String, provider: String?): String {
    if (provider == "codex") return "full-access"
    val supported = supportedSessionModeIds(provider)
    if (mode in supported) return mode
    return if ("managed" in supported) "managed" else supported.first()
}
