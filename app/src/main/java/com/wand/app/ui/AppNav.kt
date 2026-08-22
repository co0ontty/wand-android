package com.wand.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.wand.app.data.WandApi
import com.wand.app.ui.theme.WandAppearanceMode

/** 原生界面的页面栈。结构化对话原生承载，PTY 套在原生头部里嵌一层终端 WebView。 */
sealed class Screen {
    data object SessionList : Screen()
    data class Chat(
        val sessionId: String,
        val workspaceName: String? = null,
        val taskName: String? = null,
    ) : Screen()
    data class PtyTerminal(
        val sessionId: String,
        val workspaceName: String? = null,
        val taskName: String? = null,
    ) : Screen()
    data class NewSession(val initialCwd: String? = null) : Screen()
    data object Missions : Screen()
    data object Settings : Screen()
    /** 项目 / 任务列表。 */
    data object Workspaces : Screen()
    /**
     * 任务详情宿主页：导航参数只携带稳定的 workspaceId/taskId 和编码短显示名，
     * 不携带 cwd、layout 或凭据。Saver 用结构化 save record，避免任务名中的
     * `:`、换行或 Unicode 破坏字符串恢复。
     */
    data class WorkspaceTask(
        val workspaceId: String,
        val taskId: String,
        val workspaceName: String,
        val taskName: String,
    ) : Screen()
}

/** 长按图标快捷操作（对称 iOS QuickAction）：认证就绪后落到对应页面，消费一次。 */
sealed class QuickAction {
    data object NewSession : QuickAction()
    data class OpenSession(
        val sessionId: String,
        val isStructured: Boolean? = null,
    ) : QuickAction()
}

class NavState {
    val stack = mutableStateListOf<Screen>(Screen.SessionList)

    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun setDetail(screen: Screen) {
        if (stack.size <= 1) {
            stack.add(screen)
            return
        }
        // 列表-详情：侧栏点选应替换整条详情栈，而不是只换栈顶。
        // 否则 [列表, 任务, 聊天] 再点另一个任务会变成 [列表, 任务, 新任务]，
        // 返回还会落到已离开的旧任务上。
        if (stack.size > 2) {
            stack.removeRange(2, stack.size)
        }
        if (stack[1] != screen) {
            stack[1] = screen
        }
    }

