package com.wand.app.data

import com.wand.app.data.WandAuth.AuthFailure
import com.wand.app.data.WandAuth.LocalLoginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.MalformedURLException
import java.net.SocketTimeoutException

/**
 * N2：登录失败判定必须是纯函数可测的 —— 单次 401 不许再直接判死，
 * 「重新获取连接码」也不许当第一反应。
 */
class WandAuthTest {

    @Test
    fun firstFourOhOneIsRetryableNotExpired() {
        val failure = WandAuth.classifyLoginFailure(401, null, 1)
        assertEquals(AuthFailure.AuthPending, failure)
        assertTrue(failure.retryable)
        // 首次 401 的文案里不得出现「过期」或对换码的断言。
        val message = WandAuth.loginFailureMessage(failure, 401)
        assertEquals("登录暂未通过，正在重试…", message)
        assertFalse(message.contains("过期"))
        assertFalse(message.contains("重新获取连接码"))
    }

    @Test
    fun fourOhOneAfterRetryIsCredentialRejectedWithNeutralCopy() {
        val failure = WandAuth.classifyLoginFailure(401, null, 2)
        assertEquals(AuthFailure.CredentialRejected, failure)
        assertFalse(failure.retryable)
        assertEquals("连接失败，请重试；若一直失败，再重新获取连接码",
            WandAuth.loginFailureMessage(failure, 401))
    }

    @Test
    fun forbiddenDeadCopyIsGoneFromEveryMessage() {
        for (failure in AuthFailure.values()) {
            val message = WandAuth.loginFailureMessage(failure, 401)
            assertFalse(
                "$failure 仍在用「连接码可能已过期」这句把人推去换码",
                message.contains("连接码可能已过期"),
            )
            assertFalse("$failure 不得断言密码已更改", message.contains("密码已更改"))
        }
    }

    @Test
    fun serverBusyAndNetworkAreTransient() {
        assertEquals(AuthFailure.ServerBusy, WandAuth.classifyLoginFailure(429, null, 1))
        assertEquals(AuthFailure.ServerBusy, WandAuth.classifyLoginFailure(500, null, 1))
        assertEquals(AuthFailure.ServerBusy, WandAuth.classifyLoginFailure(503, null, 3))
        assertTrue(AuthFailure.ServerBusy.retryable)
        assertEquals(AuthFailure.Unreachable, WandAuth.classifyLoginFailure(null, LocalLoginError.Network, 1))
        assertEquals(AuthFailure.Unreachable, WandAuth.classifyLoginFailure(null, LocalLoginError.Timeout, 2))
        assertTrue(AuthFailure.Unreachable.retryable)
    }

    @Test
    fun permissionDeniedIsNotCredentialExpiry() {
        val failure = WandAuth.classifyLoginFailure(403, null, 1)
        assertEquals(AuthFailure.PermissionDenied, failure)
        assertFalse(failure.retryable)
        assertFalse(WandAuth.loginFailureMessage(failure, 403).contains("连接码"))
    }

    @Test
    fun unknownStatusKeepsCodeAndDoesNotAskForNewCode() {
        val failure = WandAuth.classifyLoginFailure(404, null, 1)
        assertEquals(AuthFailure.UnexpectedStatus, failure)
        assertFalse(failure.retryable)
        assertEquals("服务器返回了异常状态码：404", WandAuth.loginFailureMessage(failure, 404))
    }

    @Test
    fun badAddressIsNotRetryable() {
        assertEquals(AuthFailure.BadAddress, WandAuth.classifyLoginFailure(null, LocalLoginError.BadUrl, 1))
        assertFalse(AuthFailure.BadAddress.retryable)
        // 地址写错时退避重探没有意义，网络类才重试。
        assertEquals(AuthFailure.Unreachable, WandAuth.classifyLoginFailure(null, LocalLoginError.Network, 5))
    }

    @Test
    fun localErrorClassificationSeparatesTimeoutFromNetworkFromBadUrl() {
        assertEquals(LocalLoginError.Timeout, WandAuth.localErrorOf(SocketTimeoutException("timeout")))
        assertEquals(LocalLoginError.BadUrl, WandAuth.localErrorOf(MalformedURLException("bad")))
        assertEquals(LocalLoginError.BadUrl, WandAuth.localErrorOf(IllegalArgumentException("bad scheme")))
        assertEquals(LocalLoginError.Network, WandAuth.localErrorOf(IOException("unexpected end of stream")))
    }
}
