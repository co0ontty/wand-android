package com.wand.app.ui

import androidx.compose.runtime.mutableStateMapOf
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.WorkspaceSessionSummary

const val SESSION_TITLE_MAX_LENGTH = 24
const val SESSION_DESCRIPTION_MAX_LENGTH = 120

private val GENERIC_SESSION_TITLES = setOf(
    "会话",
    "wand 会话",
    "claude",
    "codex",
    "opencode",
    "grok",
    "qoder",
    "pi",
    "终端",
)

/**
 * Live overlay for command-summarized session titles.
 * Web overlays WS titles onto the polled task list; Android does the same here
 * so the list/header can change as soon as the user submits, without waiting
 * for the 10s workspace poll.
 */
object SessionTitleStore {
    private val titles = mutableStateMapOf<String, String>()
    private val generating = mutableStateMapOf<String, Boolean>()
    private val ptyBusy = mutableStateMapOf<String, Boolean>()

    fun titleOf(id: String): String? = titles[id]?.takeIf { it.isNotEmpty() }

    fun isGenerating(id: String): Boolean = generating[id] == true

    fun ptyBusyOf(id: String): Boolean? = ptyBusy[id]

    fun apply(
        id: String,
        title: String? = null,
        generating: Boolean? = null,
        ptyBusy: Boolean? = null,
    ) {
        if (id.isEmpty()) return
        title?.let { value ->
            val cleaned = collapseWhitespace(value)
            if (cleaned.isNotEmpty()) titles[id] = cleaned
        }
        if (generating != null) this.generating[id] = generating
        if (ptyBusy != null) this.ptyBusy[id] = ptyBusy
    }

    fun clear() {
        titles.clear()
        generating.clear()
        ptyBusy.clear()
    }
}

data class PtyComposerChunk(
    val input: String,
    val view: String,
    val shortcutKey: String?,
)

/**
 * Match Web `sendTerminalChunks(..., "enter_text")`: tag both the command text
 * and the isolated CR. The server only summarizes PTY terminal input when
 * `shortcutKey=enter_text`; tagging only `"\r"` would summarize an empty string.
 */
fun ptyComposerSubmitChunks(text: String, view: String): List<PtyComposerChunk> = listOf(
    PtyComposerChunk(input = text, view = view, shortcutKey = "enter_text"),
    PtyComposerChunk(input = "\r", view = view, shortcutKey = "enter_text"),
)

fun sessionCwdLeaf(cwd: String?): String? {
    val leaf = cwd
        ?.replace('\\', '/')
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        .orEmpty()
    return leaf.takeIf { it.isNotEmpty() }
}

fun isGenericSessionTitle(
    title: String,
    blockedTitles: Collection<String> = emptyList(),
): Boolean {
    val cleaned = collapseWhitespace(title)
    if (cleaned.isEmpty() || cleaned == "会话") return true
    if (cleaned.lowercase() in GENERIC_SESSION_TITLES) return true
    return blockedTitles.any { collapseWhitespace(it).equals(cleaned, ignoreCase = true) }
}

/** Immediate title from the user's command, matching server `summarizeSessionTitleFromInput`. */
fun summarizeSessionTitleFromInput(
    input: String,
    blockedTitles: Collection<String> = emptyList(),
): String {
    val lines = input
        .splitToSequence('\n', '\r')
        .map { collapseWhitespace(it.replace(Regex("^#+\\s*"), "")) }
        .filter { it.isNotEmpty() }
        .toList()
    val fallback = clipTitle(lines.firstOrNull().orEmpty())
    for (line in lines) {
        val title = clipTitle(line)
        if (title.isNotEmpty() && !isGenericSessionTitle(title, blockedTitles)) return title
    }
    return fallback
}

fun summarizeSessionDescriptionFromInput(input: String): String =
    collapseWhitespace(input).take(SESSION_DESCRIPTION_MAX_LENGTH)

fun sessionTopicBlocklist(
    taskName: String? = null,
    workspaceName: String? = null,
    cwd: String? = null,
): List<String> = buildList {
    collapseWhitespace(taskName.orEmpty()).takeIf { it.isNotEmpty() }?.let(::add)
    collapseWhitespace(workspaceName.orEmpty()).takeIf { it.isNotEmpty() }?.let(::add)
    sessionCwdLeaf(cwd)?.let(::add)
}.distinct()

fun sessionChromeTitle(
    title: String?,
    latestUserInput: String? = null,
    liveTitle: String? = null,
    blockedTitles: Collection<String> = emptyList(),
    fallback: String = "会话",
): String {
    for (candidate in listOf(liveTitle, title)) {
        val cleaned = collapseWhitespace(candidate.orEmpty())
        if (cleaned.isNotEmpty() && !isGenericSessionTitle(cleaned, blockedTitles)) return cleaned
    }
    val fromInput = summarizeSessionTitleFromInput(latestUserInput.orEmpty(), blockedTitles)
    if (fromInput.isNotEmpty() && !isGenericSessionTitle(fromInput, blockedTitles)) return fromInput
    for (candidate in listOf(liveTitle, title)) {
        val cleaned = collapseWhitespace(candidate.orEmpty())
        if (cleaned.isNotEmpty()) return cleaned
    }
    return fallback
}

fun latestUserInputText(messages: List<ConversationTurn>): String? {
    for (turn in messages.asReversed()) {
        if (turn.role != "user") continue
        val text = collapseWhitespace(
            turn.content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text },
        )
        if (text.isNotEmpty()) return text
    }
    return null
}

fun applyProvisionalSessionTopic(
    sessionId: String,
    input: String,
    blockedTitles: Collection<String> = emptyList(),
): String? {
    val title = summarizeSessionTitleFromInput(input, blockedTitles)
    if (title.isEmpty()) return null
    SessionTitleStore.apply(sessionId, title = title, generating = true)
    return title
}

fun WorkspaceSessionSummary.withLiveTitle(): WorkspaceSessionSummary {
    val liveTitle = SessionTitleStore.titleOf(id)
    val liveBusy = SessionTitleStore.ptyBusyOf(id)
    val nextTitle = liveTitle ?: title
    val nextBusy = liveBusy ?: ptyBusy
    if (nextTitle == title && nextBusy == ptyBusy) return this
    return copy(title = nextTitle, ptyBusy = nextBusy)
}

private fun collapseWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()

private fun clipTitle(value: String, maxLength: Int = SESSION_TITLE_MAX_LENGTH): String {
    if (value.length <= maxLength) return value
    val sliced = value.take(maxLength)
    val lastSpace = sliced.lastIndexOf(' ')
    if (lastSpace >= (maxLength * 0.55).toInt()) return sliced.take(lastSpace)
    return sliced
}
