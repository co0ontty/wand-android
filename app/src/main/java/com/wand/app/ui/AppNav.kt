package com.wand.app.ui

import androidx.compose.runtime.mutableStateListOf
import com.wand.app.ui.theme.WandAppearanceMode

/** 原生界面的页面栈。结构化对话原生承载，PTY 套在原生头部里嵌一层终端 WebView。 */
sealed class Screen {
    data object SessionList : Screen()
    data class Chat(val sessionId: String) : Screen()
    data class PtyTerminal(val sessionId: String) : Screen()
    data object NewSession : Screen()
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

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }
}

/**
 * 宿主 Activity 注入的能力包：把 com.wand.app 包里 package-private 的 Java 辅助类
 * （ServerStore / NotificationHelper / UpdateManager / WandForegroundService）
 * 包成 lambda 给 ui 包用，避免 Kotlin 跨包访问不到 Java package-private 成员。
 */
class HomeActions(
    val serverUrl: String,
    val hasToken: Boolean,
    val appVersion: String,
    val openWeb: () -> Unit,
    val openWebSession: (String) -> Unit,
    val switchServer: () -> Unit,
    val disconnect: () -> Unit,
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
