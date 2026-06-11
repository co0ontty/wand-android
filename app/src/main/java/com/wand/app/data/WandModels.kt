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

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.bool(key: String): Boolean? =
    if (has(key) && !isNull(key) && opt(key) is Boolean) getBoolean(key) else null

internal fun JSONObject.int(key: String): Int? =
    if (has(key) && !isNull(key) && opt(key) is Number) (opt(key) as Number).toInt() else null

internal fun JSONObject.dbl(key: String): Double? =
    if (has(key) && !isNull(key) && opt(key) is Number) (opt(key) as Number).toDouble() else null

internal fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

internal fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

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
    ) : ContentBlock()
    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean,
        val truncated: Boolean,
        val subagent: SubagentMeta?,
    ) : ContentBlock()
    object Unknown : ContentBlock()

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
                )
                "tool_result" -> {
                    // content: string | Array<{type, text?, ...}> —— 数组时抽取所有 text 拼接。
                    val text = when (val content = o.opt("content")) {
                        is String -> content
                        is JSONArray -> {
                            val pieces = mutableListOf<String>()
                            for (i in 0 until content.length()) {
                                val part = content.optJSONObject(i) ?: continue
                                part.str("text")?.let { pieces.add(it) }
                            }
                            pieces.joinToString("\n")
                        }
                        else -> ""
                    }
                    ToolResult(
                        toolUseId = o.str("tool_use_id") ?: "",
                        text = text,
                        isError = o.bool("is_error") ?: false,
                        truncated = o.bool("_truncated") ?: false,
                        subagent = subagent,
                    )
                }
                else -> Unknown
            }
        }
    }
}

data class ConversationTurn(
    val role: String,
    val content: List<ContentBlock>,
) {
    companion object {
        fun parse(o: JSONObject): ConversationTurn {
            val blocks = mutableListOf<ContentBlock>()
            val arr = o.arr("content")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    // 逐块容错：单个块解析失败不拖垮整条消息。
                    val blockObj = arr.optJSONObject(i) ?: continue
                    try {
                        blocks.add(ContentBlock.parse(blockObj))
                    } catch (_: Exception) {
                        // skip bad block
                    }
                }
            }
            return ConversationTurn(role = o.str("role") ?: "assistant", content = blocks)
        }

        fun parseList(arr: JSONArray?): List<ConversationTurn>? {
            if (arr == null) return null
            val turns = mutableListOf<ConversationTurn>()
            for (i in 0 until arr.length()) {
                val turnObj = arr.optJSONObject(i) ?: continue
                try {
                    turns.add(parse(turnObj))
                } catch (_: Exception) {
                }
            }
            return turns
        }
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
    val claudeSessionId: String?,
    val messages: List<ConversationTurn>?,
    val queuedMessages: List<String>?,
    val structuredState: StructuredSessionState?,
    val pendingEscalation: EscalationRequest?,
    val permissionBlocked: Boolean?,
    val autoApprovePermissions: Boolean?,
) {
    val isStructured: Boolean get() = (sessionKind ?: "pty") == "structured"

    /** 列表标题：摘要 > 当前任务 > cwd 末段。 */
    val displayTitle: String
        get() {
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
            claudeSessionId = o.str("claudeSessionId"),
            messages = ConversationTurn.parseList(o.arr("messages")),
            queuedMessages = o.arr("queuedMessages")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            },
            structuredState = StructuredSessionState.parse(o.obj("structuredState")),
            pendingEscalation = EscalationRequest.parse(o.obj("pendingEscalation")),
            permissionBlocked = o.bool("permissionBlocked"),
            autoApprovePermissions = o.bool("autoApprovePermissions"),
        )

        fun parseList(arr: JSONArray): List<SessionSnapshot> {
            val out = mutableListOf<SessionSnapshot>()
            for (i in 0 until arr.length()) {
                val snapObj = arr.optJSONObject(i) ?: continue
                try {
                    out.add(parse(snapObj))
                } catch (_: Exception) {
                }
            }
            return out
        }
    }
}

// MARK: - WebSocket 消息

/**
 * /ws 推送的统一包络。data 的形状随 type 不同，用「超集类」WsData 承接：
 * init 的 data 就是 SessionSnapshot；output/status/ended 的 data 是其子集 + 增量字段。
 */
data class WsIncoming(
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

data class WsData(
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
    val currentTaskTitle: String?,
    val selectedModel: String?,
    val claudeSessionId: String?,
    val messages: List<ConversationTurn>?,
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
            claudeSessionId = claudeSessionId, messages = null, queuedMessages = queuedMessages,
            structuredState = structuredState, pendingEscalation = pendingEscalation,
            permissionBlocked = permissionBlocked, autoApprovePermissions = autoApprovePermissions,
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
            currentTaskTitle = o.str("currentTaskTitle"),
            selectedModel = o.str("selectedModel"),
            claudeSessionId = o.str("claudeSessionId"),
            messages = ConversationTurn.parseList(o.arr("messages")),
            queuedMessages = o.arr("queuedMessages")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            },
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
        fun parse(o: JSONObject): DirectoryListing {
            val items = mutableListOf<DirectoryItem>()
            val arr = o.arr("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { items.add(DirectoryItem.parse(it)) }
                }
            }
            return DirectoryListing(items = items, truncated = o.bool("truncated"))
        }
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
        fun parseList(arr: JSONArray): List<RecentPath> {
            val out = mutableListOf<RecentPath>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val path = p.str("path") ?: continue
                out.add(RecentPath(path, p.str("name"), p.str("lastUsedAt")))
            }
            return out
        }
    }
}

/** GET /api/config 的客户端子集。 */
data class ServerConfigInfo(
    val defaultCwd: String?,
    val defaultMode: String?,
    val currentVersion: String?,
) {
    companion object {
        fun parse(o: JSONObject): ServerConfigInfo = ServerConfigInfo(
            defaultCwd = o.str("defaultCwd"),
            defaultMode = o.str("defaultMode"),
            currentVersion = o.str("currentVersion"),
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
            val files = o.arr("files")?.let { arr ->
                val out = mutableListOf<GitFileEntry>()
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    out.add(
                        GitFileEntry(
                            path = f.str("path") ?: "",
                            status = f.str("status") ?: "",
                            isSubmodule = f.bool("isSubmodule"),
                        )
                    )
                }
                out
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
