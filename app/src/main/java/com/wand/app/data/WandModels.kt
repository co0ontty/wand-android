package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * wand 服务端 REST / WebSocket 协议模型，org.json 手写容错解析。
 * 字段名与服务端 src/types.ts 一一对应；全部可空 + 逐字段容错，
 * 服务端新增字段或个别字段形状变化时客户端不至于整体解析失败。
 * 对称 iOS 端 WandModels.swift 的「全字段 optional Codable」策略。
 */

// MARK: - org.json 容错取值辅助

/** 泛型 JSONArray 迭代：消除重复的 for-i-optJSONObject 样板。 */
internal inline fun <T> JSONArray.parseEach(block: (JSONObject) -> T?): List<T> {
    val out = mutableListOf<T>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        block(obj)?.let { out.add(it) }
    }
    return out
}

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONObject.int(key: String): Int? = (opt(key) as? Number)?.toInt()

internal fun JSONObject.dbl(key: String): Double? = (opt(key) as? Number)?.toDouble()

internal fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

internal fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

internal fun JSONArray.nonEmptyStrings(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotEmpty() } }

/**
 * 读取 tool_use input 里的数组字段，容忍服务端把数组拍成 JSON 字符串。
 * `claude -p --output-format stream-json`（默认结构化 runner）会把 TodoWrite.todos /
 * AskUserQuestion.questions 当成 "[{...}]" 字符串下发，直接 optJSONArray 拿到 null，
 * 待办进度条与提问卡片整段渲染不出来。数组形态直接用，字符串形态再解析一次。
 */
internal fun JSONObject.arrayField(key: String): JSONArray? {
    optJSONArray(key)?.let { return it }
    val s = str(key)?.trim() ?: return null
    if (!s.startsWith("[")) return null
    return try { JSONArray(s) } catch (_: Exception) { null }
}

/** 任意 JSON 值 → 单行摘要文本，用于 tool_use 卡片展示参数（对齐 JSONValue.summaryText）。 */
fun summaryText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is String -> value
    is Boolean -> if (value) "true" else "false"
    is Int -> value.toString()
    is Long -> value.toString()
    is Number -> {
        val d = value.toDouble()
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
    is JSONArray -> "[${value.length()} 项]"
    is JSONObject -> "{…}"
    else -> value.toString()
}

/**
 * 工具结果既可能是纯字符串，也可能是 Responses/MCP 风格的 content part 数组。
 * 不认识的 part 不能静默丢弃：优先抽取常见文本字段，否则保留格式化 JSON 作为兜底。
 */
private val STRUCTURED_TEXT_KEYS = listOf("text", "output_text", "input_text", "message", "summary")

private fun structuredContentText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is String -> value
    is JSONArray -> buildList {
        for (i in 0 until value.length()) {
            structuredContentText(value.opt(i)).takeIf { it.isNotBlank() }?.let(::add)
        }
    }.joinToString("\n")
    is JSONObject -> {
        STRUCTURED_TEXT_KEYS.firstNotNullOfOrNull { key ->
            structuredContentText(value.opt(key)).takeIf { it.isNotBlank() }
        } ?: try {
            value.toString(2)
        } catch (_: Exception) {
            value.toString()
        }
    }
    else -> value.toString()
}

// MARK: - 会话消息块

data class SubagentMeta(
    val taskId: String?,
    val agentType: String?,
    val taskDescription: String?,
) {
    companion object {
        fun parse(o: JSONObject?): SubagentMeta? {
            if (o == null) return null
            return SubagentMeta(o.str("taskId"), o.str("agentType"), o.str("taskDescription"))
        }
    }
}

data class SemanticQuestionOption(val label: String, val description: String?)

data class SemanticQuestion(
    val question: String,
    val header: String?,
    val multiSelect: Boolean,
    val options: List<SemanticQuestionOption>,
)

data class SemanticTaskItem(
    val id: String,
    val content: String,
    val status: String,
    val activeForm: String?,
)

sealed class ToolUseSemantic {
    data class QuestionRequest(val questions: List<SemanticQuestion>) : ToolUseSemantic()
    data class TaskList(val items: List<SemanticTaskItem>) : ToolUseSemantic()

