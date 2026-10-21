package com.wand.app.data

/**
 * 任务引用（`#TASK-133`）解析契约，与服务端 `src/task-comment-references.ts` 逐条对齐。
 *
 * 规则（Web / iOS / Android 三端必须一致）：
 * - `#` 紧跟一个任务编号（形如 `TASK-133`）构成一个引用标记；`#` 单独出现不是引用，
 *   也不匹配 Markdown 标题（ATX 标题要求 `#` 后有空格）。
 * - `#` 前面必须是行首、空白或成对开括号（`(` `（` `【` `「` `《`）。这样
 *   `做 #TASK-133`、`（#TASK-133）` 是引用，而 `a#TASK-133`、
 *   `https://x/doc#TASK-5` 里的 URL 片段不是——后者会被误当成引用，让服务端以
 *   「引用的任务不存在」拒收整条评论。
 * - 引用只认本任务下挂的直接子任务，不跨任务、不跨工作区；存在性由服务端裁决。
 * - 同一段正文里可以出现多个引用，也可以和任意多个 `@员工` 混排；正文整体是本轮指令。
 * - 解析只做「切词」，不判断任务是否存在。
 */
internal object TaskCommentReference {
    /**
     * JS `\s`（ECMAScript WhiteSpace ∪ LineTerminator）的字符类源码。
     * Java 正则的 `\s` 只覆盖 ASCII 空白，比 JS 少 NBSP / 全角空格 / BOM 等字符；
     * 三端写死同一份集合，同一个字符才不会在两端得到不同判断。
     */
    private const val JS_WHITESPACE_SOURCE =
        "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** 任务编号形如 `TASK-133`；前缀允许大写字母与数字，编号必须是数字。 */
    private val TASK_REFERENCE_PATTERN =
        Regex("(^|[$JS_WHITESPACE_SOURCE(（【「《])#([A-Z][A-Z0-9]*-\\d+)(?![\\w-])")

    /** `#` 之后允许继续输入的查询片段（大写字母 / 数字 / 连字符）。 */
    private val REFERENCE_QUERY_PATTERN = Regex("^[A-Z][A-Z0-9-]*$")

    /** `#` 前面必须是行首或空白，或这些成对开括号；否则 URL 片段 / 词内 `#` 不算引用起点。 */
    private const val REFERENCE_BOUNDARY_CHARS = "（【「《("

    /** JS `\s` 的判定：与 Web 端 `src/task-comment-references.ts` 的正则语义一致。 */
    private val JS_WHITESPACE = Regex("[$JS_WHITESPACE_SOURCE]")

    /** 光标处的引用 token 查询。 */
    data class Query(val start: Int, val query: String)

    /** 正文渲染片段：普通文本或引用 token。 */
    data class Segment(val text: String, val identifier: String?)

    /** 引用目标（本任务的直接子任务）。 */
    data class Target(val id: String, val identifier: String)

    /** 执行卡片「目标」行的 chip：对外展示的编号 + 点开要用的子任务 id。 */
    data class ReferenceChip(val identifier: String, val taskId: String)

    /**
     * 执行卡片上的「目标」chip 行。
     *
     * 编号优先用服务端给的 `referencedTaskIdentifiers`（与 Web 端同一个字段、同一个顺序）；
     * 老服务端只给内部 id 时，回到本任务的子任务列表里换编号。只有能换到子任务 id 的编号才出
     * chip：换不到目标的 chip 点不动，而内部 UUID 绝不能当编号给用户看。
     */
    fun executionReferenceChips(comment: TaskComment, children: List<Target>): List<ReferenceChip> {
        val identifiers = comment.referencedTaskIdentifiers.ifEmpty {
            val ids = comment.referencedTaskIds.ifEmpty { comment.dispatch?.referencedTaskIds.orEmpty() }
            ids.mapNotNull { id -> children.firstOrNull { it.id == id }?.identifier }
        }
        return identifiers.distinct().mapNotNull { identifier ->
            if (identifier.isBlank()) return@mapNotNull null
            val child = children.firstOrNull { it.identifier == identifier } ?: return@mapNotNull null
            ReferenceChip(identifier, child.id)
        }
    }

