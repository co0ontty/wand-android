package com.wand.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wand.app.ui.ChatComposer
import com.wand.app.data.UploadedFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Isolated native preview only: actual submit/IME/scroll, no network, models or user sessions. */
@RunWith(AndroidJUnit4::class)
class ChatViewportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(activity: Activity, name: String): Any? = activity.javaClass.getMethod("get$name").invoke(activity)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(80)
        }
        screenshot("failure-" + message.replace(Regex("[^A-Za-z0-9]+"), "-").take(80))
        fail(message)
    }
    private fun find(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matches(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), matches)?.let { return it }
        return null
    }
    private fun appRoot(): AccessibilityNodeInfo? {
        // Compose can update a label without changing geometry; UiAutomation's node cache
        // otherwise keeps the prior send/stop semantics on a stationary circle.
        instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == context.packageName }
            ?: instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun labelled(label: String) = find(appRoot()) {
        it.contentDescription?.toString() == label || it.text?.toString() == label ||
            it.actionList.any { action -> action.label?.toString() == label }
    }
    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var node = labelled(label)
            while (node != null) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node = node.parent
            }
            Thread.sleep(80)
        }
        fail("Missing native action: $label")
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().apply { node.getBoundsInScreen(this) }
    private fun viewport(activity: Activity) = field(activity, "MessageViewportForTest") as Rect
    private fun assertBoundary(activity: Activity, text: String? = null) {
        val area = viewport(activity)
        for (label in listOf("返回", "通话", "会话文件", "更多：会话对象、工作目录与变更")) {
            val control = labelled(label)
            assertNotNull(label, control)
            assertTrue("Reading viewport must clear $label: $area", bounds(control!!).bottom <= area.top)
        }
        if (text != null) {
            val message = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
            assertNotNull("First sent message must be readable", message)
            assertTrue("First message cannot cross the reading boundary", bounds(message!!).top >= area.top)
        }
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Native screenshot unavailable")
        FileOutputStream(File(context.getExternalFilesDir(null), "$name.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun swipe(area: Rect) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val y = area.bottom - area.height() * (0.15f + 0.6f * step / 12)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                if (step == 0) MotionEvent.ACTION_DOWN else if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                area.centerX().toFloat(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
            Thread.sleep(30)
        }
    }

    @Test fun runningDraftCanSendAndStopFromMenuWithoutLosingAttachments() {
        assumeTrue(context.packageName.endsWith(".preview"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        for ((index, variant) in listOf(false to 1f, true to 1.6f).withIndex()) {
            for (expanded in listOf(false, true)) {
                val activity = instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "running-empty")
                    .putExtra("dark", variant.first).putExtra("fontScale", variant.second).putExtra("reduceMotion", variant.first))
                try {
                    waitFor("running stop visible without opening editor") { labelled("停止当前任务") != null }
                    val composer = field(activity, "ComposerForTest") as ChatComposer
                    val initialStop = bounds(labelled("停止当前任务")!!)
                    assertFalse(field(activity, "EditorVisible") as Boolean)
                    val density = context.resources.displayMetrics.density
                    assertTrue("48dp stop target", initialStop.width() >= (48 * density).toInt() - 1)
                    assertTrue("48dp stop target", initialStop.height() >= (48 * density).toInt() - 1)
                    val draft = "运行中补充要求：停止不能清掉这条草稿"
                    instrumentation.runOnMainSync {
                        composer.editDraft(draft)
                        composer.upload { listOf(UploadedFile("停止保留附件.txt", "/mock/stop.txt", 12, "text/plain")) }
                    }
                    waitFor("attachment ready") { composer.attachments.size == 1 && !composer.uploading }
                    if (expanded) {
                        click("展开键盘输入")
                        waitFor("editor and native IME visible") {
                            field(activity, "EditorVisible") == true && labelled("继续发送（排队）") != null &&
                                activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                        }
                        val voice = bounds(labelled("按住说话，松手转写，上滑取消")!!)
                        val send = bounds(labelled("继续发送（排队）")!!)
                        assertTrue("send is right of independent voice", voice.right <= send.left)
                        assertEquals("send slot does not move on expansion", initialStop.left, send.left)
                    }
                    waitFor("new draft exposes send and stop together") { labelled("继续发送（排队）") != null && labelled("停止当前任务") != null }
                    val stop = bounds(labelled("停止当前任务")!!)
                    val send = bounds(labelled("继续发送（排队）")!!)
                    assertTrue("stop and send never overlap", stop.right <= send.left)
                    screenshot("continue-$index-${if (expanded) "ime" else "collapsed"}")
                    click("停止当前任务")
                    waitFor("stop callback applied without clearing voice") { field(activity, "StopCount") == 1 && field(activity, "RunningForTest") == false }
                    assertEquals(draft, composer.draft)
                    assertEquals(listOf("/mock/stop.txt"), composer.attachments.map { it.savedPath })
                    assertEquals("stop never submits the draft", 0, field(activity, "SendCount"))
                    waitFor("stop changes to send for retained draft") { labelled("停止当前任务") == null && labelled("发送消息") != null }
                    assertNotNull("voice stays independent", labelled("按住说话，松手转写，上滑取消"))
                    assertEquals("send/stop slot has stable x", initialStop.left, bounds(labelled("发送消息")!!).left)
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        }
    }

    @Test fun independentStopIsNotBlockedBySendingOrUnknownReceipt() {
        assumeTrue(context.packageName.endsWith(".preview"))
        for (stopWhileSending in listOf(true, false)) {
            val activity = instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "running-unknown"))
            try {
                waitFor("running fixture ready") { labelled("继续发送（排队）") != null }
                val composer = field(activity, "ComposerForTest") as ChatComposer
                val draft = composer.draft
                instrumentation.runOnMainSync { assertTrue(composer.submit()) }
                waitFor("submission phase") {
                    if (stopWhileSending) composer.sendPhase == com.wand.app.ui.SendPhase.Sending
                    else composer.sendPhase == com.wand.app.ui.SendPhase.Failed
                }
                click("停止当前任务")
                waitFor("independent stop accepted") { field(activity, "StopCount") == 1 && field(activity, "RunningForTest") == false }
                waitFor("unknown receipt remains protected") { composer.sendPhase == com.wand.app.ui.SendPhase.Failed }
                assertEquals(draft, composer.draft)
                assertFalse("unknown draft cannot be resubmitted", composer.canSubmit)
                instrumentation.runOnMainSync { assertFalse(composer.submit()) }
                assertEquals(1, field(activity, "SendCount"))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun continuingWhileRunningSubmitsOnceAndKeepsInputTypedBeforeAck() {
        assumeTrue(context.packageName.endsWith(".preview"))
        val activity = instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "running-empty"))
        try {
            waitFor("running fixture ready") { labelled("停止当前任务") != null }
            val composer = field(activity, "ComposerForTest") as ChatComposer
            click("展开键盘输入")
            instrumentation.runOnMainSync { composer.editDraft("执行中继续发送的要求") }
            waitFor("continue available") { labelled("继续发送（排队）") != null }
            click("继续发送（排队）")
            waitFor("request started") { composer.sendPhase == com.wand.app.ui.SendPhase.Sending }
            instrumentation.runOnMainSync {
                assertFalse("duplicate submission blocked", composer.submit())
                composer.editDraft("回执期间输入的新草稿")
            }
            waitFor("ack retains new draft and continue action") {
                composer.sendPhase == com.wand.app.ui.SendPhase.Idle && labelled("继续发送（排队）") != null
            }
            assertEquals("回执期间输入的新草稿", composer.draft)
            assertEquals(1, field(activity, "SendCount"))
            assertEquals(0, field(activity, "StopCount"))
            screenshot("continue-keeps-new-draft")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun emptyCapsuleReclaimsTheActionWidthAfterSendAndStopFinish() {
        assumeTrue(context.packageName.endsWith(".preview"))
        val activity = instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "idle"))
        try {
            waitFor("idle capsule ready") { labelled("按住说话，松手转写，上滑取消") != null }
            assertNull(labelled("发送消息")); assertNull(labelled("停止当前任务"))
            val emptyVoice = bounds(labelled("按住说话，松手转写，上滑取消")!!)
            screenshot("circle-empty-idle")
            val composer = field(activity, "ComposerForTest") as ChatComposer
            instrumentation.runOnMainSync { composer.editDraft("请检查独立圆钮") }
            waitFor("send outside collapsed capsule") { labelled("发送消息") != null }
            val send = bounds(labelled("发送消息")!!)
            val voice = bounds(labelled("按住说话，松手转写，上滑取消")!!)
            assertTrue("empty capsule occupies the trailing action space", kotlin.math.abs(send.right - emptyVoice.right) <= (8 * context.resources.displayMetrics.density).toInt())
            assertTrue(voice.right <= send.left)
            assertFalse(field(activity, "EditorVisible") as Boolean)
            screenshot("circle-draft-idle")
            click("发送消息")
            assertTrue("visible send alone reserves one touch slot", emptyVoice.left - voice.left >= (48 * context.resources.displayMetrics.density).toInt())
            waitFor("accepted submission leaves stop after receipt dwell") { field(activity, "SendCount") == 1 && composer.draft.isEmpty() && labelled("停止当前任务") != null && composer.sendPhase == com.wand.app.ui.SendPhase.Idle }
            assertEquals(send.left, bounds(labelled("停止当前任务")!!).left)
            assertEquals(voice, bounds(labelled("按住说话，松手转写，上滑取消")!!))
            screenshot("circle-running-empty")
            click("停止当前任务")
            waitFor("idle empty exterior circle hidden after feedback") { labelled("停止当前任务") == null && labelled("发送消息") == null && composer.sendPhase == com.wand.app.ui.SendPhase.Idle }
            assertNotNull(labelled("按住说话，松手转写，上滑取消"))
            assertEquals("empty composer reclaims the hidden slot", emptyVoice, bounds(labelled("按住说话，松手转写，上滑取消")!!))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun firstSubmissionAndLongScrollRemainBelowControlsWithImeAndLargeText() {
        assumeTrue(context.packageName.endsWith(".preview"))
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        for ((index, variant) in listOf(false to 1f, true to 1.6f).withIndex()) {
            val activity = instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", "viewport")
                .putExtra("dark", variant.first).putExtra("fontScale", variant.second))
            try {
                waitFor("native preview ready") { field(activity, "MessageViewportForTest") != null && field(activity, "ComposerForTest") != null }
                val top = viewport(activity).top
                click("展开键盘输入")
                waitFor("editor expanded with real native IME") {
                    field(activity, "EditorVisible") == true &&
                        activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
                val message = "首条消息布局回归：不要被右上角按钮挡住"
                val composer = field(activity, "ComposerForTest") as ChatComposer
                // Seed only the inert draft; submission still uses the real native button and receipt owner.
                instrumentation.runOnMainSync { composer.editDraft(message) }
                waitFor("send action visible") { labelled("发送消息") != null }
                assertBoundary(activity)
                assertEquals("Keyboard does not move the reading upper boundary", top, viewport(activity).top)
                click("发送消息")
                waitFor("first native submission accepted and rendered outside editor") {
                    field(activity, "SendCount") == 1 && composer.draft.isEmpty() && labelled(message) != null
                }
                assertBoundary(activity, message)
                screenshot("viewport-first-$index")
                var imeVisible = false
                instrumentation.runOnMainSync {
                    imeVisible = activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
                // Successful ACK may already collapse the editor; Back must not leave the preview task.
                if (imeVisible) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(300)
                assertFalse("IME dismissal must not navigate away", activity.isFinishing)
                assertBoundary(activity, message)
                instrumentation.runOnMainSync { composer.editDraft((1..80).joinToString("\n") { "长消息第${it}行：滚动也不能进入顶部按钮区。" }); assertTrue(composer.submit()) }
                waitFor("second native submission accepted") { field(activity, "SendCount") == 2 && composer.draft.isEmpty() }
                swipe(viewport(activity)); Thread.sleep(500)
                assertEquals(top, viewport(activity).top)
                assertBoundary(activity)
                screenshot("viewport-scroll-$index")
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
