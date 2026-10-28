package com.wand.app.data

import org.json.JSONObject

enum class KnowledgeScope(val wire: String, val label: String) {
    Project("project", "项目知识库"), Personal("personal", "个人知识库");
}

data class KnowledgeOwner(val id: String, val name: String, val readOnly: Boolean = false)
data class KnowledgeOwners(val employees: List<KnowledgeOwner>, val projects: List<KnowledgeOwner>) {
    fun forScope(scope: KnowledgeScope) = if (scope == KnowledgeScope.Personal) employees else projects
    companion object {
        fun parse(json: JSONObject): KnowledgeOwners {
            fun owners(key: String) = json.getJSONArray(key).let { array ->
                (0 until array.length()).map { index -> array.getJSONObject(index).let {
                    KnowledgeOwner(it.getString("id"), it.getString("name"), it.optBoolean("readOnly"))
                } }
            }
            return KnowledgeOwners(owners("employees"), owners("projects"))
        }
    }
}

data class KnowledgeDocument(
    val id: String, val scope: KnowledgeScope, val ownerId: String,
    val title: String, val content: String, val folder: String, val revision: Int,
    val createdAt: String, val updatedAt: String,
) {
    companion object {
        fun parse(json: JSONObject): KnowledgeDocument {
            val scope = KnowledgeScope.entries.first { it.wire == json.getString("scope") }
            val revision = json.getInt("revision")
            require(revision > 0) { "知识版本无效" }
            return KnowledgeDocument(json.getString("id"), scope, json.getString("ownerId"),
                json.getString("title"), json.getString("content"), json.optString("folder"), revision,
                json.getString("createdAt"), json.getString("updatedAt"))
        }
    }
}

data class KnowledgeLibrary(
    val scope: KnowledgeScope, val ownerId: String, val ownerName: String,
    val readOnly: Boolean, val entries: List<KnowledgeDocument>, val total: Int, val limit: Int,
) {
    companion object {
        fun parse(json: JSONObject): KnowledgeLibrary {
            val scope = KnowledgeScope.entries.first { it.wire == json.getString("scope") }
            val ownerId = json.getString("ownerId")
            val array = json.getJSONArray("entries")
            val entries = (0 until array.length()).map { KnowledgeDocument.parse(array.getJSONObject(it)) }
            require(entries.all { it.scope == scope && it.ownerId == ownerId }) { "知识归属不一致" }
            return KnowledgeLibrary(scope, ownerId, json.getString("ownerName"), json.optBoolean("readOnly"),
                entries, json.getInt("total"), json.getInt("limit"))
        }
    }
}

data class KnowledgeDraft(val title: String = "", val content: String = "", val folder: String = "") {
    fun validationError(): String? = when {
        title.isBlank() -> "请填写文档标题"
        content.isBlank() -> "请填写正文"
        title.length > 100 -> "标题最多 100 个字符"
        content.length > 12_000 -> "正文最多 12000 个字符"
        folder.length > 100 -> "目录名称最多 100 个字符"
        else -> null
    }
    fun json(scope: KnowledgeScope, ownerId: String) = JSONObject()
        .put("scope", scope.wire).put("ownerId", ownerId)
        .put("title", title).put("content", content).put("folder", folder)
}

interface KnowledgePort {
    suspend fun knowledgeOwners(): KnowledgeOwners
    suspend fun knowledgeLibrary(scope: KnowledgeScope, ownerId: String): KnowledgeLibrary
    suspend fun createKnowledge(scope: KnowledgeScope, ownerId: String, draft: KnowledgeDraft): KnowledgeDocument
    suspend fun updateKnowledge(document: KnowledgeDocument, draft: KnowledgeDraft): KnowledgeDocument
    suspend fun deleteKnowledge(document: KnowledgeDocument)
}
