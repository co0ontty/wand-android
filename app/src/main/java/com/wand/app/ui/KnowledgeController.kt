package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One server-bound library owner. Drafts stay in memory and never change a running employee. */
class KnowledgeController(private val api: KnowledgePort, private val coroutineScope: CoroutineScope) {
    var owners by mutableStateOf<KnowledgeOwners?>(null); private set
    var scope by mutableStateOf(KnowledgeScope.Project); private set
    var ownerId by mutableStateOf<String?>(null); private set
    var library by mutableStateOf<KnowledgeLibrary?>(null); private set
    var selected by mutableStateOf<KnowledgeDocument?>(null); private set
    var editing by mutableStateOf(false); private set
    var draft by mutableStateOf(KnowledgeDraft()); private set
    var query by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var conflict by mutableStateOf(false); private set
    var latestDocument by mutableStateOf<KnowledgeDocument?>(null); private set
    var latestRead by mutableStateOf(false); private set
    var confirmingDiscard by mutableStateOf(false); private set
    var confirmingDelete by mutableStateOf(false); private set
    private var original = KnowledgeDraft()
    private var pendingAction: (() -> Unit)? = null
    private var generation = 0L
    private var request: Job? = null
    private var active = true
    private var initialized = false
    val dirty get() = editing && draft != original
    val owner get() = owners?.forScope(scope)?.firstOrNull { it.id == ownerId }
    val readOnly get() = library?.readOnly == true || owner?.readOnly == true
    val canCreate get() = library != null && !readOnly && !loading && !busy && library!!.total < library!!.limit
    val visibleDocuments get(): List<KnowledgeDocument> {
        val needle = query.trim()
        return library?.entries.orEmpty().filter {
            needle.isEmpty() || it.title.contains(needle, true) || it.content.contains(needle, true) || it.folder.contains(needle, true)
        }
    }

    fun open() {
        if (active && (!initialized || (!editing && !busy))) { initialized = true; refreshOwners() }
    }
    fun dispose() { active = false; generation++; request?.cancel(); pendingAction = null }
    fun updateQuery(value: String) { query = value }
    fun updateDraft(value: KnowledgeDraft) { if (editing && !busy) { draft = value; notice = null } }

    /** Every navigation out of the editor uses the same discard decision. */
    fun guard(action: () -> Unit) {
        if (!active || busy) return
        if (dirty) { pendingAction = action; confirmingDiscard = true }
        else action()
    }
    fun keepEditing() { pendingAction = null; confirmingDiscard = false }
    fun discardAndContinue() {
        val next = pendingAction
        pendingAction = null; confirmingDiscard = false
        draft = original; editing = false; error = null; conflict = false
        next?.invoke()
    }

    fun refreshOwners() {
        if (busy || !active) return
        val ticket = ++generation
        request?.cancel(); loading = true; error = null
        request = coroutineScope.launch {
            try {
                val result = api.knowledgeOwners()
                if (!current(ticket)) return@launch
                owners = result
                val id = ownerId?.takeIf { wanted -> result.forScope(scope).any { it.id == wanted } }
                    ?: result.forScope(scope).firstOrNull()?.id
                loading = false
                if (id != null) load(scope, id, selected?.takeIf { it.ownerId == id && it.scope == scope }?.id)
                else { ownerId = null; library = null; selected = null }
            } catch (_: CancellationException) {
            } catch (e: Exception) { if (current(ticket)) { loading = false; error = e.message ?: "知识库加载失败" } }
        }
    }

    fun selectScope(value: KnowledgeScope) {
        if (scope == value) return
        guard { switch(value, owners?.forScope(value)?.firstOrNull()?.id) }
    }
    fun selectOwner(id: String) {
        if (ownerId == id || owners?.forScope(scope)?.none { it.id == id } != false) return
        guard { switch(scope, id) }
    }
    private fun switch(value: KnowledgeScope, id: String?) {
        generation++; request?.cancel(); scope = value; ownerId = id; library = null
        selected = null; editing = false; draft = KnowledgeDraft(); original = draft
        query = ""; error = null; notice = null; conflict = false; loading = false
        latestDocument = null; latestRead = false
        if (id != null) load(value, id)
    }
    fun reload() = guard { ownerId?.let { load(scope, it, selected?.id) } ?: refreshOwners() }
    private fun load(targetScope: KnowledgeScope, id: String, selectId: String? = null) {
        val ticket = ++generation
        request?.cancel(); scope = targetScope; ownerId = id; loading = true; error = null
        request = coroutineScope.launch {
            try {
                val result = api.knowledgeLibrary(targetScope, id)
                if (!current(ticket)) return@launch
                require(result.scope == targetScope && result.ownerId == id) { "知识归属不一致，请重新加载" }
                library = result; selected = selectId?.let { wanted -> result.entries.firstOrNull { it.id == wanted } }
                editing = false; conflict = false; loading = false; latestDocument = null; latestRead = false
            } catch (_: CancellationException) {
            } catch (e: Exception) { if (current(ticket)) { loading = false; error = e.message ?: "知识库加载失败" } }
        }
    }
    fun selectDocument(document: KnowledgeDocument) = guard {
        if (!loading && document.scope == scope && document.ownerId == ownerId) {
            selected = document; editing = false; error = null; notice = null; conflict = false
            clearComparison()
        }
    }
    fun backToDirectory() = guard { selected = null; editing = false; error = null; notice = null; conflict = false; clearComparison() }
    fun startNew() = guard {
        if (canCreate) { selected = null; original = KnowledgeDraft(); draft = original; editing = true; error = null; notice = null; conflict = false; clearComparison() }
    }
    fun edit() {
        val document = selected ?: return
        if (readOnly || busy || loading) return
        original = KnowledgeDraft(document.title, document.content, document.folder)
        draft = original; editing = true; error = null; notice = null; conflict = false; latestDocument = null; latestRead = false
    }
    fun cancelEditing() {
        val wasConflict = conflict
        guard {
            editing = false; error = null; conflict = false
            if (wasConflict) {
                if (latestRead) selected = latestDocument
                else ownerId?.let { load(scope, it, selected?.id) }
            }
            clearComparison()
        }
    }

