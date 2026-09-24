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

import com.wand.app.data.WandHttp;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
                WandLog.i(TAG, "检查更新 current=" + currentVersion + " channel=" + channel);
                WandHttp.SimpleResponse response = WandHttp.get(apiUrl, 10_000, serverUrl);
                int code = response.getCode();
                if (code != 200) {
                    WandLog.w(TAG, "检查更新失败 HTTP " + code);
                    notifyNoUpdate(noUpdateCallback, "检查更新失败：服务器返回 " + code);
                    return;
                }

                JSONObject data = new JSONObject(response.getBody());
                if (!data.optBoolean("updateAvailable", false)) {
                    WandLog.i(TAG, "已是最新：current=" + currentVersion
                            + " latest=" + data.optString("latestVersion", "?"));
                    notifyNoUpdate(noUpdateCallback,
                            "beta".equals(channel) ? "已是最新 Beta 版本。" : "已是最新正式版。");
                    return;
                }

                String latestVersion = data.optString("latestVersion", "");
                String downloadUrl = data.optString("downloadUrl", "");
                String fileName = data.optString("fileName", "wand-update.apk");
                long size = data.optLong("size", 0);
                String source = data.optString("source", "");
                // 服务端「没有更新说明」时下发的是 JSON null，optString 会把它读成字符串
                // "null" 并原样显示在更新面板里，所以这里的每个可空字段都走 nullString。
                String releaseNotes = nullString(data, "releaseNotes", "");
                // 服务端对本地分发的 APK 计算 SHA-256（旧服务端没有该字段 → 跳过校验）。
                String sha256 = nullString(data, "sha256", "");

                if (latestVersion.isEmpty() || downloadUrl.isEmpty()) {
                    WandLog.w(TAG, "更新响应缺少版本或下载地址");
                    notifyNoUpdate(noUpdateCallback, "没有可用的更新包。");
                    return;
                }
                if (latestVersion.equals(serverStore.getSkippedVersion(channel))) {
                    WandLog.i(TAG, "版本已被跳过 latest=" + latestVersion);
                    notifyNoUpdate(noUpdateCallback, "这个版本已被跳过。");
                    return;
                }
                WandLog.i(TAG, "发现新版本 " + latestVersion + " （" + formatSize(size)
                        + "，source=" + source + "）");
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    callback.onUpdateFound(currentVersion, latestVersion,
                            downloadUrl, fileName, size, source, releaseNotes, channel, sha256);
                });

            } catch (Exception e) {
                WandLog.e(TAG, "检查更新异常", e);
                notifyNoUpdate(noUpdateCallback,
                        NetworkErrorHelper.describeError(e, "check_update"));
            }
        });
    }

    /** JSON null / 缺失都取默认值，避免把字面量 "null" 当成内容。 */
    private static String nullString(JSONObject data, String key, String fallback) {
        if (data.isNull(key)) return fallback;
        String value = data.optString(key, fallback);
        return value.isEmpty() || "null".equals(value) ? fallback : value;
    }

    private void notifyNoUpdate(NoUpdateCallback callback, String message) {
        if (callback == null) return;
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed()) return;
            callback.onNoUpdate(message);
        });
    }

    /**
     * 只下载，不直接弹窗或安装。HomeActivity 的 Compose 更新面板以此驱动进度状态。
     *
     * 安全语义：
     * - 关闭自动重定向，手工逐跳处理；每跳用 WandHttp.requestClient 按 origin
     *   打开——只有 wand server 同源的跳信任自签名证书并带 cookie，GitHub 等跨源
     *   跳走系统默认校验。
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
        WandLog.i(TAG, "开始下载更新包 " + safeFileName + "（预期 " + formatSize(expectedSize)
                + "，sha256=" + (expectedSha256 == null || expectedSha256.isEmpty() ? "无" : "有")
                + "）");
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
                        WandLog.i(TAG, "用户取消下载 " + safeFileName);
                        postDownloadCancelled(listener);
                        return;
                    }
                    WandLog.w(TAG, "下载失败 attempt=" + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS
                            + "：" + e.getMessage(), e);
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
        Response response = null;
        File partFile = null;
        try {
            // 下载新包前先清掉目录里的历史 APK / 残留 .part：既释放本次下载需要的空间，
            // 也保证外部目录里任意时刻最多只有一个安装包在堆积。
            purgeStaleApks(activity, pendingInstallFile);
            String currentUrl = downloadUrl.startsWith("http")
                    ? downloadUrl : serverUrl + downloadUrl;
            int responseCode = 0;
            for (int hop = 0; hop < MAX_REDIRECT_HOPS; hop++) {
                OkHttpClient client = WandHttp.requestClient(
                        currentUrl, serverUrl, 15_000, 120_000);
                Request httpRequest = new Request.Builder()
                        .url(currentUrl)
                        .header("User-Agent", "wand-android")
                        .header("Accept", "application/octet-stream")
                        .header("Accept-Encoding", "identity")
                        .build();
                if (response != null) {
                    response.close();
                    response = null;
                }
                response = client.newCall(httpRequest).execute();
                responseCode = response.code();
                if (responseCode == 301 || responseCode == 302 || responseCode == 303
                        || responseCode == 307 || responseCode == 308) {
                    String location = response.header("Location");
                    response.close();
                    response = null;
                    if (location == null || location.isEmpty()) {
                        throw new Exception("服务器重定向缺少目标地址");
                    }
                    currentUrl = java.net.URI.create(currentUrl).resolve(location).toString();
                    WandLog.i(TAG, "下载重定向 → " + hostOf(currentUrl));
                    continue;
                }
                break;
            }
            if (response == null) throw new Exception("重定向次数过多，已中止下载");
            if (responseCode != 200) throw new Exception("服务器返回 " + responseCode);

            // 下载响应头里的 X-APK-Sha256 反映本次实际发送的字节；check 与
            // 下载之间服务端 APK 若被重新部署，以响应头为准，避免用过期
            // 快照误报完整性校验失败。旧服务端无此头 → 回退 check 时的值。
            String headerSha256 = response.header("X-APK-Sha256");
            final String effectiveSha256 =
                    (headerSha256 != null && !headerSha256.trim().isEmpty())
                            ? headerSha256.trim() : expectedSha256;

            long headerLength = response.body() != null ? response.body().contentLength() : -1;
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
            if (response.body() == null) throw new Exception("服务器没有返回安装包内容");
            try (InputStream in = response.body().byteStream();
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
            WandLog.i(TAG, "下载完成 " + fileName + "（" + formatSize(outputFile.length()) + "）");
            return outputFile;
        } catch (Exception e) {
            if (partFile != null && partFile.exists()) {
                try { partFile.delete(); } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            if (response != null) {
                try { response.close(); } catch (Exception ignored) {}
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
        WandLog.i(TAG, "请求安装更新包 " + (apkFile != null ? apkFile.getName() : "null"));
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            WandLog.w(TAG, "缺少「安装未知应用」权限，先引导授权");
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
            WandLog.i(TAG, "安装权限已授予，继续安装");
            doInstallApk(toInstall);
        } else {
            WandLog.w(TAG, "安装权限被拒绝，取消安装");
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
            WandLog.e(TAG, "安装包不存在或已损坏：" + apkFile);
            showInstallFailure("安装包不存在或已损坏，请重新下载。");
            return;
        }
        if (executor == null || executor.isShutdown()) {
            WandLog.w(TAG, "安装执行器已关闭，回退到 VIEW Intent 安装");
            try {
                installWithViewIntent(apkFile);
            } catch (Exception e) {
                WandLog.e(TAG, "VIEW Intent 安装失败", e);
                showInstallFailure(e.getMessage());
            }
            return;
        }
        Toast.makeText(activity, "正在准备安装…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                installWithSession(apkFile);
            } catch (Exception sessionError) {
                WandLog.w(TAG, "PackageInstaller 会话安装失败，回退到 VIEW Intent", sessionError);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    try {
                        installWithViewIntent(apkFile);
                    } catch (Exception fallback) {
                        WandLog.e(TAG, "回退安装也失败", fallback);
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
            // 回调带上会话号：旧会话被放弃后弹窗可能还活着，它的迟到回调必须能被认出来并丢弃。
            callback.putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId);
            PendingIntent pending = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            // 先记下「正在安装哪个版本」：系统安装器会杀掉本进程，重启后的客户端靠它
            // 判断更新是否已生效，以及当前进程是不是安装前的旧代码（需要重启）。
            recordPendingInstall(apkFile, sessionId);
            session.commit(pending.getIntentSender());
            WandLog.i(TAG, "已提交安装会话 sessionId=" + sessionId);
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
        recordPendingInstall(apkFile, -1);
        WandLog.i(TAG, "通过系统安装器打开安装包 " + apkFile.getName());
        activity.startActivity(intent);
    }

    /**
     * 记录本次安装请求（版本名 / versionCode / 时间），并落到持久化存储供重启后判定。
     * versionCode 直接读 APK 自身元数据，不依赖服务端下发的字符串。
     */
    private void recordPendingInstall(File apkFile, int sessionId) {
        long versionCode = 0L;
        String versionName = null;
        try {
            android.content.pm.PackageInfo archive = activity.getPackageManager()
                    .getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (archive != null) {
                versionName = archive.versionName;
                versionCode = archive.getLongVersionCode();
            }
        } catch (Exception e) {
            WandLog.w(TAG, "读取安装包版本信息失败", e);
        }
        if (versionName == null || versionName.isEmpty()) {
            versionName = extractVersionFromFileName(apkFile.getName());
        }
        serverStore.setPendingInstall(versionName, versionCode, sessionId);
        WandLog.i(TAG, "记录待安装版本 " + versionName + " (" + versionCode + ")"
                + (sessionId >= 0 ? " sessionId=" + sessionId : ""));
    }

    /** 安装包所在目录的 host（日志里不打印可能带签名的完整下载地址）。 */
    private static String hostOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() != null ? uri.getHost() : url;
        } catch (Exception e) {
            return url;
        }
    }

    /** TAG 常量，与服务端日志约定一致。 */
    private static final String TAG = "update";

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
        WandLog.e(TAG, "安装失败：" + message);
        UpdateInstallReceiver.notifyStatus(
                android.content.pm.PackageInstaller.STATUS_FAILURE,
                message != null ? message : "无法启动系统安装器");
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
