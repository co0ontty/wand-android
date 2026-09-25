package com.wand.app.data

import com.wand.app.WandLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** REST 错误：status 为 null 表示网络层失败。message 面向用户（中文）。 */
class WandApiException(val status: Int?, message: String) : Exception(message)

/**
 * wand 服务端 REST 客户端 —— 对称 iOS 端 WandAPI.swift。
 * 复用当前 endpoint 的 WandHttp client（自签证书放行 + endpoint 独立 CookieJar），
 * 登录 cookie 自动携带；
 * 遇到 401 时用存储的 appToken 重新登录一次再重试。
 */
class WandApi(baseUrl: String, val token: String?) : MissionsPort, WorkspacePort, TaskBoardPort {

    companion object {
        /**
         * 结构化聊天首屏的块级窗口预算：与 Web/iOS 一致，只拉最近这么多内容块。
         * 单条 turn 上百块、1MB 级的长任务全靠它把首屏载荷压到几十 KB。
         */
        const val CHAT_BLOCK_WINDOW = 60
    }

    val baseUrl: String = WandHttp.normalizeBaseUrl(baseUrl)
    private val client = WandHttp.clientFor(this.baseUrl)
    private val taskMutations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val taskChanges = taskMutations.asSharedFlow()

    // MARK: - 基础请求

