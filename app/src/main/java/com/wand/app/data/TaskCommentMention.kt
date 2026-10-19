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

    fun insert(body: String, caret: Int, name: String): Pair<String, Int> {
        val end = caret.coerceIn(0, body.length)
        val start = query(body, end)?.start ?: end
        val token = "@$name "
        return body.substring(0, start) + token + body.substring(end) to start + token.length
    }

    fun holds(body: String, name: String): Boolean {
        if (name.isEmpty()) return false
        return Regex("(^|\\s)@${Regex.escape(name)}(?=\\s|$)").containsMatchIn(body)
    }
}
