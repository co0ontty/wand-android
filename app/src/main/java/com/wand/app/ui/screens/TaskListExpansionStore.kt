package com.wand.app.ui.screens

import android.content.Context

/** 首页「最近对话」那一区（员工 / 团队 / 终端分组）的折叠档位。 */
internal const val FOLD_SECTION_RECENT = "recent"

/** 首页「任务与工作区」那一区（工作区 → 任务 → 终端）的折叠档位。 */
internal const val FOLD_SECTION_WORKSPACE = "workspace"

/**
 * 首页两个小节各自的折叠档位（展开 / 收起 / 在跑），按配置目录 + 服务端隔离。
 * 只存两档：控制只长在小节表头上，区内每一层都跟随它，没有单层状态。
 */
interface TaskListExpansionStore {
    fun sectionFold(section: String): HomeFoldMode
    fun setSectionFold(section: String, mode: HomeFoldMode)
}

class MemoryTaskListExpansionStore : TaskListExpansionStore {
    private val folds = mutableMapOf<String, HomeFoldMode>()
    override fun sectionFold(section: String): HomeFoldMode = folds[section] ?: HomeFoldMode.Expand
    override fun setSectionFold(section: String, mode: HomeFoldMode) {
        folds[section] = mode
    }
}

class SharedTaskListExpansionStore(
    context: Context,
    serverKey: String,
) : TaskListExpansionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefix = serverKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifEmpty { "default" }

    override fun sectionFold(section: String): HomeFoldMode =
        parseHomeFoldMode(prefs.getString(key(section), null))

    override fun setSectionFold(section: String, mode: HomeFoldMode) {
        prefs.edit().putString(key(section), homeFoldModeStorageValue(mode)).apply()
    }

    private fun key(section: String): String = "$prefix.section.$section"

    companion object {
        private const val PREFS_NAME = "wand_task_list"
    }
}