    private fun buildRequest(method: String, path: String, body: JSONObject?): Request {
        val builder = Request.Builder().url("$baseUrl$path")
        if (body != null) {
            builder.method(method, body.toString().toRequestBody("application/json".toMediaType()))
        } else if (method == "POST" || method == "PUT") {
            builder.method(method, "{}".toRequestBody("application/json".toMediaType()))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private val longTimeoutClient by lazy {
        client.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private fun execute(request: Request, timeoutSec: Int): Pair<Int, String> {
        val requestClient = when (timeoutSec) {
            30 -> client
            180 -> longTimeoutClient
            else -> client.newBuilder()
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
        }
        // 用 System.nanoTime 而不是 SystemClock：REST 客户端是被 JVM 单测直接驱动的。
        val startedAt = System.nanoTime()
        requestClient.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            // 带上响应体大小：会话首屏慢/卡大多能直接从这条日志看出来（长任务单条 turn
            // 上百块时曾是 MB 级），否则只能算时间猜原因。
            val sizeLabel = " · ${text.length}B"
            if (response.code in 200..299) {
                WandLog.d(TAG, "${request.method} ${request.url.encodedPath} → ${response.code} (${elapsed}ms)$sizeLabel")
            } else {
                WandLog.w(TAG, "${request.method} ${request.url.encodedPath} → ${response.code} (${elapsed}ms) ${text.take(300)}")
            }
            return response.code to text
        }
    }

    private fun IOException.toApiException() =
        WandApiException(null, "网络错误：${message ?: "请求失败"}")

    /** 网络层失败统一转成面向用户的 WandApiException。 */
    private fun executeOrThrow(request: Request, timeoutSec: Int): Pair<Int, String> =
        try {
            execute(request, timeoutSec)
        } catch (e: IOException) {
            WandLog.e(TAG, "网络错误 ${request.method} ${request.url.encodedPath}：${e.message}", e)
            throw e.toApiException()
        }

    /** 执行一次 HTTP 请求并处理 401 自动重登。 */
    private suspend fun executeWithRetry(request: Request, timeoutSec: Int = 30): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val (code, text) = executeOrThrow(request, timeoutSec)
            if (code != 401 || token.isNullOrEmpty()) return@withContext code to text
            WandLog.i(TAG, "收到 401，用 appToken 重新登录后重试")
            try {
                WandAuth.loginWithToken(baseUrl, token, client)
            } catch (e: Exception) {
                WandLog.w(TAG, "重新登录失败：${e.message}", e)
                throw WandApiException(401, "登录已失效，请重新连接")
            }
            executeOrThrow(request, timeoutSec)
        }

    /** 带 401 自动重登的请求入口。返回响应 body 字符串。 */
    private suspend fun requestData(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutSec: Int = 30,
    ): String {
        val request = buildRequest(method, path, body)
        val (code, text) = executeWithRetry(request, timeoutSec)
        if (code !in 200..299) {
            if (code == 401) throw WandApiException(401, "登录已失效，请重新连接")
            val serverError = runCatching { JSONObject(text).str("error") }.getOrNull()
            val message = serverError?.takeIf { it.isNotEmpty() } ?: "服务器返回 $code"
            WandLog.w(TAG, "$method $path 失败：$message")
            throw WandApiException(code, message)
        }
        if (changesTaskHierarchy(method, path)) taskMutations.tryEmit(Unit)
        return text
    }

    /** 服务端响应不是合法 JSON 时统一报错，不用每个封装函数各写一遍 try/catch。 */
    private inline fun <T> parseResponse(text: String, parse: (String) -> T): T =
        try {
            parse(text)
        } catch (e: Exception) {
            WandLog.e(TAG, "响应解析失败：${e.message} ${text.take(200)}", e)
            throw WandApiException(null, "响应解析失败：${e.message}")
        }

    private suspend fun requestObject(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutSec: Int = 30,
    ): JSONObject = parseResponse(requestData(method, path, body, timeoutSec)) { JSONObject(it) }

    private suspend fun requestArray(method: String, path: String): JSONArray =
        parseResponse(requestData(method, path)) { JSONArray(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // MARK: - 会话

    /** Returns all managed sessions for notification state, without session-list pagination. */
    suspend fun listSessions(): List<SessionSnapshot> =
        SessionSnapshot.parseList(requestArray("GET", "/api/sessions"))

    suspend fun getSession(id: String, blockBudget: Int? = CHAT_BLOCK_WINDOW): SessionSnapshot =
        SessionSnapshot.parse(
            requestObject(
                "GET",
                "/api/sessions/$id?format=chat" +
                    (blockBudget?.let { "&blockBudget=$it" } ?: ""),
            ),
        )

    /** 历史消息分页：返回完整历史的 [offset, offset+limit) 段 + 总数。 */
    suspend fun fetchMessages(id: String, offset: Int, limit: Int): MessagesPage =
        MessagesPage.parse(requestObject("GET", "/api/sessions/$id/messages?offset=$offset&limit=$limit"))

    /**
     * 块级翻页（对齐 iOS fetchEarlierBlocks）：取该 turn 里 `blockOffset` 之前的一段块，
     * 用于把首屏被块级窗口切掉的头部按页补回来。
     */
    suspend fun fetchEarlierBlocks(
        id: String,
        turn: Int,
        blockOffset: Int,
        blockLimit: Int,
    ): BlocksPage = BlocksPage.parse(
        requestObject(
            "GET",
            "/api/sessions/${encode(id)}/messages?turn=$turn" +
                "&blockOffset=$blockOffset&blockLimit=$blockLimit",
        ),
    )

    /** 按需取回被消息窗口截断的完整 tool_result 内容。 */
    suspend fun fetchToolContent(id: String, toolUseId: String): ContentBlock.ToolResult {
        val response = requestObject(
            "GET",
            "/api/sessions/${encode(id)}/tool-content/${encode(toolUseId)}",
        )
        val normalized = JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", response.str("tool_use_id") ?: toolUseId)
            .put("content", response.opt("content") ?: "")
            .put("is_error", response.bool("is_error") ?: false)
        return ContentBlock.parse(normalized) as? ContentBlock.ToolResult
            ?: throw WandApiException(null, "工具结果解析失败")
    }

    suspend fun sendInput(
        id: String,
        input: String,
        view: String? = null,
        shortcutKey: String? = null,
        respondImmediately: Boolean = false,
    ): SessionSnapshot {
        val body = JSONObject().put("input", input)
        if (view != null) body.put("view", view)
        if (shortcutKey != null) body.put("shortcutKey", shortcutKey)
        if (respondImmediately) body.put("respondImmediately", true)
        return SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/input", body))
    }

    /**
     * PTY 写入只需要服务端确认。对齐 iOS `sendPtyInputChunk`：
     * `responseMode=accepted` 避免每个按键/回车都下载并解码整份会话快照。
     * 旧服务端会忽略该字段并仍返回 snapshot，requestData 同样当成 2xx。
     */
    suspend fun sendPtyInputChunk(
        id: String,
        input: String,
        view: String,
        shortcutKey: String? = null,
    ) {
        val body = JSONObject()
            .put("input", input)
            .put("view", view)
            .put("responseMode", "accepted")
        if (shortcutKey != null) body.put("shortcutKey", shortcutKey)
        requestData("POST", "/api/sessions/$id/input", body)
    }

    suspend fun stopSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/stop"))

    // MARK: - 排队消息（仅结构化会话）

    /** 由服务端按 index 摘掉队列项并立即发送，避免客户端与自动 flush 重复发送。 */
    suspend fun promoteQueued(id: String, index: Int, expectedText: String): SessionSnapshot {
        val body = JSONObject()
            .put("expectedText", expectedText)
            .put("idempotencyKey", java.util.UUID.randomUUID().toString())
        return SessionSnapshot.parse(
            requestObject("POST", "/api/structured-sessions/$id/queued/$index/promote", body)
        )
    }

    suspend fun editQueued(id: String, index: Int, expectedText: String, text: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("PATCH", "/api/structured-sessions/$id/queued/$index",
            JSONObject().put("expectedText", expectedText).put("text", text)))

    /** 删除第 index 条排队消息。 */
    suspend fun deleteQueued(id: String, index: Int) {
        requestData("DELETE", "/api/structured-sessions/$id/queued/$index")
    }

    /** 清空全部排队消息。 */
    suspend fun clearQueued(id: String) {
        requestData("DELETE", "/api/structured-sessions/$id/queued")
    }

    suspend fun resumeSession(id: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/resume"))

    // MARK: - 模型与思考深度

    suspend fun models(): ModelsResponse =
        ModelsResponse.parse(requestObject("GET", "/api/models"))

    /** model 传 null 表示恢复默认（服务端收 JSON null）。 */
    suspend fun setModel(id: String, model: String?): SessionSnapshot {
        val body = JSONObject().put("model", model ?: JSONObject.NULL)
        return SessionSnapshot.parse(requestObject("POST", "/api/sessions/$id/model", body))
    }

    suspend fun setThinkingEffort(id: String, thinkingEffort: String): SessionSnapshot =
        SessionSnapshot.parse(
            requestObject(
                "POST",
                "/api/sessions/$id/thinking-effort",
                JSONObject().put("thinkingEffort", thinkingEffort),
            )
        )

    suspend fun setMode(id: String, mode: String): SessionSnapshot =
        SessionSnapshot.parse(
            requestObject(
                "POST",
                "/api/sessions/$id/mode",
                JSONObject().put("mode", mode),
            )
        )

    // MARK: - 附件上传

    /**
     * 上传附件：multipart/form-data，字段名 files，服务端限制单文件 10MB、单次最多 5 个。
     * 入参是 (文件名, 字节) 对 —— UI 层负责从 content Uri 读出字节。
     */
    suspend fun uploadAttachments(
        id: String,
        files: List<Pair<String, ByteArray>>,
    ): List<UploadedFile> {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((name, bytes) in files.take(5)) {
            if (bytes.size > 10 * 1024 * 1024) {
                throw WandApiException(null, "$name 超过 10 MB")
            }
            multipart.addFormDataPart(
                "files",
                name,
                bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
        }
        val request = Request.Builder()
            .url("$baseUrl/api/sessions/$id/upload")
            .post(multipart.build())
            .build()
        val (code, text) = executeWithRetry(request, timeoutSec = 60)
        if (code !in 200..299) throw WandApiException(code, "附件上传失败")
        return parseResponse(text) { UploadedFile.parseList(JSONObject(it)) }
    }

    // MARK: - 权限

    suspend fun resolveEscalation(
        sessionId: String,
        requestId: String,
        resolution: String,
    ): SessionSnapshot = SessionSnapshot.parse(
        requestObject(
            "POST",
            "/api/sessions/$sessionId/escalations/${encode(requestId)}/resolve",
            JSONObject().put("resolution", resolution),
        )
    )

    suspend fun approvePermission(sessionId: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$sessionId/approve-permission"))

    suspend fun denyPermission(sessionId: String): SessionSnapshot =
        SessionSnapshot.parse(requestObject("POST", "/api/sessions/$sessionId/deny-permission"))

    // MARK: - 新建会话

    /** 将「新建会话」默认项持久化到服务端配置。 */
    suspend fun updateNewSessionDefaults(
        mode: String? = null,
        model: String? = null,
        modelProvider: String = "claude",
        thinkingEffort: String? = null,
        defaultProvider: String? = null,
        defaultSessionKind: String? = null,
        defaultTaskWorktree: Boolean? = null,
    ) {
        val body = JSONObject()
        if (mode != null) body.put("defaultMode", mode)
        if (model != null) {
            // 老服务端只认 provider 专属字段，新服务端读 defaultModels 映射；两边同时写。
            val (legacyKey, canonicalProvider) = when (modelProvider) {
                "codex" -> "defaultCodexModel" to "codex"
                "opencode" -> "defaultOpenCodeModel" to "opencode"
                "qoder" -> "defaultQoderModel" to "qoder"
                "grok" -> "defaultGrokModel" to "grok"
                "pi" -> "defaultPiModel" to "pi"
                else -> "defaultModel" to "claude"
            }
            body.put(legacyKey, model)
            body.put("defaultModels", JSONObject().put(canonicalProvider, model))
        }
        if (thinkingEffort != null) body.put("defaultThinkingEffort", thinkingEffort)
        if (defaultProvider != null) body.put("defaultProvider", defaultProvider)
        if (defaultSessionKind != null) body.put("defaultSessionKind", defaultSessionKind)
        if (defaultTaskWorktree != null) body.put("defaultTaskWorktree", defaultTaskWorktree)
        requestData("POST", "/api/settings/config", body)
    }

    // MARK: - Missions

    override suspend fun defaultMissionCwd(): String =
        requestObject("GET", "/api/config").str("defaultCwd").orEmpty()

    override suspend fun fetchMissions(): List<MissionInfo> =
        MissionInfo.parseList(requestObject("GET", "/api/missions").arr("missions"))

    override suspend fun createMission(
        title: String?,
        prompt: String,
        cwd: String,
        providers: List<String>,
        taskId: String?,
        baseRef: String?,
        sharedDirectories: List<String>,
        copyPaths: List<String>,
    ): MissionInfo {
        val body = createMissionRequestBody(
            title = title,
            prompt = prompt,
            cwd = cwd,
            providers = providers,
            taskId = taskId,
            baseRef = baseRef,
            sharedDirectories = sharedDirectories,
            copyPaths = copyPaths,
        )
        return MissionInfo.parse(requestObject("POST", "/api/missions", body, timeoutSec = 180))
    }

    override suspend fun fetchMissionDiff(missionId: String, attemptId: String): MissionDiff =
        MissionDiff.parse(requestObject(
            "GET",
            "/api/missions/${encode(missionId)}/attempts/${encode(attemptId)}/diff",
        ))

    override suspend fun addMissionReviewComment(
        missionId: String,
        attemptId: String,
        filePath: String,
        line: Int?,
        side: String,
        body: String,
    ): MissionReviewComment {
        val payload = JSONObject()
            .put("filePath", filePath)
            .put("side", side)
            .put("body", body)
        if (line != null) payload.put("line", line)
        return MissionReviewComment.parseList(JSONArray().put(requestObject(
            "POST",
            "/api/missions/${encode(missionId)}/attempts/${encode(attemptId)}/comments",
            payload,
        ))).first()
    }

    override suspend fun sendMissionReview(missionId: String, attemptId: String): List<MissionReviewComment> =
        MissionReviewComment.parseList(requestObject(
            "POST",
            "/api/missions/${encode(missionId)}/attempts/${encode(attemptId)}/review/send",
            JSONObject(),
        ).arr("comments"))

    override suspend fun archiveMission(missionId: String): MissionInfo =
        MissionInfo.parse(requestObject("POST", "/api/missions/${encode(missionId)}/archive"))

    // MARK: - Git 快速提交

    suspend fun gitStatus(sessionId: String): GitStatusResult =
        GitStatusResult.parse(requestObject("GET", "/api/sessions/$sessionId/git-status"))

    /**
     * 快速提交：customMessage 为 null 时服务端用 AI 根据 staged diff 生成；
     * autoTag 时再让 AI 推荐下一个语义化版本号。AI 链路 + push 较慢，超时放宽到 180s。
     */
    suspend fun quickCommit(
        sessionId: String,
        customMessage: String?,
        tag: String?,
        autoTag: Boolean,
        push: Boolean,
        submodule: Boolean,
    ): QuickCommitResult {
        val body = JSONObject()
            .put("autoMessage", customMessage == null)
            .put("autoTag", autoTag)
            .put("push", push)
            .put("submodule", submodule)
        if (customMessage != null) body.put("customMessage", customMessage)
        if (!tag.isNullOrEmpty()) body.put("tag", tag)
        return QuickCommitResult.parse(
            requestObject("POST", "/api/sessions/$sessionId/quick-commit", body, timeoutSec = 180)
        )
    }

    /** AI 预生成 commit message 与推荐 tag（只生成不提交，对应网页版「AI」按钮）。 */
    suspend fun generateCommitMessage(sessionId: String): GenerateCommitMessageResult =
        GenerateCommitMessageResult.parse(
            requestObject(
                "POST",
                "/api/sessions/$sessionId/generate-commit-message",
                timeoutSec = 180,
            )
        )

    /** 补推送：把已有 commit / tag 推到远端；submodule 为 true 时递归推送各 submodule。 */
    suspend fun gitPush(
        sessionId: String,
        pushCommits: Boolean,
        pushTags: Boolean,
        submodule: Boolean,
        tag: String?,
    ): GitPushResult {
        val body = JSONObject()
            .put("pushCommits", pushCommits)
            .put("pushTags", pushTags)
            .put("submodule", submodule)
        if (!tag.isNullOrEmpty()) body.put("tag", tag)
        return GitPushResult.parse(
            requestObject("POST", "/api/sessions/$sessionId/git/push", body, timeoutSec = 180)
        )
    }

    // MARK: - 任务管理（WandTask 看板）

    override suspend fun listBoardTasks(workspaceId: String?): List<BoardTask> {
        val query = workspaceId?.takeIf { it.isNotBlank() }?.let { "?workspaceId=${encode(it)}" }.orEmpty()
        return BoardTask.parseList(requestArray("GET", "/api/wand-tasks$query"))
    }

    override suspend fun getBoardTask(id: String): BoardTask? =
        BoardTask.parse(requestObject("GET", "/api/wand-tasks/${encode(id)}"))

    override suspend fun createBoardTask(
        title: String,
        description: String,
        status: String,
        priority: String,
        workspaceId: String?,
        agent: BoardTaskAgent?,
    ): BoardTask = BoardTask.parse(
        requestObject("POST", "/api/wand-tasks", createBoardTaskBody(title, description, status, priority, workspaceId, agent)),
    ) ?: throw WandApiException(500, "创建任务响应无效。")

    override suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask =
        BoardTask.parse(requestObject("PATCH", "/api/wand-tasks/${encode(id)}", body))
            ?: throw WandApiException(500, "更新任务响应无效。")

    override suspend fun deleteBoardTask(id: String) {
        requestData("DELETE", "/api/wand-tasks/${encode(id)}")
    }

    override suspend fun dispatchBoardTask(
        id: String,
        agent: BoardTaskAgent,
        prompt: String?,
        workspaceId: String?,
    ): BoardDispatchResult =
        BoardDispatchResult.parse(
            requestObject(
                "POST",
                "/api/wand-tasks/${encode(id)}/dispatch",
                JSONObject().put("agent", agent.toJson()).also { body ->
                    if (!prompt.isNullOrBlank()) body.put("prompt", prompt)
                    if (workspaceId !== UNSET_WORKSPACE) {
                        if (workspaceId.isNullOrBlank()) body.put("workspaceId", JSONObject.NULL)
                        else body.put("workspaceId", workspaceId)
                    }
                },
            ),
        )

    override suspend fun listBoardWorkspaces(): List<Workspace> = listWorkspaces()

    override suspend fun boardModels(): ModelsResponse = models()

    override suspend fun boardTaskAgentDefaults(): BoardTaskAgent =
        BoardTaskAgent.parse(requestObject("GET", "/api/wand-task-agent-defaults"))
            ?: BoardTaskAgent.default()

    override suspend fun saveBoardTaskAgentDefaults(agent: BoardTaskAgent): BoardTaskAgent =
        BoardTaskAgent.parse(requestObject("PUT", "/api/wand-task-agent-defaults", agent.toJson()))
            ?: agent

    // MARK: - 工作空间（项目）与任务

    override suspend fun listWorkspaces(): List<Workspace> =
        Workspace.parseList(requestArray("GET", "/api/workspaces"))

    override suspend fun createWorkspace(name: String, cwd: String): Workspace =
        Workspace.parse(
            requestObject(
                "POST",
                "/api/workspaces",
                JSONObject().put("name", name).put("cwd", cwd),
            ),
        ) ?: throw WandApiException(500, "创建项目响应无效。")

    override suspend fun workspaceWorktreeOverview(workspaceId: String): WorkspaceWorktreeOverview =
        WorkspaceWorktreeOverview.parse(
            requestObject("GET", "/api/workspaces/${encode(workspaceId)}/worktrees"),
        ) ?: throw WandApiException(500, "Worktree 概览响应无效。")

    override suspend fun startWorktreeMergeAgent(
        workspace: Workspace,
        provider: String,
        prompt: String,
    ): SessionSnapshot {
        val command = WandProvider.cliCommandFor(provider)
        val body = JSONObject()
            .put("command", command)
            .put("provider", provider)
            .put("cwd", workspace.cwd)
            .put("mode", "managed")
            .put("initialInput", prompt)
            .put("sessionSource", "interactive")
            .put("workspaceId", workspace.id)
        return SessionSnapshot.parse(requestObject("POST", "/api/commands", body))
    }

    override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> =
        WorkspaceTask.parseList(requestArray("GET", "/api/workspaces/${encode(workspaceId)}/tasks"))

    override suspend fun createWorkspaceTask(
        workspaceId: String,
        name: String,
        baseRef: String?,
        worktree: Boolean?,
        cwd: String?,
        description: String?,
    ): WorkspaceTaskCreation {
        val body = createWorkspaceTaskRequestBody(name, baseRef, worktree, cwd, description)
        return WorkspaceTaskCreation.parse(
            requestObject("POST", "/api/workspaces/${encode(workspaceId)}/tasks", body),
        ) ?: throw WandApiException(500, "任务创建响应无效。")
    }

    override suspend fun createStandaloneTask(
        name: String,
        cwd: String?,
        worktree: Boolean?,
        description: String?,
    ): WorkspaceTaskCreation {
        val body = createStandaloneTaskRequestBody(name, cwd, worktree, description)
        return WorkspaceTaskCreation.parse(
            requestObject("POST", "/api/tasks", body),
        ) ?: throw WandApiException(500, "任务创建响应无效。")
    }

    override suspend fun listTaskGroups(): List<TaskDirectoryGroup> =
        listTaskGroupsPage().groups

    override suspend fun listTaskGroupsPage(revision: String?): TaskGroupsPage {
        val path = "/api/tasks?revision=${encode(revision ?: "")}"
        return TaskGroupsPage.parse(requestData("GET", path))
    }

    override suspend fun listDirectory(path: String): DirectoryListing =
        DirectoryListing.parse(requestObject("GET", "/api/directory?q=${encode(path)}"))

    override suspend fun taskDefaultCwd(): String? =
        requestObject("GET", "/api/config").str("defaultCwd")?.takeIf { it.isNotBlank() }

    override suspend fun recentTaskPaths(): List<RecentPath> = recentPaths()

    override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask {
        val body = JSONObject().put("name", name)
        return WorkspaceTask.parse(
            requestObject("PATCH", "/api/workspace-tasks/${encode(taskId)}", body),
        ) ?: throw WandApiException(500, "任务重命名响应无效。")
    }

    override suspend fun renameWorkspace(workspaceId: String, name: String): Workspace =
        Workspace.parse(
            requestObject(
                "PATCH",
                "/api/workspaces/${encode(workspaceId)}",
                JSONObject().put("name", name),
            ),
        ) ?: throw WandApiException(500, "重命名项目响应无效。")

    override suspend fun deleteWorkspace(workspaceId: String, cascade: Boolean) {
        requestData(
            "DELETE",
            "/api/workspaces/${encode(workspaceId)}?cascade=${if (cascade) "1" else "0"}",
        )
    }

    override suspend fun saveWorkspaceGroupOrder(ids: List<String>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        try {
            requestData("PUT", "/api/workspaces/order", JSONObject().put("ids", array))
        } catch (error: WandApiException) {
            if (error.status == 404) {
                throw WandApiException(404, "当前服务不支持保存工作区顺序，请先更新服务端")
            }
            throw error
        }
    }

    override suspend fun renameSessionDirectory(cwd: String, name: String?) {
        requestData(
            "PUT",
            "/api/session-directories/name",
            JSONObject().put("path", cwd).put("name", name ?: JSONObject.NULL),
        )
    }

    override suspend fun deleteWorkspaceTask(taskId: String) {
        requestData("DELETE", "/api/workspace-tasks/${encode(taskId)}?cascade=1")
    }

    override suspend fun archiveWorkspaceTask(taskId: String): WorkspaceTask =
        WorkspaceTask.parse(
            requestObject("POST", "/api/workspace-tasks/${encode(taskId)}/archive"),
        ) ?: throw WandApiException(500, "归档任务响应无效。")

    override suspend fun clearWorkspaceTaskSessions(taskId: String): Int {
        val sessionIds = workspaceTask(taskId).sessions.map { it.id }.distinct()
        return deleteWorkspaceSessions(sessionIds)
    }

    override suspend fun deleteWorkspaceSessions(sessionIds: List<String>): Int {
        val ids = sessionIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return 0
        val response = requestObject(
            "POST",
            "/api/sessions/batch-delete",
            JSONObject().put("sessionIds", JSONArray(ids)),
        )
        return response.int("deleted") ?: 0
    }

    override suspend fun moveWorkspaceSession(taskId: String, sessionId: String) {
        requestObject("POST", "/api/workspace-tasks/${encode(taskId)}/sessions", JSONObject().put("sessionId", sessionId))
    }

    override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail {
        val detail = WorkspaceTaskDetail.parse(requestObject("GET", "/api/workspace-tasks/${encode(taskId)}"))
            ?: throw WandApiException(404, "未找到该任务。")
        return detail
    }

    override suspend fun saveWorkspaceTaskLayout(
        taskId: String,
        layout: TaskWindowLayout?,
    ): TaskWindowLayout? {
        val body = JSONObject().put("layout", layout?.toJsonObject() ?: JSONObject.NULL)
        val response = requestObject("PUT", "/api/workspace-tasks/${encode(taskId)}/layout", body)
        // 服务端返回 { ok, layout }；layout 已经过 sanitizeTaskLayout 清洗。
        return TaskWindowLayout.parse(response.opt("layout"))
    }

    /**
     * 任务内选择 Agent：结构化走 /api/structured-sessions，PTY/空白终端走 /api/commands。
     * 绑定 workspaceId/workspaceTaskId 和任务 cwd。不调用 updateNewSessionDefaults。
     */
    override suspend fun createWorkspaceTaskWindow(
        target: WorkspaceSessionTarget,
        binding: WorkspaceBinding,
        kind: WorkspaceSessionKind,
        prompt: String?,
        model: String?,
        thinkingEffort: String?,
    ): SessionSnapshot {
        val request = createWorkspaceTaskWindowRequest(target, binding, kind, prompt, model, thinkingEffort)
        return SessionSnapshot.parse(requestObject("POST", request.path, request.body))
    }

    // MARK: - 目录与配置

    suspend fun recentPaths(): List<RecentPath> =
        RecentPath.parseList(requestArray("GET", "/api/recent-paths"))

    override suspend fun serverConfig(): ServerConfigInfo =
        ServerConfigInfo.parse(requestObject("GET", "/api/config"))

    override suspend fun updateCreationDefaults(
        defaultProvider: String?,
        defaultSessionKind: String?,
        defaultTaskWorktree: Boolean?,
    ) {
        updateNewSessionDefaults(
            defaultProvider = defaultProvider,
            defaultSessionKind = defaultSessionKind,
            defaultTaskWorktree = defaultTaskWorktree,
        )
    }
}

/** 诊断日志 tag：与设置页导出日志里的分类对应。 */
private const val TAG = "api"
