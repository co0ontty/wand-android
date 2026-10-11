package com.wand.app.speech

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallControllerTest {
    private class Engine : SpeechEngine {
        override val label = "fixture"
        lateinit var listener: SpeechEngine.Listener
        var cancelled = false
        override fun start(listener: SpeechEngine.Listener) { this.listener = listener }
        override fun finish() { listener.onFinal("已确认转写") }
        override fun cancel() { cancelled = true }
        override fun destroy() {}
    }
    private class Speaker : TextSpeaker {
        override var isSpeaking = false
        var spoken = ""
        var done: (() -> Unit)? = null
        var error: ((String) -> Unit)? = null
        var stopped = 0
        override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) { spoken = text; done = onDone; error = onError }
        override fun stop() { stopped++; isSpeaking = false }
        override fun destroy() {}
    }
    private fun user(text: String = "问题") = ConversationTurn("user", listOf(ContentBlock.Text(text, null)))
    private fun reply(text: String = "已完成") = ConversationTurn("assistant", listOf(ContentBlock.Text(text, null)), completedAt = "done")

    @Test fun mouthStartsOnlyWithAudioAndPauseStopsVoiceWithoutDispatchingTask() = runTest {
        val engine = Engine(); val speaker = Speaker(); var sends = 0
        val controller = VoiceCallController(this, { engine }, speaker, { sends++; true }, { true }, { 0 })
        controller.start(); engine.listener.onFinal("问题"); runCurrent()
        controller.agentUpdate(listOf(user(), reply()), false, false, true)
        assertEquals(VoiceCallPhase.Speaking, controller.phase)
        assertFalse(controller.audioPlaying) // Platform initialization/queued speech.
        speaker.isSpeaking = true // Fake platform onStart.
        assertTrue(controller.audioPlaying)
        controller.pause("只暂停声音")
        assertFalse(controller.audioPlaying)
        assertEquals(1, sends)
        assertEquals(VoiceCallPhase.Paused, controller.phase)
        controller.destroy()
    }

    @Test fun acceptedUtteranceWaitsForOwnReplySpeaksThenListensAgain() = runTest {
        val engines = mutableListOf<Engine>(); val speaker = Speaker(); val sent = mutableListOf<String>()
        val controller = VoiceCallController(this, { Engine().also { engines.add(it) } }, speaker,
            { sent.add(it); true }, { true }, { 2 })
        controller.start(); engines.last().listener.onFinal("  新问题  "); runCurrent()
        assertEquals(listOf("新问题"), sent); assertEquals(VoiceCallPhase.Processing, controller.phase)
        controller.agentUpdate(listOf(user("历史"), reply("历史回复")), false, false, true)
        assertEquals("", speaker.spoken)
        controller.agentUpdate(listOf(user("历史"), reply("历史回复"), user("新问题"), reply()), false, false, true)
        assertEquals("已完成", speaker.spoken); assertEquals(VoiceCallPhase.Speaking, controller.phase)
        speaker.done!!.invoke(); assertEquals(VoiceCallPhase.Listening, controller.phase); assertEquals(2, engines.size)
        controller.destroy()
    }
    @Test fun duplicateFinalAndUnknownDeliveryNeverResubmit() = runTest {
        val engine = Engine(); var sends = 0
        val controller = VoiceCallController(this, { engine }, Speaker(), { sends++; false }, { true }, { 0 })
        controller.start(); engine.listener.onFinal("问题"); engine.listener.onFinal("问题"); runCurrent()
        assertEquals(1, sends); assertEquals(VoiceCallPhase.Error, controller.phase)
        controller.agentUpdate(listOf(user(), reply()), false, false, true)
        assertEquals(VoiceCallPhase.Error, controller.phase); controller.destroy()
    }
    @Test fun approvalStopsCaptureAndDoesNotApproveOrReplay() = runTest {
        val engine = Engine(); var sends = 0
        val controller = VoiceCallController(this, { engine }, Speaker(), { sends++; true }, { true }, { 0 })
        controller.start(); controller.agentUpdate(emptyList(), false, true, true)
        assertTrue(engine.cancelled); assertEquals(VoiceCallPhase.Approval, controller.phase)
        engine.listener.onFinal("迟到"); assertEquals(0, sends)
        controller.agentUpdate(emptyList(), false, false, true)
        assertEquals(VoiceCallPhase.Paused, controller.phase); controller.destroy()
    }
    @Test fun interruptionStopsSpeechAndIgnoresOldCompletion() = runTest {
        val engines = mutableListOf<Engine>(); val speaker = Speaker()
        val controller = VoiceCallController(this, { Engine().also { engines.add(it) } }, speaker, { true }, { true }, { 0 })
        controller.start(); engines.last().listener.onFinal("问题"); runCurrent()
        controller.agentUpdate(listOf(user(), reply()), false, false, true)
        val lateDone = speaker.done!!; controller.interrupt(); lateDone()
        assertEquals(VoiceCallPhase.Listening, controller.phase); assertEquals(2, engines.size)
        controller.destroy()
    }
    @Test fun backgroundAndOfflineRequireExplicitResume() = runTest {
        val engines = mutableListOf<Engine>(); var sends = 0
        val controller = VoiceCallController(this, { Engine().also { engines.add(it) } }, Speaker(), { sends++; true }, { true }, { 0 })
        controller.start(); val old = engines.last(); controller.pause("后台")
        old.listener.onFinal("迟到"); runCurrent(); assertEquals(0, sends)
        controller.agentUpdate(emptyList(), false, false, true); assertEquals(1, engines.size)
        controller.start(); assertEquals(2, engines.size)
        controller.agentUpdate(emptyList(), false, false, false); assertEquals(VoiceCallPhase.Paused, controller.phase)
        controller.destroy()
    }
    @Test fun pauseWhileSubmittingCannotAutomaticallySendAgain() = runTest {
        val engine = Engine(); var sends = 0
        val controller = VoiceCallController(this, { engine }, Speaker(), { sends++; kotlinx.coroutines.delay(1000); true }, { true }, { 0 })
        controller.start(); engine.listener.onFinal("问题"); runCurrent(); controller.pause(); advanceTimeBy(2000); runCurrent()
        assertEquals(VoiceCallPhase.Error, controller.phase); assertEquals(1, sends); controller.destroy()
    }
    @Test fun missingEngineAndChangedDraftDoNotDispatch() = runTest {
        var ready = true; var sends = 0; val engine = Engine()
        val controller = VoiceCallController(this, { engine }, Speaker(), { sends++; true }, { ready }, { 0 })
        controller.start(); ready = false; engine.listener.onFinal("草稿已变化"); runCurrent()
        assertEquals(0, sends); assertEquals("草稿已变化", controller.transcript); assertEquals(VoiceCallPhase.Error, controller.phase)
        controller.destroy()
        val unavailable = VoiceCallController(this, { null }, Speaker(), { true }, { true }, { 0 })
        unavailable.start(); assertEquals(VoiceCallPhase.Error, unavailable.phase); unavailable.destroy()
    }
    @Test fun silencePausesInsteadOfReopeningMicrophone() = runTest {
        val engine = Engine(); val controller = VoiceCallController(this, { engine }, Speaker(), { true }, { true }, { 0 })
        controller.start(); advanceTimeBy(30_001); runCurrent()
        assertEquals(VoiceCallPhase.Paused, controller.phase); assertTrue(engine.cancelled); controller.destroy()
    }
    @Test fun finalBudgetDoesNotDispatchUnconfirmedPartial() = runTest {
        var sends = 0
        val engine = object : SpeechEngine {
            override val label = "fixture"
            lateinit var listener: SpeechEngine.Listener
            override fun start(listener: SpeechEngine.Listener) { this.listener = listener; listener.onPartial("仅partial") }
            override fun finish() {}
            override fun cancel() {}
            override fun destroy() {}
        }
        val controller = VoiceCallController(this, { engine }, Speaker(), { sends++; true }, { true }, { 0 })
        controller.start(); advanceTimeBy(3_200); runCurrent()
        assertEquals(0, sends); assertEquals(VoiceCallPhase.Error, controller.phase); controller.destroy()
    }
    @Test fun speakerFailureLeavesFullTextAndStopsLoop() = runTest {
        val engine = Engine(); val speaker = Speaker()
        val messages = listOf(user(), reply("完整文本"))
        val controller = VoiceCallController(this, { engine }, speaker, { true }, { true }, { 0 })
        controller.start(); engine.listener.onFinal("问题"); runCurrent(); controller.agentUpdate(messages, false, false, true)
        speaker.error!!.invoke("未安装语音"); assertEquals(VoiceCallPhase.Error, controller.phase)
        assertEquals("完整文本", (messages[1].content[0] as ContentBlock.Text).text); controller.destroy()
    }
    @Test fun spokenSummaryOmitsCodeSymbolsAndBoundsLongProse() {
        val turns = listOf(reply("结论\n```ts\nsecret_code();\n```\n[详情](https://example.com)"),
            ConversationTurn("assistant", listOf(ContentBlock.ToolUse("t", "Read", null, JSONObject(), null))))
        val spoken = spokenReply(turns)
        assertTrue(spoken.contains("结论")); assertFalse(spoken.contains("secret_code")); assertFalse(spoken.contains("https://"))
        assertTrue(spoken.contains("工具执行详情")); assertTrue(spokenReply(listOf(reply("字".repeat(3000)))).length < 1000)
    }
}
