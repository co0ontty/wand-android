package com.wand.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import com.wand.app.data.WandHttp;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {

    static final int INSTALL_PERMISSION_REQUEST = 1003;

    private final AppCompatActivity activity;
    private final ServerStore serverStore;
    private final ExecutorService executor;
    private final String serverUrl;
    private File pendingInstallFile;

    UpdateManager(AppCompatActivity activity, ServerStore serverStore,
                  ExecutorService executor, String serverUrl) {
        this.activity = activity;
        this.serverStore = serverStore;
        this.executor = executor;
        this.serverUrl = serverUrl;
        // 启动即清扫历史更新包：旧版本下载安装后从不清理，长期使用的设备上
        // 会堆积数 GB 的过期 APK（这是历史遗留问题，见 sweepOnLaunch）。
        if (executor != null) {
            executor.execute(() -> sweepOnLaunch(activity));
        }
    }

    interface UpdateFoundCallback {
        void onUpdateFound(String currentVersion, String latestVersion,
                           String downloadUrl, String fileName, long size,
                           String source, String releaseNotes, String channel, String sha256);
    }

    interface NoUpdateCallback {
        void onNoUpdate(String message);
    }

    /**
     * Compose 更新面板使用的下载句柄。下载可以在面板仍然打开时被取消，但取消不会
     * 影响已经落盘的已完成安装包。
     */
    static final class DownloadRequest {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        void cancel() {
            cancelled.set(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** 让原生 Compose 界面接管下载的进度、完成和失败状态。 */
    interface DownloadListener {
        void onProgress(long downloadedBytes, long totalBytes, long bytesPerSecond);
        void onCompleted(File apkFile);
        void onCancelled();
        void onFailed(String message);
    }

    void checkForUpdate(UpdateFoundCallback callback) {
        performCheckForUpdate(callback, null);
    }

    void checkForUpdate(UpdateFoundCallback callback, NoUpdateCallback noUpdateCallback) {
        performCheckForUpdate(callback, noUpdateCallback);
    }

    private void performCheckForUpdate(UpdateFoundCallback callback, NoUpdateCallback noUpdateCallback) {
        String currentVersion;
        try {
            currentVersion = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            notifyNoUpdate(noUpdateCallback, "无法读取当前版本。");
            return;
        }

        if (executor == null || executor.isShutdown()) {
            notifyNoUpdate(noUpdateCallback, "更新检查暂不可用。");
            return;
        }
        executor.execute(() -> {
            try {
                // 更新通道随设置走：beta 接收 -debug 开发构建，stable 只看正式版。
                String channel = serverStore.isBetaChannel() ? "beta" : "stable";
                String apiUrl = serverUrl + "/api/android-apk-update?currentVersion=" +
                        java.net.URLEncoder.encode(currentVersion, "UTF-8") +
                        "&channel=" + channel;
                HttpURLConnection conn = NetUtils.openConnection(apiUrl,
                        NetUtils.CONNECT_TIMEOUT_MS, NetUtils.READ_TIMEOUT_MS);

                String cookie = WandHttp.cookieHeaderFor(serverUrl);
                if (cookie != null) conn.setRequestProperty("Cookie", cookie);

                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    notifyNoUpdate(noUpdateCallback, "检查更新失败：服务器返回 " + code);
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject data = new JSONObject(sb.toString());
                if (!data.optBoolean("updateAvailable", false)) {
                    notifyNoUpdate(noUpdateCallback,
                            "beta".equals(channel) ? "已是最新 Beta 版本。" : "已是最新正式版。");
                    return;
                }

                String latestVersion = data.optString("latestVersion", "");
                String downloadUrl = data.optString("downloadUrl", "");
                String fileName = data.optString("fileName", "wand-update.apk");
                long size = data.optLong("size", 0);
                String source = data.optString("source", "");
                String releaseNotes = data.optString("releaseNotes", "");
                // 服务端对本地分发的 APK 计算 SHA-256（旧服务端没有该字段 → 跳过校验）。
                String sha256 = data.optString("sha256", "");

                if (latestVersion.isEmpty() || downloadUrl.isEmpty()) {
                    notifyNoUpdate(noUpdateCallback, "没有可用的更新包。");
                    return;
                }
                if (latestVersion.equals(serverStore.getSkippedVersion(channel))) {
                    notifyNoUpdate(noUpdateCallback, "这个版本已被跳过。");
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    callback.onUpdateFound(currentVersion, latestVersion,
                            downloadUrl, fileName, size, source, releaseNotes, channel, sha256);
                });

            } catch (Exception e) {
                notifyNoUpdate(noUpdateCallback,
                        NetworkErrorHelper.describeError(e, "check_update"));
            }
        });
    }

    private void notifyNoUpdate(NoUpdateCallback callback, String message) {
        if (callback == null) return;
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed()) return;
            callback.onNoUpdate(message);
        });
    }

    @SuppressLint("DefaultLocale")
    void showUpdateDialog(String currentVer, String latestVer,
                          String downloadUrl, String fileName, long size,
                          String source, String releaseNotes, String channel, String sha256) {
        String sizeText = size > 0 ? "\n文件大小: " + formatSize(size) : "";
        String sourceText = "github".equals(source) ? "\n来源: GitHub Release" : "";
        String channelText = "beta".equals(channel) ? "\n通道: Beta" : "\n通道: Stable";
        String notesText = (releaseNotes != null && !releaseNotes.isEmpty())
                ? "\n\n更新内容:\n" + releaseNotes : "";

        new MaterialAlertDialogBuilder(activity, R.style.Theme_Wand_Dialog)
                .setTitle(R.string.update_title)
                .setMessage("当前版本: " + currentVer + "\n最新版本: " + latestVer
                        + channelText + sizeText + sourceText + notesText)
                .setPositiveButton(R.string.update_now, (dialog, which) ->
                        downloadAndInstall(downloadUrl, fileName, source, latestVer, channel, sha256, size))
                .setNegativeButton(R.string.remind_later, null)
                .setNeutralButton(R.string.skip_version, (dialog, which) ->
                        serverStore.setSkippedVersion(latestVer, channel))
                .setCancelable(true)
                .show();
    }

    void downloadAndInstall(String downloadUrl, String fileName,
                            String source, String latestVersion) {
        downloadAndInstall(downloadUrl, fileName, source, latestVersion,
                serverStore.isBetaChannel() ? "beta" : "stable", null);
    }

    void downloadAndInstall(String downloadUrl, String fileName,
                            String source, String latestVersion, String channel) {
        downloadAndInstall(downloadUrl, fileName, source, latestVersion, channel, null);
    }

    void downloadAndInstall(String downloadUrl, String fileName,
                            String source, String latestVersion, String channel, String sha256) {
        downloadAndInstall(downloadUrl, fileName, source, latestVersion, channel, sha256, 0);
    }

    void downloadAndInstall(String downloadUrl, String fileName,
                            String source, String latestVersion, String channel, String sha256,
                            long expectedSize) {
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            Toast.makeText(activity, "下载地址为空", Toast.LENGTH_LONG).show();
            return;
        }
        final String safeFileName = sanitizeApkFileName(fileName);

        View progressView = activity.getLayoutInflater()
                .inflate(R.layout.dialog_download_progress, null);
        final ProgressBar progressBar = progressView.findViewById(R.id.progressBar);
        final TextView progressPercent = progressView.findViewById(R.id.progressPercent);
        final TextView progressBytes = progressView.findViewById(R.id.progressBytes);

        final DownloadRequest[] request = {null};
        final AlertDialog progress = new MaterialAlertDialogBuilder(activity, R.style.Theme_Wand_Dialog)
                .setView(progressView)
                .setNegativeButton(R.string.cancel_download, (d, w) -> {
                    if (request[0] != null) request[0].cancel();
                })
                .setCancelable(false)
                .create();
        progress.show();

        request[0] = download(
                downloadUrl,
                safeFileName,
                latestVersion,
                channel,
                sha256,
                expectedSize,
                new DownloadListener() {
                    @Override public void onProgress(long downloaded, long total, long bytesPerSecond) {
                        String speedText = "  " + formatSize(bytesPerSecond) + "/s";
                        if (total > 0) {
                            int percent = (int) (downloaded * 100 / total);
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(percent);
                            progressPercent.setText(percent + "%");
                            progressBytes.setText(formatSize(downloaded) + " / "
                                    + formatSize(total) + speedText);
                        } else {
                            progressBar.setIndeterminate(true);
                            progressPercent.setText("大小未知");
                            progressBytes.setText(formatSize(downloaded) + speedText);
                        }
                    }

                    @Override public void onCompleted(File apkFile) {
                        progress.dismiss();
                        installApk(apkFile);
                    }

                    @Override public void onCancelled() {
                        progress.dismiss();
                    }

                    @Override public void onFailed(String message) {
                        progress.dismiss();
                        new MaterialAlertDialogBuilder(activity, R.style.Theme_Wand_Dialog)
                            .setTitle("下载失败")
                            .setMessage(message)
                            .setPositiveButton("重试", (d, w) ->
                                    downloadAndInstall(downloadUrl, safeFileName, source, latestVersion, channel, sha256, expectedSize))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    }
                }
        );
    }

    /**
     * 只下载，不直接弹窗或安装。HomeActivity 的 Compose 更新面板以此驱动进度状态；
     * MainActivity 仍通过上面的兼容入口使用相同的网络和落盘逻辑。
     *
     * 安全语义：
     * - 关闭自动重定向，手工逐跳处理；每跳用 [NetUtils#openConnection] 的 origin
     *   限定版本打开——只有 wand server 同源的跳信任自签名证书，GitHub 等跨源
     *   跳走系统默认校验，Cookie 也只发给同源跳。
     * - 先写 {@code <fileName>.part} 临时文件，完整 + 哈希校验通过后才 rename 成
     *   最终文件名，进程被杀不会留下可被当作「待安装更新」的截断 APK。
     * - GitHub 来源用 Release digest + 检查接口给出的 size；再叠加 Content-Length
     *   比对和 zip magic。失败自动整体重试至多 {@link #MAX_DOWNLOAD_ATTEMPTS} 次。
     */
    DownloadRequest download(String downloadUrl, String fileName,
                             String latestVersion, String channel,
                             DownloadListener listener) {
        return download(downloadUrl, fileName, latestVersion, channel, null, 0, listener);
    }

    DownloadRequest download(String downloadUrl, String fileName,
                             String latestVersion, String channel, String expectedSha256,
                             DownloadListener listener) {
        return download(downloadUrl, fileName, latestVersion, channel, expectedSha256, 0, listener);
    }

    DownloadRequest download(String downloadUrl, String fileName,
                             String latestVersion, String channel, String expectedSha256,
                             long expectedSize, DownloadListener listener) {
        final DownloadRequest request = new DownloadRequest();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            postDownloadFailure(listener, "下载地址为空");
            return request;
        }
        if (executor == null || executor.isShutdown()) {
            postDownloadFailure(listener, "下载服务暂不可用，请稍后重试。");
            return request;
        }
        final String safeFileName = sanitizeApkFileName(fileName);
        executor.execute(() -> {
            // 旧客户端仍可能直连 GitHub CDN；新服务端会改走 wand 同源代理。
            // 跨境链路或代理中途 reset 时，截断包由完整性校验拦下后在这里整体重下。
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
                try {
                    downloadAttempt(downloadUrl, safeFileName, expectedSha256, expectedSize,
                            latestVersion, channel, listener, request);
                    // 成功与用户取消都已在 downloadAttempt 内回调收尾。
                    return;
                } catch (Exception e) {
                    lastFailure = e;
                    if (request.isCancelled()) {
                        postDownloadCancelled(listener);
                        return;
                    }
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        try {
                            Thread.sleep(DOWNLOAD_RETRY_DELAY_MS * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            postDownloadCancelled(listener);
                            return;
                        }
                    }
                }
            }
            postDownloadFailure(listener, NetworkErrorHelper.describeError(lastFailure, "download"));
        });
        return request;
    }

    /**
     * 单次下载尝试：成功返回落盘的 APK 并回调 onCompleted；用户取消返回 null
     * （已回调 onCancelled）；失败抛出异常，由调用方决定是否重试。
     *
     * 完整性防线（按序）：
     * 1. SHA-256（响应头 X-APK-Sha256 优先，回退 check 时的值；本地哈希或 GitHub digest）；
     * 2. Content-Length，缺失时回退检查接口给出的 size——GitHub CDN 经常 chunked
     *    且不带长度，断流时 read() 同样返回 -1，不做此检查截断包会直接进安装器；
     * 3. zip magic（PK\u005cx03\u005cx04）兜底，拦截错误页 HTML 等非 APK 内容。
     * 全部通过后才把 {@code <fileName>.part} rename 成最终文件名，进程被杀也不会
     * 留下可被当作「待安装更新」的截断 APK。
     */
    private File downloadAttempt(String downloadUrl, String fileName, String expectedSha256,
                                 long expectedSize, String latestVersion, String channel,
                                 DownloadListener listener, DownloadRequest request) throws Exception {
        HttpURLConnection conn = null;
        File partFile = null;
        try {
            // 下载新包前先清掉目录里的历史 APK / 残留 .part：既释放本次下载需要的空间，
            // 也保证外部目录里任意时刻最多只有一个安装包在堆积。
            purgeStaleApks(activity, pendingInstallFile);
            String currentUrl = downloadUrl.startsWith("http")
                    ? downloadUrl : serverUrl + downloadUrl;
            int responseCode = 0;
            for (int hop = 0; hop < MAX_REDIRECT_HOPS; hop++) {
                conn = NetUtils.openConnection(currentUrl,
                        NetUtils.DOWNLOAD_CONNECT_TIMEOUT_MS, NetUtils.DOWNLOAD_READ_TIMEOUT_MS,
                        serverUrl);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "wand-android");
                conn.setRequestProperty("Accept", "application/octet-stream");
                // 禁止透明 gzip，否则 Content-Length 是压缩体积、读到的是解压后字节。
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (NetUtils.isSameOrigin(new java.net.URL(currentUrl), serverUrl)) {
                    String cookie = WandHttp.cookieHeaderFor(serverUrl);
                    if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                }
                responseCode = conn.getResponseCode();
                if (responseCode == 301 || responseCode == 302 || responseCode == 303
                        || responseCode == 307 || responseCode == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location == null || location.isEmpty()) {
                        throw new Exception("服务器重定向缺少目标地址");
                    }
                    currentUrl = new java.net.URL(new java.net.URL(currentUrl), location).toString();
                    continue;
                }
                break;
            }
            if (conn == null) throw new Exception("重定向次数过多，已中止下载");
            if (responseCode != 200) throw new Exception("服务器返回 " + responseCode);

            // 下载响应头里的 X-APK-Sha256 反映本次实际发送的字节；check 与
            // 下载之间服务端 APK 若被重新部署，以响应头为准，避免用过期
            // 快照误报完整性校验失败。旧服务端无此头 → 回退 check 时的值。
            String headerSha256 = conn.getHeaderField("X-APK-Sha256");
            final String effectiveSha256 =
                    (headerSha256 != null && !headerSha256.trim().isEmpty())
                            ? headerSha256.trim() : expectedSha256;

            long headerLength = conn.getContentLengthLong();
            long fileLength = headerLength > 0 ? headerLength : Math.max(0, expectedSize);
            File dir = activity.getExternalFilesDir(null);
            if (dir == null) throw new Exception("外部存储不可用");
            File outputFile = new File(dir, fileName);
            partFile = new File(dir, fileName + ".part");
            if (fileLength > 0) {
                long usable = dir.getUsableSpace();
                if (usable < fileLength + 5 * 1024 * 1024) {
                    throw new Exception("存储空间不足，需要约 " + formatSize(fileLength) + "，请清理后重试");
                }
            }

            final java.security.MessageDigest digest =
                    (effectiveSha256 != null && !effectiveSha256.isEmpty())
                            ? java.security.MessageDigest.getInstance("SHA-256")
                            : null;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partFile)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                long lastUiUpdate = 0;
                final long startTime = System.currentTimeMillis();
                while ((count = in.read(buffer)) != -1) {
                    if (request.isCancelled()) break;
                    total += count;
                    out.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdate > 50 || total == fileLength) {
                        lastUiUpdate = now;
                        long elapsed = Math.max(1, now - startTime);
                        postDownloadProgress(listener, total, fileLength, total * 1000 / elapsed);
                    }
                }
            }

            if (request.isCancelled()) {
                try { partFile.delete(); } catch (Exception ignored) {}
                postDownloadCancelled(listener);
                return null;
            }
            if (!partFile.exists() || partFile.length() == 0) {
                throw new Exception("下载文件为空");
            }
            // 连接被 reset 时 read() 同样返回 -1。优先信响应 Content-Length，
            // 缺失时用检查接口给出的 GitHub asset.size / 本地 size。
            if (fileLength > 0 && partFile.length() != fileLength) {
                throw new Exception("下载不完整（已接收 " + formatSize(partFile.length())
                        + " / " + formatSize(fileLength) + "），连接被中断");
            }
            if (!isZipArchive(partFile)) {
                throw new Exception("下载内容不是有效的安装包，已丢弃");
            }
            if (digest != null) {
                String actual = toHex(digest.digest());
                if (!actual.equalsIgnoreCase(effectiveSha256)) {
                    throw new Exception("安装包完整性校验失败，已丢弃本次下载");
                }
            }
            // 完整且（可选）哈希匹配后才占用最终文件名。
            if (outputFile.exists()) {
                try { outputFile.delete(); } catch (Exception ignored) {}
            }
            if (!partFile.renameTo(outputFile)) {
                throw new Exception("安装包落盘失败");
            }
            partFile = null;
            String versionToRecord = latestVersion != null
                    ? latestVersion : extractVersionFromFileName(fileName);
            if (versionToRecord != null) {
                serverStore.setDownloadedApkVersion(versionToRecord, channel);
            }
            postDownloadCompleted(listener, outputFile);
            return outputFile;
        } catch (Exception e) {
            if (partFile != null && partFile.exists()) {
                try { partFile.delete(); } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /** APK 本质是 zip；校验文件头 magic，兜底拦截错误页 HTML 或其他非 APK 内容。 */
    private static boolean isZipArchive(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            return raf.length() >= 4 && raf.readInt() == 0x504B0304;
        } catch (Exception e) {
            return false;
        }
    }

    private static final int MAX_REDIRECT_HOPS = 5;

    /**
     * 更新包下载失败自动重试次数。GitHub 直连走 objects.githubusercontent.com，
     * 跨境链路常见中途断流；截断的包由完整性校验拦下后在这里整体重下。
     */
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_DELAY_MS = 1500;

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private void postDownloadProgress(DownloadListener listener, long downloaded,
                                      long total, long bytesPerSecond) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) listener.onProgress(downloaded, total, bytesPerSecond);
        });
    }

    private void postDownloadCompleted(DownloadListener listener, File apkFile) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) listener.onCompleted(apkFile);
        });
    }

    private void postDownloadCancelled(DownloadListener listener) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) listener.onCancelled();
        });
    }

    private void postDownloadFailure(DownloadListener listener, String message) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) listener.onFailed(message);
        });
    }

    void installApk(File apkFile) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstallFile = apkFile;
            new MaterialAlertDialogBuilder(activity, R.style.Theme_Wand_Dialog)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_message)
                .setPositiveButton(R.string.install_permission_goto, (d, w) -> requestInstallPermission())
                .setNegativeButton(android.R.string.cancel, (d, w) -> pendingInstallFile = null)
                .setCancelable(true)
                .show();
            return;
        }
        doInstallApk(apkFile);
    }

    boolean handleActivityResult(int requestCode) {
        if (requestCode != INSTALL_PERMISSION_REQUEST) return false;
        File toInstall = pendingInstallFile;
        pendingInstallFile = null;
        if (toInstall == null) return true;
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            doInstallApk(toInstall);
        } else {
            Toast.makeText(activity, R.string.install_permission_denied, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private void requestInstallPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, INSTALL_PERMISSION_REQUEST);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(fallback, INSTALL_PERMISSION_REQUEST);
            } catch (Exception ignored) {
                Toast.makeText(activity, R.string.install_permission_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doInstallApk(File apkFile) {
        if (apkFile == null || !apkFile.isFile() || apkFile.length() == 0) {
            showInstallFailure("安装包不存在或已损坏，请重新下载。");
            return;
        }
        if (executor == null || executor.isShutdown()) {
            try {
                installWithViewIntent(apkFile);
            } catch (Exception e) {
                showInstallFailure(e.getMessage());
            }
            return;
        }
        Toast.makeText(activity, "正在准备安装…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                installWithSession(apkFile);
            } catch (Exception sessionError) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    try {
                        installWithViewIntent(apkFile);
                    } catch (Exception fallback) {
                        showInstallFailure(fallback.getMessage());
                    }
                });
            }
        });
    }

    private void installWithSession(File apkFile) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apkFile.length());
        params.setAppPackageName(activity.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("wand-update.apk", 0, apkFile.length())) {
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                session.fsync(out);
            }
            Intent callback = new Intent(activity, UpdateInstallReceiver.class);
            callback.setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS);
            PendingIntent pending = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            try { session.abandon(); } catch (Exception ignored) {}
            throw e;
        } finally {
            try { session.close(); } catch (Exception ignored) {}
        }
    }

    private void installWithViewIntent(File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("", apkUri));
        grantInstallUriPermission(apkUri);
        activity.startActivity(intent);
    }

    private void grantInstallUriPermission(Uri apkUri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        Intent probe = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive");
        java.util.List<ResolveInfo> resolvers = activity.getPackageManager()
                .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolvers != null) {
            for (ResolveInfo info : resolvers) {
                if (info.activityInfo == null) continue;
                activity.grantUriPermission(info.activityInfo.packageName, apkUri, flags);
            }
        }
        String[] knownInstallers = {
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.miui.packageinstaller",
        };
        for (String pkg : knownInstallers) {
            try {
                activity.grantUriPermission(pkg, apkUri, flags);
            } catch (Exception ignored) {}
        }
    }

    private void showInstallFailure(String message) {
        if (activity.isDestroyed()) return;
        new MaterialAlertDialogBuilder(activity, R.style.Theme_Wand_Dialog)
            .setTitle("安装失败")
            .setMessage(message != null ? message : "无法启动系统安装器")
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /**
     * GitHub Release 文件名带 {@code +} build metadata。content URI / 部分 OEM
     * 安装器会把 {@code +} 当成空格，下载成功后无法拉起安装。
     */
    static String sanitizeApkFileName(String fileName) {
        if (fileName == null) return "wand-update.apk";
        String base = fileName.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.isEmpty()) return "wand-update.apk";
        base = base.replace('+', '-').replaceAll("[^A-Za-z0-9._-]+", "-");
        if (base.isEmpty()) return "wand-update.apk";
        if (!base.toLowerCase(Locale.ROOT).endsWith(".apk")) return base + ".apk";
        return base;
    }

    static String extractVersionFromFileName(String fileName) {
        if (fileName == null) return null;
        // 锚到结尾并让 .apk 后缀可选：否则 [A-Za-z0-9.-]+ 会把 ".apk" 一起吞进
        // 版本串（4.42.1-debug.08150708.apk），污染 setDownloadedApkVersion 的记录。
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+?)?)(?:\\.apk)?$")
                .matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 删除外部私有目录里的全部更新 APK 与下载残留（.part），保留 keep（可为 null，
     * 如正要安装的 pendingInstallFile）。返回释放的字节数。文件名带版本号
     * （wand-v4.42.1-….apk），每个版本的包都是独立文件，装完即失效，删掉不会影响任何功能。
     */
    static long purgeStaleApks(Context context, File keep) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return 0;
        File[] apks = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".apk") || lower.endsWith(".part");
        });
        if (apks == null) return 0;
        long freed = 0;
        for (File apk : apks) {
            if (keep != null && apk.getAbsolutePath().equals(keep.getAbsolutePath())) continue;
            long len = apk.length();
            if (apk.delete()) freed += len;
        }
        return freed;
    }

    /**
     * 启动清扫：修复前下载的更新包从不删除，长期使用会堆积到数 GB。最多保留
     * 一个「主版本号确实比当前已装版本更新」的最新 APK（可能是用户已下载还没
     * 安装的更新），其余全部删除。幂等，清完后再跑释放 0 字节。
     *
     * .part 是中断下载的截断残留（旧版本直写最终文件名时甚至会被误当成待安装
     * 更新保护起来），一律无条件删除。
     */
    static void sweepOnLaunch(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File[] parts = dir.listFiles((d, name) ->
                    name.toLowerCase(Locale.ROOT).endsWith(".part"));
            if (parts != null) {
                for (File part : parts) {
                    try { part.delete(); } catch (Exception ignored) {}
                }
            }
            File[] apks = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".apk"));
            if (apks == null || apks.length == 0) return;
            String installed = null;
            try {
                installed = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            File keep = null;
            for (File apk : apks) {
                if (!isNewerCoreVersion(extractVersionFromFileName(apk.getName()), installed)) continue;
                if (keep == null || apk.lastModified() > keep.lastModified()) keep = apk;
            }
            purgeStaleApks(context, keep);
        } catch (Exception ignored) {
            // 清理是尽力而为，失败不影响启动。
        }
    }

    /**
     * 按 major.minor.patch 比较 candidate 是否严格更新。忽略 -debug.时间戳 / +构建号
     * 后缀：4.42.1-debug.08150708 与已装的 4.42.1-debug.08132148 主版本相同，
     * 是已消费过的包而不是待安装更新。
     */
    static boolean isNewerCoreVersion(String candidate, String installed) {
        int[] a = coreVersion(candidate);
        int[] b = coreVersion(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** 提取主版本三元组；解析不出返回 null。 */
    private static int[] coreVersion(String version) {
        if (version == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) return null;
        try {
            return new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
