package com.wand.app.ui

import android.content.Context

/**
 * 安卓端「新建会话」页面偏好。
 *
 * Provider 和会话类型只影响当前设备上的创建页，不属于服务端运行配置，
 * 因此使用本地 SharedPreferences 保存（与 iOS NewSessionPreferences 语义一致）。
 */
internal class NewSessionPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var provider: String
        get() = if (prefs.getString(KEY_PROVIDER, DEFAULT_PROVIDER) == "codex") "codex" else DEFAULT_PROVIDER
        set(value) {
            prefs.edit().putString(KEY_PROVIDER, if (value == "codex") "codex" else DEFAULT_PROVIDER).apply()
        }

    var isStructured: Boolean
        get() = prefs.getBoolean(KEY_IS_STRUCTURED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_STRUCTURED, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "wand_new_session"
        const val KEY_PROVIDER = "provider"
        const val KEY_IS_STRUCTURED = "is_structured"
        const val DEFAULT_PROVIDER = "claude"
    }
}