    companion object {
        fun parse(o: JSONObject?): ToolUseSemantic? {
            if (o == null) return null
            return when (o.str("kind")) {
                "question_request" -> {
                    val questions = o.arr("questions")?.parseEach { question ->
                        val options = question.arr("options")?.parseEach { option ->
                            SemanticQuestionOption(
                                label = option.str("label") ?: "",
                                description = option.str("description"),
                            )
                        } ?: emptyList()
                        SemanticQuestion(
                            question = question.str("question") ?: "",
                            header = question.str("header"),
                            multiSelect = question.bool("multiSelect") ?: false,
                            options = options,
                        )
                    } ?: emptyList()
                    QuestionRequest(questions)
                }
                "task_list" -> {
                    val items = o.arr("items")?.parseEach { item ->
                        SemanticTaskItem(
                            id = item.str("id") ?: "",
                            content = item.str("content") ?: "",
                            status = item.str("status") ?: "pending",
                            activeForm = item.str("activeForm"),
                        )
                    } ?: emptyList()
                    TaskList(items)
                }
                else -> null
            }
        }
    }
}

/** ConversationTurn.content 里的一个块。types.ts: ContentBlock 四种变体 + 容错。 */
sealed class ContentBlock {
    data class Text(val text: String, val subagent: SubagentMeta?) : ContentBlock()
    data class Thinking(val thinking: String, val subagent: SubagentMeta?) : ContentBlock()
    data class ToolUse(
        val id: String,
        val name: String,
        val description: String?,
        val input: JSONObject,
        val subagent: SubagentMeta?,
        val semantic: ToolUseSemantic? = null,
    ) : ContentBlock()
    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean,
        val truncated: Boolean,
        val subagent: SubagentMeta?,
    ) : ContentBlock()
    /** 协议升级兜底：保留类型与原始载荷，UI 可明确提示而不是整块消失。 */
    data class Unknown(val type: String, val payload: String) : ContentBlock()

    companion object {
        fun parse(o: JSONObject): ContentBlock {
            val subagent = SubagentMeta.parse(o.obj("__subagent"))
            return when (o.str("type") ?: "") {
                "text" -> Text(o.str("text") ?: "", subagent)
                "thinking" -> Thinking(o.str("thinking") ?: "", subagent)
                "tool_use" -> ToolUse(
                    id = o.str("id") ?: "",
                    name = o.str("name") ?: "tool",
                    description = o.str("description"),
                    input = o.obj("input") ?: JSONObject(),
                    subagent = subagent,
                    semantic = ToolUseSemantic.parse(o.obj("semantic")),
                )
                "tool_result" -> {
                    val text = structuredContentText(o.opt("content"))
                    ToolResult(
                        toolUseId = o.str("tool_use_id") ?: "",
                        text = text,
                        isError = o.bool("is_error") ?: false,
                        truncated = o.bool("_truncated") ?: false,
                        subagent = subagent,
                    )
                }
                else -> Unknown(
                    type = o.str("type")?.takeIf { it.isNotBlank() } ?: "unknown",
                    payload = try { o.toString(2) } catch (_: Exception) { o.toString() },
                )
            }
        }
    }
}

/** 单轮 assistant 用量；兼容服务端 camelCase 与上游 snake_case。 */
data class TurnUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cacheReadInputTokens: Int?,
    val cacheCreationInputTokens: Int?,
    val reasoningOutputTokens: Int?,
    val totalCostUsd: Double?,
    val estimated: Boolean?,
) {
    val hasVisibleValue: Boolean
        get() = (inputTokens ?: 0) > 0 ||
            (outputTokens ?: 0) > 0 ||
            (cacheReadInputTokens ?: 0) > 0 ||
            (cacheCreationInputTokens ?: 0) > 0 ||
            (reasoningOutputTokens ?: 0) > 0 ||
            (totalCostUsd ?: 0.0) > 0.0

    companion object {
        private fun JSONObject.intEither(camel: String, snake: String): Int? = int(camel) ?: int(snake)
        private fun JSONObject.doubleEither(camel: String, snake: String): Double? = dbl(camel) ?: dbl(snake)

        fun parse(o: JSONObject?): TurnUsage? {
            if (o == null) return null
            return TurnUsage(
                inputTokens = o.intEither("inputTokens", "input_tokens"),
                outputTokens = o.intEither("outputTokens", "output_tokens"),
                cacheReadInputTokens = o.intEither("cacheReadInputTokens", "cache_read_input_tokens"),
                cacheCreationInputTokens = o.intEither("cacheCreationInputTokens", "cache_creation_input_tokens"),
                reasoningOutputTokens = o.intEither("reasoningOutputTokens", "reasoning_output_tokens"),
                totalCostUsd = o.doubleEither("totalCostUsd", "total_cost_usd"),
                estimated = o.bool("estimated"),
            )
        }
    }
}

