package com.wand.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.wand.app.data.ServerProfile;
import com.wand.app.data.WandHttp;
import com.wand.app.data.WandWebSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements NetworkMonitor.Listener {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;
    private static final int FILE_CHOOSER_REQUEST = 1002;
    private static final int WEB_MEDIA_PERMISSION_REQUEST = 1004;

    private WebView webView;
    private ViewGroup webViewParent;
    private final List<WebView> popupWebViews = new ArrayList<>();
    private LinearLayout errorOverlay;
    private LinearLayout loadingOverlay;
    private TextView errorMessage;
    private String serverId;
    private String serverUrl;
    private String appToken;
    private String sessionId;
    private boolean hasLoadedPage = false;
    private boolean lastLoadFailed = false;
    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private String[] pendingWebPermissionResources;
    private boolean keepAliveRunning = false;
    private long lastBackPressedTime = 0;
    private ExecutorService backgroundExecutor;
    private int webSessionGeneration = 0;
    private final String webSessionOwnerId = "main-" + UUID.randomUUID();
    private boolean activityResumed = false;

    private ServerStore serverStore;
    private NotificationHelper notificationHelper;
    private UpdateManager updateManager;
    private NetworkMonitor networkMonitor;

    // IME 动画跟踪
    private boolean imeAnimating = false;
    private int lastSysBarTopPx = 0;
    private int lastSysBarBottomPx = 0;
    private int lastSysBarLeftPx = 0;
    private int lastSysBarRightPx = 0;
    private int lastImeBottomPx = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applySystemBarAppearance();

        serverStore = new ServerStore(this);
        serverId = getIntent().getStringExtra(WandShortcuts.EXTRA_SERVER_ID);
        if (serverId != null) {
            ServerProfile profile = serverStore.getServerProfile(serverId);
            if (profile == null) {
                openMissingServerScreen(serverId);
                return;
            }
            serverUrl = profile.getBaseUrl();
            appToken = profile.getToken();
        } else {
            // Legacy PendingIntents may contain URL/token. Resolve the migrated saved profile by
            // URL and ignore the embedded token, so a notification cannot resurrect credentials
            // after that profile has been removed.
            String legacyUrl = getIntent().getStringExtra("server_url");
            ServerProfile profile = legacyUrl == null
                    ? null : serverStore.getServerProfileByUrl(legacyUrl);
            if (profile == null) {
                openConnectScreen();
                return;
            }
            serverId = profile.getId();
            serverUrl = profile.getBaseUrl();
            appToken = profile.getToken();
        }
        sessionId = getIntent().getStringExtra("session_id");
        if (serverUrl == null || serverUrl.isEmpty()) {
            finish();
            return;
        }

        notificationHelper = new NotificationHelper(this);
        backgroundExecutor = Executors.newFixedThreadPool(2);
        updateManager = new UpdateManager(this, serverStore, backgroundExecutor, serverUrl);
        networkMonitor = new NetworkMonitor(this, this);

        webView = findViewById(R.id.webView);
        webViewParent = (ViewGroup) webView.getParent();
        errorOverlay = findViewById(R.id.errorOverlay);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorMessage = findViewById(R.id.errorMessage);

        findViewById(R.id.retryButton).setOnClickListener(v -> {
            prepareWebViewSessionAndLoad();
        });
        findViewById(R.id.backToConnectButton).setOnClickListener(v -> openConnectScreen());

        setVolumeControlStream(AudioManager.STREAM_NOTIFICATION);

        notificationHelper.createChannels();
        setupWebView();
        networkMonitor.register();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){try{return window.handleNativeBack?window.handleNativeBack():false;}catch(e){return false;}})()",
                    result -> {
                        if ("true".equals(result)) return;
                        if (webView.canGoBack()) {
                            webView.goBack();
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastBackPressedTime < 2000) {
                            navigateBackToNative();
                        } else {
                            lastBackPressedTime = now;
                            Toast.makeText(MainActivity.this, "再按一次返回原生界面", Toast.LENGTH_SHORT).show();
                        }
                    }
                );
            }
        });
    }

    // ── NetworkMonitor.Listener ──

    @Override
    public void onNetworkStateChanged(String state) {
        runOnUiThread(() -> {
            if (!activityResumed || webView == null) return;
            if (("available".equals(state) || "validated".equals(state) || "changed".equals(state))
                    && errorOverlay != null && errorOverlay.getVisibility() == View.VISIBLE) {
                prepareWebViewSessionAndLoad();
                return;
            }
            String safe = state == null ? "" : state.replace("'", "");
            evalJs("window.dispatchEvent(new CustomEvent('wand-android-network',"
                    + "{detail:{state:'" + safe + "'}}));");
        });
    }

    // ── Navigation ──

    private void openConnectScreen() {
        SessionWatcher.INSTANCE.stop();
        stopService(new Intent(this, WandForegroundService.class));
        Intent connectIntent = new Intent(this, ConnectActivity.class);
        connectIntent.putExtra("skip_auto_connect", true);
        connectIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(connectIntent);
        finish();
    }

    private void openMissingServerScreen(String missingServerId) {
        Intent connectIntent = new Intent(this, ConnectActivity.class);
        connectIntent.putExtra("skip_auto_connect", true);
        connectIntent.putExtra(WandShortcuts.EXTRA_SERVER_ID, missingServerId);
        startActivity(connectIntent);
        finish();
    }

    /** 回到原生主界面。显式导航而不是单纯 finish()——
     *  点通知直接拉起本页时任务栈里可能没有 HomeActivity。 */
    private void navigateBackToNative() {
        Intent home = new Intent(this, HomeActivity.class);
        if (serverId != null) {
            home.putExtra(WandShortcuts.EXTRA_SERVER_ID, serverId);
        }
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }

    private String buildTargetUrl() {
        if (sessionId == null || sessionId.isEmpty()) return serverUrl;
        Uri uri = Uri.parse(serverUrl);
        return uri.buildUpon()
                .clearQuery()
                .encodedQuery(uri.getEncodedQuery())
                .appendQueryParameter("session", sessionId)
                .build()
                .toString();
    }

    /**
     * WebView's CookieManager is process-global and cookie identity does not include a port.
     * Clear the app-private WebView cookie store, authenticate only the selected endpoint, and
     * only then issue the first page request. Native endpoint CookieJars remain independent.
     */
    private void prepareWebViewSession(Runnable onReady) {
        final int generation = ++webSessionGeneration;
        WandWebSession.prepareAsync(
                webSessionOwnerId,
                serverUrl,
                appToken,
                this::revokeWebSession,
                error -> {
            if (generation != webSessionGeneration || isDestroyed() || !activityResumed) return;
            if (error != null) {
                showError(error);
                return;
            }
            onReady.run();
        });
    }

    /** Called synchronously before another endpoint may replace process-global WebView cookies. */
    private void revokeWebSession() {
        webSessionGeneration++;
        cancelPendingWebInteractions();
        destroyOwnedPopupWebViews();
        destroyOwnedWebView();
    }

    private void ensureWebView() {
        if (webView != null || webViewParent == null) return;
        WebView replacement = new WebView(this);
        replacement.setId(R.id.webView);
        replacement.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webViewParent.addView(replacement, 0);
        webView = replacement;
        hasLoadedPage = false;
        lastLoadFailed = false;
        if (loadingOverlay != null) {
            loadingOverlay.animate().cancel();
            loadingOverlay.setAlpha(1f);
            loadingOverlay.setVisibility(View.VISIBLE);
        }
        setupWebView();
    }

    private void destroyOwnedWebView() {
        WebView current = webView;
        if (current == null) return;
        webView = null;
        hasLoadedPage = false;
        lastLoadFailed = false;
        try { current.getSettings().setBlockNetworkLoads(true); } catch (Exception ignored) {}
        try { current.getSettings().setJavaScriptEnabled(false); } catch (Exception ignored) {}
        try { current.stopLoading(); } catch (Exception ignored) {}
        try { current.onPause(); } catch (Exception ignored) {}
        try {
            if (current.getParent() instanceof ViewGroup) {
                ((ViewGroup) current.getParent()).removeView(current);
            }
        } catch (Exception ignored) {}
        try { current.removeAllViews(); } catch (Exception ignored) {}
        try { current.destroy(); } catch (Exception ignored) {}
    }

    private void destroyOwnedPopupWebViews() {
        for (WebView popup : new ArrayList<>(popupWebViews)) {
            destroyPopupWebView(popup);
        }
    }

    private void destroyPopupWebView(WebView popup) {
        if (!popupWebViews.remove(popup)) return;
        try { popup.getSettings().setBlockNetworkLoads(true); } catch (Exception ignored) {}
        try { popup.getSettings().setJavaScriptEnabled(false); } catch (Exception ignored) {}
        try { popup.stopLoading(); } catch (Exception ignored) {}
        try { popup.onPause(); } catch (Exception ignored) {}
        try {
            if (popup.getParent() instanceof ViewGroup) {
                ((ViewGroup) popup.getParent()).removeView(popup);
            }
        } catch (Exception ignored) {}
        try { popup.removeAllViews(); } catch (Exception ignored) {}
        try { popup.destroy(); } catch (Exception ignored) {}
    }

    private void cancelPendingWebInteractions() {
        if (pendingFileChooserCallback != null) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
        }
        if (pendingWebPermissionRequest != null) {
            pendingWebPermissionRequest.deny();
            pendingWebPermissionRequest = null;
            pendingWebPermissionResources = null;
        }
    }

    private void prepareWebViewSessionAndLoad() {
        prepareWebViewSession(() -> {
            if (webView == null) return;
            webView.onResume();
            hideError();
            webView.loadUrl(buildTargetUrl());
        });
    }

    private void prepareWebViewSessionForResume() {
        final boolean shouldReload = !hasLoadedPage || lastLoadFailed
                || (errorOverlay != null && errorOverlay.getVisibility() == View.VISIBLE);
        prepareWebViewSession(() -> {
            if (webView == null) return;
            webView.onResume();
            if (shouldReload) {
                hideError();
                webView.loadUrl(buildTargetUrl());
                return;
            }
            webView.post(() -> {
                try {
                    webView.evaluateJavascript(
                            "window.dispatchEvent(new Event('wand-android-resume'));", null);
                } catch (Exception ignored) {}
            });
        });
    }

    // ── WebView setup ──

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(settings, true);
        }

        String versionName = "1.0";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        settings.setUserAgentString(settings.getUserAgentString()
                + " WandApp/" + versionName + " WandPlatform/Android");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        installWindowInsetsBridge();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                lastLoadFailed = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hasLoadedPage = true;
                if (lastLoadFailed) return;
                hideError();
                hideLoadingOverlay();
                injectNativeInsetsMarker();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    lastLoadFailed = true;
                    int msgRes;
                    switch (error.getErrorCode()) {
                        case ERROR_HOST_LOOKUP:
                            msgRes = R.string.error_host_lookup; break;
                        case ERROR_CONNECT:
                        case ERROR_IO:
                            msgRes = R.string.error_connect; break;
                        case ERROR_TIMEOUT:
                            msgRes = R.string.error_timeout; break;
                        default:
                            msgRes = networkMonitor.hasUsableNetwork()
                                    ? R.string.connection_failed : R.string.error_no_network;
                    }
                    showError(getString(msgRes));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (error != null && error.getUrl() != null
                        && isSameServerOrigin(Uri.parse(error.getUrl()))) {
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!request.isForMainFrame()) return !isSameServerOrigin(uri);
                if (isSameServerOrigin(uri)) return false;
                openExternalUri(uri);
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                try {
                    webSessionGeneration++;
                    cancelPendingWebInteractions();
                    destroyOwnedPopupWebViews();
                    destroyOwnedWebView();
                    ensureWebView();
                    Toast.makeText(MainActivity.this, R.string.renderer_crashed,
                            Toast.LENGTH_SHORT).show();
                    if (activityResumed) prepareWebViewSessionAndLoad();
                } catch (Exception e) {
                    recreate();
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }
                pendingFileChooserCallback = filePathCallback;

                Intent contentIntent;
                try {
                    contentIntent = fileChooserParams.createIntent();
                } catch (Exception ignored) {
                    contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    contentIntent.setType("*/*");
                }
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                Intent chooser = Intent.createChooser(contentIntent,
                        fileChooserParams.getTitle() != null
                                ? fileChooserParams.getTitle().toString()
                                : "选择文件");
                try {
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    pendingFileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "未找到可用的文件选择器",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                if (!isUserGesture) return false;
                WebView popup = new WebView(MainActivity.this);
                final int popupGeneration = webSessionGeneration;
                boolean[] handled = {false};
                popupWebViews.add(popup);
                popup.setWebViewClient(new WebViewClient() {
                    private void handle(Uri uri) {
                        if (handled[0] || uri == null) return;
                        if ("about".equalsIgnoreCase(uri.getScheme())) return;
                        handled[0] = true;
                        WebView current = webView;
                        if (popupGeneration != webSessionGeneration
                                || !popupWebViews.contains(popup)
                                || current == null) {
                            destroyPopupWebView(popup);
                            return;
                        }
                        if (isSameServerOrigin(uri)) {
                            current.loadUrl(uri.toString());
                        } else {
                            openExternalUri(uri);
                        }
                        destroyPopupWebView(popup);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popupView,
                                                            WebResourceRequest request) {
                        if ("about".equalsIgnoreCase(request.getUrl().getScheme())) return false;
                        handle(request.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView popupView, String url, Bitmap favicon) {
                        handle(Uri.parse(url));
                    }
                });
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                popup.postDelayed(() -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    destroyPopupWebView(popup);
                }, 10_000);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                handleWebPermissionRequest(request);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingWebPermissionRequest == request) {
                        pendingWebPermissionRequest = null;
                        pendingWebPermissionResources = null;
                    }
                });
            }
        });

        webView.setDownloadListener(this::startBrowserDownload);
        webView.addJavascriptInterface(new NativeBridge(), "WandNative");
    }

    private boolean isSameServerOrigin(Uri uri) {
        if (uri == null || serverUrl == null) return false;
        try {
            Uri server = Uri.parse(serverUrl);
            String leftScheme = server.getScheme();
            String rightScheme = uri.getScheme();
            String leftHost = server.getHost();
            String rightHost = uri.getHost();
            return leftScheme != null
                    && leftScheme.equalsIgnoreCase(rightScheme)
                    && leftHost != null
                    && leftHost.equalsIgnoreCase(rightHost)
                    && effectivePort(server) == effectivePort(uri);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int effectivePort(Uri uri) {
        int explicit = uri.getPort();
        if (explicit >= 0) return explicit;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void openExternalUri(Uri uri) {
        if (uri == null) return;
        try {
            if ("intent".equalsIgnoreCase(uri.getScheme())) {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else if (fallback != null && !fallback.isEmpty()) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                } else {
                    throw new IllegalStateException("No activity can handle this link");
                }
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Toast.makeText(this, "无法打开此链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void startBrowserDownload(String url, String userAgent, String contentDisposition,
                                      String mimeType, long contentLength) {
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception ignored) {
            Toast.makeText(this, "下载地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            openExternalUri(uri);
            return;
        }

        String resolvedMime = mimeType;
        if (resolvedMime == null || resolvedMime.trim().isEmpty()) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(url);
            resolvedMime = extension == null
                    ? null
                    : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        if (resolvedMime == null || resolvedMime.trim().isEmpty()) {
            resolvedMime = "application/octet-stream";
        }

        String fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .trim();
        if (fileName.isEmpty()) fileName = "wand-download";

        if (isSameServerOrigin(uri)) {
            startSameOriginDownload(uri, fileName, resolvedMime, userAgent);
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(fileName)
                    .setDescription("正在从 Wand 下载")
                    .setMimeType(resolvedMime)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "已开始下载：" + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, "系统下载不可用，已改用外部浏览器", Toast.LENGTH_LONG).show();
            openExternalUri(uri);
        }
    }

    private void startSameOriginDownload(Uri uri, String fileName, String mimeType,
                                         String userAgent) {
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            Toast.makeText(this, "下载服务暂不可用", Toast.LENGTH_LONG).show();
            return;
        }
        String cookie = WandHttp.cookieHeaderFor(serverUrl);
        Toast.makeText(this, "已开始下载：" + fileName, Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            Uri outputUri = null;
            HttpURLConnection connection = null;
            try {
                String currentUrl = uri.toString();
                int responseCode = 0;
                for (int redirects = 0; redirects < 5; redirects++) {
                    connection = NetUtils.openConnection(
                            currentUrl,
                            NetUtils.DOWNLOAD_CONNECT_TIMEOUT_MS,
                            NetUtils.DOWNLOAD_READ_TIMEOUT_MS);
                    connection.setInstanceFollowRedirects(false);
                    if (cookie != null && !cookie.isEmpty()) {
                        connection.setRequestProperty("Cookie", cookie);
                    }
                    if (userAgent != null && !userAgent.isEmpty()) {
                        connection.setRequestProperty("User-Agent", userAgent);
                    }
                    connection.setRequestProperty("Referer", serverUrl);
                    responseCode = connection.getResponseCode();
                    if (responseCode < 300 || responseCode >= 400) break;

                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.isEmpty()) {
                        throw new IllegalStateException("服务器返回了无目标的重定向");
                    }
                    currentUrl = new URL(new URL(currentUrl), location).toString();
                    if (!isSameServerOrigin(Uri.parse(currentUrl))) {
                        throw new IllegalStateException("下载被重定向到非 Wand 地址");
                    }
                }
                if (connection == null || responseCode < 200 || responseCode >= 300) {
                    throw new IllegalStateException("服务器返回 " + responseCode);
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                outputUri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IllegalStateException("无法创建下载文件");

                try (InputStream input = connection.getInputStream();
                     OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
                    if (output == null) throw new IllegalStateException("无法写入下载文件");
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count > 0) output.write(buffer, 0, count);
                    }
                }

                ContentValues completed = new ContentValues();
                completed.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(outputUri, completed, null, null);
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "下载完成，可在“下载”目录查看",
                        Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                if (outputUri != null) {
                    try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
                }
                String message = NetworkErrorHelper.describeError(error, "download");
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "下载失败：" + message,
                        Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        runOnUiThread(() -> {
            if (request == null || !isSameServerOrigin(request.getOrigin())) {
                if (request != null) request.deny();
                return;
            }

            List<String> resources = new ArrayList<>();
            List<String> missingPermissions = new ArrayList<>();
            for (String resource : request.getResources()) {
                String androidPermission = null;
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    androidPermission = Manifest.permission.RECORD_AUDIO;
                } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                    androidPermission = Manifest.permission.CAMERA;
                }
                if (androidPermission == null) continue;
                resources.add(resource);
                if (ContextCompat.checkSelfPermission(MainActivity.this, androidPermission)
                        != PackageManager.PERMISSION_GRANTED
                        && !missingPermissions.contains(androidPermission)) {
                    missingPermissions.add(androidPermission);
                }
            }

            if (resources.isEmpty()) {
                request.deny();
                return;
            }
            if (missingPermissions.isEmpty()) {
                request.grant(resources.toArray(new String[0]));
                return;
            }

            if (pendingWebPermissionRequest != null) {
                pendingWebPermissionRequest.deny();
            }
            pendingWebPermissionRequest = request;
            pendingWebPermissionResources = resources.toArray(new String[0]);
            ActivityCompat.requestPermissions(
                    MainActivity.this,
                    missingPermissions.toArray(new String[0]),
                    WEB_MEDIA_PERMISSION_REQUEST);
        });
    }

    // ── PendingIntent factory ──

    PendingIntent buildSelfPendingIntent(int requestCode) {
        Intent intent = new Intent(this, MainActivity.class);
        if (serverId != null) {
            intent.putExtra(WandShortcuts.EXTRA_SERVER_ID, serverId);
        }
        intent.setData(new Uri.Builder()
                .scheme("wand")
                .authority("web-server")
                .appendPath(serverId == null ? serverUrl : serverId)
                .appendPath(String.valueOf(requestCode))
                .build());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private String notificationScope() {
        return serverId != null ? serverId : "legacy-" + Integer.toHexString(serverUrl.hashCode());
    }

    private String scopedNotificationTag(String tag) {
        if (tag == null || tag.isEmpty()) return tag;
        int separator = tag.indexOf(':');
        if (separator < 0) return tag + ":" + notificationScope();
        return tag.substring(0, separator + 1)
                + notificationScope() + ":" + tag.substring(separator + 1);
    }

    private String scopedProgressId(String sessionId) {
        return notificationScope() + ":" + sessionId;
    }

    // ── JS bridge ──

    private class NativeBridge {

        @JavascriptInterface
        public void switchServer() {
            runOnUiThread(MainActivity.this::openConnectScreen);
        }

        @JavascriptInterface
        public void backToNative() {
            // 网页侧边栏「返回App」按钮：回到原生主界面。
            runOnUiThread(MainActivity.this::navigateBackToNative);
        }

        @JavascriptInterface
        public String getPermission() {
            int result = ContextCompat.checkSelfPermission(
                    MainActivity.this, android.Manifest.permission.POST_NOTIFICATIONS);
            if (result == PackageManager.PERMISSION_GRANTED) return "granted";
            if (serverStore.wasNotificationPermissionRequested()
                    || ActivityCompat.shouldShowRequestPermissionRationale(
                            MainActivity.this, android.Manifest.permission.POST_NOTIFICATIONS))
                return "denied";
            return "default";
        }

        @JavascriptInterface
        public void requestPermission() {
            serverStore.markNotificationPermissionRequested();
            runOnUiThread(() -> ActivityCompat.requestPermissions(
                    MainActivity.this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST));
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                } catch (Exception ignored) {
                    Toast.makeText(MainActivity.this,
                            "无法打开通知设置，请在系统设置中找到 Wand",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String getAppIcon() {
            return serverStore.getAppIcon();
        }

        @JavascriptInterface
        public void setAppIcon(String iconName) {
            AppIconSwitcher.setAppIcon(MainActivity.this, serverStore, iconName);
        }

        @JavascriptInterface
        public String getAppVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void downloadUpdate(String url, String fileName, String source) {
            runOnUiThread(() -> updateManager.downloadAndInstall(url, fileName, source, null));
        }

        @JavascriptInterface
        public void sendNotification(String title, String body, String tag) {
            String scopedTag = scopedNotificationTag(tag);
            int requestCode = (scopedTag != null ? scopedTag.hashCode() : 0) & 0x7FFFFFFF;
            notificationHelper.sendNotification(title, body, scopedTag,
                    buildSelfPendingIntent(requestCode), serverStore);
        }

        @JavascriptInterface
        public String getNotificationSound() {
            return serverStore.getNotificationSound();
        }

        @JavascriptInterface
        public boolean isNotificationSoundEnabled() {
            return serverStore.isNotificationSoundEnabled();
        }

        @JavascriptInterface
        public void setNotificationSoundEnabled(boolean enabled) {
            serverStore.setNotificationSoundEnabled(enabled);
        }

        @JavascriptInterface
        public void setNotificationSound(String name) {
            if (!NotificationHelper.isValidSound(name)) return;
            serverStore.setNotificationSound(name);
        }

        @JavascriptInterface
        public String getAvailableSounds() {
            try {
                JSONArray arr = new JSONArray();
                for (String[] preset : NotificationHelper.SOUND_PRESETS) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", preset[0]);
                    obj.put("name", preset[1]);
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public int getNotificationVolume() {
            return serverStore.getNotificationVolume();
        }

        @JavascriptInterface
        public void setNotificationVolume(int volume) {
            serverStore.setNotificationVolume(volume);
        }

        @JavascriptInterface
        public void previewSound(String name) {
            if (!NotificationHelper.isValidSound(name)) return;
            runOnUiThread(() -> {
                if (notificationHelper.isSystemMuted()) {
                    Toast.makeText(MainActivity.this,
                            "系统已静音/振动模式，无法预览声音",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                notificationHelper.playPresetSound(name,
                        serverStore.getNotificationVolume() / 100f);
            });
        }

        @JavascriptInterface
        public void updateSessionProgress(String sessionId, String jsonData) {
            String scopedSessionId = scopedProgressId(sessionId);
            int requestCode = ("progress:" + scopedSessionId).hashCode() & 0x7FFFFFFF;
            notificationHelper.updateSessionProgress(scopedSessionId, jsonData,
                    buildSelfPendingIntent(requestCode));
        }

        @JavascriptInterface
        public void clearSessionProgress(String sessionId) {
            notificationHelper.clearSessionProgress(scopedProgressId(sessionId));
        }

        @JavascriptInterface
        public String copyToClipboard(String text) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("wand", text));
                    return "ok";
                }
                return "error";
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void startKeepAlive() {
            if (keepAliveRunning) return;
            keepAliveRunning = true;
            runOnUiThread(() -> {
                try {
                    Intent serviceIntent = new Intent(MainActivity.this, WandForegroundService.class);
                    if (serverId != null) {
                        serviceIntent.putExtra(WandShortcuts.EXTRA_SERVER_ID, serverId);
                    }
                    startForegroundService(serviceIntent);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopKeepAlive() {
            if (!keepAliveRunning) return;
            keepAliveRunning = false;
            runOnUiThread(() -> {
                try {
                    stopService(new Intent(MainActivity.this, WandForegroundService.class));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void vibrate(String pattern) {
            if (!serverStore.isHapticEnabled()) return;
            android.os.Vibrator vibrator = (android.os.Vibrator)
                    getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            try {
                android.os.VibrationEffect effect;
                switch (pattern != null ? pattern : "light") {
                    case "medium":
                        effect = android.os.VibrationEffect.createOneShot(30,
                                android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                        break;
                    case "success":
                        effect = android.os.VibrationEffect.createWaveform(
                                new long[]{0, 10, 80, 10}, -1);
                        break;
                    case "error":
                        effect = android.os.VibrationEffect.createWaveform(
                                new long[]{0, 30, 60, 30, 60, 30}, -1);
                        break;
                    case "light":
                    default:
                        effect = android.os.VibrationEffect.createOneShot(10,
                                android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                        break;
                }
                vibrator.vibrate(effect);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean isHapticEnabled() {
            return serverStore.isHapticEnabled();
        }

        @JavascriptInterface
        public void setHapticEnabled(boolean enabled) {
            serverStore.setHapticEnabled(enabled);
        }
    }

    // ── Activity results & permissions ──

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (updateManager.handleActivityResult(requestCode)) return;

        if (requestCode != FILE_CHOOSER_REQUEST) return;
        ValueCallback<Uri[]> cb = pendingFileChooserCallback;
        pendingFileChooserCallback = null;
        if (cb == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        cb.onReceiveValue(results);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            String result = (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    ? "granted" : "denied";
            webView.evaluateJavascript(
                    "if(window._onNativePermissionResult) window._onNativePermissionResult('" + result + "');",
                    null);
            return;
        }
        if (requestCode == WEB_MEDIA_PERMISSION_REQUEST) {
            PermissionRequest request = pendingWebPermissionRequest;
            String[] resources = pendingWebPermissionResources;
            pendingWebPermissionRequest = null;
            pendingWebPermissionResources = null;
            if (request == null || resources == null) return;

            boolean granted = true;
            for (String resource : resources) {
                String permission = PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                        ? Manifest.permission.RECORD_AUDIO
                        : PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                            ? Manifest.permission.CAMERA
                            : null;
                if (permission == null || ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) request.grant(resources);
            else request.deny();
        }
    }

    // ── Lifecycle ──

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String targetServerId = intent.getStringExtra(WandShortcuts.EXTRA_SERVER_ID);
        String targetServerUrl = intent.getStringExtra("server_url");
        boolean differentEndpoint = targetServerId != null
                ? !targetServerId.equals(serverId)
                : targetServerUrl != null && !targetServerUrl.equals(serverUrl);
        if (!differentEndpoint) return;

        // SINGLE_TOP/CLEAR_TOP notifications must not reuse a WebView authenticated to another
        // endpoint. A fresh Activity gets a fresh routing state and runs cookie preparation first.
        Intent replacement = new Intent(intent).setClass(this, MainActivity.class);
        replacement.setFlags(0);
        SessionWatcher.INSTANCE.stop();
        stopService(new Intent(this, WandForegroundService.class));
        startActivity(replacement);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        ensureWebView();
        if (webView == null) return;
        prepareWebViewSessionForResume();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        webSessionGeneration++;
        cancelPendingWebInteractions();
        destroyOwnedPopupWebViews();
        destroyOwnedWebView();
        WandWebSession.release(webSessionOwnerId);
        if (keepAliveRunning) {
            try { stopService(new Intent(this, WandForegroundService.class)); } catch (Exception ignored) {}
            keepAliveRunning = false;
        }
        if (networkMonitor != null) networkMonitor.unregister();
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
            backgroundExecutor = null;
        }
        if (notificationHelper != null) notificationHelper.cancelAllProgress();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView == null) return;
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            evalJs("if(window.wandTrimCache)window.wandTrimCache(" + level + ");");
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            try { webView.freeMemory(); } catch (Exception ignored) {}
        }
    }

    // ── Error/loading overlay ──

    private void showError(String message) {
        errorMessage.setText(message);
        errorOverlay.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
            loadingOverlay.animate().cancel();
            loadingOverlay.setAlpha(1f);
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay == null || loadingOverlay.getVisibility() != View.VISIBLE) return;
        loadingOverlay.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                })
                .start();
    }

    // ── Window insets (edge-to-edge + IME) ──

    private void installWindowInsetsBridge() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insetsCompat) -> {
            Insets bars = insetsCompat.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insetsCompat.getInsets(WindowInsetsCompat.Type.ime());

            lastSysBarTopPx = bars.top;
            lastSysBarBottomPx = bars.bottom;
            lastSysBarLeftPx = bars.left;
            lastSysBarRightPx = bars.right;

            if (!imeAnimating) {
                lastImeBottomPx = ime.bottom;
                applyInsetPadding(v);
            }

            injectNativeInsetsMarker();

            return new WindowInsetsCompat.Builder(insetsCompat)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                .build();
        });

        ViewCompat.setWindowInsetsAnimationCallback(root, new WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {

            @Override
            public void onPrepare(WindowInsetsAnimationCompat animation) {
                if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                    imeAnimating = true;
                    dispatchImeState("start");
                }
            }

            @Override
            public WindowInsetsCompat onProgress(WindowInsetsCompat insets,
                                                 java.util.List<WindowInsetsAnimationCompat> running) {
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                lastImeBottomPx = ime.bottom;
                applyInsetPadding(root);
                return insets;
            }

            @Override
            public void onEnd(WindowInsetsAnimationCompat animation) {
                if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                    imeAnimating = false;
                    applyInsetPadding(root);
                    dispatchImeState(lastImeBottomPx > 0 ? "shown" : "hidden");
                }
            }
        });

        ViewCompat.requestApplyInsets(root);
    }

    private void applyInsetPadding(View v) {
        int bottom = Math.max(lastSysBarBottomPx, lastImeBottomPx);
        v.setPadding(lastSysBarLeftPx, lastSysBarTopPx, lastSysBarRightPx, bottom);
    }

    private void dispatchImeState(String state) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String safe = state == null ? "" : state.replace("'", "");
            evalJs("window.__wandImeNative=true;"
                    + "window.dispatchEvent(new CustomEvent('wand-ime-state',"
                    + "{detail:{state:'" + safe + "'}}));");
        });
    }

    // ── System bar appearance ──

    private void applySystemBarAppearance() {
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) return;
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(true);
    }

    private void injectNativeInsetsMarker() {
        evalJs(
            "var r=document.documentElement;" +
            "r.classList.add('is-wand-app-native-insets');" +
            "r.style.setProperty('--app-inset-top','0px');" +
            "r.style.setProperty('--app-inset-bottom','0px');" +
            "r.style.setProperty('--app-inset-left','0px');" +
            "r.style.setProperty('--app-inset-right','0px');");
    }

    // ── JS eval helper ──

    private void evalJs(String innerBody) {
        if (webView == null) return;
        try {
            webView.evaluateJavascript("(function(){try{" + innerBody + "}catch(e){}})();", null);
        } catch (Exception ignored) {}
    }
}
