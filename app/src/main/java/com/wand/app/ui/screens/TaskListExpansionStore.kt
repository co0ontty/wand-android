package com.wand.app.ui.screens

import android.content.Context

internal const val TASK_LIST_EXPANSION_DIRS = "directories"
internal const val TASK_LIST_EXPANSION_TASKS = "tasks"
internal const val TASK_LIST_EXPANSION_LOOSE = "loose"

internal fun encodeCollapsedIds(ids: Collection<String>): String =
    ids.filter { it.isNotBlank() }.toSortedSet().joinToString("\n")

internal fun decodeCollapsedIds(raw: String?): Set<String> =
    raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

interface TaskListExpansionStore {
    fun collapsedIds(kind: String): Set<String>
    fun setCollapsedIds(kind: String, ids: Set<String>)
}

class MemoryTaskListExpansionStore : TaskListExpansionStore {
    private val collapsed = mutableMapOf<String, Set<String>>()
    override fun collapsedIds(kind: String): Set<String> = collapsed[kind].orEmpty()
    override fun setCollapsedIds(kind: String, ids: Set<String>) {
        collapsed[kind] = ids.toSet()
    }
}

class SharedTaskListExpansionStore(
    context: Context,
    serverKey: String,
) : TaskListExpansionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefix = serverKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifEmpty { "default" }

    override fun collapsedIds(kind: String): Set<String> =
        decodeCollapsedIds(prefs.getString(key(kind), null))

    override fun setCollapsedIds(kind: String, ids: Set<String>) {
        prefs.edit().putString(key(kind), encodeCollapsedIds(ids)).apply()
    }

    private fun key(kind: String): String = "$prefix.$kind"

    companion object {
        private const val PREFS_NAME = "wand_task_list"
    }
}