data class ConversationTurn(
    val role: String,
    val content: List<ContentBlock>,
    val usage: TurnUsage? = null,
) {
    companion object {
        fun parse(o: JSONObject): ConversationTurn {
            // 逐块容错：单个块解析失败不拖垮整条消息。
            val blocks = o.arr("content")?.parseEach {
                try { ContentBlock.parse(it) } catch (_: Exception) { null }
            } ?: emptyList()
            return ConversationTurn(
                role = o.str("role") ?: "assistant",
                content = blocks,
                usage = TurnUsage.parse(o.obj("usage")),
            )
        }

        fun parseList(arr: JSONArray?): List<ConversationTurn>? =
            arr?.parseEach { try { parse(it) } catch (_: Exception) { null } }
    }
}

// MARK: - 权限请求

data class EscalationRequest(
    val requestId: String,
    val scope: String,
    val reason: String,
    val target: String?,
    val source: String?,
) {
    /** scope → 用户可读标题（types.ts EscalationScope）。 */
    val scopeTitle: String
        get() = when (scope) {
            "write_file" -> "写入文件"
            "run_command" -> "执行命令"
            "network" -> "访问网络"
            "outside_workspace" -> "访问工作区外路径"
            "dangerous_shell" -> "执行高危命令"
            else -> "权限请求"
        }

    companion object {
        fun parse(o: JSONObject?): EscalationRequest? {
            if (o == null) return null
            val requestId = o.str("requestId") ?: return null
            return EscalationRequest(
                requestId = requestId,
                scope = o.str("scope") ?: "",
                reason = o.str("reason") ?: "",
                target = o.str("target"),
                source = o.str("source"),
            )
        }
    }
}

/** PTY 会话 status 事件里的旧式权限提示（ws data.permissionRequest）。 */
data class PermissionRequestInfo(
    val scope: String?,
    val target: String?,
    val prompt: String?,
) {
    companion object {
        fun parse(o: JSONObject?): PermissionRequestInfo? {
            if (o == null) return null
            return PermissionRequestInfo(o.str("scope"), o.str("target"), o.str("prompt"))
        }
    }
}

data class StructuredSessionState(
    val runner: String?,
    val model: String?,
    val lastError: String?,
    val inFlight: Boolean?,
    val activeRequestId: String?,
) {
    companion object {
        fun parse(o: JSONObject?): StructuredSessionState? {
            if (o == null) return null
            return StructuredSessionState(
                runner = o.str("runner"),
                model = o.str("model"),
                lastError = o.str("lastError"),
                inFlight = o.bool("inFlight"),
                activeRequestId = o.str("activeRequestId"),
            )
        }
    }
}

// MARK: - 会话快照

/**
 * SessionSnapshot 的客户端子集。GET /api/sessions 返回 slim 版（无 messages），
 * GET /api/sessions/:id?format=chat 与 ws init 返回带 messages 的完整版。
 */
data class SessionSnapshot(
    val id: String,
    val sessionKind: String?,
    val provider: String?,
    val runner: String?,
    val command: String?,
    val cwd: String?,
    val mode: String?,
    val status: String?,
    val exitCode: Int?,
    val startedAt: String?,
    val endedAt: String?,
    val archived: Boolean?,
    val summary: String?,
    val currentTaskTitle: String?,
    val selectedModel: String?,
    val thinkingEffort: String?,
    val claudeSessionId: String?,
    val messages: List<ConversationTurn>?,
    /** 窗口化：messages 是完整历史的「最近一窗」，messageOffset = 首条绝对下标，
     *  messageTotal = 完整 turn 数。更早的按需翻页（GET /api/sessions/:id/messages）。 */
    val messageOffset: Int? = null,
    val messageTotal: Int? = null,
    val queuedMessages: List<String>?,
    val structuredState: StructuredSessionState?,
    val pendingEscalation: EscalationRequest?,
    val permissionBlocked: Boolean?,
    val autoApprovePermissions: Boolean?,
    val title: String? = null,
    val description: String? = null,
    val titleGenerating: Boolean? = null,
) {
    val isStructured: Boolean get() = (sessionKind ?: "pty") == "structured"

    val providerLabel: String
        get() = providerDisplayName(provider)

    /** 列表标题：模型标题 > 摘要 > 当前任务 > cwd 末段。 */
    val displayTitle: String
        get() {
            title?.takeIf { it.isNotEmpty() }?.let { return it }
            summary?.takeIf { it.isNotEmpty() }?.let { return it }
            currentTaskTitle?.takeIf { it.isNotEmpty() }?.let { return it }
            cwd?.takeIf { it.isNotEmpty() }?.let { c ->
                val name = c.trimEnd('/').substringAfterLast('/')
                return name.ifEmpty { c }
            }
            return "会话"
        }

    val isResponding: Boolean get() = structuredState?.inFlight ?: false

    val hasPendingPermission: Boolean
        get() = pendingEscalation != null || (permissionBlocked ?: false)

    companion object {
        fun parse(o: JSONObject): SessionSnapshot = SessionSnapshot(
            id = o.str("id") ?: "",
            sessionKind = o.str("sessionKind"),
            provider = o.str("provider"),
            runner = o.str("runner"),
            command = o.str("command"),
            cwd = o.str("cwd"),
            mode = o.str("mode"),
            status = o.str("status"),
            exitCode = o.int("exitCode"),
            startedAt = o.str("startedAt"),
            endedAt = o.str("endedAt"),
            archived = o.bool("archived"),
            summary = o.str("summary"),
            currentTaskTitle = o.str("currentTaskTitle"),
            selectedModel = o.str("selectedModel"),
            thinkingEffort = o.str("thinkingEffort"),
            claudeSessionId = o.str("claudeSessionId"),
            messages = ConversationTurn.parseList(o.arr("messages")),
            messageOffset = o.int("messageOffset"),
            messageTotal = o.int("messageTotal"),
            queuedMessages = o.arr("queuedMessages")?.nonEmptyStrings(),
            structuredState = StructuredSessionState.parse(o.obj("structuredState")),
            pendingEscalation = EscalationRequest.parse(o.obj("pendingEscalation")),
            permissionBlocked = o.bool("permissionBlocked"),
            autoApprovePermissions = o.bool("autoApprovePermissions"),
            title = o.str("title"),
            description = o.str("description"),
            titleGenerating = o.bool("titleGenerating"),
        )

        fun parseList(arr: JSONArray): List<SessionSnapshot> =
            arr.parseEach { try { parse(it) } catch (_: Exception) { null } }
    }
}

