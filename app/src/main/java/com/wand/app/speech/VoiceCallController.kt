package com.wand.app.speech

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Platform adapters are replaceable; tests never open a microphone or dispatch AI. */
interface TextSpeaker {
    /** True only after the platform reports output started, not while initializing. */
    val isSpeaking: Boolean get() = false
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun destroy()
}

enum class VoiceCallPhase { Off, Listening, Transcribing, Submitting, Processing, Speaking, Paused, Approval, Error }

class VoiceCallController(
    private val scope: CoroutineScope,
    private val engineFactory: () -> SpeechEngine?,
    private val speaker: TextSpeaker,
    private val send: suspend (String) -> Boolean,
    private val ready: () -> Boolean,
    private val messageCount: () -> Int,
) {
    var phase by mutableStateOf(VoiceCallPhase.Off)
        private set
    var transcript by mutableStateOf("")
        private set
    var notice by mutableStateOf("")
        private set
    val active: Boolean get() = phase != VoiceCallPhase.Off
    val audioPlaying: Boolean get() = phase == VoiceCallPhase.Speaking && speaker.isSpeaking
    private var generation = 0
    private var engine: SpeechEngine? = null
    private var silence: Job? = null
    private var finalBudget: Job? = null
    private var submission: Job? = null
    private var outstanding = false
    private var afterMessageCount = 0
    private var disposed = false
    private var responseBudget: Job? = null
    private var observedRunning = false

    fun start() {
        if (disposed || phase !in listOf(VoiceCallPhase.Off, VoiceCallPhase.Paused, VoiceCallPhase.Error)) return
        if (outstanding) { phase = VoiceCallPhase.Processing; notice = "等待当前回复"; return }
        if (!ready()) { fail("请先处理现有草稿、附件、权限或正在执行的任务，再开始通话"); return }
        listen()
    }

    private fun listen() {
        releaseInput()
        speaker.stop()
        val chosen = engineFactory() ?: run { fail("端侧识别不可用，请在语音设置中启用本地模型；文本聊天仍可用"); return }
        engine = chosen
        val token = ++generation
        transcript = ""
        notice = "正在聆听，停顿后自动发送"
        phase = VoiceCallPhase.Listening
        // No silent, hot-microphone loop. A quiet window pauses for explicit resume.
        silence = scope.launch { delay(30_000); if (token == generation) pause("没有听到内容，点击继续") }
        val listener = object : SpeechEngine.Listener {
            override fun onPartial(text: String) {
                if (token != generation || phase != VoiceCallPhase.Listening) return
                if (text != transcript && text.isNotBlank()) {
                    transcript = text
                    silence?.cancel()
                    silence = scope.launch { delay(1_600); if (token == generation) finishListening() }
                }
            }
            override fun onFinal(text: String) {
                if (token != generation || phase !in listOf(VoiceCallPhase.Listening, VoiceCallPhase.Transcribing)) return
                accept(text)
            }
            override fun onError(message: String) { if (token == generation) fail(message) }
        }
        try { chosen.start(listener) } catch (_: Exception) { if (token == generation) fail("麦克风或识别引擎启动失败，请核对系统权限") }
    }

    fun finishListening() {
        if (phase != VoiceCallPhase.Listening) return
        silence?.cancel()
        phase = VoiceCallPhase.Transcribing
        val token = generation
        finalBudget = scope.launch {
            delay(engine?.finalTimeoutMs ?: 1_500)
            // Do not dispatch an unconfirmed partial when final delivery fails.
            if (token == generation) fail("识别未返回最终文字，请核对转写后再继续")
        }
        engine?.finish()
    }

    private fun accept(text: String) {
        releaseInput()
        generation++
        transcript = text.trim()
        if (transcript.isBlank()) { pause("没有识别到文字，点击继续"); return }
        if (!ready()) { fail("当前会话或草稿已变化，未发送；请核对转写"); return }
        afterMessageCount = messageCount()
        phase = VoiceCallPhase.Submitting
        notice = "正在提交"
        val token = generation
        submission = scope.launch {
            val accepted = try { send(transcript) } catch (_: Exception) { false }
            if (token != generation || disposed) return@launch
            if (accepted) {
                outstanding = true; observedRunning = false; phase = VoiceCallPhase.Processing; notice = "等待员工回复"
                responseBudget = scope.launch { delay(300_000); if (token == generation && phase == VoiceCallPhase.Processing) pause("回复等待较久，请核对执行状态后继续") }
            }
            else fail("发送未确认，请核对文本记录与草稿；通话不会自动重发")
        }
    }

    /** Only the response after this call's accepted user turn is eligible for speech. */
    fun agentUpdate(messages: List<ConversationTurn>, running: Boolean, approval: Boolean, connected: Boolean) {
        if (!active || phase == VoiceCallPhase.Error || phase == VoiceCallPhase.Paused) return
        if (!connected) { pause("网络已断开，恢复后点击继续"); return }
        if (approval) { releaseInput(); speaker.stop(); generation++; phase = VoiceCallPhase.Approval; notice = "请在原权限面板核对并确认"; return }
        if (phase == VoiceCallPhase.Approval) { phase = if (outstanding) VoiceCallPhase.Processing else VoiceCallPhase.Paused; return }
        if (phase != VoiceCallPhase.Processing || !outstanding) return
        if (running) { observedRunning = true; return }
        val fresh = messages.drop(afterMessageCount)
        if (fresh.none { it.role == "user" }) return
        val replies = fresh.dropWhile { it.role != "user" }.drop(1).filter { it.role == "assistant" && !it.notice }
        if (replies.isEmpty()) {
            if (observedRunning) { outstanding = false; pause("执行已结束，未收到可播报回复；请核对文本记录") }
            return
        }
        responseBudget?.cancel(); responseBudget = null
        outstanding = false
        val text = spokenReply(replies)
        if (text.isBlank()) { listen(); return }
        phase = VoiceCallPhase.Speaking
        notice = "正在播报，点击插话可立即停止"
        val token = ++generation
        speaker.speak(text, onDone = { if (token == generation && phase == VoiceCallPhase.Speaking) listen() },
            onError = { if (token == generation) fail(it) })
    }

    fun interrupt() {
        if (phase != VoiceCallPhase.Speaking) return
        generation++
        speaker.stop()
        listen()
    }

    fun pause(reason: String = "通话已暂停，点击继续") {
        if (!active) return
        generation++
        releaseInput()
        speaker.stop()
        submission?.cancel()
        // A pending transport belongs to ChatComposer, so pausing never repeats it.
        if (phase == VoiceCallPhase.Submitting) {
            phase = VoiceCallPhase.Error
            notice = "提交期间通话被打断，请核对文本记录和草稿；不会自动重发"
        } else {
            phase = VoiceCallPhase.Paused
            notice = reason
        }
    }

    private fun fail(message: String) {
        generation++
        releaseInput()
        speaker.stop()
        phase = VoiceCallPhase.Error
        notice = message
    }

    fun end() {
        generation++
        releaseInput()
        speaker.stop()
        submission?.cancel()
        responseBudget?.cancel(); responseBudget = null
        outstanding = false
        phase = VoiceCallPhase.Off
        notice = ""
    }

    fun destroy() { end(); disposed = true; speaker.destroy() }
    private fun releaseInput() {
        silence?.cancel(); silence = null
        finalBudget?.cancel(); finalBudget = null
        engine?.cancel(); engine?.destroy(); engine = null
    }
}

/** Read prose, summarize code/tools, preserve the original messages in ChatStore. */
fun spokenReply(turns: List<ConversationTurn>): String {
    val prose = turns.flatMap { it.content }.filterIsInstance<ContentBlock.Text>().filter { it.subagent == null }
        .joinToString("\n") { it.text }
    val hasTools = turns.any { turn -> turn.content.any { it is ContentBlock.ToolUse || it is ContentBlock.ToolResult } }
    val code = Regex("(?s)```.*?(?:```|$)|(?s)~~~.*?(?:~~~|$)")
    val hadCode = code.containsMatchIn(prose)
    val plain = prose.replace(code, "。代码内容可在聊天记录中查看。")
        .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}(?:#{1,6}\\s+|>\\s*)"), "")
        .replace(Regex("[`*_]"), "").trim()
    val bounded = if (plain.length > 900) plain.take(900) + "。更多内容请查看完整文本。" else plain
    return listOf(bounded, if (hasTools) "工具执行详情保留在聊天记录中。" else "",
        if (bounded.isBlank() && hadCode) "代码内容可在聊天记录中查看。" else "").filter { it.isNotBlank() }.joinToString(" ")
}
