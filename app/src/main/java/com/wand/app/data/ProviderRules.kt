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

/**
 * provider 标识的单一真源：展示名、structured runner、PTY CLI 名都在这里。
 *
 * 加一个 provider 只改这张表，不再到 `WandApi` / `WorkspaceRequestBodies` 里各补一个 `when`。
 */
enum class WandProvider(
    val id: String,
    val displayName: String,
    /** 服务端 structured runner 标识；claude 是默认值。 */
    val structuredRunner: String,
    /** PTY 场景下的 CLI 可执行文件名（多数与 id 相同）。 */
    val cliCommand: String,
) {
    Claude("claude", "Claude", "claude-cli-print", "claude"),
    Codex("codex", "Codex", "codex-cli-exec", "codex"),
    OpenCode("opencode", "OpenCode", "opencode-cli-run", "opencode"),
    Grok("grok", "Grok", "grok-cli-headless", "grok"),
    Qoder("qoder", "Qoder", "qoder-cli-print", "qodercli"),
    Pi("pi", "Pi", "pi-cli-json", "pi"),
    ;

    companion object {
        fun fromId(provider: String?): WandProvider? =
            entries.firstOrNull { it.id == provider }

        /** PTY / 命令类接口用的可执行文件名；未识别的 provider 原样透传。 */
        fun cliCommandFor(provider: String): String =
            fromId(provider)?.cliCommand ?: provider
    }
}

fun providerDisplayName(provider: String?): String = when (provider) {
    null, "terminal" -> "终端"
    else -> WandProvider.fromId(provider)?.displayName ?: WandProvider.Claude.displayName
}

fun ProviderDefaultModels.defaultFor(provider: String?): String? = when (WandProvider.fromId(provider)) {
    WandProvider.Codex -> codex
    WandProvider.OpenCode -> opencode
    WandProvider.Grok -> grok
    WandProvider.Qoder -> qoder
    WandProvider.Pi -> pi
    else -> claude
}

fun supportedSessionModeIds(provider: String?): Set<String> = when (provider) {
    "codex" -> setOf("full-access")
    "opencode", "grok", "pi" -> setOf("default", "full-access", "managed")
    "qoder" -> setOf("default", "full-access", "auto-edit", "managed")
    else -> allSessionModeIds
}
