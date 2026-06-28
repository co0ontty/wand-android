package com.wand.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.GitStatusResult
import com.wand.app.data.WandApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Git 快捷提交面板状态机 —— 对齐网页版 git-commit.ts 的语义：
 *   - message 留空 → autoMessage（服务端 AI 根据 staged diff 撰写）
 *   - 带 tag 动作且 tag 留空 → autoTag（AI 推荐下一个语义化版本号）
 *   - 动作字符串与网页一致：commit / commit-tag / commit-push / commit-tag-push，
 *     submodule 是正交 scope flag（不进动作字符串）
 *   - push 成功 → 收面板 + toast；未 push → 留在结果面板，可补「Push & Close」
 */
class QuickCommitStore(
    val sessionId: String,
    private val api: WandApi,
    private val onToast: (String) -> Unit = {},
) : ScopedStore() {

    var status by mutableStateOf<GitStatusResult?>(null)
        private set
    var statusLoading by mutableStateOf(false)
        private set

    var panelOpen by mutableStateOf(false)
        private set

    // 表单（直接由 UI 双向编辑）
    var messageDraft by mutableStateOf("")
    var tagDraft by mutableStateOf("")

    /** 用户手动改过 tag 后，AI 推荐不再覆盖它（对齐网页 tagEdited）。 */
    var tagEdited by mutableStateOf(false)

    var generating by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set

    /** 本次提交是否在等 AI 生成（决定 busy 文案）。 */
    var autoGenerating by mutableStateOf(false)
        private set

    /** 本次提交是否纳入 submodule（busy 文案 + 补推时复用）。 */
    var submoduleIntent by mutableStateOf(false)
        private set
    var pushing by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set
    var pushError by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<QuickCommitOutcome?>(null)
        private set

    private var lastFetchAt = 0L
    private var entryFeedbackToken = 0

    val inFlight: Boolean get() = submitting || pushing
    val entryLocked: Boolean get() = inFlight || entryPhase != QuickCommitEntryPhase.Idle

    var entryPhase by mutableStateOf(QuickCommitEntryPhase.Idle)
        private set

    // MARK: - git 状态

    /** 1s 内的重复请求直接吃掉（对齐网页 loadGitStatus 的去抖）。 */
    fun loadStatus(force: Boolean = false) {
        if (statusLoading) return
        val now = SystemClock.elapsedRealtime()
        if (!force && status != null && now - lastFetchAt < 1_000) return
        statusLoading = true
        scope.launch {
            try {
                status = api.gitStatus(sessionId)
                lastFetchAt = SystemClock.elapsedRealtime()
            } catch (_: Exception) {
                // 静默失败：badge 不显示即可，面板内有空态文案。
            }
            statusLoading = false
        }
    }

    // MARK: - 面板开合

    fun openPanel() {
        messageDraft = ""
        tagDraft = ""
        tagEdited = false
        generating = false
        submitting = false
        autoGenerating = false
        submoduleIntent = false
        pushing = false
        error = null
        pushError = null
        result = null
        panelOpen = true
        loadStatus(force = true)
    }

    fun closePanel() {
        if (inFlight) return
        panelOpen = false
    }

    // MARK: - AI 生成（只填表单，不提交）

    fun generateAI() {
        if (generating || submitting) return
        generating = true
        error = null
        scope.launch {
            try {
                val r = api.generateCommitMessage(sessionId)
                val aiMessage = r.message?.trim().orEmpty()
                val aiTag = r.suggestedTag?.trim().orEmpty()
                // 只在空白时填 message，绝不覆盖用户已输入的内容。
                if (messageDraft.isBlank() && aiMessage.isNotEmpty()) messageDraft = aiMessage
                if (aiTag.isNotEmpty() && !tagEdited) tagDraft = aiTag
            } catch (e: Exception) {
                error = e.message ?: "AI 生成失败"
            }
            generating = false
        }
    }

    // MARK: - 提交

    fun submit(action: String, includeSubmodule: Boolean) {
        if (submitting) return
        val withTag = action == "commit-tag" || action == "commit-tag-push"
        val push = action == "commit-push" || action == "commit-tag-push"
        val userTag = if (withTag) tagDraft.trim() else ""
        val message = messageDraft.trim()
        val autoMessage = message.isEmpty()
        val before = status

        submitting = true
        beginEntryLoading()
        panelOpen = false
        submoduleIntent = includeSubmodule
        autoGenerating = autoMessage || (withTag && userTag.isEmpty())
        error = null
        pushError = null
        result = null
        scope.launch {
            try {
                val r = api.quickCommit(
                    sessionId = sessionId,
                    customMessage = if (autoMessage) null else message,
                    tag = userTag.ifEmpty { null },
                    autoTag = withTag && userTag.isEmpty(),
                    push = push,
                    submodule = includeSubmodule,
                )
                val outcome = QuickCommitOutcome(
                    includeSubmodule = includeSubmodule,
                    pushed = r.pushed == true,
                    pushError = r.pushError?.takeIf { it.isNotEmpty() },
                    commitHash = r.commitHash?.take(7).orEmpty(),
                    commitMessage = r.commitMessage ?: message,
                    tagName = r.tagName.orEmpty(),
                    oldTag = before?.latestTag.orEmpty(),
                    oldCommitHash = before?.lastCommitShortHash.orEmpty(),
                    oldCommitSubject = before?.lastCommitSubject.orEmpty(),
                    submoduleCount = r.submoduleCommitCount ?: 0,
                )
                result = null
                val message = buildString {
                    append(outcome.summaryText())
                    if (push && outcome.pushError == null) append("，已推送")
                    outcome.pushError?.let { append("，推送失败：").append(it) }
                }
                if (outcome.pushError == null) {
                    onToast(message)
                    finishEntrySuccess()
                } else {
                    pushError = outcome.pushError
                    failEntry(message)
                }
                loadStatus(force = true)
            } catch (e: Exception) {
                val message = e.message ?: "快捷提交失败"
                error = message
                failEntry(message)
            }
            submitting = false
            autoGenerating = false
        }
    }

    // MARK: - 仅推送（工作区干净但 ahead > 0 时的快捷动作）

    fun pushCommitsOnly() {
        if (inFlight) return
        pushing = true
        beginEntryLoading()
        panelOpen = false
        error = null
        pushError = null
        scope.launch {
            try {
                val res = api.gitPush(
                    sessionId = sessionId,
                    pushCommits = true,
                    pushTags = false,
                    submodule = false,
                    tag = null,
                )
                if (!res.error.isNullOrEmpty()) {
                    pushError = res.error
                    failEntry(res.error)
                } else {
                    onToast("已推送 commits")
                    finishEntrySuccess()
                    loadStatus(force = true)
                }
            } catch (e: Exception) {
                val message = e.message ?: "推送失败"
                pushError = message
                failEntry(message)
            }
            pushing = false
        }
    }

    // MARK: - 补推送（结果面板的 Push & Close）

    fun pushOnly() {
        val r = result ?: return
        if (pushing) return
        pushing = true
        beginEntryLoading()
        panelOpen = false
        pushError = null
        scope.launch {
            try {
                val res = api.gitPush(
                    sessionId = sessionId,
                    pushCommits = true,
                    pushTags = r.tagName.isNotEmpty(),
                    submodule = r.includeSubmodule,
                    tag = r.tagName.ifEmpty { null },
                )
                if (res.error != null && res.error.isNotEmpty()) {
                    pushError = res.error
                    failEntry(res.error)
                } else {
                    result = r.copy(pushed = true)
                    val parts = buildList {
                        if (res.pushedCommits == true) add("commits")
                        if (res.pushedTags == true) add("tags")
                    }
                    onToast("已推送 " + (if (parts.isEmpty()) "（无内容）" else parts.joinToString(" 和 ")))
                    finishEntrySuccess()
                    loadStatus(force = true)
                }
            } catch (e: Exception) {
                val message = e.message ?: "推送失败"
                pushError = message
                failEntry(message)
            }
            pushing = false
        }
    }

    private fun beginEntryLoading() {
        entryFeedbackToken += 1
        entryPhase = QuickCommitEntryPhase.Loading
    }

    private fun finishEntrySuccess() {
        val token = ++entryFeedbackToken
        entryPhase = QuickCommitEntryPhase.Done
        scope.launch {
            delay(1_000)
            if (entryFeedbackToken == token) {
                entryPhase = QuickCommitEntryPhase.Idle
                loadStatus(force = true)
            }
        }
    }

    private fun failEntry(message: String) {
        entryFeedbackToken += 1
        entryPhase = QuickCommitEntryPhase.Idle
        onToast(message)
        loadStatus(force = true)
    }
}

enum class QuickCommitEntryPhase {
    Idle,
    Loading,
    Done,
}

/** 一次快捷提交的结果（new 侧），old 侧字段来自提交前的 git 状态快照。 */
data class QuickCommitOutcome(
    val includeSubmodule: Boolean,
    val pushed: Boolean,
    val pushError: String?,
    val commitHash: String,
    val commitMessage: String,
    val tagName: String,
    val oldTag: String,
    val oldCommitHash: String,
    val oldCommitSubject: String,
    val submoduleCount: Int,
) {
    fun summaryText(): String {
        val subPrefix = if (submoduleCount > 0) "已先提交 $submoduleCount 个 submodule，" else ""
        return subPrefix + "已提交" +
            (if (commitHash.isNotEmpty()) " $commitHash" else "") +
            (if (tagName.isNotEmpty()) "，已打 Tag $tagName" else "")
    }
}
