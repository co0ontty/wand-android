package com.wand.app.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.GitPushResult
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
 *   - 成功后收面板 + toast；工作区干净但仍领先远端时，可从入口重新打开并补推
 *   - 请求可在面板关闭后继续执行，避免网络等待时把用户困在弹层内
 *   - 草稿跨开合保留（关闭不清，只有提交成功才消费），见 [QuickCommitDrafts]
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

    // 表单。草稿单独成块（见 [QuickCommitDrafts]），因为它要跨面板开合活下来。
    private var drafts by mutableStateOf(QuickCommitDrafts())

    var messageDraft: String
        get() = drafts.messageDraft
        set(value) {
            drafts = drafts.onMessageTyped(value)
        }
    /** 敲过的 tag 会记住「用户改过」，之后 AI 推荐不再覆写（见 [QuickCommitDrafts.onTagTyped]）。 */
    var tagDraft: String
        get() = drafts.tagDraft
        set(value) {
            drafts = drafts.onTagTyped(value)
        }
    /** 仅本次提交生效；归档由服务端按本次关联任务与项目范围执行。 */
    var archiveRelatedTasks by mutableStateOf(false)

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
        // §2.22 S12：打开只复位「本次请求」的状态，不清草稿。
        // 之前每次打开都清空 messageDraft/tagDraft，任何关闭路径 + 重开都会丢掉已输入内容。
        archiveRelatedTasks = false
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
                // 只在空白时填 message / 只在用户没改过 tag 时填 tag，规则集中在
                // [QuickCommitDrafts.onAiGenerated]，不走 set 通道（否则会被当成用户编辑）。
                drafts = drafts.onAiGenerated(r.message.orEmpty(), r.suggestedTag.orEmpty())
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
        val archiveIntent = archiveRelatedTasks

        submitting = true
        beginEntryLoading()
        // 保持面板打开：失败时用户能看到服务端错误，并直接修改 message/tag 后重试。
        // 关闭面板会让错误只剩一个短 Toast，看起来像“提交按钮没有作用”。
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
                    archiveRelatedTasks = archiveIntent,
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
                val toastMessage = buildString {
                    append(outcome.summaryText())
                    if (push && outcome.pushError == null) append("，已推送")
                    if (archiveIntent) {
                        if (r.archiveError != null) append("，归档失败：").append(r.archiveError)
                        else if (r.archivedTaskCount > 0) append("，已归档 ${r.archivedTaskCount} 个关联任务")
                        else append("，没有可归档的关联任务")
                    }
                    outcome.pushError?.let { append("，推送失败：").append(it) }
                }
                if (outcome.pushError == null) {
                    result = null
                    panelOpen = false
                    // commit 已落地 → 草稿被消费，下一次打开是空的（§2.22 验收 6）。
                    drafts = drafts.onCommitSuccess()
                    onToast(toastMessage)
                    finishEntrySuccess()
                } else {
                    // Commit/tag 已落地但 push 失败时，若面板仍开着则保留结果供用户补推；
                    // 用户已手动关闭时不重新弹出，失败信息会以 toast 呈现。
                    result = outcome
                    pushError = outcome.pushError
                    // commit 同样已经落地，草稿没有保留价值了。
                    drafts = drafts.onCommitSuccess()
                    failEntry(toastMessage)
                }
                loadStatus(force = true)
            } catch (e: Exception) {
                // 失败不清草稿：用户要能在原地改文案直接重试。
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
        error = null
        pushCommits(
            pushTags = false,
            // 父仓库的待推 commit 可能引用尚未推到远端的 submodule HEAD。
            // 此时普通 git push 会被 --recurse-submodules=check 拒绝；快捷入口
            // 应先推声明的 submodule，再推父仓库，和「Sub + Push」一致。
            submodule = status?.hasSubmodule == true,
            tag = null,
        ) {
            panelOpen = false
            onToast("已推送 commits")
        }
    }

    // MARK: - 补推送（结果面板的 Push & Close）

    fun pushOnly() {
        val r = result ?: return
        pushCommits(
            pushTags = r.tagName.isNotEmpty(),
            submodule = r.includeSubmodule,
            tag = r.tagName.ifEmpty { null },
        ) { res ->
            result = r.copy(pushed = true)
            val parts = buildList {
                if (res.pushedCommits == true) add("commits")
                if (res.pushedTags == true) add("tags")
            }
            panelOpen = false
            onToast("已推送 " + (if (parts.isEmpty()) "（无内容）" else parts.joinToString(" 和 ")))
        }
    }

    /**
     * 「仅推送」与「补推送」共用的 git push 流程：失败只置条内错误，成功交给
     * [onSuccess] 收尾（关面板 / 更新结果）。
     */
    private fun pushCommits(
        pushTags: Boolean,
        submodule: Boolean,
        tag: String?,
        onSuccess: (GitPushResult) -> Unit,
    ) {
        if (pushing) return
        pushing = true
        beginEntryLoading()
        pushError = null
        scope.launch {
            try {
                val res = api.gitPush(
                    sessionId = sessionId,
                    pushCommits = true,
                    pushTags = pushTags,
                    submodule = submodule,
                    tag = tag,
                )
                if (!res.error.isNullOrEmpty()) {
                    pushError = res.error
                    failEntry(res.error)
                } else {
                    onSuccess(res)
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

/**
 * 快捷提交表单草稿（§2.22 S12）。抽成纯类型是为了把「什么时候清」这条规则
 * 变成可单测的状态转换：关闭面板（任何路径）原样恢复，只有提交成功才清空。
 */
internal data class QuickCommitDrafts(
    val messageDraft: String = "",
    val tagDraft: String = "",
    /** 用户手动改过 tag 后，AI 推荐不再覆盖它（对齐网页 tagEdited）。 */
    val tagEdited: Boolean = false,
) {
    fun onMessageTyped(value: String): QuickCommitDrafts = copy(messageDraft = value)

    /** 表单里敲过的 tag 一律算「用户改过」，包括删空。 */
    fun onTagTyped(value: String): QuickCommitDrafts = copy(tagDraft = value, tagEdited = true)

    /** AI 只补空白位：已输入的 message 和不该被覆写的 tag 都不动。 */
    fun onAiGenerated(message: String, suggestedTag: String): QuickCommitDrafts {
        val aiMessage = message.trim()
        val aiTag = suggestedTag.trim()
        return copy(
            messageDraft = if (messageDraft.isBlank() && aiMessage.isNotEmpty()) aiMessage else messageDraft,
            tagDraft = if (tagEdited || aiTag.isEmpty()) tagDraft else aiTag,
        )
    }

    /** 提交成功：草稿成为仓库事实，表单回到初始态。 */
    fun onCommitSuccess(): QuickCommitDrafts = QuickCommitDrafts()
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