    /** 提取正文里出现的任务引用编号，按首次出现顺序去重。 */
    fun parseTaskReferenceIds(body: String): List<String> {
        val ids = mutableListOf<String>()
        for (match in TASK_REFERENCE_PATTERN.findAll(body)) {
            val id = match.groupValues[2]
            if (id.isNotEmpty() && !ids.contains(id)) ids.add(id)
        }
        return ids
    }

    /** 正文里是否引用了某个任务编号。 */
    fun hasTaskReference(body: String, identifier: String): Boolean {
        if (identifier.isEmpty()) return false
        return parseTaskReferenceIds(body).contains(identifier)
    }

    /** 正文中某个引用编号的出现次数；用于判断「引用是否还在正文里」。 */
    fun countTaskReference(body: String, identifier: String): Int {
        if (identifier.isEmpty()) return 0
        return TASK_REFERENCE_PATTERN.findAll(body).count { it.groupValues[2] == identifier }
    }

    /** 把正文里的引用编号解析成「本任务可引用的子任务」；不认识的编号一律不返回。 */
    fun resolveTaskReferences(body: String, children: List<Target>): List<Target> {
        val wanted = parseTaskReferenceIds(body)
        if (wanted.isEmpty()) return emptyList()
        val byIdentifier = children.associateBy { it.identifier }
        return wanted.mapNotNull { byIdentifier[it] }
    }

    /**
     * 引用编号 → 子任务 id。
     *
     * 正文里写的是编号（`TASK-133`），而详情页按任务 id 打开；点引用 token 时必须先在这里
     * 把编号换成 id，直接把编号当 id 用会打开一个不存在的任务。编号只在本任务的直接子任务里找，
     * 找不到返回 null（不猜、不跨任务）。
     */
    fun taskReferenceTargetId(identifier: String, children: List<Target>): String? {
        if (identifier.isEmpty()) return null
        return children.firstOrNull { it.identifier == identifier }?.id
    }

    /** 正文中引用编号是否全部能在给定子任务集合里找到。 */
    fun unknownTaskReferenceIds(body: String, children: List<Target>): List<String> {
        val known = children.map { it.identifier }.toSet()
        return parseTaskReferenceIds(body).filter { it !in known }
    }

    /**
     * `#` 开始一个引用 token。光标必须仍在这个 token 内；前面必须是行首、空白或成对开括号，
     * 避免把 `a#TASK-1` 或 URL 片段当成引用。
     */
    fun commentReferenceQuery(body: String, caret: Int): Query? {
        val index = caret.coerceIn(0, body.length)
        if (index == 0) return null
        val at = body.lastIndexOf('#', index - 1)
        if (at < 0) return null
        if (at > 0 && !isReferenceBoundary(body[at - 1])) return null
        val query = body.substring(at + 1, index)
        if (query.any { isJsWhitespace(it) }) return null
        if (query.isNotEmpty() && !REFERENCE_QUERY_PATTERN.matches(query)) return null
        return Query(at, query)
    }

    /** 插入选中的引用编号。稳定编号本身留在正文里，不额外编码。 */
    fun insertCommentReference(body: String, caret: Int, identifier: String): Pair<String, Int> {
        val index = caret.coerceIn(0, body.length)
        val start = commentReferenceQuery(body, index)?.start ?: index
        // 末尾已经紧跟空白时不再补空格，否则正文里会多出一个空格。
        val suffix = if (index < body.length && isJsWhitespace(body[index])) "" else " "
        val token = "#$identifier$suffix"
        return (body.substring(0, start) + token + body.substring(index)) to (start + token.length)
    }

    /** 渲染用：把正文切成「引用 token」与「普通文本」两类片段。 */
    fun taskReferenceSegments(body: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (match in TASK_REFERENCE_PATTERN.findAll(body)) {
            val at = match.range.first
            val identifier = match.groupValues[2]
            val start = at + match.groupValues[1].length
            if (start > cursor) segments.add(Segment(body.substring(cursor, start), null))
            segments.add(Segment("#$identifier", identifier))
            cursor = start + identifier.length + 1
        }
        if (cursor < body.length) segments.add(Segment(body.substring(cursor), null))
        return segments.ifEmpty { listOf(Segment(body, null)) }
    }

    private fun isReferenceBoundary(char: Char): Boolean =
        isJsWhitespace(char) || REFERENCE_BOUNDARY_CHARS.contains(char)

    private fun isJsWhitespace(char: Char): Boolean = JS_WHITESPACE.matches(char.toString())
}
