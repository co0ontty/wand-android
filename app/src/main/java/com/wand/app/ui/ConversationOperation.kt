package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.ConversationReceipt
import com.wand.app.data.ConversationUnconfirmedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Store-scoped request lock. Detaching a view never cancels or retries a submitted request. */
internal class ConversationOperation(private val scope: CoroutineScope, private val retainAccepted: Boolean = true) {
    var phase by mutableStateOf("idle")
        private set
    var feedback by mutableStateOf("")
        private set
    var unknown by mutableStateOf<String?>(null)
        private set
    var accepted by mutableStateOf<ConversationReceipt?>(null)
        private set
    var purpose by mutableStateOf<String?>(null)
        private set
    val approvalFeedback: Boolean get() = purpose == "approve" && phase != "idle"
    private var viewRevision = 0

    fun attach(): Int = ++viewRevision
    fun detach() { viewRevision++ }
    val canSubmit: Boolean get() = phase != "sending" && unknown == null && accepted == null && !approvalFeedback

    fun submit(request: suspend () -> ConversationReceipt, onAccepted: (ConversationReceipt) -> Unit = {}, purpose: String? = null): Boolean {
        if (!canSubmit) return false
        val revision = viewRevision
        this.purpose = purpose
        phase = "sending"
        feedback = ""
        scope.launch {
            try {
                val receipt = request()
                phase = if (receipt.error == null) "sent" else "failed"
                feedback = receipt.error ?: "请求已接受"
                val acceptedFact = receipt.state == "accepted"
                if (acceptedFact) accepted = receipt
                delay(if (receipt.error == null) SEND_SENT_DWELL_MS else SEND_FAILED_DWELL_MS)
                if (acceptedFact && receipt.error == null && purpose == "approve") {
                    phase = "result"
                    delay(SEND_SENT_DWELL_MS)
                }
                if (viewRevision == revision && acceptedFact && receipt.error == null) onAccepted(receipt)
                if (!acceptedFact || !retainAccepted) { phase = "idle"; accepted = null }
            } catch (e: ConversationUnconfirmedException) {
                phase = "unknown"; unknown = e.requestId; feedback = e.message.orEmpty()
            } catch (e: Exception) {
                phase = "failed"; feedback = e.message.orEmpty()
                delay(SEND_FAILED_DWELL_MS)
                phase = "idle"
            }
        }
        return true
    }

    fun reconcile(read: suspend (String) -> ConversationReceipt) {
        val id = unknown ?: return
        if (phase == "sending") return
        phase = "sending"
        scope.launch {
            try {
                val receipt = read(id)
                when (receipt.state) {
                    "accepted" -> {
                        unknown = null; feedback = receipt.error ?: "已核对：请求已接受"
                        if (purpose == "approve") {
                            accepted = receipt; phase = if (receipt.error == null) "sent" else "failed"
                            delay(if (receipt.error == null) SEND_SENT_DWELL_MS else SEND_FAILED_DWELL_MS)
                            if (receipt.error == null) { phase = "result"; delay(SEND_SENT_DWELL_MS) }
                            if (!retainAccepted) { accepted = null; phase = "idle" }
                        } else {
                            accepted = receipt.takeIf { retainAccepted }
                            phase = if (retainAccepted) if (receipt.error == null) "sent" else "failed" else "idle"
                        }
                    }
                    "rejected" -> {
                        unknown = null; phase = "failed"; feedback = receipt.error ?: "未接受，草稿保留"
                        if (purpose == "approve") { delay(SEND_FAILED_DWELL_MS); phase = "idle" }
                    }
                    else -> { phase = "unknown"; feedback = "仍未确认，请勿重复提交" }
                }
            } catch (e: Exception) { phase = "unknown"; feedback = e.message.orEmpty() }
        }
    }
}

/** UI metadata only; composer text and attachments remain exclusively in ChatComposer. */
internal class ConversationComposeDraft {
    var title by mutableStateOf("")
    var projectId by mutableStateOf("")
    var continueTask by mutableStateOf<String?>(null)
    var memberVersion by mutableStateOf(1)
    var chatCwd by mutableStateOf("")
    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ConversationComposeDraft, Any>(
            save = { listOf(it.title, it.projectId, it.continueTask.orEmpty(), it.memberVersion, it.chatCwd) },
            restore = { saved -> ConversationComposeDraft().apply {
                title = saved[0] as String; projectId = saved[1] as String
                continueTask = (saved[2] as String).takeIf { it.isNotBlank() }
                memberVersion = saved[3] as Int; chatCwd = saved[4] as String
            } },
        )
    }
}

internal class ConversationGroupDraft {
    var selected by mutableStateOf(emptyList<String>())
    var templateId by mutableStateOf("")
    var excluded by mutableStateOf(emptyList<String>())
    var leaderId by mutableStateOf("")
    var name by mutableStateOf("")
    var tab by mutableStateOf(0)
    var duties by mutableStateOf<Map<String, String>>(emptyMap())

    /** Instance-only metadata. No employee/template/run is modified by editing or serialization. */
    fun groupInput(dutyValues: Map<String, String>, inviting: Boolean): org.json.JSONObject = org.json.JSONObject()
        .put("employeeIds", org.json.JSONArray(selected))
        .put("excludedMemberIds", org.json.JSONArray(excluded))
        .put("duties", org.json.JSONObject(dutyValues)).apply {
            if (templateId.isNotBlank()) put("templateId", templateId)
            if (!inviting) {
                put("name", name)
                if (leaderId.isNotBlank()) put("leaderId", leaderId)
            }
        }

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ConversationGroupDraft, Any>(
            save = { listOf(it.selected, it.templateId, it.excluded, it.leaderId, it.name, it.tab, it.duties.toMap()) },
            restore = { saved -> ConversationGroupDraft().apply {
                @Suppress("UNCHECKED_CAST")
                selected = saved[0] as List<String>
                templateId = saved[1] as String
                @Suppress("UNCHECKED_CAST")
                excluded = saved[2] as List<String>
                leaderId = saved[3] as String; name = saved[4] as String; tab = saved[5] as Int
                duties = (saved.getOrNull(6) as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                    if (key is String && value is String) key to value else null
                }?.toMap().orEmpty()
            } },
        )
    }
}
