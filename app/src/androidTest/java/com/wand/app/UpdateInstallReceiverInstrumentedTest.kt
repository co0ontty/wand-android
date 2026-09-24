package com.wand.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 安装状态回调的会话号过滤（真实 Receiver + SharedPreferences + WandLog 内存环）。
 *
 * 回归场景来自实机日志 2026-09-25 05:57：安装已经回报 SUCCESS 之后 3.7 秒，被放弃的旧会话
 * （`05:56:56` 提交、`05:57:03` 被判定未生效）才送来 FAILURE_ABORTED，把刚标记的「安装成功」
 * 改写成「安装失败；已取消安装」。旧实现只认识「最近一次回调」，认不出回调属于哪次安装。
 *
 * 测试直接写在应用进程里（AndroidJUnitRunner 随 targetPackage 启动），所以写的是真实的
 * install 状态；用例前后都清账，结束时留下的就是「一次安装结束后」的正常状态。
 *
 * 本地跑法（**不要**带 -PAPP_VERSION_NAME）：`./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.wand.app.UpdateInstallReceiverInstrumentedTest`。
 * 带上 APP_VERSION_NAME 会打开分发包的 R8 裁剪，把 kotlin stdlib 改名，而 androidx.test
 * 的 runner 按原名引用 `kotlin.LazyKt`，会直接 NoClassDefFoundError。
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallReceiverInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: ServerStore

    @Before
    fun setUp() {
        store = ServerStore(context)
        store.clearInstallState()
        // 用例不应该把真实界面翻成「安装失败」。
        UpdateInstallReceiver.setStatusListener(null)
    }

    @After
    fun tearDown() {
        store.clearInstallState()
    }

    @Test
    fun abandonedSessionCallbackDoesNotEraseTheSuccessfulInstall() {
        store.setPendingInstall("4.75.2-debug.09250600", 40750201L, 762669748)
        store.markInstallSucceeded()

        receive(
            status = PackageInstaller.STATUS_FAILURE_ABORTED,
            sessionId = 565493771,
            message = "INSTALL_FAILED_ABORTED: User rejected permissions",
        )

        assertEquals(
            "过期会话的失败回调不能清掉刚记录的待安装版本",
            "4.75.2-debug.09250600",
            store.pendingInstallVersion,
        )
        assertTrue("过期会话的失败回调不能清掉「安装成功」标记", store.installSucceededAtMs > 0L)
        assertTrue(
            "丢弃过期回调要留日志，否则线上无从判断",
            WandLog.ringText().contains("忽略过期安装会话回调 sessionId=565493771"),
        )
    }

    @Test
    fun abortedCallbackForTheCurrentSessionClearsPendingInstall() {
        store.setPendingInstall("4.75.2-debug.09250600", 40750201L, 762669748)

        receive(status = PackageInstaller.STATUS_FAILURE_ABORTED, sessionId = 762669748)

        assertTrue("当前会话被取消后必须清掉待安装状态", store.pendingInstallVersion.isBlank())
    }

    @Test
    fun callbackWithoutSessionIdIsStillHandled() {
        // 从修复前的版本升级上来：这次 commit 由旧代码发起，回调里没有会话号，认不出也得处理。
        store.setPendingInstall("4.75.2-debug.09250600", 40750201L, 762669748)

        receive(status = PackageInstaller.STATUS_FAILURE_ABORTED, sessionId = null)

        assertTrue("没有会话号的回调不能因为「认不出」被丢弃", store.pendingInstallVersion.isBlank())
    }

    private fun receive(status: Int, sessionId: Int?, message: String? = null) {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
        if (sessionId != null) intent.putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId)
        if (message != null) intent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
        UpdateInstallReceiver().onReceive(context, intent)
    }
}
