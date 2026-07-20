package com.wand.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.webkit.CookieManager;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    }

    interface UpdateFoundCallback {
        void onUpdateFound(String currentVersion, String latestVersion,
                           String downloadUrl, String fileName, long size,
                           String source, String releaseNotes, String channel);
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

                String cookie = CookieManager.getInstance().getCookie(serverUrl);
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
                            downloadUrl, fileName, size, source, releaseNotes, channel);
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
                          String source, String releaseNotes, String channel) {
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
                        downloadAndInstall(downloadUrl, fileName, source, latestVer, channel))
                .setNegativeButton(R.string.remind_later, null)
                .setNeutralButton(R.string.skip_version, (dialog, which) ->
                        serverStore.setSkippedVersion(latestVer, channel))
                .setCancelable(true)
                .show();
    }

    void downloadAndInstall(String downloadUrl, String fileName,
                            String source, String latestVersion) {
        downloadAndInstall(downloadUrl, fileName, source, latestVersion,
                serverStore.isBetaChannel() ? "beta" : "stable");
    }

    void downloadAndInstall(String downloadUrl, String fileName,
                            String source, String latestVersion, String channel) {
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            Toast.makeText(activity, "下载地址为空", Toast.LENGTH_LONG).show();
            return;
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = "wand-update.apk";
        }
        final String safeFileName = fileName;

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
                                    downloadAndInstall(downloadUrl, safeFileName, source, latestVersion, channel))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    }
                }
        );
    }

    /**
     * 只下载，不直接弹窗或安装。HomeActivity 的 Compose 更新面板以此驱动进度状态；
     * MainActivity 仍通过上面的兼容入口使用相同的网络和落盘逻辑。
     */
    DownloadRequest download(String downloadUrl, String fileName,
                             String latestVersion, String channel,
                             DownloadListener listener) {
        final DownloadRequest request = new DownloadRequest();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            postDownloadFailure(listener, "下载地址为空");
            return request;
        }
        if (executor == null || executor.isShutdown()) {
            postDownloadFailure(listener, "下载服务暂不可用，请稍后重试。");
            return request;
        }
        final String safeFileName = (fileName == null || fileName.isEmpty())
                ? "wand-update.apk" : fileName;
        executor.execute(() -> {
            HttpURLConnection conn = null;
            File outputFile = null;
            try {
                String fullUrl = downloadUrl.startsWith("http")
                        ? downloadUrl : serverUrl + downloadUrl;
                conn = NetUtils.openConnection(fullUrl,
                        NetUtils.DOWNLOAD_CONNECT_TIMEOUT_MS, NetUtils.DOWNLOAD_READ_TIMEOUT_MS);
                if (!downloadUrl.startsWith("http")) {
                    String cookie = CookieManager.getInstance().getCookie(serverUrl);
                    if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                }
                conn.setInstanceFollowRedirects(true);
                int responseCode = conn.getResponseCode();
                if (responseCode == 302 || responseCode == 301) {
                    String redirectUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (redirectUrl != null) {
                        conn = NetUtils.openConnection(redirectUrl,
                                NetUtils.DOWNLOAD_CONNECT_TIMEOUT_MS, NetUtils.DOWNLOAD_READ_TIMEOUT_MS);
                        conn.setInstanceFollowRedirects(true);
                        responseCode = conn.getResponseCode();
                    }
                }
                if (responseCode != 200) throw new Exception("服务器返回 " + responseCode);

                int fileLength = conn.getContentLength();
                outputFile = new File(activity.getExternalFilesDir(null), safeFileName);
                if (fileLength > 0) {
                    File dir = outputFile.getParentFile();
                    long usable = dir != null ? dir.getUsableSpace() : Long.MAX_VALUE;
                    if (usable < (long) fileLength + 5 * 1024 * 1024) {
                        throw new Exception("存储空间不足，需要约 " + formatSize(fileLength) + "，请清理后重试");
                    }
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    long lastUiUpdate = 0;
                    final long startTime = System.currentTimeMillis();
                    while ((count = in.read(buffer)) != -1) {
                        if (request.isCancelled()) break;
                        total += count;
                        out.write(buffer, 0, count);
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate > 50 || total == fileLength) {
                            lastUiUpdate = now;
                            long elapsed = Math.max(1, now - startTime);
                            postDownloadProgress(listener, total, fileLength, total * 1000 / elapsed);
                        }
                    }
                }

                if (request.isCancelled()) {
                    if (outputFile.exists()) {
                        try { outputFile.delete(); } catch (Exception ignored) {}
                    }
                    postDownloadCancelled(listener);
                    return;
                }
                if (!outputFile.exists() || outputFile.length() == 0) {
                    throw new Exception("下载文件为空");
                }
                String versionToRecord = latestVersion != null
                        ? latestVersion : extractVersionFromFileName(safeFileName);
                if (versionToRecord != null) {
                    serverStore.setDownloadedApkVersion(versionToRecord, channel);
                }
                postDownloadCompleted(listener, outputFile);
            } catch (Exception e) {
                if (request.isCancelled()) {
                    if (outputFile != null && outputFile.exists()) {
                        try { outputFile.delete(); } catch (Exception ignored) {}
                    }
                    postDownloadCancelled(listener);
                } else {
                    postDownloadFailure(listener, NetworkErrorHelper.describeError(e, "download"));
                }
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        });
        return request;
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
        try {
            Uri apkUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(activity, R.style.Theme_Wand_Dialog)
                .setTitle("安装失败")
                .setMessage(e.getMessage())
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }
    }

    static String extractVersionFromFileName(String fileName) {
        if (fileName == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?)").matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
