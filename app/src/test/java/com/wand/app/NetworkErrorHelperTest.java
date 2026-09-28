package com.wand.app;

import static org.junit.Assert.assertEquals;

import com.wand.app.data.WandAuth;

import org.junit.Test;

import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 「连不上服务器」这句话的同源测试。
 *
 * 更新检查/下载（本 helper）与登录探测（{@link WandAuth}）对同一件事实必须是同一句，
 * 曾经这里是「请确认地址和端口是否正确」而登录侧是「请检查网络」。
 * 其余分支语义不同（超时 / 解析不到 host / 地址格式 / 空间不足），逐条钉住不被一刀切掉。
 */
public class NetworkErrorHelperTest {

    @Test
    public void connectExceptionMatchesLoginCopy() {
        assertEquals(WandAuth.UNREACHABLE_MESSAGE,
                NetworkErrorHelper.describeError(new ConnectException("Failed to connect"), "check_update"));
        assertEquals(WandAuth.UNREACHABLE_MESSAGE,
                NetworkErrorHelper.describeError(new ConnectException("Failed to connect"), "download"));
    }

    @Test
    public void sslExceptionFallsIntoUnreachableCopy() {
        assertEquals(WandAuth.UNREACHABLE_MESSAGE,
                NetworkErrorHelper.describeError(new javax.net.ssl.SSLException("handshake failed"), "download"));
    }

    @Test
    public void unreachableLoginCopyIsTheSameSentence() {
        assertEquals(WandAuth.UNREACHABLE_MESSAGE,
                WandAuth.loginFailureMessage(WandAuth.AuthFailure.Unreachable, null));
    }

    @Test
    public void addressFormatStaysSeparate() {
        assertEquals("地址格式不正确，请检查后重试",
                NetworkErrorHelper.describeError(new MalformedURLException("bad url"), "check_update"));
    }

    @Test
    public void timeoutSplitsByContext() {
        assertEquals("连接超时，请检查网络或服务器是否在运行",
                NetworkErrorHelper.describeError(new SocketTimeoutException("timeout"), "check_update"));
        assertEquals("下载超时，请检查网络后重试",
                NetworkErrorHelper.describeError(new SocketTimeoutException("timeout"), "download"));
    }

    @Test
    public void unknownHostSplitsByContext() {
        assertEquals("无法解析地址，请检查服务器地址是否正确",
                NetworkErrorHelper.describeError(new UnknownHostException("no such host"), "check_update"));
        assertEquals("无法连接到下载服务器，请检查网络",
                NetworkErrorHelper.describeError(new UnknownHostException("no such host"), "download"));
    }

    @Test
    public void diskSpaceAndFallbackAreTheirOwnThings() {
        assertEquals("存储空间不足，请清理后重试",
                NetworkErrorHelper.describeError(new java.io.IOException("write failed: ENOSPC"), "download"));
        assertEquals("boom",
                NetworkErrorHelper.describeError(new IllegalStateException("boom"), "download"));
        assertEquals("操作失败，请稍后重试",
                NetworkErrorHelper.describeError(new IllegalStateException(), "download"));
    }
}