    fun replaceTop(screen: Screen) {
        if (stack.size <= 1) {
            stack.add(screen)
        } else {
            stack[stack.lastIndex] = screen
        }
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    fun renameWorkspaceTask(taskId: String, taskName: String) {
        val index = stack.indexOfLast { it is Screen.WorkspaceTask && it.taskId == taskId }
        if (index < 0) return
        val current = stack[index] as Screen.WorkspaceTask
        if (current.taskName != taskName) {
            stack[index] = current.copy(taskName = taskName)
        }
    }

    fun closeWorkspaceTask(taskId: String) {
        val index = stack.indexOfFirst { it is Screen.WorkspaceTask && it.taskId == taskId }
        if (index < 0) return
        while (stack.size > index) stack.removeAt(stack.lastIndex)
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
        private const val NEW_SESSION_PREFIX = "new-session:"
        private const val MISSIONS_KEY = "missions"
        private const val SETTINGS_KEY = "settings"
        private const val WORKSPACES_KEY = "workspaces"
        private const val WORKSPACE_TASK_KEY = "workspace-task"
        private const val FIELD_SEP = "\u0001"

        private fun Screen.saveKey(): String = when (this) {
            Screen.SessionList -> SESSION_LIST_KEY
            is Screen.Chat -> buildSessionDetailKey(CHAT_PREFIX, sessionId, workspaceName, taskName)
            is Screen.PtyTerminal -> buildSessionDetailKey(
                PTY_TERMINAL_PREFIX,
                sessionId,
                workspaceName,
                taskName,
            )
            is Screen.NewSession -> initialCwd?.let { "$NEW_SESSION_PREFIX$it" } ?: NEW_SESSION_KEY
            Screen.Missions -> MISSIONS_KEY
            Screen.Settings -> SETTINGS_KEY
            Screen.Workspaces -> WORKSPACES_KEY
            // 结构化分隔：用 \u0001 作为不可打印分隔符，避免任务名中的 `:`
            // 或换行破坏恢复（与 Web 不同，这里 ID 不含控制字符）。
            is Screen.WorkspaceTask ->
                WORKSPACE_TASK_KEY + FIELD_SEP + workspaceId + FIELD_SEP + taskId + FIELD_SEP + workspaceName + FIELD_SEP + taskName
        }

        private fun String.restoreScreen(): Screen? = when {
            this == SESSION_LIST_KEY -> Screen.SessionList
            startsWith(CHAT_PREFIX) -> restoreSessionDetail(
                prefix = CHAT_PREFIX,
                create = { sessionId, workspaceName, taskName ->
                    Screen.Chat(sessionId, workspaceName, taskName)
                },
            )
            startsWith(PTY_TERMINAL_PREFIX) -> restoreSessionDetail(
                prefix = PTY_TERMINAL_PREFIX,
                create = { sessionId, workspaceName, taskName ->
                    Screen.PtyTerminal(sessionId, workspaceName, taskName)
                },
            )
            this == NEW_SESSION_KEY -> Screen.NewSession()
            startsWith(NEW_SESSION_PREFIX) -> Screen.NewSession(removePrefix(NEW_SESSION_PREFIX))
            this == MISSIONS_KEY -> Screen.Missions
            this == SETTINGS_KEY -> Screen.Settings
            this == WORKSPACES_KEY -> Screen.Workspaces
            startsWith(WORKSPACE_TASK_KEY + FIELD_SEP) -> {
                val parts = removePrefix(WORKSPACE_TASK_KEY + FIELD_SEP).split(FIELD_SEP)
                if (parts.size >= 4) {
                    Screen.WorkspaceTask(
                        workspaceId = parts[0],
                        taskId = parts[1],
                        workspaceName = parts[2],
                        taskName = parts[3],
                    )
                } else {
                    null
                }
            }
            else -> null
        }

        private fun buildSessionDetailKey(
            prefix: String,
            sessionId: String,
            workspaceName: String?,
            taskName: String?,
        ): String {
            if (workspaceName.isNullOrBlank() && taskName.isNullOrBlank()) return "$prefix$sessionId"
            return prefix + sessionId + FIELD_SEP + workspaceName.orEmpty() + FIELD_SEP + taskName.orEmpty()
        }

        private fun String.restoreSessionDetail(
            prefix: String,
            create: (String, String?, String?) -> Screen,
        ): Screen? {
            val parts = removePrefix(prefix).split(FIELD_SEP, limit = 3)
            val sessionId = parts.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
            val workspaceName = parts.getOrNull(1)?.takeIf(String::isNotBlank)
            val taskName = parts.getOrNull(2)?.takeIf(String::isNotBlank)
            return create(sessionId, workspaceName, taskName)
        }

        // 测试辅助：把一个 Screen 序列化为字符串再恢复回来，验证 Saver 语义。
        internal fun serializeScreen(screen: Screen): String = screen.saveKey()
        internal fun deserializeScreen(key: String): Screen? = key.restoreScreen()
    }
}

/**
 * 宿主 Activity 注入的能力包：把 com.wand.app 包里 package-private 的 Java 辅助类
 * （ServerStore / NotificationHelper / UpdateManager / WandForegroundService）
 * 包成 lambda 给 ui 包用，避免 Kotlin 跨包访问不到 Java package-private 成员。
 */
data class HomeConnectionInfo(
    val serverId: String,
    val serverDisplayName: String,
    val serverUrl: String,
    val hasToken: Boolean,
    val savedServerCount: Int,
)

/**
 * 已保存服务器在原生界面中的运行时视图。凭据封装在对应 WandApi 内，页面与 Intent
 * 只传稳定 serverId，避免连接码在导航参数中扩散。
 */
class HomeServerConnection(
    val serverId: String,
    val displayName: String,
    val serverUrl: String,
    val hasToken: Boolean,
    val api: WandApi,
)

class HomeNavigationActions(
    val openWeb: () -> Unit,
    val switchServer: () -> Unit,
    val manageServers: () -> Unit,
    val reconnectServer: (serverId: String) -> Unit,
    val disconnect: () -> Unit,
)

class HomeSettingsActions(
    val appVersion: String,
    val manualCheckUpdate: () -> Unit,
    val isBetaChannel: () -> Boolean,
    val setBetaChannel: (Boolean) -> Unit,
    val isHapticEnabled: () -> Boolean,
    val setHapticEnabled: (Boolean) -> Unit,
    val isKeepAlive: () -> Boolean,
    val setKeepAlive: (Boolean) -> Unit,
    val getAppearanceMode: () -> WandAppearanceMode,
    val setAppearanceMode: (WandAppearanceMode) -> Unit,
)

/** 宿主能力按连接信息、导航和设备设置分组，页面只向下传递实际需要的能力。 */
class HomeActions(
    val connection: HomeConnectionInfo,
    val servers: List<HomeServerConnection>,
    val navigation: HomeNavigationActions,
    val settings: HomeSettingsActions,
)
