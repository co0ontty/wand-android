package com.wand.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wand.app.ui.ChatComposer
import com.wand.app.ui.SendPhase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real native pointers/layout; inert recognizer and receipt adapter. No permissions, AI or user data. */
@RunWith(AndroidJUnit4::class)
class CompanionDockInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun start(mode: String): Activity {
        assumeTrue(context.packageName.endsWith(".preview"))
        return instrumentation.startActivitySync(Intent().setClassName(context.packageName, "com.wand.app.ChatPreviewActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", mode))
    }
    private fun finish(activity: Activity) = instrumentation.runOnMainSync { activity.finish() }
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) web(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun field(activity: Activity, name: String): Any? = activity.javaClass.getMethod("get$name").invoke(activity)
    private fun composer(activity: Activity) = field(activity, "ComposerForTest") as ChatComposer
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            instrumentation.runOnMainSync { result = condition() }
            if (result) return
            Thread.sleep(60)
        }
        fail(message)
    }
    private fun point(activity: Activity): Pair<Float, Float> {
        var result = 0f to 0f
        waitFor("real scene mounted") { web(activity.window.decorView) != null }
        instrumentation.runOnMainSync {
            val scene = web(activity.window.decorView)!!
            val location = IntArray(2); scene.getLocationOnScreen(location)
            result = location[0] + scene.width / 2f to location[1] + scene.height / 2f
        }
        return result
    }
    private fun pointer(start: Pair<Float, Float>, holdMs: Long, dx: Float = 0f, dy: Float = 0f, earlyDrag: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            val input = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            input.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(input, true)); input.recycle()
        }
        event(MotionEvent.ACTION_DOWN, start.first, start.second)
        if (earlyDrag) {
            Thread.sleep(45); event(MotionEvent.ACTION_MOVE, start.first + dx * 0.2f, start.second)
            Thread.sleep(45); event(MotionEvent.ACTION_MOVE, start.first + dx, start.second + dy)
        } else {
            Thread.sleep(holdMs)
            if (dx != 0f || dy != 0f) event(MotionEvent.ACTION_MOVE, start.first + dx, start.second + dy)
        }
        Thread.sleep(40)
        event(MotionEvent.ACTION_UP, start.first + dx, start.second + dy)
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label || node.text?.toString() == label ||
            node.actionList.any { it.label?.toString() == label }) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node = node.parent
            }
            Thread.sleep(80)
        }
        fail("Native accessible action missing: $label")
    }
    private fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
        return null
    }
    private fun type(text: String) {
        val node = editable(instrumentation.uiAutomation.rootInActiveWindow)
        assertNotNull("Native text field must remain editable", node)
        node!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
    }
    private fun imeVisible(activity: Activity): Boolean =
        ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test fun modelSearchSelectCancelAndPickerReturnKeepTheSameDraftEditor() {
        val activity = start("composer-focus")
        try {
            click("展开键盘输入")
            waitFor("editor IME opened") { imeVisible(activity) && field(activity, "EditorVisible") == true }
            type("模型切换保留草稿")
            waitFor("draft accepted") { composer(activity).draft == "模型切换保留草稿" }
            click("选择示例模型")
            waitFor("model sheet survives IME handoff") { field(activity, "ModelSheetOpen") == true && !imeVisible(activity) }
            assertEquals(true, field(activity, "EditorVisible"))
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "搜索模型"))
            type("model-b")
            click("示例模型 B")
            waitFor("selection applied and editor focus restored") {
                field(activity, "ModelForTest") == "model-b" && field(activity, "ModelSheetOpen") == false && imeVisible(activity)
            }
            assertEquals("模型切换保留草稿", composer(activity).draft)
            click("选择示例模型")
            waitFor("second model sheet opened") { field(activity, "ModelSheetOpen") == true && !imeVisible(activity) }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("cancel restores editor not page navigation") { field(activity, "ModelSheetOpen") == false && imeVisible(activity) }
            assertEquals(true, field(activity, "EditorVisible"))
            click("更多操作：附件、设置与任务")
            click("从文件选择")
            waitFor("native picker borrowed the window") { !activity.hasWindowFocus() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("picker cancel restored editor") { activity.hasWindowFocus() && imeVisible(activity) && field(activity, "EditorVisible") == true }
            assertEquals("模型切换保留草稿", composer(activity).draft)
            assertEquals(0, field(activity, "SendCount"))
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("explicit editor Back still collapses") { field(activity, "EditorVisible") == false }
            assertEquals("模型切换保留草稿", composer(activity).draft)
        } finally { finish(activity) }
    }

    @Test fun taskDetailsRevealUsageWithoutMovingTheCompanionOrItsControls() {
        val activity = start("progress")
        try {
            val initial = point(activity)
            val mic = find(instrumentation.uiAutomation.rootInActiveWindow, "按住说话，松手转写，上滑取消")
            assertNotNull(mic)
            val micBounds = Rect(); mic!!.getBoundsInScreen(micBounds)
            assertTrue("Scene and controls share one centerline", kotlin.math.abs(initial.second - micBounds.exactCenterY()) <= 2f)
            instrumentation.runOnMainSync { composer(activity).editDraft("测试第三个操作槽") }
            Thread.sleep(500)
            val expandedMic = find(instrumentation.uiAutomation.rootInActiveWindow, "按住说话，松手转写，上滑取消")
            val afterDraft = Rect(); expandedMic!!.getBoundsInScreen(afterDraft)
            assertEquals("Mic does not move when send/stop appears", micBounds, afterDraft)
            instrumentation.runOnMainSync { composer(activity).editDraft("") }
            Thread.sleep(500)
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "正在处理"))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "\$0.13"))
            click("展开任务进度")
            Thread.sleep(500)
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "\$0.13"))
            assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "修复输入焦点与重复语音入口，保留模型搜索、附件选择和原生文字编辑"))
            assertEquals(initial.second, point(activity).second, 2f)
            click("收起任务进度")
            Thread.sleep(500)
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "\$0.13"))
            assertEquals(initial.second, point(activity).second, 2f)
            assertEquals(0, field(activity, "SendCount"))
        } finally { finish(activity) }
    }

    @Test fun emptyTextFieldLongPressNeverStartsAudio() {
        val activity = start("composer-focus")
        try {
            click("展开键盘输入")
            waitFor("editor ready") { imeVisible(activity) && field(activity, "EditorVisible") == true }
            val node = editable(instrumentation.uiAutomation.rootInActiveWindow)
            assertNotNull(node)
            val bounds = Rect(); node!!.getBoundsInScreen(bounds)
            pointer(bounds.centerX().toFloat() to bounds.centerY().toFloat(), 650)
            assertEquals(0, field(activity, "VoiceStarts"))
            assertEquals(0, field(activity, "VoiceCommits"))
            assertEquals("", composer(activity).draft)
            assertEquals(true, field(activity, "EditorVisible"))
        } finally { finish(activity) }
    }

    private fun side(): String? = context.getSharedPreferences("wand_chat_companion", Context.MODE_PRIVATE).getString("side", "Left")
    private fun resetFixtureSide() {
        assumeTrue(context.packageName.endsWith(".preview"))
        context.getSharedPreferences("wand_chat_companion", Context.MODE_PRIVATE).edit().putString("side", "Left").commit()
    }

    @Test fun actualTapDragHoldCancelAndDraftTranscriptionAreExclusive() {
        resetFixtureSide()
        val activity = start("idle")
        try {
            waitFor("fixture ready") { field(activity, "ComposerForTest") != null }
            assertEquals(false, field(activity, "EditorVisible"))
            val startPoint = point(activity)
            val first = web(activity.window.decorView)
            pointer(startPoint, 70)
            waitFor("tap opens original native editor") { field(activity, "EditorVisible") == true }
            instrumentation.runOnMainSync { assertSame("One scene survives editing", first, web(activity.window.decorView)); composer(activity).editDraft("原草稿") }
            Thread.sleep(700)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("keyboard dismissal retains collapsed draft") { field(activity, "EditorVisible") == false }
            assertEquals("原草稿", composer(activity).draft)
            val density = context.resources.displayMetrics.density
            pointer(point(activity), 0, 220 * density, 0f, true)
            waitFor("drag snaps right and persists") { side() == "Right" }
            assertEquals(0, field(activity, "VoiceStarts"))
            pointer(point(activity), 370, -40 * density, -90 * density)
            waitFor("upward release cancels") { field(activity, "VoiceCancels") == 1 }
            assertEquals("Right", side())
            assertEquals("原草稿", composer(activity).draft)
            assertEquals(0, field(activity, "VoiceCommits"))
            pointer(point(activity), 370)
            waitFor("release only appends editable draft") { field(activity, "VoiceCommits") == 1 }
            assertEquals("原草稿 请继续检查布局", composer(activity).draft)
            assertEquals(0, field(activity, "SendCount"))
            waitFor("transcription restores editor") { field(activity, "EditorVisible") == true }
            assertEquals(SendPhase.Idle, composer(activity).sendPhase)
        } finally { finish(activity) }
        val otherChat = start("orange")
        try {
            val position = point(otherChat)
            assertTrue("Global local side retained across chats", position.first > context.resources.displayMetrics.widthPixels / 2)
        } finally { finish(otherChat) }
    }

    @Test fun noSpeechNeverOverwritesExistingDraftOrDispatches() {
        resetFixtureSide()
        val activity = start("silence")
        try {
            waitFor("fixture ready") { field(activity, "ComposerForTest") != null }
            instrumentation.runOnMainSync { composer(activity).editDraft("保留原稿") }
            pointer(point(activity), 370)
            Thread.sleep(900)
            assertEquals("保留原稿", composer(activity).draft)
            assertEquals(0, field(activity, "VoiceCommits"))
            assertEquals(0, field(activity, "SendCount"))
        } finally { finish(activity) }
    }

    @Test fun successFailureAndUnknownUseOriginalReceiptAndAttachmentOwner() {
        for (mode in listOf("send", "failure", "unknown")) {
            val activity = start(mode)
            try {
                waitFor("fixture ready") { field(activity, "ComposerForTest") != null && !composer(activity).uploading }
                click("发送消息")
                waitFor("receipt applied") { composer(activity).sendPhase == if (mode == "send") SendPhase.Sent else SendPhase.Failed }
                if (mode == "send") {
                    assertEquals("", composer(activity).draft)
                    waitFor("only accepted empty draft collapses") { field(activity, "EditorVisible") == false }
                } else {
                    assertEquals("继续检查键盘遮挡", composer(activity).draft)
                    assertEquals(1, composer(activity).attachments.size)
                    waitFor("failed/unknown visible full editor") { field(activity, "EditorVisible") == true }
                    if (mode == "unknown") assertFalse(composer(activity).canSubmit)
                }
                assertEquals(1, field(activity, "SendCount"))
            } finally { finish(activity) }
        }
    }

    @Test fun attachmentOnlyHasExplicitExpandedSendAndEmptyRunningHasOnlyTwoActions() {
        val emptyRunning = start("running-empty")
        try {
            point(emptyRunning); Thread.sleep(400)
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "停止当前任务"))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "发送消息"))
            click("更多：会话对象、工作目录与变更")
            click("停止当前任务") // Existing More route stays available.
        } finally { finish(emptyRunning) }
        val attachment = start("attachment")
        try {
            waitFor("local fake attachment added") { field(attachment, "ComposerForTest") != null && composer(attachment).attachments.size == 1 }
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "发送消息"))
            click("展开键盘输入")
            click("发送附件")
            waitFor("attachment receipt accepted") { composer(attachment).sendPhase == SendPhase.Sent }
            assertTrue(composer(attachment).attachments.isEmpty())
            assertEquals(1, field(attachment, "SendCount"))
        } finally { finish(attachment) }
    }
}