// MARK: - 会话列表

/** 服务端按时间混排的托管/可恢复会话分页。 */
data class SessionListPage(
    val entries: List<SessionListEntry>,
    val offset: Int,
    val total: Int,
    val revision: String? = null,
) {
    companion object {
        fun parse(o: JSONObject): SessionListPage {
            val rawEntries = o.arr("entries") ?: throw IllegalArgumentException("响应缺少 entries")
            val entries = SessionListEntry.parseList(rawEntries)
            if (entries.size != rawEntries.length() || entries.map { it.key }.toSet().size != entries.size) {
                throw IllegalArgumentException("响应包含无效会话条目")
            }
            val offset = o.int("offset") ?: throw IllegalArgumentException("响应缺少 offset")
            val total = o.int("total") ?: throw IllegalArgumentException("响应缺少 total")
            val revision = o.str("revision")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("响应缺少 revision")
            require(offset >= 0 && total >= offset && entries.size <= total - offset) {
                "响应分页范围无效"
            }
            return SessionListPage(entries, offset, total, revision)
        }
    }
}

sealed interface SessionListEntry {
    val key: String
    val sortTimestamp: Long

    data class Managed(
        override val key: String,
        override val sortTimestamp: Long,
        val session: SessionSnapshot,
    ) : SessionListEntry

    data class Recoverable(
        override val key: String,
        override val sortTimestamp: Long,
        val history: HistorySession,
    ) : SessionListEntry

    companion object {
        fun parseList(arr: JSONArray): List<SessionListEntry> = arr.parseEach(::parse)

        private fun parse(o: JSONObject): SessionListEntry? {
            val key = o.str("key")?.takeIf { it.isNotBlank() } ?: return null
            val sortTimestamp = o.dbl("sortTimestamp")?.toLong() ?: return null
            return when (o.str("type")) {
                "managed" -> {
                    val session = o.obj("session")?.let(SessionSnapshot::parse)
                        ?.takeIf { it.id.isNotBlank() }
                        ?: return null
                    Managed(key, sortTimestamp, session)
                }
                "recoverable" -> {
                    val history = o.obj("history")?.let(HistorySession::parse)
                        ?.takeIf { it.id.isNotBlank() }
                        ?: return null
                    Recoverable(key, sortTimestamp, history)
                }
                else -> null
            }
        }
    }
}

/** 从 Claude/Codex 本地历史文件扫描出的会话。两个 provider 的接口形状一致（对称 iOS HistorySession）。 */
data class HistorySession(
    val claudeSessionId: String,
    val cwd: String,
    val firstUserMessage: String,
    val timestamp: String?,
    val mtimeMs: Double?,
    val hasConversation: Boolean?,
    val managedByWand: Boolean?,
    val provider: String?,
) {
    val id: String get() = claudeSessionId

    /** API 路径归一化：服务端接口只认 "claude" 或 "codex"。 */
    val apiProvider: String get() = if (provider == "codex") "codex" else "claude"

    companion object {
        fun parse(o: JSONObject): HistorySession? {
            val sid = o.str("claudeSessionId") ?: return null
            return HistorySession(
                claudeSessionId = sid,
                cwd = o.str("cwd") ?: "",
                firstUserMessage = o.str("firstUserMessage") ?: "",
                timestamp = o.str("timestamp"),
                mtimeMs = o.dbl("mtimeMs"),
                hasConversation = o.bool("hasConversation"),
                managedByWand = o.bool("managedByWand"),
                provider = o.str("provider"),
            )
        }

        fun parseList(arr: JSONArray, provider: String): List<HistorySession> =
            arr.parseEach { o -> parse(o)?.copy(provider = o.str("provider") ?: provider) }
    }
}

