package com.wand.app;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * 网络相关的共享工具。
 *
 * wand server 默认用自签名证书 (src/cert.ts), 浏览器侧靠 WebView 的
 * onReceivedSslError 放行; 而 APK 自己发起的 HttpURLConnection (更新检查 /
 * APK 下载 / 连接探测) 没有 WebView 兜底, 必须显式信任自签名链, 否则
 * 一律 SSLHandshakeException。这里集中处理, 供各 Activity 共用。
 */
final class NetUtils {

    static final int CONNECT_TIMEOUT_MS = 10000;
    static final int READ_TIMEOUT_MS = 10000;
    static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 15000;
    static final int DOWNLOAD_READ_TIMEOUT_MS = 120000;

    private NetUtils() {}

    /**
     * 让传入的连接 (若为 HTTPS) 信任任意证书 + 跳过 hostname 校验。
     * 仅用于目标就是用户自己的 wand server 的请求（调用方保证同源），
     * 跨源目标（如 GitHub 重定向）必须走 [openConnection] 四参版本按 origin 收紧。
     */
    private static SSLContext trustedSslContext;

    static void trustSelfSigned(HttpURLConnection conn) throws Exception {
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            if (trustedSslContext == null) {
                TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                trustedSslContext = sc;
            }
            httpsConn.setSSLSocketFactory(trustedSslContext.getSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true);
        }
    }

    static HttpURLConnection openConnection(String urlStr, int connectTimeout, int readTimeout) throws Exception {
        return openConnection(urlStr, connectTimeout, readTimeout, null);
    }

    /**
     * 带 origin 限定的连接打开。[trustOriginBaseUrl] 非空时，仅当目标 URL 与该
     * wand server 同源（scheme + host + port）才信任自签名证书；跨源目标
     * （如 APK 下载被重定向到 GitHub）走系统默认证书校验，防止 trust-all
     * 扩散到公网链路被中间人利用。
     */
    static HttpURLConnection openConnection(String urlStr, int connectTimeout, int readTimeout,
                                            String trustOriginBaseUrl) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (trustOriginBaseUrl == null || isSameOrigin(url, trustOriginBaseUrl)) {
            trustSelfSigned(conn);
        }
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        return conn;
    }

    /** 判断 url 与 originBase（wand server 根地址）是否同源；解析失败视为不同源。 */
    static boolean isSameOrigin(URL url, String originBase) {
        try {
            URL origin = new URL(originBase);
            return url.getProtocol().equalsIgnoreCase(origin.getProtocol())
                    && url.getHost().equalsIgnoreCase(origin.getHost())
                    && effectivePort(url) == effectivePort(origin);
        } catch (Exception e) {
            return false;
        }
    }

    private static int effectivePort(URL url) {
        if (url.getPort() != -1) return url.getPort();
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    /** dp → px（合并 ConnectActivity.dpToPx 与 QrScannerOverlayView.dp）。 */
    static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** dp → px（浮点版，供 QrScannerOverlayView 绘制用）。 */
    static float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }
}
