package com.wand.app.ui

import androidx.compose.runtime.mutableStateListOf

/** 原生界面的页面栈。会话内容由独立 WebView Activity 承载。 */
sealed class Screen {
    data object SessionList : Screen()
    data object NewSession : Screen()
    data object Settings : Screen()
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
)
