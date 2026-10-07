package com.wand.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Read-only instance DTO projection. Session protocol/queues still belong to ChatStore. */
class ConversationStore(
    val api: WandApi,
    private val drafts: SessionDraftStore,
    private val scope: CoroutineScope,
    private val getPreference: () -> String,
    private val setPreference: (String) -> Unit,
) {
    private val saved = runCatching { JSONObject(getPreference()) }.getOrNull() ?: JSONObject()
    var selectedId by mutableStateOf(saved.optString("selectedId"))
        private set
    var selectedTitle by mutableStateOf(saved.optString("selectedTitle"))
        private set
    val items = mutableStateListOf<ConversationInstance>()
    val employees = mutableStateListOf<SiliconEmployee>()
    val presets = mutableStateListOf<AiTeam>()
    val projects = mutableStateListOf<Workspace>()
    val details = mutableStateMapOf<String, ConversationInstance>()
    val feedback = mutableStateMapOf<String, String>()
    val unknownRequests = mutableStateMapOf<String, String>()
    val targets = mutableStateMapOf<String, ConversationTarget>()
    val filters = mutableStateMapOf<String, String>()
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private val composers = mutableMapOf<String, ChatComposer>()
    private val listStates = mutableMapOf<String, LazyListState>()
    // IM 和执行页共用 ChatStore 协议；列表重绘或卡片收放不重建提问选择。
    private val sessionStores = mutableMapOf<String, ChatStore>()
    private val sessionSubscribers = mutableMapOf<String, Int>()
    internal fun sessionStore(id: String): ChatStore = sessionStores.getOrPut(id) { ChatStore(id, api) }
    internal fun attachSession(id: String) {
        val count = sessionSubscribers[id] ?: 0
        sessionSubscribers[id] = count + 1
        if (count == 0) sessionStore(id).start()
    }
    internal fun detachSession(id: String) {
        val count = (sessionSubscribers[id] ?: 1) - 1
        if (count <= 0) { sessionSubscribers.remove(id); sessionStores[id]?.shutdown() }
        else sessionSubscribers[id] = count
    }
    private var refreshJob: Job? = null
    private val generations = mutableMapOf<String, Int>()
    var onSelection: (String) -> Unit = {}
    var layerRevision by mutableStateOf(0)
        private set
    fun closeLayers() { layerRevision++ }
    val expandedGroups = mutableStateListOf<String>()
    val expandedMessages = mutableStateListOf<String>()
    private val operations = mutableMapOf<String, ConversationOperation>()
    private val groupDrafts = mutableMapOf<String, ConversationGroupDraft>()
    private val composeDrafts = mutableMapOf<String, ConversationComposeDraft>()
    internal fun composeDraft(id: String): ConversationComposeDraft = composeDrafts.getOrPut(id) { ConversationComposeDraft() }
    internal fun rememberComposeDraft(id: String, draft: ConversationComposeDraft) { composeDrafts[id] = draft }
    internal fun operation(key: String): ConversationOperation = operations.getOrPut(key) { ConversationOperation(scope, retainAccepted = key.startsWith("group:")) }
    internal fun groupDraft(key: String): ConversationGroupDraft = groupDrafts.getOrPut(key) { ConversationGroupDraft() }
    internal fun rememberGroupDraft(key: String, draft: ConversationGroupDraft) { groupDrafts[key] = draft }
    internal fun finishGroup(key: String) {
        operations.remove("group:$key")
        groupDrafts.remove(key)?.apply { selected = emptyList(); templateId = ""; excluded = emptyList(); leaderId = ""; name = ""; tab = 0; duties = emptyMap() }
    }
    fun openSession(id: String, onOpen: (SessionSnapshot) -> Unit) { scope.launch { runCatching { api.getSession(id) }.onSuccess(onOpen).onFailure { error = it.message } } }

    init {
        saved.optJSONArray("expandedGroups")?.let { rows -> repeat(rows.length()) { expandedGroups.add(rows.optString(it)) } }
        saved.optJSONArray("expandedMessages")?.let { rows -> repeat(rows.length()) { expandedMessages.add(rows.optString(it)) } }
        saved.optJSONObject("targets")?.let { json -> json.keys().forEach { id ->
            ConversationTarget.parse(json.optJSONObject(id))?.let { targets[id] = it }
        } }
        saved.optJSONObject("filters")?.let { json -> json.keys().forEach { filters[it] = json.optString(it) } }
    }
    fun select(id: String) {
        if (selectedId != id) selectedTitle = ""
        selectedId = id
        selectedTitle = details[id]?.title ?: items.firstOrNull { it.id == id }?.title ?: employees.firstOrNull { employeeConversationId(it.id) == id }?.name ?: selectedTitle
        onSelection(id); persist()
    }
    fun openTask(link: ConversationLink, preview: ConversationTaskPreview?) {
        if (preview?.status == "unavailable") return
        filter(link.conversationId, link.taskId)
        target(link.conversationId, preview?.runId?.takeIf { preview.status in listOf("running", "awaiting_approval", "waiting_user") }?.let { ConversationTarget(link.taskId, it) })
        select(link.conversationId)
    }
    /** One read-only socket for the mounted DM; the existing detail poll repairs reconnect gaps. */
    internal fun watchTaskPreviews(id: String): () -> Unit {
        val socket = WandSocket(api.baseUrl, api.token)
        socket.onConversationSessionPreview = { update ->
            val detail = details[id]
            if (selectedId == id && update.conversationId == id && detail != null) {
                details[id] = detail.copy(messages = detail.messages.map { turn ->
                    if (turn.sessionLink?.sessionId == update.sessionId) turn.copy(sessionPreview = update.preview) else turn
                })
            }
        }
        socket.onTeamStepLive = { live ->
            val detail = details[id]
            val text = conversationTaskLiveText(live.steps)
            if (selectedId == id && detail != null && text.isNotBlank()) {
                val messages = detail.messages.map { turn ->
                    val preview = turn.taskPreview
                    if (preview?.runId == live.runId && preview.status == "running") turn.copy(taskPreview = preview.copy(text = text)) else turn
                }
                if (messages != detail.messages) details[id] = detail.copy(messages = messages)
            }
        }
        socket.connect()
        return { socket.close() }
    }
    fun target(id: String, target: ConversationTarget?) {
        if (target == null) targets.remove(id) else targets[id] = target
        persist()
    }
    fun filter(id: String, value: String) { filters[id] = value; persist() }
    fun draftKey(id: String, target: ConversationTarget? = targets[id]): String = "conversation:$id:${target?.taskId.orEmpty()}:${target?.runId ?: "talk"}"
    fun listState(key: String): LazyListState = listStates.getOrPut(key) {
        val position = saved.optJSONObject("scrolls")?.optJSONObject(key)
        LazyListState(position?.optInt("index") ?: 0, position?.optInt("offset") ?: 0)
    }
    fun persist() {
        val scrolls = JSONObject()
        listStates.forEach { (key, value) -> scrolls.put(key, JSONObject().put("index", value.firstVisibleItemIndex).put("offset", value.firstVisibleItemScrollOffset)) }
        setPreference(JSONObject().put("selectedId", selectedId).put("selectedTitle", selectedTitle)
            .put("targets", JSONObject().apply { targets.forEach { (id, t) -> put(id, t.toJson()) } })
            .put("filters", JSONObject(filters.toMap())).put("scrolls", scrolls)
            .put("expandedGroups", org.json.JSONArray(expandedGroups)).put("expandedMessages", org.json.JSONArray(expandedMessages)).toString())
    }
    suspend fun refresh() {
        try {
            val revision = listRevision
            val list = api.conversations()
            if (revision == listRevision && list != items.toList()) { items.clear(); items.addAll(list) }
            val employeeList = api.listSiliconEmployees(includeArchived = true)
            if (employeeList != employees.toList()) { employees.clear(); employees.addAll(employeeList) }
            val presetList = api.listAiTeams()
            if (presetList != presets.toList()) { presets.clear(); presets.addAll(presetList) }
            val projectList = api.listWorkspaces()
            if (projectList != projects.toList()) { projects.clear(); projects.addAll(projectList) }
            if (selectedId.isBlank() && composer("").draft.isBlank() && composer("").attachments.isEmpty()) employees.firstOrNull { it.id == "e_wand_default" && !it.archived }?.let { select(employeeConversationId(it.id)) }
            error = null
        } catch (e: Exception) { error = e.message ?: "读取对话失败" }
        finally { loading = false }
    }
    private var listRevision = 0
    suspend fun updateListState(id: String, patch: JSONObject) {
        val updated = api.updateConversationListState(id, patch)
        listRevision++
        generations[id] = (generations[id] ?: 0) + 1
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = updated
        items.sortWith(compareByDescending<ConversationInstance> { it.pinnedAt != null }.thenByDescending { it.pinnedAt.orEmpty() }.thenByDescending { it.messageAt })
        details[id]?.let { current -> details[id] = current.copy(pinnedAt = updated.pinnedAt, dissolvedAt = updated.dissolvedAt,
            dissolvedBy = updated.dissolvedBy, deleting = updated.deleting, unavailableReason = updated.unavailableReason) }
    }

    suspend fun remove(id: String) {
        api.deleteConversation(id)
        listRevision++
        generations[id] = (generations[id] ?: 0) + 1
        items.removeAll { it.id == id }; details.remove(id)
        targets.remove(id); filters.remove(id)
        val prefix = "conversation:$id:"
        composers.keys.filter { it.startsWith(prefix) }.forEach { composers.remove(it)?.shutdown() }
        drafts.discardScope(prefix)
        composeDrafts.remove(id); expandedGroups.remove(id)
        feedback.keys.filter { it.startsWith(prefix) }.forEach { feedback.remove(it) }
        if (selectedId == id) { selectedId = ""; selectedTitle = "" }
        persist()
    }

    suspend fun load(id: String) {
        if (id.isBlank()) return
        val generation = (generations[id] ?: 0) + 1; generations[id] = generation
        try {
            val detail = api.conversation(id)
            if (generations[id] == generation) { details[id] = detail; if (selectedId == id) selectedTitle = detail.title; error = null }
        } catch (e: Exception) { if (generations[id] == generation) error = e.message ?: "读取对话失败" }
    }
    fun retry() { scope.launch { refresh(); load(selectedId) } }
    fun start() {
        refreshJob = scope.launch { while (true) { refresh(); delay(6000) } }
    }
    fun composer(id: String, target: ConversationTarget? = targets[id]): ChatComposer {
        val key = draftKey(id, target)
        return composers.getOrPut(key) {
            ChatComposer(key, drafts, scope, ready = { id.isNotBlank() }, send = { text ->
                val receipt = post(key, "/api/conversations/$id/messages", JSONObject().put("input", text).put("target", target?.toJson() ?: JSONObject.NULL))
                feedback[key] = receipt.error ?: "已发送"
                load(id)
            }, notice = { feedback[key] = it })
        }
    }
    suspend fun post(key: String, path: String, body: JSONObject): ConversationReceipt {
        try { return api.conversationPost(path, body) }
        catch (e: ConversationUnconfirmedException) { unknownRequests[key] = e.requestId; throw e }
    }
    suspend fun reconcile(key: String) {
        val request = unknownRequests[key] ?: return
        val receipt = api.conversationReceipt(request)
        if (receipt.state == "pending") { feedback[key] = "仍未确认，请勿重复提交"; return }
        composers[key]?.reconcileSubmission(receipt.state == "accepted")
        unknownRequests.remove(key)
        feedback[key] = receipt.error ?: if (receipt.state == "accepted") "已核对：请求已接受" else "未接受，草稿保留"
        refresh(); load(selectedId)
    }
    fun shutdown() { persist(); refreshJob?.cancel(); composers.values.forEach(ChatComposer::shutdown); sessionStores.values.forEach(ChatStore::shutdown); sessionSubscribers.clear() }
}