// MARK: - 模型列表

/** GET /api/models 的单个模型（对称 iOS ModelInfo）。 */
data class ModelInfo(
    val id: String,
    val label: String,
    val alias: Boolean?,
    val reasoningEfforts: List<ReasoningEffortInfo>,
    val defaultReasoningEffort: String?,
) {
    companion object {
        fun parseList(arr: JSONArray?): List<ModelInfo> =
            arr?.parseEach { o ->
                o.str("id")?.let { id ->
                    ModelInfo(
                        id = id,
                        label = o.str("label") ?: id,
                        alias = o.bool("alias"),
                        reasoningEfforts = o.arr("reasoningEfforts")?.parseEach { level ->
                            level.str("effort")?.let { effort ->
                                ReasoningEffortInfo(effort, level.str("description"))
                            }
                        } ?: emptyList(),
                        defaultReasoningEffort = o.str("defaultReasoningEffort"),
                    )
                }
            } ?: emptyList()
    }
}

data class ReasoningEffortInfo(
    val effort: String,
    val description: String?,
)

private fun legacyDefaultModelFor(
    provider: String,
    claude: String?,
    codex: String?,
    opencode: String?,
    qoder: String? = null,
    grok: String? = null,
): String = when (provider) {
    "codex" -> codex.orEmpty()
    "opencode" -> opencode.orEmpty()
    "grok" -> grok.orEmpty()
    "qoder" -> qoder.orEmpty()
    else -> claude.orEmpty()
}

data class ModelsResponse(
    val models: List<ModelInfo>,
    val codexModels: List<ModelInfo>,
    val opencodeModels: List<ModelInfo>,
    val defaultModel: String?,
    val defaultCodexModel: String?,
    val defaultOpenCodeModel: String?,
    val defaultModels: ProviderDefaultModels?,
    val qoderModels: List<ModelInfo> = emptyList(),
    val defaultQoderModel: String? = null,
    val grokModels: List<ModelInfo> = emptyList(),
    val defaultGrokModel: String? = null,
) {
    fun defaultModelFor(provider: String): String =
        defaultModels?.defaultFor(provider)
            ?: legacyDefaultModelFor(
                provider,
                defaultModel,
                defaultCodexModel,
                defaultOpenCodeModel,
                defaultQoderModel,
                defaultGrokModel,
            )

    companion object {
        fun parse(o: JSONObject): ModelsResponse = ModelsResponse(
            models = ModelInfo.parseList(o.arr("models")),
            codexModels = ModelInfo.parseList(o.arr("codexModels")),
            opencodeModels = ModelInfo.parseList(o.arr("opencodeModels")),
            defaultModel = o.str("defaultModel"),
            defaultCodexModel = o.str("defaultCodexModel"),
            defaultOpenCodeModel = o.str("defaultOpenCodeModel"),
            defaultModels = ProviderDefaultModels.parse(o.obj("defaultModels")),
            qoderModels = ModelInfo.parseList(o.arr("qoderModels")),
            defaultQoderModel = o.str("defaultQoderModel"),
            grokModels = ModelInfo.parseList(o.arr("grokModels")),
            defaultGrokModel = o.str("defaultGrokModel"),
        )
    }
}

data class ProviderDefaultModels(
    val claude: String?,
    val codex: String?,
    val opencode: String?,
    val qoder: String? = null,
    val grok: String? = null,
) {
    companion object {
        fun parse(o: JSONObject?): ProviderDefaultModels? =
            o?.let {
                ProviderDefaultModels(
                    claude = it.str("claude"),
                    codex = it.str("codex"),
                    opencode = it.str("opencode"),
                    qoder = it.str("qoder"),
                    grok = it.str("grok"),
                )
            }
    }
}

