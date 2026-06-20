package com.wand.app;

/**
 * 网络错误描述工具：把连接/下载过程中的异常映射成面向用户的中文文案。
 * 合并 ConnectActivity.describeConnectionError 与 UpdateManager.friendlyDownloadError。
 */
final class NetworkErrorHelper {

    private NetworkErrorHelper() {}

    /**
     * 把网络异常映射成用户友好的中文错误描述。
     * @param e 异常对象
     * @param context 上下文："connect" 表示连接探测，"download" 表示下载
     * @return 中文错误描述
     */
    static String describeError(Exception e, String context) {
        if (e instanceof java.net.MalformedURLException) {
            return "地址格式不正确，请检查后重试";
        }
        if (e instanceof java.net.ConnectException) {
            return "无法连接到服务器，请确认地址和端口是否正确";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "connect".equals(context)
                    ? "连接超时，请检查网络或服务器是否在运行"
                    : "下载超时，请检查网络后重试";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "connect".equals(context)
                    ? "无法解析地址，请检查服务器地址是否正确"
                    : "无法连接到下载服务器，请检查网络";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            // 已 trustSelfSigned 全信任, SSL 异常基本只因 host 不通, 归并到"无法连接"。
            return "无法连接到服务器，请确认地址和端口是否正确";
        }
        // 下载特有：存储空间
        String raw = e.getMessage() != null ? e.getMessage() : "";
        if (raw.contains("ENOSPC") || raw.toLowerCase().contains("space")) {
            return "存储空间不足，请清理后重试";
        }
        return raw.isEmpty() ? "操作失败，请稍后重试" : raw;
    }
}
