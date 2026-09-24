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
                nowMs = pendingAt + 5_000L,
            ),
        )
    }

    @Test
    fun appliedUpdateInAProcessStartedAfterwardsIsFresh() {
        assertEquals(
            UpdateInstallOutcome.INSTALLED,
            UpdateInstallState.evaluate(
                runningVersionName = "4.74.0-debug.10201530",
                runningVersionCode = 100_740_001L,
                pendingVersionName = "4.74.0-debug.10201530",
                pendingVersionCode = 100_740_001L,
                pendingSinceMs = pendingAt,
                installedAtMs = pendingAt + 4_000L,
                // 安装完成广播之后系统才拉起的新进程
                nowMs = pendingAt + 8_000L,
            ),
        )
    }

    @Test
    fun sameVersionCodeBuildFromTheSameTagIsStillNotApplied() {
        // 同一个 tag 下连续构建的 debug 包 versionCode 完全相同，只有 versionName 的
        // 时间戳不同：取消安装时绝不能把它当成「已经装上」。
        assertEquals(
            UpdateInstallOutcome.NONE,
            UpdateInstallState.evaluate(
                runningVersionName = "4.75.2-debug.09250125",
                runningVersionCode = 40750201L,
                pendingVersionName = "4.75.2-debug.09250131",
                pendingVersionCode = 40750201L,
                pendingSinceMs = pendingAt,
                installedAtMs = 0L,
                nowMs = pendingAt + 5_000L,
            ),
        )
        assertEquals(
            UpdateInstallOutcome.INSTALLED,
            UpdateInstallState.evaluate(
                runningVersionName = "4.75.2-debug.09250131",
                runningVersionCode = 40750201L,
                pendingVersionName = "4.75.2-debug.09250131",
                pendingVersionCode = 40750201L,
                pendingSinceMs = pendingAt,
                installedAtMs = 0L,
                nowMs = pendingAt + 5_000L,
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

    @Test
    fun callbackFromAnAbandonedSessionIsNotCurrent() {
        // 实机日志：提交会话 762669748 安装成功后，3.7 秒前放弃的旧会话回调才到。
        assertFalse(UpdateInstallState.isCurrentSession(910154005, 762669748))
        assertFalse(UpdateInstallState.isCurrentSession(565493771, 762669748))
    }

    @Test
    fun callbackFromTheLatestSessionIsCurrent() {
        assertTrue(UpdateInstallState.isCurrentSession(762669748, 762669748))
    }

    @Test
    fun callbackWithoutASessionIdIsStillHandled() {
        // 从修复前的版本升级上来时，commit 由旧代码发起，回调里没有会话号：不能因为认不出就丢弃。
        assertTrue(UpdateInstallState.isCurrentSession(-1, 762669748))
        assertTrue(UpdateInstallState.isCurrentSession(-1, -1))
    }

    @Test
    fun withoutAnyRecordedSessionNothingIsFiltered() {
        assertTrue(UpdateInstallState.isCurrentSession(1423138288, -1))
    }
}
