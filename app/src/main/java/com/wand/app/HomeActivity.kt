package com.wand.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.ServerProfile
import com.wand.app.data.WandApi
import com.wand.app.data.WandHttp
import com.wand.app.data.WandWebSession
import com.wand.app.ui.HomeActions
import com.wand.app.ui.HomeConnectionInfo
import com.wand.app.ui.HomeNavigationActions
import com.wand.app.ui.HomeServerConnection
import com.wand.app.ui.HomeSettingsActions
import com.wand.app.ui.QuickAction
import com.wand.app.ui.WandApp
import com.wand.app.ui.screens.AppUpdateInfo
import com.wand.app.ui.screens.UpdatePresentation
import com.wand.app.ui.screens.UpdateSheet
import com.wand.app.ui.theme.WandAppearanceMode
import com.wand.app.ui.theme.WandTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 原生主界面（Compose）：会话列表 / 聊天 / 新建会话 / 设置。
 * 对称 iOS 端的 NativeRootView；WebView（MainActivity）只作「网页版」兜底入口。
 *
 * 由 ConnectActivity 在连接成功后启动。主路径只传稳定 server_id，URL 与凭据从
 * ServerStore 解析；server_url/app_token 仅保留为旧 Intent 的兼容入口。
 */
class HomeActivity : AppCompatActivity() {

    private var updateExecutor: ExecutorService? = null
    private var updateManager: UpdateManager? = null
    private var currentServerId: String? = null
    private var serverProfilesSnapshot: List<ServerProfile> = emptyList()
    private var activeServerSnapshotId: String? = null
    private var runtimeReady = false
    private var hasResumedRuntime = false
    /** 同一 Activity 的认证重试不应重复弹出同一个更新提示。 */
    private var autoUpdateCheckStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverStore = ServerStore(this)
        // 创建任务不依赖 Activity 生命周期。重建或从外部入口返回时，始终重新挂到发起
        // 创建的 Home；忽略这期间到达的通知/快捷操作，避免把目标 session 路由到另一台服务器。
        val busyHostServerId = SessionCreationCoordinator.busyHostServerId()
        val busyHostProfile = busyHostServerId?.let(serverStore::getServerProfile)
        val requestedServerId = if (busyHostServerId == null) {
            intent.getStringExtra(WandShortcuts.EXTRA_SERVER_ID)
        } else {
            busyHostProfile?.id
        }
        val legacyServerUrl = if (busyHostServerId == null) {
            intent.getStringExtra("server_url")
        } else {
            null
        }
        val requestedProfile = busyHostProfile
            ?: requestedServerId?.let(serverStore::getServerProfile)
        if (requestedServerId != null && requestedProfile == null) {
            // server_id 是稳定路由键。绝不能在目标已移除时回退到另一个 active profile，
            // 否则旧通知或快捷入口中的 session id 可能被错误地发往另一台服务器。
            switchServer(requestedServerId)
            return
        }
        // Old PendingIntents are resolved through the migrated store. Never re-import their
        // embedded token, which may have been intentionally removed since creation.
        val explicitLegacyUrl = legacyServerUrl?.takeIf { it.isNotBlank() }
        val legacyProfile = explicitLegacyUrl?.let(serverStore::getServerProfileByUrl)
        if (requestedServerId == null && explicitLegacyUrl != null && legacyProfile == null) {
            switchServer()
            return
        }
        val serverProfile = requestedProfile
            ?: legacyProfile
            ?: serverStore.activeServerProfile
        if (serverProfile == null) {
            switchServer()
            return
        }
        currentServerId = serverProfile.id
        serverStore.setActiveServerId(serverProfile.id)
        serverProfilesSnapshot = serverStore.serverProfiles.toList()
        activeServerSnapshotId = serverStore.activeServerProfile?.id
        runtimeReady = true
        val serverUrl = serverProfile.baseUrl
        val appToken = serverProfile.token

        // 长按图标快捷操作（WandShortcuts → ConnectActivity 透传）：认证就绪后消费一次。
        val initialQuickAction = if (busyHostServerId != null) {
            null
        } else {
            when (intent.getStringExtra(WandShortcuts.EXTRA_QUICK_ACTION)) {
                WandShortcuts.ACTION_NEW_SESSION -> QuickAction.NewSession
                WandShortcuts.ACTION_OPEN_WEB -> QuickAction.OpenWeb
                else -> intent.getStringExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { sessionId ->
                        val isStructured = when (intent.getStringExtra(WandShortcuts.EXTRA_OPEN_SESSION_KIND)) {
                            "structured" -> true
                            "pty" -> false
                            else -> null
                        }
                        QuickAction.OpenSession(sessionId, isStructured)
                    }
            }
        }

