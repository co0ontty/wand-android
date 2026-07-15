package com.wand.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationPolicyTest {
    private var now = 1_000_000L
    private val policy = SessionNotificationPolicy { now }
    private val background = NotificationVisibility(false, null)

    @Test
    fun taskProgressOnlyNotifiesInBackgroundAndThrottlesByTitle() {
        assertNull(policy.taskProgress("s1", "Project", "Build", NotificationVisibility(true, null)))

        val first = policy.taskProgress("s1", "Project", "Build", background)
        assertEquals("任务进行中", first?.title)
        assertEquals("Project\nBuild", first?.body)
        assertNull(policy.taskProgress("s1", "Project", "Build", background))
        assertTrue(policy.taskProgress("s1", "Project", "Test", background) != null)

        now += 90_000L
        assertTrue(policy.taskProgress("s1", "Project", "Build", background) != null)
    }

    @Test
    fun foregroundActiveChatSuppressesPermissionWithoutConsumingThrottle() {
        val active = NotificationVisibility(true, "s1")
        assertNull(policy.permissionRequired("s1", "Project", "Approve", null, active))

        val notification = policy.permissionRequired(
            "s1", "Project", "Approve", "/tmp", NotificationVisibility(true, "other"),
        )
        assertEquals("需要你的授权", notification?.title)
        assertEquals("Project\nApprove · /tmp", notification?.body)
        assertNull(policy.permissionRequired("s1", "Project", "Approve", null, background))
    }

    @Test
    fun successfulEndIncludesAssistantTextWhileFailureDoesNot() {
        val success = policy.sessionEnded("ok", "Project", "Done", false, background)
        val failure = policy.sessionEnded("bad", "Project", "Details", true, background)

        assertEquals("任务已完成", success?.title)
        assertEquals("Project\nDone", success?.body)
        assertEquals("任务异常结束", failure?.title)
        assertEquals("Project", failure?.body)
    }

    @Test
    fun responseCompletionRespectsBlockingTerminalAndVisibilityRules() {
        assertNull(policy.responseCompleted("blocked", "P", "Done", true, "running", background))
        assertNull(policy.responseCompleted("ended", "P", "Done", false, "exited", background))
        assertNull(
            policy.responseCompleted(
                "active", "P", "Done", false, "running", NotificationVisibility(true, "active"),
            ),
        )
        assertEquals(
            "P\nDone",
            policy.responseCompleted("ready", "P", "Done", false, "running", background)?.body,
        )
    }

    @Test
    fun resetClearsThrottleHistory() {
        assertTrue(policy.permissionRequired("s1", "P", "Approve", null, background) != null)
        assertNull(policy.permissionRequired("s1", "P", "Approve", null, background))

        policy.reset()

        assertTrue(policy.permissionRequired("s1", "P", "Approve", null, background) != null)
    }
}
