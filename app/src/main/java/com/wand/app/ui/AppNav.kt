package com.wand.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.wand.app.ui.theme.WandAppearanceMode

/** 原生界面的页面栈。结构化对话原生承载，PTY 套在原生头部里嵌一层终端 WebView。 */
sealed class Screen {
    data object SessionList : Screen()
    data class Chat(val sessionId: String) : Screen()
    data class PtyTerminal(val sessionId: String) : Screen()
    data object NewSession : Screen()
    data object Missions : Screen()
}

/** 长按图标快捷操作（对称 iOS QuickAction）：认证就绪后落到对应页面，消费一次。 */
sealed class QuickAction {
    data object NewSession : QuickAction()
    data object OpenWeb : QuickAction()
    data class OpenSession(val sessionId: String) : QuickAction()
}

class NavState {
    val stack = mutableStateListOf<Screen>(Screen.SessionList)

    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun setDetail(screen: Screen) {
        if (stack.size == 1) {
            stack.add(screen)
        } else {
            stack[stack.lastIndex] = screen
        }
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    companion object {
        val Saver: Saver<NavState, Any> = listSaver(
            save = { nav -> nav.stack.map { screen -> screen.saveKey() } },
            restore = { savedStack ->
                val restoredScreens = savedStack.mapNotNull { savedScreen ->
                    savedScreen.restoreScreen()
                }
                NavState().apply {
                    if (restoredScreens.firstOrNull() == Screen.SessionList) {
                        stack.clear()
                        stack.addAll(restoredScreens)
                    }
                }
            },
        )

        private const val SESSION_LIST_KEY = "session-list"
        private const val CHAT_PREFIX = "chat:"
        private const val PTY_TERMINAL_PREFIX = "pty-terminal:"
        private const val NEW_SESSION_KEY = "new-session"
        private const val MISSIONS_KEY = "missions"

        private fun Screen.saveKey(): String = when (this) {
            Screen.SessionList -> SESSION_LIST_KEY
            is Screen.Chat -> "$CHAT_PREFIX$sessionId"
            is Screen.PtyTerminal -> "$PTY_TERMINAL_PREFIX$sessionId"
            Screen.NewSession -> NEW_SESSION_KEY
            Screen.Missions -> MISSIONS_KEY
        }

        private fun String.restoreScreen(): Screen? = when {
            this == SESSION_LIST_KEY -> Screen.SessionList
            startsWith(CHAT_PREFIX) -> removePrefix(CHAT_PREFIX)
                .takeIf(String::isNotBlank)
                ?.let(Screen::Chat)
            startsWith(PTY_TERMINAL_PREFIX) -> removePrefix(PTY_TERMINAL_PREFIX)
                .takeIf(String::isNotBlank)
                ?.let(Screen::PtyTerminal)
            this == NEW_SESSION_KEY -> Screen.NewSession
            this == MISSIONS_KEY -> Screen.Missions
            else -> null
        }
    }
}

/**
 * 宿主 Activity 注入的能力包：把 com.wand.app 包里 package-private 的 Java 辅助类
 * （ServerStore / NotificationHelper / UpdateManager / WandForegroundService）
 * 包成 lambda 给 ui 包用，避免 Kotlin 跨包访问不到 Java package-private 成员。
 */
data class HomeConnectionInfo(
    val serverUrl: String,
    val hasToken: Boolean,
)

class HomeNavigationActions(
    val openWeb: () -> Unit,
    val switchServer: () -> Unit,
    val disconnect: () -> Unit,
)

class HomeSettingsActions(
    val appVersion: String,
    val manualCheckUpdate: () -> Unit,
    val isBetaChannel: () -> Boolean,
    val setBetaChannel: (Boolean) -> Unit,
    val getAppIcon: () -> String,
    val setAppIcon: (String) -> Unit,
    val getNotificationSound: () -> String,
    val setNotificationSound: (String) -> Unit,
    val previewSound: (String) -> Unit,
    val getNotificationVolume: () -> Int,
    val setNotificationVolume: (Int) -> Unit,
    val isHapticEnabled: () -> Boolean,
    val setHapticEnabled: (Boolean) -> Unit,
    val setKeepAlive: (Boolean) -> Unit,
    val getAppearanceMode: () -> WandAppearanceMode,
    val setAppearanceMode: (WandAppearanceMode) -> Unit,
)

/** 宿主能力按连接信息、导航和设备设置分组，页面只向下传递实际需要的能力。 */
class HomeActions(
    val connection: HomeConnectionInfo,
    val navigation: HomeNavigationActions,
    val settings: HomeSettingsActions,
)