        var appearanceMode by mutableStateOf(
            WandAppearanceMode.fromStorageValue(serverStore.appearanceMode)
        )
        val notificationHelper = NotificationHelper(this).also { it.createChannels() }
        updateExecutor = Executors.newSingleThreadExecutor()
        val manager = UpdateManager(this, serverStore, updateExecutor, serverUrl)
        updateManager = manager
        var updatePresentation by mutableStateOf<UpdatePresentation>(UpdatePresentation.Hidden)
        var activeDownload: UpdateManager.DownloadRequest? = null

        fun asUpdateInfo(
            currentVersion: String,
            latestVersion: String,
            downloadUrl: String,
            fileName: String,
            size: Long,
            source: String,
            releaseNotes: String,
            channel: String,
        ) = AppUpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            downloadUrl = downloadUrl,
            fileName = fileName,
            size = size,
            source = source,
            releaseNotes = releaseNotes,
            channel = channel,
        )

        fun startDownload(update: AppUpdateInfo) {
            updatePresentation = UpdatePresentation.Downloading(update)
            activeDownload = manager.download(
                update.downloadUrl,
                update.fileName,
                update.latestVersion,
                update.channel,
                object : UpdateManager.DownloadListener {
                    override fun onProgress(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) {
                        updatePresentation = UpdatePresentation.Downloading(
                            update = update,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            bytesPerSecond = bytesPerSecond,
                        )
                    }

                    override fun onCompleted(apkFile: java.io.File) {
                        activeDownload = null
                        updatePresentation = UpdatePresentation.Ready(update, apkFile)
                    }

                    override fun onCancelled() {
                        activeDownload = null
                        updatePresentation = UpdatePresentation.Hidden
                    }

                    override fun onFailed(message: String) {
                        activeDownload = null
                        updatePresentation = UpdatePresentation.Failed(update, message)
                    }
                },
            )
        }

        fun checkUpdate(manual: Boolean) {
            if (manual) updatePresentation = UpdatePresentation.Checking
            val found = UpdateManager.UpdateFoundCallback { current, latest, url, file, size,
                                                            source, notes, channel ->
                updatePresentation = UpdatePresentation.Available(
                    asUpdateInfo(current, latest, url, file, size, source, notes, channel),
                )
            }
            if (manual) {
                manager.checkForUpdate(found) { message ->
                    updatePresentation = UpdatePresentation.UpToDate(message)
                }
            } else {
                manager.checkForUpdate(found)
            }
        }

        val api = WandApi(serverUrl, appToken)
        val serverConnections = serverStore.serverProfiles.map { profile ->
            HomeServerConnection(
                serverId = profile.id,
                displayName = profile.displayName,
                serverUrl = profile.baseUrl,
                hasToken = profile.hasToken,
                api = if (profile.id == serverProfile.id) api else WandApi(profile.baseUrl, profile.token),
            )
        }
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

        fun runWhenCreationIdle(action: () -> Unit) {
            if (SessionCreationCoordinator.isBusy()) {
                Toast.makeText(this, "会话正在创建，请稍候", Toast.LENGTH_SHORT).show()
            } else {
                action()
            }
        }

        val actions = HomeActions(
            connection = HomeConnectionInfo(
                serverId = serverProfile.id,
                serverDisplayName = serverProfile.displayName,
                serverUrl = api.baseUrl,
                hasToken = !appToken.isNullOrEmpty(),
                savedServerCount = serverConnections.size,
            ),
            servers = serverConnections,
            navigation = HomeNavigationActions(
                openWeb = {
                    runWhenCreationIdle { openWebFallback(serverProfile.id) }
                },
                switchServer = {
                    runWhenCreationIdle { switchServer() }
                },
                manageServers = {
                    runWhenCreationIdle { manageServers(serverProfile.id) }
                },
                reconnectServer = { targetServerId ->
                    runWhenCreationIdle { manageServers(serverProfile.id, targetServerId) }
                },
                disconnect = {
                    runWhenCreationIdle { disconnect(serverStore, serverProfile.id) }
                },
            ),
            settings = HomeSettingsActions(
                appVersion = appVersion,
                manualCheckUpdate = { checkUpdate(manual = true) },
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
                setKeepAlive = { enabled -> setKeepAlive(enabled, serverProfile.id) },
                getAppearanceMode = { appearanceMode },
                setAppearanceMode = { mode ->
                    appearanceMode = mode
                    serverStore.appearanceMode = mode.storageValue
                },
            ),
        )

        // Completion delivery must not depend on WandApp reaching its authenticated Ready phase.
        // A failed host login can still consume a failed/successful create and release the global
        // gate, preventing a Home ↔ Connect redirect loop after Activity recreation.
        val creationOwnerServerId = busyHostServerId ?: serverProfile.id
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                SessionCreationCoordinator.state.collect { state ->
                    val completed = state as? SessionCreationCoordinator.State.Completed
                        ?: return@collect
                    if (completed.hostServerId != creationOwnerServerId) return@collect
                    val claimed = SessionCreationCoordinator.takeCompleted(completed.requestId)
                        ?: return@collect
                    when (val outcome = claimed.outcome) {
                        is SessionCreationCoordinator.Outcome.Success -> {
                            openCreatedSession(
                                serverStore = serverStore,
                                serverId = claimed.targetServerId,
                                snapshot = outcome.snapshot,
                            )
                        }
                        is SessionCreationCoordinator.Outcome.Failure -> {
                            Toast.makeText(
                                this@HomeActivity,
                                "创建失败：${outcome.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }

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
                Box(Modifier.fillMaxSize()) {
                    WandApp(
                        api = api,
                        actions = actions,
                        initialQuickAction = initialQuickAction,
                        onAuthenticated = {
                            // 认证成功后启动会话通知中枢（全局 WS → 进度/完成/授权通知）。
                            // start 幂等，Activity 重建时复用既有连接。
                            SessionWatcher.start(
                                this@HomeActivity,
                                serverProfile.id,
                                api.baseUrl,
                                appToken,
                            )
                            // 自动检查必须等认证完成：更新接口会复用登录后的 Cookie。没有更新、
                            // 已跳过或已下载的版本都静默处理；只有确有新包才呈现 Compose 更新页。
                            if (!autoUpdateCheckStarted) {
                                autoUpdateCheckStarted = true
                                checkUpdate(manual = false)
                            }
                        },
                    )
                    UpdateSheet(
                        presentation = updatePresentation,
                        onDismiss = { updatePresentation = UpdatePresentation.Hidden },
                        onDownload = ::startDownload,
                        onCancelDownload = { activeDownload?.cancel() },
                        onInstall = { apkFile ->
                            updatePresentation = UpdatePresentation.Hidden
                            manager.installApk(apkFile)
                        },
                        onSkipVersion = { update ->
                            serverStore.setSkippedVersion(update.latestVersion, update.channel)
                            updatePresentation = UpdatePresentation.Hidden
                        },
                    )
                }
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

    override fun onResume() {
        super.onResume()
        if (!runtimeReady) return
        if (!hasResumedRuntime) {
            hasResumedRuntime = true
            return
        }
        val store = ServerStore(this)
        val profiles = store.serverProfiles.toList()
        val activeId = store.activeServerProfile?.id
        if (profiles == serverProfilesSnapshot && activeId == activeServerSnapshotId) return

        val target = currentServerId?.let(store::getServerProfile)
            ?: store.activeServerProfile
        if (target == null) {
            SessionWatcher.stop()
            runCatching { stopService(Intent(this, WandForegroundService::class.java)) }
            switchServer()
            return
        }
        if (target.id != currentServerId) {
            SessionWatcher.stop()
            runCatching { stopService(Intent(this, WandForegroundService::class.java)) }
        }
        val replacement = Intent(this, HomeActivity::class.java).apply {
            putExtra(WandShortcuts.EXTRA_SERVER_ID, target.id)
            putExtra(WandShortcuts.EXTRA_FORCE_SERVER_RELOAD, true)
        }
        startActivity(replacement)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (SessionCreationCoordinator.isBusy()) {
            Toast.makeText(this, "会话正在创建，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val targetServerId = intent.getStringExtra(WandShortcuts.EXTRA_SERVER_ID)
        val hasNavigationTarget =
            intent.hasExtra(WandShortcuts.EXTRA_QUICK_ACTION) ||
                intent.hasExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID) ||
                intent.getBooleanExtra(WandShortcuts.EXTRA_FORCE_SERVER_RELOAD, false)
        if (targetServerId != null && (targetServerId != currentServerId || hasNavigationTarget)) {
            // 通知使用 SINGLE_TOP/CLEAR_TOP。切到另一服务器时必须让 onCreate 重新解析
            // profile 并重建整套 API/Compose runtime。启动全新实例可避免 recreate 把 A 的
            // rememberSaveable 导航栈恢复到 B，再用 B API 打开 A 的 session id。
            val replacement = Intent(intent).setClass(this, HomeActivity::class.java).apply {
                flags = 0
            }
            SessionWatcher.stop()
            runCatching { stopService(Intent(this, WandForegroundService::class.java)) }
            startActivity(replacement)
            finish()
        }
    }

    private fun openWebFallback(
        serverId: String,
        sessionId: String? = null,
    ) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(WandShortcuts.EXTRA_SERVER_ID, serverId)
        if (!sessionId.isNullOrEmpty()) intent.putExtra("session_id", sessionId)
        startActivity(intent)
    }

    private fun switchServer(requestedServerId: String? = null) {
        // 换服务器 / 断开：停掉旧服务器的通知中枢，避免跨服务器串通知。
        SessionWatcher.stop()
        runCatching { stopService(Intent(this, WandForegroundService::class.java)) }
        val intent = Intent(this, ConnectActivity::class.java)
        intent.putExtra("skip_auto_connect", true)
        if (requestedServerId != null) {
            intent.putExtra(WandShortcuts.EXTRA_SERVER_ID, requestedServerId)
        }
        startActivity(intent)
        finish()
    }

    private fun manageServers(returnServerId: String, requestedServerId: String? = null) {
        val intent = Intent(this, ConnectActivity::class.java).apply {
            putExtra("skip_auto_connect", true)
            putExtra(ConnectActivity.EXTRA_MANAGEMENT_MODE, true)
            putExtra(ConnectActivity.EXTRA_RETURN_SERVER_ID, returnServerId)
            if (requestedServerId != null) {
                putExtra(WandShortcuts.EXTRA_SERVER_ID, requestedServerId)
            }
        }
        startActivity(intent)
    }

    private fun disconnect(serverStore: ServerStore, serverId: String) {
        serverStore.getServerProfile(serverId)?.let { profile ->
            WandHttp.resetClient(profile.baseUrl)
        }
        serverStore.removeServerProfile(serverId)
        WandWebSession.clearAsync()
        runCatching { stopService(Intent(this, WandForegroundService::class.java)) }
        // 移除当前服务器后清掉会话快捷项，避免长按图标还能直达已移除的连接。
        WandShortcuts.clear(this)
        switchServer()
    }

    private fun openCreatedSession(
        serverStore: ServerStore,
        serverId: String,
        snapshot: SessionSnapshot,
    ) {
        val profile = serverStore.getServerProfile(serverId) ?: run {
            Toast.makeText(this, "找不到所选服务器，请重新连接", Toast.LENGTH_LONG).show()
            return
        }
        serverStore.setActiveServerId(profile.id)
        SessionWatcher.stop()
        runCatching { stopService(Intent(this, WandForegroundService::class.java)) }
        val target = Intent(this, HomeActivity::class.java).apply {
            putExtra(WandShortcuts.EXTRA_SERVER_ID, profile.id)
            putExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID, snapshot.id)
            putExtra(
                WandShortcuts.EXTRA_OPEN_SESSION_KIND,
                if (snapshot.isStructured) "structured" else "pty",
            )
        }
        startActivity(target)
        finish()
    }

    private fun setKeepAlive(enabled: Boolean, serverId: String) {
        try {
            val serviceIntent = Intent(this, WandForegroundService::class.java)
            if (enabled) {
                serviceIntent.putExtra(WandShortcuts.EXTRA_SERVER_ID, serverId)
                startForegroundService(serviceIntent)
            } else {
                stopService(serviceIntent)
            }
        } catch (_: Exception) {
        }
    }
}