/** 结构化聊天卡片的全局默认展开状态（由服务端 /api/config.cardDefaults 下发）。 */
data class CardExpandDefaults(
    val editCards: Boolean = false,
    val inlineTools: Boolean = false,
    val terminal: Boolean = false,
    val thinking: Boolean = false,
    val toolGroup: Boolean = false,
) {
    fun shouldExpandTool(toolName: String): Boolean = when (toolName) {
        "Read", "Glob", "Grep", "WebFetch", "WebSearch", "TodoRead" -> inlineTools
        "Bash" -> terminal
        "Edit", "Write", "MultiEdit" -> editCards
        // 与 Web 通用工具卡保持一致：未单独分类的工具沿用 editCards。
        else -> editCards
    }

    companion object {
        fun parse(o: JSONObject?): CardExpandDefaults = CardExpandDefaults(
            editCards = o?.bool("editCards") == true,
            inlineTools = o?.bool("inlineTools") == true,
            terminal = o?.bool("terminal") == true,
            thinking = o?.bool("thinking") == true,
            toolGroup = o?.bool("toolGroup") == true,
        )
    }
}

// MARK: - 附件上传

/** POST /api/sessions/:id/upload 返回的单个文件。 */
data class UploadedFile(
    val originalName: String,
    val savedPath: String,
    val size: Int,
    val mimeType: String,
) {
    companion object {
        fun parseList(o: JSONObject): List<UploadedFile> {
            val arr = o.arr("files") ?: return emptyList()
            return arr.parseEach { f ->
                UploadedFile(
                    originalName = f.str("originalName") ?: "",
                    savedPath = f.str("savedPath") ?: "",
                    size = f.int("size") ?: 0,
                    mimeType = f.str("mimeType") ?: "",
                )
            }
        }
    }
}

// MARK: - WebSocket 消息

/**
 * /ws 推送的统一包络。data 的形状随 type 不同，用「超集类」WsData 承接：
 * init 的 data 就是 SessionSnapshot；output/status/ended 的 data 是其子集 + 增量字段。
 */
internal data class WsIncoming(
    val type: String,
    val sessionId: String?,
    val seq: Int?,
    val t: Double?,
    val error: String?,
    val data: WsData?,
) {
    companion object {
        fun parse(o: JSONObject): WsIncoming = WsIncoming(
            type = o.str("type") ?: "",
            sessionId = o.str("sessionId"),
            seq = o.int("seq"),
            t = o.dbl("t"),
            error = o.str("error"),
            data = o.obj("data")?.let { WsData.parse(it) },
        )
    }
}

internal data class WsData(
    // —— 快照公共字段（init / status / ended）——
    val id: String?,
    val sessionKind: String?,
    val provider: String?,
    val runner: String?,
    val command: String?,
    val cwd: String?,
    val mode: String?,
    val status: String?,
    val exitCode: Int?,
    val startedAt: String?,
    val endedAt: String?,
    val archived: Boolean?,
    val summary: String?,
    val title: String?,
    val description: String?,
    val titleGenerating: Boolean?,
    val currentTaskTitle: String?,
    val selectedModel: String?,
    val thinkingEffort: String?,
    val claudeSessionId: String?,
    val messages: List<ConversationTurn>?,
    val messageOffset: Int? = null,
    val messageTotal: Int? = null,
    val queuedMessages: List<String>?,
    val structuredState: StructuredSessionState?,
    val pendingEscalation: EscalationRequest?,
    val permissionBlocked: Boolean?,
    val autoApprovePermissions: Boolean?,
    // —— output 事件增量字段 ——
    val lastMessage: ConversationTurn?,
    val messageCount: Int?,
    val incremental: Boolean?,
    val isResponding: Boolean?,
    // —— status 事件附加字段 ——
    val permissionRequest: PermissionRequestInfo?,
    // —— task 事件：data 本身就是任务对象（{title, …}），其余字段缺省 ——
    val taskTitle: String?,
) {
    /** init 的 data 是完整快照 —— 转成 SessionSnapshot（messages 不带，避免双份内存）。 */
    fun toSnapshot(): SessionSnapshot? {
        val sid = id ?: return null
        return SessionSnapshot(
            id = sid, sessionKind = sessionKind, provider = provider, runner = runner,
            command = command, cwd = cwd, mode = mode, status = status, exitCode = exitCode,
            startedAt = startedAt, endedAt = endedAt, archived = archived, summary = summary,
            currentTaskTitle = currentTaskTitle, selectedModel = selectedModel,
            thinkingEffort = thinkingEffort,
            claudeSessionId = claudeSessionId, messages = null,
            messageOffset = messageOffset, messageTotal = messageTotal,
            queuedMessages = queuedMessages,
            structuredState = structuredState, pendingEscalation = pendingEscalation,
            permissionBlocked = permissionBlocked, autoApprovePermissions = autoApprovePermissions,
            title = title, description = description, titleGenerating = titleGenerating,
        )
    }

    companion object {
        fun parse(o: JSONObject): WsData = WsData(
            id = o.str("id"),
            sessionKind = o.str("sessionKind"),
            provider = o.str("provider"),
            runner = o.str("runner"),
            command = o.str("command"),
            cwd = o.str("cwd"),
            mode = o.str("mode"),
            status = o.str("status"),
            exitCode = o.int("exitCode"),
            startedAt = o.str("startedAt"),
            endedAt = o.str("endedAt"),
            archived = o.bool("archived"),
            summary = o.str("summary"),
            title = o.str("title"),
            description = o.str("description"),
            titleGenerating = o.bool("titleGenerating"),
            currentTaskTitle = o.str("currentTaskTitle"),
            selectedModel = o.str("selectedModel"),
            thinkingEffort = o.str("thinkingEffort"),
            claudeSessionId = o.str("claudeSessionId"),
            messages = ConversationTurn.parseList(o.arr("messages")),
            messageOffset = o.int("messageOffset"),
            messageTotal = o.int("messageTotal"),
            queuedMessages = o.arr("queuedMessages")?.nonEmptyStrings(),
            structuredState = StructuredSessionState.parse(o.obj("structuredState")),
            pendingEscalation = EscalationRequest.parse(o.obj("pendingEscalation")),
            permissionBlocked = o.bool("permissionBlocked"),
            autoApprovePermissions = o.bool("autoApprovePermissions"),
            lastMessage = o.obj("lastMessage")?.let {
                try {
                    ConversationTurn.parse(it)
                } catch (_: Exception) {
                    null
                }
            },
            messageCount = o.int("messageCount"),
            incremental = o.bool("incremental"),
            isResponding = o.bool("isResponding"),
            permissionRequest = PermissionRequestInfo.parse(o.obj("permissionRequest")),
            taskTitle = o.str("title"),
        )
    }
}

