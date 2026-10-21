package com.wand.app.data

/** A caret @ token. Choosing a person is separate from whatever text happens to contain an @. */
internal object TaskCommentMention {
    data class Query(val start: Int, val text: String)

    fun query(body: String, caret: Int): Query? {
        val index = caret.coerceIn(0, body.length)
        if (index == 0) return null
        val at = body.lastIndexOf('@', index - 1)
        if (at < 0) return null
        if (at > 0 && !body[at - 1].isWhitespace()) return null
        val text = body.substring(at + 1, index)
        if (text.any { it.isWhitespace() }) return null
        return Query(at, text)
    }

    fun insert(body: String, caret: Int, name: String, replacingName: String = ""): Pair<String, Int> {
        val index = caret.coerceIn(0, body.length)
        val query = query(body, index) ?: return body to index
        var end = index
        while (end < body.length && !body[end].isWhitespace()) end++
        val previous = mentionRanges(body, replacingName).firstOrNull { it.last < query.start || it.first >= end }
        if (previous != null) {
            val edits = listOf(Triple(query.start, end, ""), Triple(previous.first, previous.last + 1, "@$name"))
            var value = body
            edits.sortedByDescending { it.first }.forEach { value = value.replaceRange(it.first, it.second, it.third) }
            val offset = query.start + if (previous.first < query.start) name.length - replacingName.length else 0
            return compactPrefix(value, offset, name, replacingName)
        }
        val token = "@$name" + if (end == body.length) " " else ""
        val value = body.substring(0, query.start) + token + body.substring(end)
        return compactPrefix(value, query.start + token.length + if (end < body.length) 1 else 0, name, replacingName)
    }

    /** Canonicalize only the dispatch preamble, not @ examples/addresses in the instruction. */
    private fun compactPrefix(body: String, caret: Int, name: String, replacingName: String = ""): Pair<String, Int> {
        if (name.isEmpty()) return body to caret
        val allowed = listOf("@$name", "@$replacingName", "@").sortedByDescending { it.length }
        var end = 0
        var count = 0
        while (end < body.length) {
            var start = end
            while (start < body.length && body[start].isWhitespace()) start++
            val token = allowed.firstOrNull {
                val next = start + it.length
                next <= body.length && body.startsWith(it, start) && (next == body.length || body[next].isWhitespace())
            } ?: break
            count++
            end = start + token.length
            while (end < body.length && body[end].isWhitespace()) end++
        }
        if (count == 0) return body to caret
        val prefix = "@$name "
        return prefix + body.substring(end) to if (caret <= end) prefix.length else caret - end + prefix.length
    }

    fun submissionBody(body: String, name: String): String = compactPrefix(body, body.length, name).first.trim()

    fun validationMessage(body: String, name: String): String? {
        val text = submissionBody(body, name)
        if (text.isEmpty()) return "请填写评论内容。"
        val tokens = text.split(Regex("(?U)\\s+"))
        val mentions = tokens.filter { it.startsWith("@") }
        if (name.isEmpty() && mentions.isNotEmpty()) return "请从 @ 候选列表选择员工，或删除未完成的提及后发表普通评论。"
        if (name.isNotEmpty()) {
            val ranges = mentionRanges(text, name)
            if (ranges.size != 1) return "请删除残留或重复的 @，本次只派发给选中的一位员工。"
            val instruction = text.removeRange(ranges.single())
            if (instruction.split(Regex("(?U)\\s+")).any { it.startsWith("@") }) return "请删除残留或重复的 @，本次只派发给选中的一位员工。"
            if (instruction.isBlank()) return "请在 @员工 后填写本轮指令。"
        }
        return null
    }

    private fun mentionRanges(body: String, name: String): List<IntRange> {
        if (name.isEmpty()) return emptyList()
        return Regex("(?U)(?<!\\S)@${Regex.escape(name)}(?=\\s|$)").findAll(body).map { it.range }.toList()
    }

    fun holds(body: String, name: String): Boolean = mentionRanges(body, name).isNotEmpty()
}
