package com.wand.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.WandApi
import com.wand.app.ui.HomeActions
import com.wand.app.ui.HomeConnectionInfo
import com.wand.app.ui.HomeNavigationActions
import com.wand.app.ui.HomeSettingsActions
import com.wand.app.ui.QuickAction
import com.wand.app.ui.WandApp
import com.wand.app.ui.theme.WandAppearanceMode
import com.wand.app.ui.theme.WandTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 原生主界面（Compose）：会话列表 / 聊天 / 新建会话 / 设置。
 * 对称 iOS 端的 NativeRootView；WebView（MainActivity）只作「网页版」兜底入口。
 *
 * 由 ConnectActivity 在连接成功后启动，extras 与原 WebView 流程一致：
 * server_url（必填）+ app_token（可选，连接码里的 token）。
 */
class HomeActivity : AppCompatActivity() {

    private var updateExecutor: ExecutorService? = null
    private var updateManager: UpdateManager? = null
    /** 同一 Activity 的认证重试不应重复弹出同一个更新提示。 */
    private var autoUpdateCheckStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverUrl = intent.getStringExtra("server_url")
        val appToken = intent.getStringExtra("app_token")
        if (serverUrl.isNullOrEmpty()) {
            switchServer()
            return
        }

        // 长按图标快捷操作（WandShortcuts → ConnectActivity 透传）：认证就绪后消费一次。
        val initialQuickAction = when (intent.getStringExtra(WandShortcuts.EXTRA_QUICK_ACTION)) {
            WandShortcuts.ACTION_NEW_SESSION -> QuickAction.NewSession
            WandShortcuts.ACTION_OPEN_WEB -> QuickAction.OpenWeb
            else -> intent.getStringExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID)
                ?.takeIf { it.isNotEmpty() }
                ?.let { QuickAction.OpenSession(it) }
        }

        val serverStore = ServerStore(this)
        var appearanceMode by mutableStateOf(
            WandAppearanceMode.fromStorageValue(serverStore.appearanceMode)
        )
        val notificationHelper = NotificationHelper(this).also { it.createChannels() }
        updateExecutor = Executors.newSingleThreadExecutor()
        val manager = UpdateManager(this, serverStore, updateExecutor, serverUrl)
        updateManager = manager

        val api = WandApi(serverUrl, appToken)
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

        val actions = HomeActions(
            connection = HomeConnectionInfo(api.baseUrl, !appToken.isNullOrEmpty()),
            navigation = HomeNavigationActions(
                openWeb = { openWebFallback(serverUrl, appToken) },
                switchServer = { switchServer() },
                disconnect = { disconnect(serverStore) },
            ),
            settings = HomeSettingsActions(
                appVersion = appVersion,
                manualCheckUpdate = {
                    Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
                    checkUpdate(manager)
                },
                isBetaChannel = { serverStore.isBetaChannel },
                setBetaChannel = { serverStore.setBetaChannel(it) },
                getAppIcon = { serverStore.appIcon },
                setAppIcon = { AppIconSwitcher.setAppIcon(this, serverStore, it) },
                getNotificationSound = { serverStore.notificationSound },
                setNotificationSound = { serverStore.notificationSound = it },
                previewSound = { name ->
                    notificationHelper.playPresetSound(name, serverStore.notificationVolume / 100f)
                },
                getNotificationVolume = { serverStore.notificationVolume },
                setNotificationVolume = { serverStore.notificationVolume = it },
                isHapticEnabled = { serverStore.isHapticEnabled },
                setHapticEnabled = { serverStore.setHapticEnabled(it) },
                setKeepAlive = { enabled -> setKeepAlive(enabled, serverUrl, appToken) },
                getAppearanceMode = { appearanceMode },
                setAppearanceMode = { mode ->
                    appearanceMode = mode
                    serverStore.appearanceMode = mode.storageValue
                },
            ),
        )

        applyEdgeToEdge(appearanceMode == WandAppearanceMode.Dark)
        setContent {
            val systemDark = isSystemInDarkTheme()
            val resolvedDark = when (appearanceMode) {
                WandAppearanceMode.Light -> false
                WandAppearanceMode.Dark -> true
                WandAppearanceMode.System -> systemDark
            }
            LaunchedEffect(resolvedDark) {
                applyEdgeToEdge(resolvedDark)
            }
            WandTheme(appearanceMode = appearanceMode) {
                WandApp(
                    api = api,
                    actions = actions,
                    initialQuickAction = initialQuickAction,
                    onAuthenticated = {
                        // 认证成功后启动会话通知中枢（全局 WS → 进度/完成/授权通知）。
                        // start 幂等，Activity 重建时复用既有连接。
                        SessionWatcher.start(this, api.baseUrl, appToken)
                        // 自动检查必须等认证完成：更新接口会复用登录后的 Cookie。没有更新、
                        // 已跳过或已下载的版本都静默处理；只有确有新包才弹出安装对话框。
                        if (!autoUpdateCheckStarted) {
                            autoUpdateCheckStarted = true
                            manager.checkForUpdate { cur, latest, url, file, size, source, notes, channel ->
                                manager.showUpdateDialog(cur, latest, url, file, size, source, notes, channel)
                            }
                        }
                    },
                )
            }
        }
    }

    private fun applyEdgeToEdge(dark: Boolean) {
        val transparent = Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
            navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 安装未知来源权限授予后的续装回调（与 MainActivity 同款处理）。
        updateManager?.handleActivityResult(requestCode)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateExecutor?.shutdownNow()
        updateExecutor = null
    }

    private fun checkUpdate(manager: UpdateManager) {
        manager.checkForUpdate(
            { cur, latest, url, file, size, source, notes, channel ->
                manager.showUpdateDialog(cur, latest, url, file, size, source, notes, channel)
            },
            { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun openWebFallback(serverUrl: String, appToken: String?, sessionId: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        if (!appToken.isNullOrEmpty()) intent.putExtra("app_token", appToken)
        if (!sessionId.isNullOrEmpty()) intent.putExtra("session_id", sessionId)
        startActivity(intent)
    }

    private fun switchServer() {
        // 换服务器 / 断开：停掉旧服务器的通知中枢，避免跨服务器串通知。
        SessionWatcher.stop()
        val intent = Intent(this, ConnectActivity::class.java)
        intent.putExtra("skip_auto_connect", true)
        startActivity(intent)
        finish()
    }

    private fun disconnect(serverStore: ServerStore) {
        serverStore.setLastUrl("")
        serverStore.clearAppToken()
        // 断开后清掉会话快捷项，避免长按图标还能直达旧服务器的会话。
        WandShortcuts.clear(this)
        switchServer()
    }

    private fun setKeepAlive(enabled: Boolean, serverUrl: String, appToken: String?) {
        try {
            if (enabled) {
                val serviceIntent = Intent(this, WandForegroundService::class.java)
                serviceIntent.putExtra("server_url", serverUrl)
                if (appToken != null) serviceIntent.putExtra("app_token", appToken)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                stopService(Intent(this, WandForegroundService::class.java))
            }
        } catch (_: Exception) {
        }
    }
}
