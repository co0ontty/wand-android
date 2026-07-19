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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.wand.app.data.WandAuth;
import com.wand.app.data.WandHttp;

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

    private ConnectComposeView connectView;
    private ServerStore serverStore;
    // 跟踪当前是否处于自动连接阶段。后台连接探测线程跑完之后会
    // runOnUiThread 决定下一步 (跳 WebView / 报错回表单), 我们在那里
    // 检查这面旗 — 用户如果已经点了"取消"/"切换服务器", autoConnecting
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

    private static final class ConnectionResult {
        final String serverUrl;
        final String appToken;
        final String error;
        final boolean fromConnectCode;

        ConnectionResult(String serverUrl, String appToken, String error, boolean fromConnectCode) {
            this.serverUrl = serverUrl;
            this.appToken = appToken;
            this.error = error;
            this.fromConnectCode = fromConnectCode;
        }

        boolean isSuccess() {
            return error == null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectView = new ConnectComposeView(this);
        connectView.setListener(new ConnectUiListener() {
            @Override public void onConnect() { attemptConnect(); }
            @Override public void onScanQr() { requestQrScan(); }
            @Override public void onCancelAutoConnect() { abortAutoConnect(false); }
            @Override public void onSwitchServer() { abortAutoConnect(true); }
            @Override public void onPickRecent(String entry) {
                connectView.setInputValue(entry);
                attemptConnect();
            }
            @Override public void onRemoveRecent(String entry) {
                serverStore.removeRecentUrl(entry);
                refreshRecentList();
            }
            @Override public void onClearRecent() {
                serverStore.clearRecent();
                refreshRecentList();
            }
        });
        setContentView(connectView);
        applyLightSystemBars();

        serverStore = new ServerStore(this);
        networkExecutor = Executors.newSingleThreadExecutor();
        if (handleDeepLink(getIntent())) {
            return;
        }

        boolean skipAutoConnect = getIntent().getBooleanExtra("skip_auto_connect", false);
        String lastUrl = serverStore.getLastUrl();
        if (!TextUtils.isEmpty(lastUrl)) {
            connectView.setInputValue(lastUrl);
            if (!skipAutoConnect) {
                tryAutoConnect(lastUrl);
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
            connectView.setInputValue(candidate);
            attemptConnect();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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

    private void tryAutoConnect(String savedInput) {
        autoConnecting = true;
        connectView.showAutoConnecting(getString(R.string.auto_connecting));

        cancelCurrentTask();
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyConnectionInput(savedInput, 5000, true);
            runOnUiThread(() -> handleAutoConnectResult(result));
        });
    }

    private void handleAutoConnectResult(ConnectionResult result) {
        if (isDestroyed() || !autoConnecting) return;
        autoConnecting = false;
        if (!result.isSuccess()) {
            String message = result.fromConnectCode
                    ? result.error
                    : getString(R.string.auto_connect_failed);
            showFormWithMessage(message);
            return;
        }
        if (result.fromConnectCode) {
            serverStore.setAppToken(result.appToken);
        }
        launchWebView(result.serverUrl, result.appToken);
    }

    /**
     * 用户在自动连接界面点了"取消"或"切换服务器"。立刻把 autoConnecting
     * 翻成 false (兜住后台请求姗姗来迟的回调), 中断网络任务, 露表单。
     *
     * @param focusInput true 表示切换服务器流程, 需要顺手聚焦输入框 + 全选
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
        refreshRecentList();
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
        currentTask = networkExecutor.submit(() -> {
            ConnectionResult result = verifyConnectionInput(rawInput, 8000, false);
            runOnUiThread(() -> handleManualConnectResult(rawInput, result));
        });
    }

    private ConnectionResult verifyConnectionInput(
            String rawInput,
            int timeout,
            boolean reuseSavedToken
    ) {
        Pair<String, String> decoded = WandAuth.decodeConnectCode(rawInput);
        if (decoded != null) {
            setAutoStatus("正在验证连接码…");
            String serverUrl = decoded.getFirst();
            String appToken = decoded.getSecond();
            String error = testConnectionWithToken(serverUrl, appToken, timeout);
            return new ConnectionResult(serverUrl, appToken, error, true);
        }

        String serverUrl = WandHttp.normalizeBaseUrl(rawInput);
        if (reuseSavedToken) {
            String savedToken = serverStore.getAppToken();
            if (!TextUtils.isEmpty(savedToken)) {
                setAutoStatus("正在验证连接码…");
                String tokenError = testConnectionWithToken(serverUrl, savedToken, timeout);
                if (tokenError == null) {
                    return new ConnectionResult(serverUrl, savedToken, null, false);
                }
            }
            setAutoStatus("正在尝试直接连接…");
        }
        String error = testConnection(serverUrl, timeout);
        return new ConnectionResult(serverUrl, null, error, false);
    }

    private void handleManualConnectResult(String rawInput, ConnectionResult result) {
        if (isDestroyed()) return;
        connectView.setConnecting(false);
        if (!result.isSuccess()) {
            showStatus(result.error);
            return;
        }

        String savedInput = result.fromConnectCode ? rawInput : result.serverUrl;
        serverStore.setLastUrl(savedInput);
        serverStore.addRecentUrl(savedInput);
        if (result.appToken == null) {
            serverStore.clearAppToken();
        } else {
            serverStore.setAppToken(result.appToken);
        }
        launchWebView(result.serverUrl, result.appToken);
    }

    private void cancelCurrentTask() {
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
            String setCookie = conn.getHeaderField("Set-Cookie");
            conn.disconnect();

            if (code == 200) {
                if (setCookie != null) {
                    android.webkit.CookieManager.getInstance().setCookie(baseUrl, setCookie);
                    android.webkit.CookieManager.getInstance().flush();
                }
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
    private void launchWebView(String url, String appToken) {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.putExtra("server_url", url);
        if (appToken != null) {
            intent.putExtra("app_token", appToken);
        }
        // 透传长按图标快捷操作的 extra（WandShortcuts → ConnectActivity → HomeActivity）。
        Intent source = getIntent();
        if (source != null) {
            String quickAction = source.getStringExtra(WandShortcuts.EXTRA_QUICK_ACTION);
            String openSessionId = source.getStringExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID);
            if (quickAction != null) intent.putExtra(WandShortcuts.EXTRA_QUICK_ACTION, quickAction);
            if (openSessionId != null) intent.putExtra(WandShortcuts.EXTRA_OPEN_SESSION_ID, openSessionId);
        }
        startActivity(intent);
        finish();
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
            refreshRecentList();
        }
    }

    private void refreshRecentList() {
        List<String> urls = serverStore.getRecentUrls();
        connectView.setRecentEntries(urls);
    }
}
