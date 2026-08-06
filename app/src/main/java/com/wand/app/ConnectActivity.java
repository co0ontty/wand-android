package com.wand.app;

import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.wand.app.data.ServerProfile;
import com.wand.app.data.WandAuth;
import com.wand.app.data.WandHttp;
import com.wand.app.data.WandWebSession;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kotlin.Pair;

public class ConnectActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 4242;
    public static final String EXTRA_MANAGEMENT_MODE = "management_mode";
    public static final String EXTRA_RETURN_SERVER_ID = "return_server_id";
    private static final String EXTRA_PROFILES_CHANGED = "profiles_changed";

    private ConnectComposeView connectView;
    private ServerStore serverStore;
    // 跟踪当前是否处于自动连接阶段。后台连接探测线程跑完之后会
    // runOnUiThread 决定下一步 (跳 WebView / 报错回表单), 我们在那里
    // 检查这面旗 — 用户如果已经点了"取消"/"管理服务器", autoConnecting
    // 会被翻成 false, 那次姗姗来迟的结果就必须被丢掉, 否则会出现
    // "用户已经在表单里输地址了, 突然又被旧请求强制跳到 WebView" 的
    // 体验事故 (尤其在 socket 已发出 → 用户点取消 → 服务器其实在
    // 这一秒内回复了这种 race 下很容易看见)。
    private boolean autoConnecting = false;

    // 用 single-thread executor 替代裸 new Thread, 配合 Future 在 onDestroy
    // 时 cancel(true) 中断未完成的连接探测 / cookie 写入。用户秒退或快速
    // 切服务器场景下, 之前的 raw Thread 还在跑, runOnUiThread 在 Activity
    // 已经 finish 之后调 setText / launchWebView 会触发 IllegalStateException
    // (尤其在低端机网络慢的时候比较常见)。
    private ExecutorService networkExecutor;
    private Future<?> currentTask;
    private long connectionGeneration = 0L;
    private boolean managementMode = false;
    private String returnServerId;
    private boolean profilesChanged = false;

    private static final class ConnectionResult {
        final String serverUrl;
        final String appToken;
        final String error;
        final boolean authenticated;

        ConnectionResult(String serverUrl, String appToken, String error, boolean authenticated) {
            this.serverUrl = serverUrl;
            this.appToken = appToken;
            this.error = error;
            this.authenticated = authenticated;
        }

        boolean isSuccess() {
            return error == null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (redirectToCreationHostIfBusy()) return;
        managementMode = getIntent().getBooleanExtra(EXTRA_MANAGEMENT_MODE, false);
        returnServerId = getIntent().getStringExtra(EXTRA_RETURN_SERVER_ID);
        profilesChanged = getIntent().getBooleanExtra(EXTRA_PROFILES_CHANGED, false);
        // ConnectActivity is the server-management boundary. A previous native/WebView runtime
        // must not keep reconnecting or emitting notifications while profiles are edited.
        if (!managementMode) {
            SessionWatcher.INSTANCE.stop();
            stopService(new Intent(this, WandForegroundService.class));
        }
        connectView = new ConnectComposeView(this);
        connectView.setListener(new ConnectUiListener() {
            @Override public void onConnect() {
                if (!redirectToCreationHostIfBusy()) attemptConnect();
            }
            @Override public void onScanQr() {
                if (!redirectToCreationHostIfBusy()) requestQrScan();
            }
            @Override public void onCancelAutoConnect() {
                if (!redirectToCreationHostIfBusy()) abortAutoConnect(false);
            }
            @Override public void onSwitchServer() {
                if (!redirectToCreationHostIfBusy()) abortAutoConnect(true);
            }
            @Override public void onPickServer(String serverId) {
                if (redirectToCreationHostIfBusy()) return;
                ServerProfile profile = serverStore.getServerProfile(serverId);
                if (profile == null) {
                    refreshServerList();
                    return;
                }
                connectView.setInputValue(profile.getBaseUrl());
                attemptConnect(profile);
            }
            @Override public void onRemoveServer(String serverId) {
                if (redirectToCreationHostIfBusy()) return;
                cancelPendingConnectionForProfileMutation();
                ServerProfile profile = serverStore.getServerProfile(serverId);
                ServerProfile active = serverStore.getActiveServerProfile();
                if (profile != null) WandHttp.resetClient(profile.getBaseUrl());
                serverStore.removeServerProfile(serverId);
                boolean removedActive = active != null && active.getId().equals(serverId);
                markProfilesChanged();
                if (removedActive) {
                    SessionWatcher.INSTANCE.stop();
                    stopService(new Intent(ConnectActivity.this, WandForegroundService.class));
                    clearWebViewCookies();
                    WandShortcuts.INSTANCE.clear(ConnectActivity.this);
                }
                if (managementMode && serverId.equals(returnServerId)) {
                    detachRemovedRuntime();
                    return;
                }
                refreshServerList();
            }
            @Override public void onClearServers() {
                if (redirectToCreationHostIfBusy()) return;
                cancelPendingConnectionForProfileMutation();
                for (ServerProfile profile : serverStore.getServerProfiles()) {
                    WandHttp.resetClient(profile.getBaseUrl());
                }
                serverStore.clearServerProfiles();
                markProfilesChanged();
                SessionWatcher.INSTANCE.stop();
                stopService(new Intent(ConnectActivity.this, WandForegroundService.class));
                clearWebViewCookies();
                WandShortcuts.INSTANCE.clear(ConnectActivity.this);
                if (managementMode && returnServerId != null) {
                    detachRemovedRuntime();
                    return;
                }
                connectView.setInputValue("");
                refreshServerList();
            }
        });
        setContentView(connectView);
        applyLightSystemBars();

        serverStore = new ServerStore(this);
        networkExecutor = Executors.newSingleThreadExecutor();
        if (managementMode) {
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() { handleManagementBack(); }
            });
        }
        refreshServerList();
        if (handleDeepLink(getIntent())) {
            return;
        }

        boolean skipAutoConnect = getIntent().getBooleanExtra("skip_auto_connect", false);
        String requestedServerId = getIntent().getStringExtra(WandShortcuts.EXTRA_SERVER_ID);
        ServerProfile activeProfile;
        if (requestedServerId != null) {
            activeProfile = serverStore.getServerProfile(requestedServerId);
            if (activeProfile == null) {
                showFormWithMessage("该服务器已从此设备移除，请重新连接");
                return;
            }
        } else {
            activeProfile = serverStore.getActiveServerProfile();
        }
        if (activeProfile != null) {
            connectView.setInputValue(activeProfile.getBaseUrl());
            if (!skipAutoConnect) {
                tryAutoConnect(activeProfile);
            } else {
                showForm();
            }
        } else {
            showForm();
        }

    }

    private void applyLightSystemBars() {
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    private void requestQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setCaptureActivity(QrScannerActivity.class);
        integrator.setPrompt(getString(R.string.scan_qr_prompt));
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.initiateScan();
    }

    private void showCameraPermissionSettingsDialog() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Wand_Dialog)
            .setTitle("需要相机权限")
            .setMessage("扫码连接需要相机权限。你也可以直接粘贴连接码连接。\n\n如需扫码，请在系统设置中开启相机权限。")
            .setPositiveButton("去设置", (d, w) -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            })
            .setNegativeButton("知道了", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchQrScanner();
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                // 永久拒绝(勾了"不再询问"): 引导去系统设置, 否则再点扫码毫无反应。
                showCameraPermissionSettingsDialog();
            } else {
                Toast.makeText(this, R.string.scan_qr_camera_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (redirectToCreationHostIfBusy()) return;
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (TextUtils.isEmpty(contents)) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            String trimmed = contents.trim();
            // Accept either a Wand connect code (base64 URL#TOKEN), a wand://connect deep link,
            // or a plain server URL.
            String candidate = trimmed;
            if (candidate.startsWith("wand://")) {
                Uri uri = Uri.parse(candidate);
                if ("wand".equals(uri.getScheme()) && "connect".equals(uri.getHost())) {
                    String urlParam = uri.getQueryParameter("url");
                    if (!TextUtils.isEmpty(urlParam)) {
                        candidate = urlParam;
                    }
                }
            }
            Pair<String, String> decoded = WandAuth.decodeConnectCode(candidate);
            boolean looksLikeUrl = candidate.startsWith("http://") || candidate.startsWith("https://");
            if (decoded == null && !looksLikeUrl) {
                Toast.makeText(this, R.string.scan_qr_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            connectView.setInputValue(candidate);
            attemptConnect();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (redirectToCreationHostIfBusy()) return;
        handleDeepLink(intent);
    }

    private boolean handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        Uri uri = intent.getData();
        if ("wand".equals(uri.getScheme()) && "connect".equals(uri.getHost())) {
            String serverUrl = uri.getQueryParameter("url");
            if (!TextUtils.isEmpty(serverUrl)) {
                connectView.setInputValue(serverUrl);
                attemptConnect();
                return true;
            }
        }
        return false;
    }

    private void tryAutoConnect(ServerProfile profile) {
        autoConnecting = true;
        connectView.showAutoConnecting("正在连接「" + profile.getDisplayName() + "」…");

        cancelCurrentTask();
        final long requestGeneration = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyServerProfile(profile, 5000);
            runOnUiThread(() -> handleAutoConnectResult(requestGeneration, result));
        });
    }

    private void handleAutoConnectResult(long requestGeneration, ConnectionResult result) {
        if (isDestroyed() || requestGeneration != connectionGeneration || !autoConnecting) return;
        autoConnecting = false;
        if (!result.isSuccess()) {
            String message = result.authenticated
                    ? result.error
                    : getString(R.string.auto_connect_failed);
            showFormWithMessage(message);
            return;
        }
        saveActivateAndLaunch(result);
    }

    /**
     * 用户在自动连接界面点了"取消"或"管理服务器"。立刻把 autoConnecting
     * 翻成 false (兜住后台请求姗姗来迟的回调), 中断网络任务, 露表单。
     *
     * @param focusInput true 表示管理服务器流程, 需要顺手聚焦输入框 + 全选
     *                   文本; false 表示纯取消, 不打扰用户。
     */
    private void abortAutoConnect(boolean focusInput) {
        if (!autoConnecting && !connectView.isAutoConnectVisible()) {
            return;
        }
        autoConnecting = false;
        cancelCurrentTask();
        showForm();
        if (focusInput) {
            connectView.focusInput();
        }
    }

    private void showForm() {
        connectView.showForm();
        refreshServerList();
    }

    private void showFormWithMessage(String errorMessage) {
        showForm();
        if (errorMessage != null) {
            showStatus(errorMessage);
        }
    }

    private void attemptConnect() {
        String rawInput = connectView.getInputValue().trim();
        if (TextUtils.isEmpty(rawInput)) {
            showStatus("请输入连接码或服务器地址", false);
            return;
        }

        connectView.setConnecting(true);

        cancelCurrentTask();
        final long requestGeneration = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyConnectionInput(rawInput, 8000);
            runOnUiThread(() -> handleManualConnectResult(requestGeneration, result));
        });
    }

    private void attemptConnect(ServerProfile profile) {
        connectView.setConnectingServer(profile.getId());
        cancelCurrentTask();
        final long requestGeneration = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyServerProfile(profile, 8000);
            runOnUiThread(() -> handleManualConnectResult(requestGeneration, result));
        });
    }

    private ConnectionResult verifyConnectionInput(String rawInput, int timeout) {
        Pair<String, String> decoded = WandAuth.decodeConnectCode(rawInput);
        if (decoded != null) {
            setAutoStatus("正在验证连接码…");
            String serverUrl = WandHttp.normalizeBaseUrl(decoded.getFirst());
            String appToken = decoded.getSecond();
            String error = testConnectionWithToken(serverUrl, appToken, timeout);
            return new ConnectionResult(serverUrl, appToken, error, true);
        }

        String serverUrl = WandHttp.normalizeBaseUrl(rawInput);
        ServerProfile savedProfile = serverStore.getServerProfileByUrl(serverUrl);
        if (savedProfile != null && savedProfile.getHasToken()) {
            String savedToken = savedProfile.getToken();
            String tokenError = testConnectionWithToken(serverUrl, savedToken, timeout);
            return new ConnectionResult(serverUrl, savedToken, tokenError, true);
        }
        String error = testConnection(serverUrl, timeout);
        return new ConnectionResult(serverUrl, null, error, false);
    }

    private ConnectionResult verifyServerProfile(ServerProfile profile, int timeout) {
        String serverUrl = profile.getBaseUrl();
        if (profile.getHasToken()) {
            String token = profile.getToken();
            String error = testConnectionWithToken(serverUrl, token, timeout);
            return new ConnectionResult(serverUrl, token, error, true);
        }
        return new ConnectionResult(
                serverUrl,
                null,
                testConnection(serverUrl, timeout),
                false
        );
    }

    private void handleManualConnectResult(long requestGeneration, ConnectionResult result) {
        if (isDestroyed() || requestGeneration != connectionGeneration) return;
        connectView.setConnecting(false);
        if (!result.isSuccess()) {
            showStatus(result.error);
            return;
        }
        saveActivateAndLaunch(result);
    }

    private void saveActivateAndLaunch(ConnectionResult result) {
        if (redirectToCreationHostIfBusy()) return;
        ServerProfile profile = serverStore.saveServerProfile(result.serverUrl, result.appToken);
        serverStore.setActiveServerId(profile.getId());
        WandHttp.resetClient(profile.getBaseUrl());
        launchWebView(profile);
    }

    private void cancelCurrentTask() {
        connectionGeneration += 1L;
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        currentTask = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelCurrentTask();
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
            networkExecutor = null;
        }
    }

    private String testConnectionWithToken(String baseUrl, String appToken, int timeout) {
        HttpURLConnection conn = null;
        try {
            conn = NetUtils.openConnection(baseUrl + "/api/login", timeout, timeout);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("appToken", appToken);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            conn.disconnect();

            if (code == 200) {
                return null;
            } else if (code == 401) {
                return "认证失败，连接码可能已过期（密码已更改），请重新获取连接码";
            } else if (code == 429) {
                return "登录尝试次数过多，请稍后再试";
            }
            return "服务器返回了异常状态码: " + code;
        } catch (Exception e) {
            return NetworkErrorHelper.describeError(e, "connect");
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private String testConnection(String baseUrl, int timeout) {
        try {
            HttpURLConnection conn = NetUtils.openConnection(baseUrl + "/api/config", timeout, timeout);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();

            if (code == 200 || code == 401) {
                return null;
            }
            return "服务器返回了异常状态码: " + code;
        } catch (Exception e) {
            return NetworkErrorHelper.describeError(e, "connect");
        }
    }

    /** 连接成功后进入原生主界面（HomeActivity）；WebView（MainActivity）只作网页版兜底。 */
    private void launchWebView(ServerProfile profile) {
        SessionWatcher.INSTANCE.stop();
        stopService(new Intent(this, WandForegroundService.class));
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(WandShortcuts.EXTRA_SERVER_ID, profile.getId());
        intent.putExtra(WandShortcuts.EXTRA_FORCE_SERVER_RELOAD, true);
        // 透传长按图标快捷操作的 extra（WandShortcuts → ConnectActivity → HomeActivity）。
        Intent source = getIntent();
        String sourceServerId = source == null
                ? null : source.getStringExtra(WandShortcuts.EXTRA_SERVER_ID);
        // Session IDs are server-scoped. If an old shortcut for A failed and the user explicitly
        // connects B, never forward A's navigation extras into B.
        if (source != null) {
            boolean exactServerMatch = profile.getId().equals(sourceServerId);
            String quickAction = source.getStringExtra(WandShortcuts.EXTRA_QUICK_ACTION);
            String openSessionId = source.getStringExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID);
            String openSessionKind = source.getStringExtra(WandShortcuts.EXTRA_OPEN_SESSION_KIND);
            if (quickAction != null && (sourceServerId == null || exactServerMatch)) {
                intent.putExtra(WandShortcuts.EXTRA_QUICK_ACTION, quickAction);
            }
            // Legacy session shortcuts had no server ID, so their session ID cannot be routed
            // safely after multi-server upgrade. Only an explicit exact match may pass through.
            if (exactServerMatch && openSessionId != null) {
                intent.putExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID, openSessionId);
                if (openSessionKind != null) {
                    intent.putExtra(WandShortcuts.EXTRA_OPEN_SESSION_KIND, openSessionKind);
                }
            }
        }
        startActivity(intent);
        finish();
    }

    private void handleManagementBack() {
        if (redirectToCreationHostIfBusy()) return;
        if (returnServerId != null && serverStore.getServerProfile(returnServerId) != null) {
            if (profilesChanged) {
                launchStoredHome(returnServerId);
            } else {
                finish();
            }
            return;
        }
        ServerProfile fallback = serverStore.getActiveServerProfile();
        if (fallback == null) {
            finish();
            return;
        }
        launchStoredHome(fallback.getId());
    }

    private void launchStoredHome(String serverId) {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(WandShortcuts.EXTRA_SERVER_ID, serverId);
        intent.putExtra(WandShortcuts.EXTRA_FORCE_SERVER_RELOAD, true);
        startActivity(intent);
        finish();
    }

    private void markProfilesChanged() {
        profilesChanged = true;
        getIntent().putExtra(EXTRA_PROFILES_CHANGED, true);
    }

    /**
     * Removing the server used by the paused HomeActivity must also destroy that Activity's
     * Compose stores and sockets. Recreate management as the task root before accepting input.
     */
    private void detachRemovedRuntime() {
        Intent replacement = new Intent(this, ConnectActivity.class);
        replacement.putExtra("skip_auto_connect", true);
        replacement.putExtra(EXTRA_MANAGEMENT_MODE, true);
        replacement.putExtra(EXTRA_RETURN_SERVER_ID, returnServerId);
        replacement.putExtra(EXTRA_PROFILES_CHANGED, true);
        replacement.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(replacement);
        finish();
    }

    private void cancelPendingConnectionForProfileMutation() {
        autoConnecting = false;
        cancelCurrentTask();
        connectView.setConnecting(false);
    }

    private void clearWebViewCookies() {
        WandWebSession.clearAsync();
    }

    private void showStatus(String message) {
        showStatus(message, true);
    }

    private void showStatus(String message, boolean isError) {
        connectView.showStatus(message, isError);
    }

    private void setAutoStatus(String text) {
        runOnUiThread(() -> {
            if (autoConnecting) connectView.setAutoStatus(text);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (redirectToCreationHostIfBusy()) return;
        if (!autoConnecting) {
            refreshServerList();
        }
    }

    /** Existing management/multi-window instances must honor the same process-wide create gate. */
    private boolean redirectToCreationHostIfBusy() {
        if (!SessionCreationCoordinator.isBusy()) return false;
        autoConnecting = false;
        if (networkExecutor != null) cancelCurrentTask();
        Intent homeIntent = new Intent(this, HomeActivity.class);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String hostServerId = SessionCreationCoordinator.busyHostServerId();
        if (hostServerId != null) {
            homeIntent.putExtra(WandShortcuts.EXTRA_SERVER_ID, hostServerId);
        }
        startActivity(homeIntent);
        finish();
        return true;
    }

    private void refreshServerList() {
        List<ServerProfile> profiles = serverStore.getServerProfiles();
        ServerProfile active = serverStore.getActiveServerProfile();
        connectView.setServerProfiles(profiles, active == null ? null : active.getId());
    }
}
