package com.wand.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wand.app.data.SessionSnapshot

/**
 * 长按 App 图标的快捷操作（Launcher App Shortcuts），对称 iOS QuickActions.swift。
 * 全部走动态项（ShortcutManagerCompat）而非静态 XML —— 图标切换用 activity-alias，
 * 静态 shortcut 绑定到具体 alias 上会随切换失效，动态项绑 ConnectActivity 始终在。
 *
 * 每个 shortcut 都拉起 ConnectActivity（exported 启动入口），带 quick_action /
 * open_session_id extra；ConnectActivity 自动连接成功后在 launchWebView 里把 extra
 * 透传给 HomeActivity，再由 WandApp 落到对应页面。
 */
object WandShortcuts {
    const val EXTRA_QUICK_ACTION = "quick_action"
    const val EXTRA_OPEN_SESSION_ID = "open_session_id"

    const val ACTION_NEW_SESSION = "new-session"
    const val ACTION_OPEN_WEB = "open-web"

    /** 系统最多展示 4 个：固定「新建会话」「打开网页版」+ 最近 2 个结构化会话。 */
    fun update(context: Context, sessions: List<SessionSnapshot>) {
        val shortcuts = mutableListOf(
            staticShortcut(
                context,
                id = "shortcut-new-session",
                shortLabel = "新建会话",
                longLabel = "新建会话",
                iconRes = R.drawable.ic_shortcut_new,
                rank = 0,
            ) { putExtra(EXTRA_QUICK_ACTION, ACTION_NEW_SESSION) },
            staticShortcut(
                context,
                id = "shortcut-open-web",
                shortLabel = "打开网页版",
                longLabel = "打开网页版",
                iconRes = R.drawable.ic_shortcut_web,
                rank = 1,
            ) { putExtra(EXTRA_QUICK_ACTION, ACTION_OPEN_WEB) },
        )

        // 只取结构化会话：PTY 会话原生不承载（走网页版），快捷直达聊天才有意义。
        sessions.asSequence()
            .filter { (it.archived ?: false) == false && it.isStructured }
            .take(2)
            .forEachIndexed { index, session ->
                shortcuts += staticShortcut(
                    context,
                    id = "shortcut-session-${session.id}",
                    shortLabel = session.displayTitle.take(20).ifEmpty { "会话" },
                    longLabel = "${session.providerLabel} · ${session.displayTitle}".take(40),
                    iconRes = R.drawable.ic_shortcut_chat,
                    rank = 2 + index,
                ) { putExtra(EXTRA_OPEN_SESSION_ID, session.id) }
            }

        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    private inline fun staticShortcut(
        context: Context,
        id: String,
        shortLabel: String,
        longLabel: String,
        iconRes: Int,
        rank: Int,
        configureIntent: Intent.() -> Unit,
    ): ShortcutInfoCompat {
        val intent = Intent(context, ConnectActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            configureIntent()
        }
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setRank(rank)
            .setIntent(intent)
            .build()
    }
}
