package com.wand.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 「安装更新后自动回到新版本」的落地动作。
 *
 * 系统安装器完成安装时会杀掉 Wand 进程，用户只看到系统安装器的「完成」页。这里用
 * [UpdateInstallState] 判定当前处于哪种状态，并做对应收尾：
 *
 * - 更新已生效且当前进程是安装前的旧进程 → 重启进程（旧代码不会自己消失）；
 * - 更新已生效且当前进程就是新进程 → 只清理状态；
 * - 安装没落地（用户取消 / 失败）→ 清理状态，避免下次启动又看到「待安装」残影。
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
            processStartMs = WandLog.processStartWallClockMs(),
            nowMs = System.currentTimeMillis(),
        )
        when (outcome) {
            UpdateInstallOutcome.NONE -> Unit

            UpdateInstallOutcome.INSTALLED_FRESH -> {
                WandLog.i(
                    TAG,
                    "更新已生效：v$pendingVersion → 运行中 v${running.first}（进程启动于安装之后）",
                )
                store.clearInstallState()
            }

            UpdateInstallOutcome.INSTALLED_NEEDS_RESTART -> {
                WandLog.w(
                    TAG,
                    "更新已生效但当前仍是安装前的进程，重启以加载新版本 " +
                        "pending=$pendingVersion running=${running.first}",
                )
                store.clearInstallState()
                relaunchIntoNewProcess(activity)
            }

            UpdateInstallOutcome.NOT_APPLIED -> {
                WandLog.w(TAG, "上次安装请求未生效（取消或失败），清理待安装状态 pending=$pendingVersion")
                store.clearInstallState()
            }
        }
    }

    /**
     * 重启到新进程：先安排一次由系统代发的拉起（进程即将被杀，必须由系统在之后发出），
     * 再结束当前进程。安装后的新代码只有新进程才能完全加载。
     */
    fun relaunchIntoNewProcess(activity: Activity) {
        WandLog.i(TAG, "重启应用到新版本")
        UpdateInstallReceiver.scheduleRelaunch(activity, 300L)
        activity.startActivity(
            Intent(activity, ConnectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finishAffinity()
        // 给这次 startActivity 留一帧；随后由闹钟在系统侧重新拉起（若本进程已被系统清掉，
        // 闹钟同样有效）。
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 150L)
    }

    private fun runningVersion(activity: Activity): Pair<String?, Long> = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        info.versionName to info.longVersionCode
    } catch (e: Exception) {
        WandLog.w(TAG, "读取当前安装版本失败", e)
        null to 0L
    }

    private const val TAG = "update"
}
