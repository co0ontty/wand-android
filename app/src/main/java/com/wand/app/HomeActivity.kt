package com.wand.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.wand.app.data.WandApi
import com.wand.app.ui.HomeActions
import com.wand.app.ui.WandApp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverUrl = intent.getStringExtra("server_url")
        val appToken = intent.getStringExtra("app_token")
        if (serverUrl.isNullOrEmpty()) {
            switchServer()
            return
        }

        val serverStore = ServerStore(this)
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
            serverUrl = api.baseUrl,
            hasToken = !appToken.isNullOrEmpty(),
            appVersion = appVersion,
            openWeb = { openWebFallback(serverUrl, appToken) },
            switchServer = { switchServer() },
            disconnect = { disconnect(serverStore) },
            manualCheckUpdate = {
                Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
                checkUpdate(manager)
            },
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
        )

        enableEdgeToEdge()
        setContent {
            WandTheme {
                WandApp(
                    api = api,
                    actions = actions,
                    onAuthenticated = {
                        // 每个进程只在首次认证成功后静默检查一次更新（从 WebView 首屏迁来）。
                        if (!updateCheckedThisProcess) {
                            updateCheckedThisProcess = true
                            checkUpdate(manager)
                        }
                    },
                )
            }
        }
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
        manager.checkForUpdate { cur, latest, url, file, size, source, notes ->
            manager.showUpdateDialog(cur, latest, url, file, size, source, notes)
        }
    }

    private fun openWebFallback(serverUrl: String, appToken: String?) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        if (!appToken.isNullOrEmpty()) intent.putExtra("app_token", appToken)
        startActivity(intent)
    }

    private fun switchServer() {
        val intent = Intent(this, ConnectActivity::class.java)
        intent.putExtra("skip_auto_connect", true)
        startActivity(intent)
        finish()
    }

    private fun disconnect(serverStore: ServerStore) {
        serverStore.setLastUrl("")
        serverStore.clearAppToken()
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

    companion object {
        private var updateCheckedThisProcess = false
    }
}
