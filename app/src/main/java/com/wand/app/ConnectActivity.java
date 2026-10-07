package com.wand.app;

import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.wand.app.ui.theme.ThemeKt;
import com.wand.app.data.ServerProfile;
import com.wand.app.data.WandAuth;
import com.wand.app.data.WandHttp;
import com.wand.app.data.PasswordConnection;
import com.wand.app.data.LanDiscovery;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kotlin.Pair;

public class ConnectActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 4242;
    // 401 就地重探的次数与退避基数，与 WandAuth.loginWithToken 的同一条规则对齐。
    private static final int LOGIN_PROBE_MAX_ATTEMPTS = 2;
    private static final long LOGIN_PROBE_RETRY_DELAY_MS = 1_000L;
    public static final String EXTRA_MANAGEMENT_MODE = "management_mode";
    public static final String EXTRA_RETURN_SERVER_ID = "return_server_id";
    private static final String EXTRA_PROFILES_CHANGED = "profiles_changed";

    private ConnectComposeView connectView;
    private ServerStore serverStore;
    private LanDiscovery lanDiscovery;
    // 跟踪当前是否处于自动连接阶段。后台连接探测线程跑完之后会
    // runOnUiThread 决定下一步 (进入原生首页 / 报错回表单), 我们在那里
    // 检查这面旗 — 用户如果已经点了"取消"/"管理服务器", autoConnecting
    // 会被翻成 false, 那次姗姗来迟的结果就必须被丢掉, 否则会出现
    // "用户已经在表单里输地址了, 突然又被旧请求强制跳到首页" 的
    // 体验事故 (尤其在 socket 已发出 → 用户点取消 → 服务器其实在
    // 这一秒内回复了这种 race 下很容易看见)。
    private boolean autoConnecting = false;

    // 用 single-thread executor 替代裸 new Thread, 配合 Future 在 onDestroy
    // 时 cancel(true) 中断未完成的连接探测 / cookie 写入。用户秒退或快速
    // 切服务器场景下, 之前的 raw Thread 还在跑, runOnUiThread 在 Activity
    // 已经 finish 之后更新表单或导航会触发 IllegalStateException
    // (尤其在低端机网络慢的时候比较常见)。
    private ExecutorService networkExecutor;
    private Future<?> currentTask;
    private long connectionGeneration = 0L;
    private final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private ServerProfile autoConnectProfile;
    private int autoConnectAttempt = 0;
    private boolean managementMode = false;
    private String returnServerId;
    private boolean profilesChanged = false;
    private boolean openingComplete = true;
    private ServerProfile pendingLaunchProfile;

    private static final class ProbeResult {
        final String error;
        final boolean retryable;
        /** 这次探测实际连通的 endpoint（可能因 http→https 回退而改写）。 */
        final String baseUrl;
        final boolean needsPassword;

        ProbeResult(String error, boolean retryable) {
            this(error, retryable, null);
        }

        ProbeResult(String error, boolean retryable, String baseUrl) {
            this(error, retryable, baseUrl, false);
        }

        ProbeResult(String error, boolean retryable, String baseUrl, boolean needsPassword) {
            this.error = error;
            this.retryable = retryable;
            this.baseUrl = baseUrl;
            this.needsPassword = needsPassword;
        }

        static ProbeResult success(String baseUrl) {
            return new ProbeResult(null, false, baseUrl);
        }
    }

    private static final class ConnectionResult {
        final String serverUrl;
        final String appToken;
        final String error;
        final boolean authenticated;
        final boolean retryable;
        final boolean needsPassword;

        ConnectionResult(
                String serverUrl,
                String appToken,
                String error,
                boolean authenticated,
                boolean retryable
        ) {
            this(serverUrl, appToken, error, authenticated, retryable, false);
        }

        ConnectionResult(String serverUrl, String appToken, String error,
                boolean authenticated, boolean retryable, boolean needsPassword) {
            this.serverUrl = serverUrl;
            this.appToken = appToken;
            this.error = error;
            this.authenticated = authenticated;
            this.retryable = retryable;
            this.needsPassword = needsPassword;
        }

        boolean isSuccess() {
            return error == null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        managementMode = getIntent().getBooleanExtra(EXTRA_MANAGEMENT_MODE, false);
        returnServerId = getIntent().getStringExtra(EXTRA_RETURN_SERVER_ID);
        profilesChanged = getIntent().getBooleanExtra(EXTRA_PROFILES_CHANGED, false);
        boolean launcherEntry = Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER);
        // 减少动效的用户不播开屏：位移/缩放动画没有瞬时版本可看（整段就是一段位移），
        // 播出来只会快闪一帧海报，还平白把自动连接压后 1 秒。直接交给表单/自动连接。
        boolean playOpening = savedInstanceState == null
                && !managementMode
                && launcherEntry
                && !ThemeKt.reduceMotionEnabled(this);
        openingComplete = !playOpening;
        // ConnectActivity is the server-management boundary. A previous native runtime
        // must not keep reconnecting or emitting notifications while profiles are edited.
        if (!managementMode) {
            SessionWatcher.INSTANCE.stop();
            stopService(new Intent(this, WandForegroundService.class));
        }
        connectView = new ConnectComposeView(this, playOpening);
        // serverStore 必须先于 listener 注册完成：listener 回调（onPickServer 等）
        // 虽然当前都是用户触发，但不依赖「注册时序」这种隐含假设更稳。
        serverStore = new ServerStore(this);
        connectView.setListener(new ConnectUiListener() {
            @Override public void onOpeningComplete() {
                openingComplete = true;
                if (pendingLaunchProfile != null && !isFinishing() && !isDestroyed()) {
                    ServerProfile profile = pendingLaunchProfile;
                    pendingLaunchProfile = null;
                    launchHome(profile);
                }
            }
            @Override public void onConnect() {
                attemptConnect();
            }
            @Override public void onConnectWithPassword(String baseUrl, String password, String serverId) {
                attemptPasswordConnect(baseUrl, password, serverId);
            }
            @Override public void onPickLanServer(String baseUrl, String serverId) {
                ServerProfile saved = serverStore.getServerProfileByUrl(baseUrl);
                attemptConnect(saved != null ? saved : new ServerProfile(serverId, baseUrl, null, null));
            }
            @Override public void onDiscoverServers() {
                connectView.clearPasswordRequest();
                startLanDiscovery();
            }
            @Override public void onScanQr() {
                requestQrScan();
            }
            @Override public void onCancelAutoConnect() {
                abortAutoConnect();
            }
            @Override public void onSwitchServer() {
                // 服务器列表现在是主要入口；展开后不自动弹出键盘遮住列表。
                abortAutoConnect();
            }
            @Override public void onPickServer(String serverId) {
                        ServerProfile profile = serverStore.getServerProfile(serverId);
                if (profile == null) {
                    refreshServerList();
                    return;
                }
                attemptConnect(profile);
            }
            @Override public void onRenameServer(String serverId, String name) {
                // A local label change does not invalidate the active connection. Returning to
                // Home lets its alias-only resume path update labels without discarding drafts.
                if (serverStore.setServerProfileName(serverId, name) != null) {
                    refreshServerList();
                }
            }
            @Override public void onRemoveServer(String serverId) {
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
                    WandShortcuts.INSTANCE.clear(ConnectActivity.this);
                }
                if (managementMode && serverId.equals(returnServerId)) {
                    detachRemovedRuntime();
                    return;
                }
                refreshServerList();
            }
            @Override public void onClearServers() {
                        cancelPendingConnectionForProfileMutation();
                for (ServerProfile profile : serverStore.getServerProfiles()) {
                    WandHttp.resetClient(profile.getBaseUrl());
                }
                serverStore.clearServerProfiles();
                markProfilesChanged();
                SessionWatcher.INSTANCE.stop();
                stopService(new Intent(ConnectActivity.this, WandForegroundService.class));
                WandShortcuts.INSTANCE.clear(ConnectActivity.this);
                if (managementMode && returnServerId != null) {
                    detachRemovedRuntime();
                    return;
                }
                connectView.clearConnectionDraft();
                refreshServerList();
            }
        });
        setContentView(connectView);
        applyLightSystemBars();

        networkExecutor = Executors.newSingleThreadExecutor();
        lanDiscovery = new LanDiscovery(this);
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
            connectView.setScannedConnection(candidate);
            attemptConnect();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    private boolean handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        Uri uri = intent.getData();
        if ("wand".equals(uri.getScheme()) && "connect".equals(uri.getHost())) {
            String serverUrl = uri.getQueryParameter("url");
            if (!TextUtils.isEmpty(serverUrl)) {
                abortAutoConnect();
                connectView.setIncomingConnection(serverUrl);
                attemptConnect();
                return true;
            }
        }
        return false;
    }

    private void tryAutoConnect(ServerProfile profile) {
        autoConnecting = true;
        autoConnectProfile = profile;
        autoConnectAttempt = 0;
        cancelAutoConnectRetry();
        connectView.showAutoConnecting("正在连接「" + profile.getDisplayName() + "」…");
        startAutoConnectAttempt();
    }

    /**
     * The launcher path used to probe the saved server only once. A short Wi-Fi/4G handoff,
     * captive-portal check, or a sleeping server therefore dropped the user onto the connection
     * form even though the exact same request would succeed a moment later. Keep transient
     * failures inside the automatic connection state; only credential/address failures need user
     * input.
     */
    private void startAutoConnectAttempt() {
        ServerProfile profile = autoConnectProfile;
        if (!autoConnecting || profile == null || isDestroyed()) return;

        autoConnectAttempt += 1;
        int attempt = autoConnectAttempt;
        cancelCurrentTask();
        final long requestGeneration = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyServerProfile(profile, 8000);
            runOnUiThread(() -> handleAutoConnectResult(requestGeneration, result, attempt));
        });
    }

    private void handleAutoConnectResult(
            long requestGeneration,
            ConnectionResult result,
            int attempt
    ) {
        if (isDestroyed() || requestGeneration != connectionGeneration || !autoConnecting) return;
        currentTask = null;
        if (result.isSuccess()) {
            autoConnecting = false;
            autoConnectProfile = null;
            cancelAutoConnectRetry();
            saveActivateAndLaunch(result);
            return;
        }

        if (result.retryable) {
            ServerProfile profile = autoConnectProfile;
            if (profile != null) {
                connectView.setAutoStatus(
                        "暂时无法连接，正在自动重试（第 " + attempt + " 次）…"
                );
                scheduleAutoConnectRetry(autoConnectRetryDelayMs(attempt));
                return;
            }
        }

        ServerProfile failedProfile = autoConnectProfile;
        autoConnecting = false;
        autoConnectProfile = null;
        if (result.needsPassword) {
            showForm();
            connectView.requestPassword(result.serverUrl, failedProfile == null ? null : failedProfile.getId());
            return;
        }
        String message = result.authenticated
                ? result.error
                : getString(R.string.auto_connect_failed);
        showFormWithMessage(message);
    }

    private void scheduleAutoConnectRetry(long delayMs) {
        cancelAutoConnectRetry();
        autoConnectHandler.postDelayed(this::startAutoConnectAttempt, delayMs);
    }

    private void cancelAutoConnectRetry() {
        autoConnectHandler.removeCallbacksAndMessages(null);
    }

    private static long autoConnectRetryDelayMs(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 4);
        return Math.min(1_000L << exponent, 10_000L);
    }

    /**
     * 用户在自动连接界面点了"取消"或"管理服务器"。立刻把 autoConnecting
     * 翻成 false (兜住后台请求姗姗来迟的回调), 中断网络任务, 露表单。
     *
     */
    private void abortAutoConnect() {
        if (!autoConnecting && !connectView.isAutoConnectVisible()) {
            return;
        }
        autoConnecting = false;
        autoConnectProfile = null;
        cancelAutoConnectRetry();
        cancelCurrentTask();
        showForm();
    }

    private void showForm() {
        connectView.showForm();
        refreshServerList();
        startLanDiscovery();
    }

    private void showFormWithMessage(String errorMessage) {
        showForm();
        if (errorMessage != null) {
            showStatus(errorMessage);
        }
    }

    private void attemptConnect() {
        String rawInput = connectView.getInputValue().trim();
        String requestedAlias = connectView.getServerAlias().trim();
        if (TextUtils.isEmpty(rawInput)) {
            showStatus("请输入连接码或服务器地址", false);
            return;
        }

        String password = connectView.getPasswordValue();
        connectView.setConnecting(true);
        cancelCurrentTask();
        final long requestGeneration = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyConnectionInput(rawInput, password, 8000);
            runOnUiThread(() -> handleManualConnectResult(requestGeneration, result, requestedAlias, null));
        });
    }

    private void attemptConnect(ServerProfile profile) {
        connectView.clearPasswordRequest();
        connectView.setConnectingServer(profile.getId());
        cancelCurrentTask();
        final long requestGeneration = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyServerProfile(profile, 8000);
            runOnUiThread(() -> handleManualConnectResult(requestGeneration, result, null, profile.getId()));
        });
    }

    private void attemptPasswordConnect(String baseUrl, String password, String serverId) {
        String alias = serverId == null ? connectView.getServerAlias().trim() : null;
        if (serverId == null) connectView.setConnecting(true);
        else connectView.setConnectingServer(serverId);
        cancelCurrentTask();
        final long generation = connectionGeneration;
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyPassword(baseUrl, password, 8000);
            runOnUiThread(() -> handleManualConnectResult(generation, result, alias, serverId));
        });
    }

    private ConnectionResult verifyPassword(String baseUrl, String password, int timeout) {
        try {
            PasswordConnection.Result result = PasswordConnection.connect(baseUrl, password, timeout);
            return new ConnectionResult(result.getBaseUrl(), result.getToken(), result.getError(),
                    false, result.getRetryable(), result.getNeedsPassword());
        } catch (IllegalArgumentException invalidAddress) {
            return new ConnectionResult(baseUrl, null, "服务器地址不正确，请重新输入", false, false);
        }
    }

    private ConnectionResult verifyConnectionInput(String rawInput, String password, int timeout) {
        Pair<String, String> decoded = WandAuth.decodeConnectCode(rawInput);
        if (decoded != null) {
            setAutoStatus("正在验证连接码…");
            String serverUrl = WandHttp.normalizeBaseUrl(decoded.getFirst());
            String appToken = decoded.getSecond();
            ProbeResult probe = testConnectionWithToken(serverUrl, appToken, timeout);
            String resolvedUrl = probe.baseUrl != null ? probe.baseUrl : serverUrl;
            return new ConnectionResult(resolvedUrl, appToken, probe.error, true, probe.retryable, probe.needsPassword);
        }

        String serverUrl = WandHttp.normalizeBaseUrl(rawInput);
        ServerProfile savedProfile = serverStore.getServerProfileByUrl(serverUrl);
        if (password.isEmpty() && savedProfile != null && savedProfile.getHasToken()) {
            String savedToken = savedProfile.getToken();
            ProbeResult probe = testConnectionWithToken(serverUrl, savedToken, timeout);
            String resolvedUrl = probe.baseUrl != null ? probe.baseUrl : serverUrl;
            return new ConnectionResult(resolvedUrl, savedToken, probe.error, true, probe.retryable, probe.needsPassword);
        }
        return verifyPassword(serverUrl, password, timeout);
    }

    private ConnectionResult verifyServerProfile(ServerProfile profile, int timeout) {
        String serverUrl = profile.getBaseUrl();
        if (profile.getHasToken()) {
            String token = profile.getToken();
            ProbeResult probe = testConnectionWithToken(serverUrl, token, timeout);
            String resolvedUrl = probe.baseUrl != null ? probe.baseUrl : serverUrl;
            return new ConnectionResult(resolvedUrl, token, probe.error, true, probe.retryable, probe.needsPassword);
        }
        return verifyPassword(serverUrl, "", timeout);
    }

    private void handleManualConnectResult(
            long requestGeneration,
            ConnectionResult result,
            String requestedAlias,
            String serverId
    ) {
        if (isDestroyed() || isFinishing() || requestGeneration != connectionGeneration) return;
        connectView.setConnecting(false);
        if (!result.isSuccess()) {
            if (result.needsPassword) connectView.requestPassword(result.serverUrl, serverId);
            showStatus(result.error, !result.needsPassword || !"请输入服务器密码".equals(result.error));
            return;
        }
        connectView.clearPasswordRequest();
        saveActivateAndLaunch(result, requestedAlias);
    }

    private void saveActivateAndLaunch(ConnectionResult result) {
        saveActivateAndLaunch(result, null);
    }

    private void saveActivateAndLaunch(ConnectionResult result, String requestedAlias) {
        ServerProfile profile = serverStore.saveServerProfile(result.serverUrl, result.appToken);
        if (requestedAlias != null && !requestedAlias.isBlank()) {
            ServerProfile named = serverStore.setServerProfileName(profile.getId(), requestedAlias);
            if (named != null) profile = named;
        }
        serverStore.setActiveServerId(profile.getId());
        WandHttp.resetClient(profile.getBaseUrl());
        if (openingComplete) {
            launchHome(profile);
        } else {
            pendingLaunchProfile = profile;
        }
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
        pendingLaunchProfile = null;
        autoConnecting = false;
        autoConnectProfile = null;
        cancelAutoConnectRetry();
        if (lanDiscovery != null) lanDiscovery.stop();
        super.onDestroy();
        cancelCurrentTask();
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
            networkExecutor = null;
        }
    }

    private ProbeResult testConnectionWithToken(String baseUrl, String appToken, int timeout) {
        WandLog.i("connect", "探测连接（连接码）");
        return loginProbe(baseUrl, appToken, timeout, 1);
    }

    /**
     * POST /api/login 探测 + 失败归类（判定与文案都在 WandAuth 里，与冷启动登录同一份）。
     *
     * 单次 401 不再判死：口令轮换后 appToken 换发会话有竞态窗口，先就地退避重探一次；
     * 只有重探仍被拒才提示「连接失败，请重试；若一直失败，再重新获取连接码」。
     * 之前那句「连接码可能已过期（密码已更改），请重新获取连接码」是任何一次 401 都直接甩出来的，
     * 把还能用的客户端推去换码 —— 换码本身又在制造下一轮凭据漂移。
     */
    private ProbeResult loginProbe(String baseUrl, String appToken, int timeout, int attempt) {
        int code;
        try {
            JSONObject body = new JSONObject();
            body.put("appToken", appToken);
            WandHttp.SimpleResponse response = WandHttp.postJson(
                    baseUrl + "/api/login", body.toString(), timeout, baseUrl);
            code = response.getCode();
        } catch (Exception e) {
            // URL 中可能含认证信息；异常文本和 endpoint 不进入日志或界面。
            WandLog.w("connect", "探测连接失败", null);
            ProbeResult upgraded = retryWithHttpsIfPlaintextHitTlsPort(baseUrl, appToken, timeout, e);
            if (upgraded != null) return upgraded;
            WandAuth.AuthFailure failure =
                    WandAuth.classifyLoginFailure(null, WandAuth.localErrorOf(e), attempt);
            return new ProbeResult(
                    WandAuth.loginFailureMessage(failure, null), failure.getRetryable());
        }
        if (code >= 200 && code < 300) {
            WandLog.i("connect", "探测成功");
            return ProbeResult.success(baseUrl);
        }
        WandAuth.AuthFailure failure = WandAuth.classifyLoginFailure(code, null, attempt);
        if (failure == WandAuth.AuthFailure.AuthPending && attempt < LOGIN_PROBE_MAX_ATTEMPTS) {
            WandLog.i("connect", "连接码被拒 401，退避后重探（第 " + (attempt + 1) + " 次）");
            try {
                Thread.sleep(LOGIN_PROBE_RETRY_DELAY_MS * attempt);
            } catch (InterruptedException interrupted) {
                // 用户已取消这一轮连接：交回可重试语义，由 generation 校验丢弃结果。
                Thread.currentThread().interrupt();
                return new ProbeResult(
                        WandAuth.loginFailureMessage(WandAuth.AuthFailure.Unreachable, null), true);
            }
            return loginProbe(baseUrl, appToken, timeout, attempt + 1);
        }
        WandLog.w("connect", "登录探测失败 " + failure, null);
        boolean needsPassword = failure == WandAuth.AuthFailure.CredentialRejected;
        return new ProbeResult(needsPassword ? "请输入服务器密码" : WandAuth.loginFailureMessage(failure, code),
                failure.getRetryable(), baseUrl, needsPassword);
    }

    /**
     * 老服务端在 L4 反代后会把连接码写成 `http://host:tls-port`（TLS 由反代终止，node 看到的是
     * 明文，猜不出 scheme）。明文打到 TLS 端口时握手会立刻炸掉（OkHttp: unexpected end of stream，
     * OpenSSL: wrong version number），而不是超时，所以这里能安全地用 https 再试一次并把改写后的
     * endpoint 带回调用方。返回 null 表示不适用或 https 也不行，交由上层报原始错误。
     */
    private ProbeResult retryWithHttpsIfPlaintextHitTlsPort(
            String baseUrl,
            String appToken,
            int timeout,
            Exception cause
    ) {
        if (!WandHttp.looksLikeHttpOnTlsPort(cause)) return null;
        String httpsUrl = WandHttp.preferHttpsUrl(baseUrl);
        if (httpsUrl == null) return null;
        WandLog.i("connect", "明文打到 TLS 端口，改用 https 重试");
        // httpsUrl 已是 https，preferHttpsUrl 会返回 null，所以这里不会再套一层，递归有界。
        return loginProbe(httpsUrl, appToken, timeout, 1);
    }

    /** 连接成功后进入原生主界面（HomeActivity）。 */
    private void launchHome(ServerProfile profile) {
        WandLog.i("connect", "进入主界面");
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
        autoConnectProfile = null;
        cancelAutoConnectRetry();
        cancelCurrentTask();
        connectView.setConnecting(false);
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
        if (!autoConnecting) {
            refreshServerList();
            startLanDiscovery();
        } else if (currentTask == null) {
            startAutoConnectAttempt();
        }
    }

    @Override
    protected void onStop() {
        if (lanDiscovery != null) lanDiscovery.stop();
        connectView.setDiscoveryStatus("点按重新发现内网服务", false);
        cancelAutoConnectRetry();
        cancelCurrentTask();
        connectView.setConnecting(false);
        super.onStop();
    }

    private void startLanDiscovery() {
        if (lanDiscovery == null) return;
        lanDiscovery.start(serverStore.getServerProfiles(),
                profiles -> { connectView.setLanServers(profiles); return kotlin.Unit.INSTANCE; },
                (status, scanning) -> { connectView.setDiscoveryStatus(status, scanning); return kotlin.Unit.INSTANCE; });
    }

    private void refreshServerList() {
        List<ServerProfile> profiles = serverStore.getServerProfiles();
        ServerProfile active = serverStore.getActiveServerProfile();
        connectView.setServerProfiles(profiles, active == null ? null : active.getId());
    }
}