    /** Read the concurrent version for comparison without rebasing the edit or changing its revision. */
    fun readLatest() {
        val document = selected ?: return
        if (!active || busy || loading || !conflict) return
        val ticket = ++generation
        request?.cancel(); loading = true
        request = coroutineScope.launch {
            try {
                val result = api.knowledgeLibrary(document.scope, document.ownerId)
                if (!current(ticket)) return@launch
                require(result.scope == document.scope && result.ownerId == document.ownerId) { "知识归属不一致" }
                library = result; latestDocument = result.entries.firstOrNull { it.id == document.id }; latestRead = true
            } catch (_: CancellationException) {
            } catch (e: Exception) { if (current(ticket)) error = "${e.message ?: "读取失败"}。你的修改仍保留。" }
            finally { if (current(ticket)) loading = false }
        }
    }

    fun save() {
        if (!active || busy || !editing || readOnly || loading || conflict) return
        val problem = draft.validationError()
        if (problem != null) { error = problem; return }
        val id = ownerId ?: return
        val targetScope = scope
        val document = selected
        val saving = draft
        val ticket = ++generation
        busy = true; error = null; notice = null
        request = coroutineScope.launch {
            try {
                val result = if (document == null) api.createKnowledge(targetScope, id, saving)
                    else api.updateKnowledge(document, saving)
                if (!current(ticket)) return@launch
                require(result.scope == targetScope && result.ownerId == id &&
                    (document == null || result.id == document.id && result.revision > document.revision)) {
                    "保存结果未确认，请保留正文并刷新核对"
                }
                val old = library ?: return@launch
                val existed = old.entries.any { it.id == result.id }
                library = old.copy(entries = listOf(result) + old.entries.filterNot { it.id == result.id },
                    total = old.total + if (existed) 0 else 1)
                selected = result; original = KnowledgeDraft(result.title, result.content, result.folder)
                draft = original; editing = false; conflict = false; notice = "已保存"
                clearComparison()
                if (targetScope == KnowledgeScope.Global) {
                    try {
                        val refreshed = api.knowledgeLibrary(targetScope, id)
                        require(refreshed.scope == targetScope && refreshed.ownerId == id) { "知识归属不一致" }
                        if (current(ticket)) library = refreshed
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (current(ticket)) error = "知识已保存，CLI 同步状态暂不可读，请刷新确认。"
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (current(ticket)) {
                    conflict = (e as? WandApiException)?.status == 409
                    error = if (conflict) "文档已在其他地方更新。你的修改仍保留，可以读取最新版本进行对照。"
                        else "${e.message ?: "保存失败"}。你的修改仍保留。"
                }
            } finally { if (current(ticket)) busy = false }
        }
    }
    fun requestDelete() { if (!busy && !loading && selected != null && !editing) confirmingDelete = true }
    fun cancelDelete() { confirmingDelete = false }
    fun deleteConfirmed() {
        val document = selected ?: return
        if (!confirmingDelete || busy || !active) return
        confirmingDelete = false; busy = true; error = null
        val ticket = ++generation
        request = coroutineScope.launch {
            try {
                api.deleteKnowledge(document)
                if (!current(ticket)) return@launch
                library = library?.let { it.copy(entries = it.entries.filterNot { entry -> entry.id == document.id }, total = (it.total - 1).coerceAtLeast(0)) }
                selected = null; editing = false; notice = "已删除"; conflict = false
                clearComparison()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (current(ticket)) {
                    conflict = (e as? WandApiException)?.status == 409
                    error = if (conflict) "文档已更新，未删除。请重新读取后再决定。"
                        else "${e.message ?: "删除失败"}。请刷新目录核对结果。"
                }
            } finally { if (current(ticket)) busy = false }
        }
    }
    private fun current(ticket: Long) = active && generation == ticket
    private fun clearComparison() { latestDocument = null; latestRead = false }
}