/** GET /api/sessions/:id/messages 的分页响应：完整历史的 [offset, offset+limit) 段 + 总数。 */
data class MessagesPage(
    val messages: List<ConversationTurn>,
    val offset: Int,
    val total: Int,
) {
    companion object {
        fun parse(o: JSONObject): MessagesPage = MessagesPage(
            messages = ConversationTurn.parseList(o.arr("messages")) ?: emptyList(),
            offset = o.int("offset") ?: 0,
            total = o.int("total") ?: 0,
        )
    }
}

// MARK: - 目录浏览 / 最近路径

data class DirectoryItem(
    val path: String,
    val name: String,
    val type: String,
) {
    val isDirectory: Boolean get() = type == "dir"

    companion object {
        fun parse(o: JSONObject): DirectoryItem = DirectoryItem(
            path = o.str("path") ?: "",
            name = o.str("name") ?: "",
            type = o.str("type") ?: "",
        )
    }
}

data class DirectoryListing(
    val items: List<DirectoryItem>,
    val truncated: Boolean?,
) {
    companion object {
        fun parse(o: JSONObject): DirectoryListing = DirectoryListing(
            items = o.arr("items")?.parseEach { DirectoryItem.parse(it) } ?: emptyList(),
            truncated = o.bool("truncated"),
        )
    }
}

data class RecentPath(
    val path: String,
    val name: String?,
    val lastUsedAt: String?,
) {
    val displayName: String
        get() {
            name?.takeIf { it.isNotEmpty() }?.let { return it }
            val last = path.trimEnd('/').substringAfterLast('/')
            return last.ifEmpty { path }
        }

    companion object {
        fun parseList(arr: JSONArray): List<RecentPath> =
            arr.parseEach { p ->
                p.str("path")?.let { RecentPath(it, p.str("name"), p.str("lastUsedAt")) }
            }
    }
}

/** GET /api/config 的客户端子集。 */
data class ServerConfigInfo(
    val defaultCwd: String?,
    val defaultProvider: String?,
    val defaultSessionKind: String?,
    val defaultMode: String?,
    val defaultModel: String?,
    val defaultCodexModel: String?,
    val defaultOpenCodeModel: String?,
    val defaultModels: ProviderDefaultModels?,
    val defaultThinkingEffort: String?,
    val cardDefaults: CardExpandDefaults,
    val currentVersion: String?,
    val defaultQoderModel: String? = null,
    val defaultGrokModel: String? = null,
) {
    fun defaultModelFor(provider: String): String =
        defaultModels?.defaultFor(provider)
            ?: legacyDefaultModelFor(
                provider,
                defaultModel,
                defaultCodexModel,
                defaultOpenCodeModel,
                defaultQoderModel,
                defaultGrokModel,
            )

    companion object {
        fun parse(o: JSONObject): ServerConfigInfo = ServerConfigInfo(
            defaultCwd = o.str("defaultCwd"),
            defaultProvider = o.str("defaultProvider"),
            defaultSessionKind = o.str("defaultSessionKind"),
            defaultMode = o.str("defaultMode"),
            defaultModel = o.str("defaultModel"),
            defaultCodexModel = o.str("defaultCodexModel"),
            defaultOpenCodeModel = o.str("defaultOpenCodeModel"),
            defaultModels = ProviderDefaultModels.parse(o.obj("defaultModels")),
            defaultThinkingEffort = o.str("defaultThinkingEffort"),
            cardDefaults = CardExpandDefaults.parse(o.obj("cardDefaults")),
            currentVersion = o.str("currentVersion"),
            defaultQoderModel = o.str("defaultQoderModel"),
            defaultGrokModel = o.str("defaultGrokModel"),
        )
    }
}

