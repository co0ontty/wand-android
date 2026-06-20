package com.wand.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wand.app.data.WandApi
import com.wand.app.data.WandAuth
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.screens.ChatScreen
import com.wand.app.ui.screens.NewSessionScreen
import com.wand.app.ui.screens.PtyTerminalScreen
import com.wand.app.ui.screens.SessionListScreen
import com.wand.app.ui.screens.SessionListState
import com.wand.app.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 原生界面根组合：认证状态机 + 手写页面栈。
 * 对称 iOS NativeRootView：先用 appToken 登录拿 session cookie（CookieJar 在内存，
 * 冷启动后为空，所以每次启动都要重新登录），成功后进入会话列表。
 */
@Composable
fun WandApp(
    api: WandApi,
    actions: HomeActions,
    initialQuickAction: QuickAction? = null,
    onAuthenticated: () -> Unit,
) {
    var phase by remember { mutableStateOf<AuthPhase>(AuthPhase.Authenticating) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        phase = AuthPhase.Authenticating
        phase = try {
            if (actions.hasToken && api.token != null) {
                WandAuth.loginWithToken(api.baseUrl, api.token)
            } else {
                // 裸地址连接（无 token）：直接试列表，401 时引导重新连接。
                api.listSessions()
            }
            onAuthenticated()
            AuthPhase.Ready
        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            if (actions.hasToken) {
                AuthPhase.Failed(msg)
            } else {
                AuthPhase.Failed("无法访问服务器：$msg\n如果服务器设有密码，请用「连接码」重新连接。")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val p = phase) {
            is AuthPhase.Authenticating -> AuthProgress()
            is AuthPhase.Failed -> AuthFailed(
                message = p.message,
                onRetry = { retryKey++ },
                onSwitchServer = actions.switchServer,
            )
            is AuthPhase.Ready -> ReadyContent(api, actions, initialQuickAction)
        }
    }
}

private sealed class AuthPhase {
    data object Authenticating : AuthPhase()
    data object Ready : AuthPhase()
    data class Failed(val message: String) : AuthPhase()
}

@Composable
private fun AuthProgress() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            "正在登录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun AuthFailed(message: String, onRetry: () -> Unit, onSwitchServer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("重试") }
        OutlinedButton(onClick = onSwitchServer) { Text("重新连接") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    api: WandApi,
    actions: HomeActions,
    initialQuickAction: QuickAction? = null,
) {
    val nav = remember { NavState() }
    // 列表状态提升到这里：进聊天再返回时不丢已加载的会话与滚动位置。
    val listState = remember { SessionListState(api) }
    var showSettings by remember { mutableStateOf(false) }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismissSettings() {
        scope.launch {
            runCatching { settingsSheetState.hide() }
            showSettings = false
        }
    }

    // 认证就绪后消费一次长按图标快捷操作（对称 iOS consume）。
    LaunchedEffect(Unit) {
        when (val action = initialQuickAction) {
            is QuickAction.NewSession -> nav.push(Screen.NewSession)
            is QuickAction.OpenWeb -> actions.openWeb()
            is QuickAction.OpenSession -> nav.push(Screen.Chat(action.sessionId))
            null -> {}
        }
    }

    BackHandler(enabled = showSettings) { dismissSettings() }
    BackHandler(enabled = nav.stack.size > 1 && !showSettings) { nav.pop() }

    when (val screen = nav.current) {
        is Screen.SessionList -> SessionListScreen(
            state = listState,
            onOpenSession = { session ->
                if (session.isStructured) {
                    nav.push(Screen.Chat(session.id))
                } else {
                    nav.push(Screen.PtyTerminal(session.id))
                }
            },
            onNewSession = { nav.push(Screen.NewSession) },
            onOpenSettings = { showSettings = true },
            onOpenWeb = actions.openWeb,
            onSwitchServer = actions.switchServer,
        )
        is Screen.Chat -> ChatScreen(
            api = api,
            sessionId = screen.sessionId,
            isHapticEnabled = actions.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.PtyTerminal -> PtyTerminalScreen(
            api = api,
            sessionId = screen.sessionId,
            isHapticEnabled = actions.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.NewSession -> NewSessionScreen(
            api = api,
            onBack = { nav.pop() },
            onCreated = { snapshot ->
                listState.prepend(snapshot)
                nav.pop()
                if (snapshot.isStructured) {
                    nav.push(Screen.Chat(snapshot.id))
                } else {
                    nav.push(Screen.PtyTerminal(snapshot.id))
                }
            },
        )
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = settingsSheetState,
            containerColor = WandColors.bgElevated,
            scrimColor = Color.Black.copy(alpha = 0.42f),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            dragHandle = null,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f),
            ) {
                SettingsScreen(
                    api = api,
                    actions = actions,
                    onBack = { dismissSettings() },
                )
            }
        }
    }
}
