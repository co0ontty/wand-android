package com.wand.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallStateTest {

    private val pendingAt = 1_700_000_000_000L

    @Test
    fun withoutAnyStateNothingHappens() {
        assertEquals(
            UpdateInstallOutcome.NONE,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0",
                runningVersionCode = 100_740_000L,
                pendingVersionName = null,
                pendingVersionCode = 0L,
                pendingSinceMs = 0L,
                installedAtMs = 0L,
                processStartMs = pendingAt,
                nowMs = pendingAt + 5_000L,
            ),
        )
    }

    @Test
    fun appliedUpdateInAProcessStartedAfterwardsIsFresh() {
        assertEquals(
            UpdateInstallOutcome.INSTALLED_FRESH,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_001L,
                pendingVersionName = "4.74.0-debug.10201530",
                pendingVersionCode = 100_740_001L,
                pendingSinceMs = pendingAt,
                installedAtMs = pendingAt + 4_000L,
                // 安装完成广播之后系统才拉起的新进程
                processStartMs = pendingAt + 5_000L,
                nowMs = pendingAt + 8_000L,
            ),
        )
    }

    @Test
    fun appliedUpdateStillRunningOnTheOldProcessNeedsRestart() {
        // 进程启动于「发起安装」之前 —— 内存里还是旧代码，只有重启才能加载新版本。
        assertEquals(
            UpdateInstallOutcome.INSTALLED_NEEDS_RESTART,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_001L,
                pendingVersionName = "4.74.0-debug.10201530",
                pendingVersionCode = 100_740_001L,
                pendingSinceMs = pendingAt,
                installedAtMs = pendingAt + 4_000L,
                processStartMs = pendingAt - 60_000L,
                nowMs = pendingAt + 6_000L,
            ),
        )
    }

    @Test
    fun installStillInFlightIsLeftAlone() {
        assertEquals(
            UpdateInstallOutcome.NONE,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_001L,
                pendingVersionName = "4.74.0-debug.10300000",
                pendingVersionCode = 100_740_001L + 1,
                pendingSinceMs = pendingAt,
                installedAtMs = 0L,
                processStartMs = pendingAt - 60_000L,
                nowMs = pendingAt + 10_000L,
            ),
        )
    }

    @Test
    fun neverAppliedInstallIsCleanedUpAfterGrace() {
        assertEquals(
            UpdateInstallOutcome.NOT_APPLIED,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_001L,
                pendingVersionName = "4.74.0-debug.10300000",
                pendingVersionCode = 100_740_001L + 1,
                pendingSinceMs = pendingAt,
                installedAtMs = 0L,
                processStartMs = pendingAt - 60_000L,
                nowMs = pendingAt + UpdateInstallState.PENDING_GRACE_MS + 1_000L,
            ),
        )
    }

    @Test
    fun reportedSuccessWithoutVersionChangeIsNotApplied() {
        // 系统回报安装成功但版本号没动：状态是脏的，必须清掉，不能反复提示。
        assertEquals(
            UpdateInstallOutcome.NOT_APPLIED,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_001L,
                pendingVersionName = "4.74.0-debug.10300000",
                pendingVersionCode = 100_740_001L + 1,
                pendingSinceMs = pendingAt,
                installedAtMs = pendingAt + 3_000L,
                processStartMs = pendingAt + 4_000L,
                nowMs = pendingAt + 6_000L,
            ),
        )
    }

    @Test
    fun versionCodeDecidesWhenNamesCarryDebugTimestamps() {
        assertTrue(
            UpdateInstallState.isApplied(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_002L,
                pendingVersionName = "4.74.0-debug.10201530",
                pendingVersionCode = 100_740_001L,
            ),
        )
        assertFalse(
            UpdateInstallState.isApplied(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_000L,
                pendingVersionName = "4.74.0-debug.10201530",
                pendingVersionCode = 100_740_001L,
            ),
        )
    }

    @Test
    fun versionNameIsTheFallbackWhenCodesAreMissing() {
        assertTrue(
            UpdateInstallState.isApplied(
                runningVersionName = "4.74.0",
                runningVersionCode = 0L,
                pendingVersionName = "4.74.0",
                pendingVersionCode = 0L,
            ),
        )
        assertFalse(
            UpdateInstallState.isApplied(
                runningVersionName = "4.73.0",
                runningVersionCode = 0L,
                pendingVersionName = "4.74.0",
                pendingVersionCode = 0L,
            ),
        )
    }
}
