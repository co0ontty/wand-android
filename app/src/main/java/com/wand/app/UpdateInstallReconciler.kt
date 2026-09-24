package com.wand.app

import android.app.Activity
import android.content.Intent

/**
 * 「安装更新之后」的收尾：把持久化的待安装/已安装状态对齐到当前运行的版本。
 *
 * 系统安装器完成安装时会杀掉 Wand 进程，所以正常路径下这里看到的状态都是「安装已生效」，
 * 只需清账；真正需要它判断的只有两种情况：
 *
 * - 安装已经生效 → 清状态并记一条结论日志；
 * - 报过安装成功或过了宽限期，但版本号没动 → 用户取消 / 安装失败，清状态，避免下次启动
 *   又看到「待安装」残影（界面侧由 [`HomeActivity`] 把「正在安装」退回可重试）。
 *
 * 刻意不做「重启进程」：Android 10+ 的后台启动限制会拦下所有后台拉起（实测 Android 16
 * 直接 startActivity 与闹钟兜底都是 BAL_BLOCK），把进程杀掉只会让用户看到应用自己退出。
 * 需要新代码时用户点「更新已完成」通知（系统 UI 代发起，不受限制）或重新打开应用即可。
 */
object UpdateInstallReconciler {

    /** 屏幕恢复时调用；没有待处理状态时几乎没有开销（只读一次 SharedPreferences）。 */
    fun reconcile(activity: Activity) {
        val store = ServerStore(activity)
        val pendingVersion = store.pendingInstallVersion
        val pendingCode = store.pendingInstallVersionCode
        val pendingAt = store.pendingInstallAtMs
        val installedAt = store.installSucceededAtMs
        val hasState = !pendingVersion.isNullOrBlank() || pendingCode > 0L || installedAt > 0L
        if (!hasState) return

        val running = runningVersion(activity)
        val outcome = UpdateInstallState.evaluate(
            runningVersionName = running.first,
            runningVersionCode = running.second,
            pendingVersionName = pendingVersion,
            pendingVersionCode = pendingCode,
            pendingSinceMs = pendingAt,
            installedAtMs = installedAt,
            nowMs = System.currentTimeMillis(),
        )
        when (outcome) {
            UpdateInstallOutcome.NONE -> Unit

            UpdateInstallOutcome.INSTALLED -> {
                val restarted = WandLog.processStartWallClockMs() <
                    effectiveInstallAt(pendingAt, installedAt)
                WandLog.i(
                    TAG,
                    "更新已生效：v$pendingVersion → 运行中 v${running.first}" +
                        if (restarted) "（本次进程由系统安装器重启拉起）" else "",
                )
                store.clearInstallState()
            }

            UpdateInstallOutcome.NOT_APPLIED -> {
                WandLog.w(TAG, "上次安装请求未生效（取消或失败），清理待安装状态 pending=$pendingVersion", null)
                store.clearInstallState()
            }
        }
    }

    /** 打开应用（前台调用，不受后台启动限制）：清掉任务栈后从连接页重新进入。 */
    fun reopenApp(activity: Activity) {
        WandLog.i(TAG, "用户要求重新打开应用以加载新版本")
        activity.startActivity(
            Intent(activity, ConnectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finishAffinity()
    }

    private fun effectiveInstallAt(pendingAt: Long, installedAt: Long): Long =
        if (installedAt > 0L) installedAt else pendingAt

    private fun runningVersion(activity: Activity): Pair<String?, Long> = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        info.versionName to info.longVersionCode
    } catch (e: Exception) {
        WandLog.w(TAG, "读取当前安装版本失败", e)
        null to 0L
    }

    private const val TAG = "update"
}
