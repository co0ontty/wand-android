package com.wand.app

/** 更新安装状态机的判定结果。 */
enum class UpdateInstallOutcome {
    /** 没有待处理的安装请求。 */
    NONE,

    /** 更新已经生效，但当前进程是安装前启动的：必须重启进程才能加载新代码。 */
    INSTALLED_NEEDS_RESTART,

    /** 更新已经生效，当前进程本身就是新版本进程：清理状态即可。 */
    INSTALLED_FRESH,

    /** 上次安装请求没有生效（用户取消 / 安装失败）：清理状态，避免留下「待安装」残影。 */
    NOT_APPLIED,
}

/**
 * 「安装更新 → 自动回到新版本」的判定逻辑（纯函数，便于单测）。
 *
 * 背景：点「安装更新」后由系统安装器接管，Wand 进程会被系统杀掉再重启。用户看到的现象是
 * 安装完成后**应用不会自己回来**，手动打开又回到「安装更新」的旧界面。这里用一组持久化
 * 时间戳/版本号把这件事变成可判定的状态：
 *
 * - 版本号已生效（running ≥ pending）且**当前进程启动于安装成功之前** → 旧代码进程，
 *   必须重启（[UpdateInstallOutcome.INSTALLED_NEEDS_RESTART]）；
 * - 版本号已生效且进程启动于安装成功之后 → 正常的新版本进程，清状态即可；
 * - 版本号没生效且已经过了宽限期 → 安装没落地，清状态；
 * - 版本号没生效但仍在宽限期内 → 安装正在进行，什么都不做。
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
        processStartMs: Long,
        nowMs: Long,
    ): UpdateInstallOutcome {
        val hasPending = !pendingVersionName.isNullOrBlank() || pendingVersionCode > 0L
        if (!hasPending && installedAtMs <= 0L) return UpdateInstallOutcome.NONE

        val applied = isApplied(
            runningVersionName,
            runningVersionCode,
            pendingVersionName,
            pendingVersionCode,
        )
        if (applied) {
            // 以「安装成功广播时间」为准；没有该时间戳时退回「发起安装的时间」。
            val effectiveAt = if (installedAtMs > 0L) installedAtMs else pendingSinceMs
            return if (processStartMs < effectiveAt) {
                UpdateInstallOutcome.INSTALLED_NEEDS_RESTART
            } else {
                UpdateInstallOutcome.INSTALLED_FRESH
            }
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
     * 运行中的版本是否已经达到待安装版本。优先用 versionCode（单调递增，不受
     * debug 时间戳后缀影响），缺失时退回 versionName 相等判断。
     */
    fun isApplied(
        runningVersionName: String?,
        runningVersionCode: Long,
        pendingVersionName: String?,
        pendingVersionCode: Long,
    ): Boolean {
        if (runningVersionCode > 0L && pendingVersionCode > 0L) {
            return runningVersionCode >= pendingVersionCode
        }
        return !pendingVersionName.isNullOrBlank() && runningVersionName == pendingVersionName
    }
}
