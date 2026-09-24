package com.wand.app

/** 更新安装状态机的判定结果。 */
enum class UpdateInstallOutcome {
    /** 没有可判定的状态（无待安装请求，或安装仍在进行中）。 */
    NONE,

    /** 更新已经生效：运行中的版本就是当初要装的那个。 */
    INSTALLED,

    /** 上次安装请求没有生效（用户取消 / 安装失败）：清理状态，避免留下「待安装」残影。 */
    NOT_APPLIED,
}

/**
 * 「安装更新之后到底装上了没有」的判定逻辑（纯函数，便于单测）。
 *
 * 背景：点「安装更新」后由系统安装器接管，Wand 进程会被系统杀掉并在新版本里重启。重启后的
 * 客户端只有持久化的版本号/时间戳可用，必须靠这套规则判断：
 *
 * - 版本号已生效 → 安装成功，清状态；
 * - 版本号没变且已经过了宽限期 → 用户取消或安装失败，清状态（界面退回可重试）；
 * - 立刻返回「未生效」是不行的：系统安装器弹窗可能还开着，正在安装中的状态必须保持。
 *
 * versionCode 的坑：同一个 tag 下连续构建的 `X.Y.Z-debug.MMDDHHMM` 共享同一个 versionCode，
 * 所以**不能只看 versionCode**：同号时必须再比 versionName，否则「还没装」会被误判成
 * 「已经装上」，取消安装也会被当成升级成功。
 */
object UpdateInstallState {

    /** 待安装宽限期：这段时间内不判定「安装没生效」，因为系统安装器可能还在确认/写入。 */
    const val PENDING_GRACE_MS = 60_000L

    fun evaluate(
        runningVersionName: String?,
        runningVersionCode: Long,
        pendingVersionName: String?,
        pendingVersionCode: Long,
        pendingSinceMs: Long,
        installedAtMs: Long,
        nowMs: Long,
    ): UpdateInstallOutcome {
        val hasPending = !pendingVersionName.isNullOrBlank() || pendingVersionCode > 0L
        if (!hasPending && installedAtMs <= 0L) return UpdateInstallOutcome.NONE

        if (isApplied(runningVersionName, runningVersionCode, pendingVersionName, pendingVersionCode)) {
            return UpdateInstallOutcome.INSTALLED
        }
        // 版本号没变：安装还没落地。
        if (installedAtMs > 0L) return UpdateInstallOutcome.NOT_APPLIED
        return if (pendingSinceMs > 0L && nowMs - pendingSinceMs > PENDING_GRACE_MS) {
            UpdateInstallOutcome.NOT_APPLIED
        } else {
            UpdateInstallOutcome.NONE
        }
    }

    /**
     * 运行中的版本是否已经是「当初要装的那一份」。
     * versionCode 能比就用它（严格更新即已生效）；同号或缺失时退回 versionName 相等判断。
     */
    fun isApplied(
        runningVersionName: String?,
        runningVersionCode: Long,
        pendingVersionName: String?,
        pendingVersionCode: Long,
    ): Boolean {
        val hasPendingName = !pendingVersionName.isNullOrBlank()
        if (!hasPendingName && pendingVersionCode <= 0L) return false
        if (runningVersionCode > 0L && pendingVersionCode > 0L) {
            if (runningVersionCode != pendingVersionCode) return runningVersionCode > pendingVersionCode
            // 同 versionCode（同一 tag 下的连续 debug 构建）：只能靠版本名区分。
            return !hasPendingName || runningVersionName == pendingVersionName
        }
        return hasPendingName && runningVersionName == pendingVersionName
    }

    /**
     * 安装状态回调是否属于「最近一次提交的安装会话」。
     *
     * 背景：用户放弃一次安装并不会取消那个 PackageInstaller 会话 —— 弹窗可能还开着，用户过几
     * 秒再把它关掉，aborted 回调才姗姗来迟。2026-09-25 的日志就是这种形态：05:57:19 已回报
     * SUCCESS，05:57:23 又收到 3.7 秒前那次旧会话的 FAILURE_ABORTED，把刚标记的「安装成功」
     * 改成「安装失败；已取消安装」，用户看到的就是「装完了却说没装成功」。
     *
     * 所以回调必须按会话号过滤。两个方向都保持宽容，宁可处理也不要漏掉真实回调：
     *
     * - [callbackSessionId] < 0：旧版本提交安装时回调 Intent 里还没有会话号（例如从修复前的
     *   版本升级上来的第一次安装），无法判定，照旧处理；
     * - [lastCommittedSessionId] < 0：没有记录过任何会话（状态已清账），同样不拦。
     */
    @JvmStatic
    fun isCurrentSession(callbackSessionId: Int, lastCommittedSessionId: Int): Boolean {
        if (callbackSessionId < 0 || lastCommittedSessionId < 0) return true
        return callbackSessionId == lastCommittedSessionId
    }
}