// MARK: - Git 快速提交

/** GET /api/sessions/:id/git-status 的文件条目（porcelain v2 状态码）。 */
data class GitFileEntry(
    val path: String,
    val status: String,
    val isSubmodule: Boolean?,
) {
    /** ".M" → "M"、"??" → "?"，给列表一个紧凑的状态徽标。 */
    val shortStatus: String
        get() {
            val cleaned = status.replace(".", "")
            if (cleaned == "??") return "?"
            return cleaned.ifEmpty { "·" }
        }
}

/** GET /api/sessions/:id/git-status 响应（服务端 GitStatusResult 子集）。 */
data class GitStatusResult(
    val isGit: Boolean,
    val branch: String?,
    val modifiedCount: Int?,
    val files: List<GitFileEntry>?,
    val initialCommit: Boolean?,
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val lastCommitShortHash: String?,
    val lastCommitSubject: String?,
    val latestTag: String?,
    val hasSubmodule: Boolean?,
    val error: String?,
) {
    companion object {
        fun parse(o: JSONObject): GitStatusResult {
            val files = o.arr("files")?.parseEach { f ->
                GitFileEntry(
                    path = f.str("path") ?: "",
                    status = f.str("status") ?: "",
                    isSubmodule = f.bool("isSubmodule"),
                )
            }
            val lastCommit = o.obj("lastCommit")
            return GitStatusResult(
                isGit = o.bool("isGit") ?: false,
                branch = o.str("branch"),
                modifiedCount = o.int("modifiedCount"),
                files = files,
                initialCommit = o.bool("initialCommit"),
                upstream = o.str("upstream"),
                ahead = o.int("ahead"),
                behind = o.int("behind"),
                lastCommitShortHash = lastCommit?.str("shortHash"),
                lastCommitSubject = lastCommit?.str("subject"),
                latestTag = o.str("latestTag"),
                hasSubmodule = o.bool("hasSubmodule"),
                error = o.str("error"),
            )
        }
    }
}

/** POST /api/sessions/:id/generate-commit-message 响应：AI 撰写的 message 与推荐 tag（不提交）。 */
data class GenerateCommitMessageResult(
    val message: String?,
    val suggestedTag: String?,
) {
    companion object {
        fun parse(o: JSONObject): GenerateCommitMessageResult = GenerateCommitMessageResult(
            message = o.str("message"),
            suggestedTag = o.str("suggestedTag"),
        )
    }
}

/** POST /api/sessions/:id/git/push 响应。部分失败时 HTTP 仍是 200，error 带原因。 */
data class GitPushResult(
    val ok: Boolean,
    val pushedCommits: Boolean?,
    val pushedTags: Boolean?,
    val error: String?,
) {
    companion object {
        fun parse(o: JSONObject): GitPushResult = GitPushResult(
            ok = o.bool("ok") ?: false,
            pushedCommits = o.bool("pushedCommits"),
            pushedTags = o.bool("pushedTags"),
            error = o.str("error"),
        )
    }
}

/** POST /api/sessions/:id/quick-commit 响应。 */
data class QuickCommitResult(
    val ok: Boolean,
    val commitHash: String?,
    val commitMessage: String?,
    val tagName: String?,
    val pushed: Boolean?,
    val pushError: String?,
    val submoduleCommitCount: Int?,
) {
    companion object {
        fun parse(o: JSONObject): QuickCommitResult {
            val commit = o.obj("commit")
            return QuickCommitResult(
                ok = o.bool("ok") ?: false,
                commitHash = commit?.str("hash"),
                commitMessage = commit?.str("message"),
                tagName = o.obj("tag")?.str("name"),
                pushed = o.bool("pushed"),
                pushError = o.str("pushError"),
                submoduleCommitCount = o.arr("submoduleCommits")?.length(),
            )
        }
    }
}
