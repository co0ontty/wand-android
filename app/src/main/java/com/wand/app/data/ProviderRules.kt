package com.wand.app.data

private val allSessionModeIds = linkedSetOf(
    "managed",
    "full-access",
    "auto-edit",
    "default",
    "native",
)

fun providerDisplayName(provider: String?): String = when (provider) {
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
