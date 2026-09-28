package com.wand.app;

import com.wand.app.data.WandAuth;

import java.util.Locale;

/**
 * 网络错误描述工具：把连接/下载过程中的异常映射成面向用户的中文文案。
 * 合并 ConnectActivity.describeConnectionError 与 UpdateManager.friendlyDownloadError。
 *
 * <p>「连不上服务器」这一句与登录探测同源，取 {@link WandAuth#UNREACHABLE_MESSAGE}，
 * 不在这里另写一份 —— 同一件事实不该有两种说法。
 */
final class NetworkErrorHelper {

    private NetworkErrorHelper() {}

    /**
     * 把网络异常映射成用户友好的中文错误描述。
     * @param e 异常对象
     * @param context 上下文：只有 {@code "download"} 与更新检查（{@code "check_update"}）两种真实取值
     * @return 中文错误描述
     */
    static String describeError(Exception e, String context) {
        if (e instanceof java.net.MalformedURLException) {
            return "地址格式不正确，请检查后重试";
        }
        if (e instanceof java.net.ConnectException) {
            return WandAuth.UNREACHABLE_MESSAGE;
        }
        // 措辞按「下载」与「检查更新」分流。这里以前判的是 "connect"，
        // 而两个调用点传的是 "check_update" / "download"，连接措辞永远走不到。
        boolean downloading = "download".equals(context);
        if (e instanceof java.net.SocketTimeoutException) {
            return downloading
                    ? "下载超时，请检查网络后重试"
                    : "连接超时，请检查网络或服务器是否在运行";
        }
        if (e instanceof java.net.UnknownHostException) {
            return downloading
                    ? "无法连接到下载服务器，请检查网络"
                    : "无法解析地址，请检查服务器地址是否正确";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            // 已 trustSelfSigned 全信任, SSL 异常基本只因 host 不通, 归并到"无法连接"。
            return WandAuth.UNREACHABLE_MESSAGE;
        }
        // 下载特有：存储空间
        String raw = e.getMessage() != null ? e.getMessage() : "";
        if (raw.contains("ENOSPC") || raw.toLowerCase(Locale.ROOT).contains("space")) {
            return "存储空间不足，请清理后重试";
        }
        return raw.isEmpty() ? "操作失败，请稍后重试" : raw;
    }
}
