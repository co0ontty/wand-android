package com.wand.app.data

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

/** 支持的模式全集；由上面这张表派生，不再维护第二份 id 列表。 */
private val ALL_SESSION_MODE_IDS: Set<String> =
    SESSION_MODE_OPTIONS.mapTo(linkedSetOf()) { it.id }

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
    Gemini("gemini", "Gemini", "gemini-cli-json", "gemini"),
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

/**
 * 同一个 provider 的两条执行路径：`cli` 起外部命令行，`sdk` 在 Wand 进程内跑 agent loop。
 * 服务端只对 pi 同时支持这两种（`engine` 字段），其余 provider 没有引擎维度。
 */
enum class WandAgentEngine(val raw: String) {
    Cli("cli"),
    Sdk("sdk"),
    ;

    companion object {
        fun fromRaw(raw: String?): WandAgentEngine? =
            entries.firstOrNull { it.raw == raw?.trim()?.lowercase() }
    }
}

/** Wand Agent（进程内 SDK）在 UI 上的选项 id；不是 provider 名，只是一个稳定标识。 */
const val WAND_AGENT_TOOL_ID = "wand-agent"

/**
 * 一条可选的执行工具：`pi` 与 `wand-agent` 共用 pi provider，但执行方式不同，
 * 所以下拉的值必须是「工具」而不是 provider，否则两者只能共用一个名字。
 * 顺序即 UI 顺序，与 Web `AGENT_TOOL_OPTIONS` 保持一致。
 */
data class AgentToolOption(
    val id: String,
    val provider: String,
    val engine: WandAgentEngine?,
    val label: String,
    val description: String,
) {
    /** 只有结构化会话能跑（进程内 SDK 没有终端形态）。 */
    val isStructuredOnly: Boolean get() = engine == WandAgentEngine.Sdk
}

val AGENT_TOOL_OPTIONS: List<AgentToolOption> = listOf(
    AgentToolOption("claude", "claude", null, "Claude", "Claude Code"),
    AgentToolOption("codex", "codex", null, "Codex", "OpenAI Codex CLI"),
    AgentToolOption("opencode", "opencode", null, "OpenCode", "OpenCode CLI"),
    AgentToolOption("grok", "grok", null, "Grok", "Grok Build CLI"),
    AgentToolOption("qoder", "qoder", null, "Qoder", "Qoder CLI"),
    AgentToolOption("pi", "pi", WandAgentEngine.Cli, "Pi", "Pi CLI：结构化 JSON 或 PTY 终端"),
    AgentToolOption(WAND_AGENT_TOOL_ID, "pi", WandAgentEngine.Sdk, "Wand Agent", "Wand 自带 Agent：进程内 SDK 执行，只支持结构化会话"),
    AgentToolOption("gemini", "gemini", null, "Gemini", "Gemini CLI"),
)

fun agentToolOption(id: String?): AgentToolOption? =
    AGENT_TOOL_OPTIONS.firstOrNull { it.id == id }

/** provider + 引擎 → 下拉选项 id；缺省引擎按 CLI，其它 provider 就是 provider 名。 */
fun agentToolId(provider: String?, engine: String?): String =
    if (provider == "pi" && engine == WandAgentEngine.Sdk.raw) WAND_AGENT_TOOL_ID else provider.orEmpty()

/**
 * 执行工具的展示名。同一 provider 的 Pi CLI 与 Wand Agent 必须分开，
 * 否则跑进程内 SDK 的会话会被显示成 Pi。
 */
fun agentToolLabel(provider: String?, engine: String?): String =
    if (provider == "pi" && engine == WandAgentEngine.Sdk.raw) "Wand Agent" else providerDisplayName(provider)

fun ProviderDefaultModels.defaultFor(provider: String?): String? = when (WandProvider.fromId(provider)) {
    WandProvider.Codex -> codex
    WandProvider.OpenCode -> opencode
    WandProvider.Grok -> grok
    WandProvider.Qoder -> qoder
    WandProvider.Pi -> pi
    WandProvider.Gemini -> gemini
    else -> claude
}

fun supportedSessionModeIds(provider: String?): Set<String> = when (provider) {
    "codex" -> setOf("full-access")
    "opencode", "grok", "pi" -> setOf("default", "full-access", "managed")
    // gemini 有 default / auto_edit / yolo（托管、全权限同走 yolo），与 qoder 同组。
    "qoder", "gemini" -> setOf("default", "full-access", "auto-edit", "managed")
    else -> ALL_SESSION_MODE_IDS
}
