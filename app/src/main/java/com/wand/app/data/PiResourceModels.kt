package com.wand.app.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

data class PiResourceSelection(val skills: List<String> = emptyList(), val mcpServers: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("skills", JSONArray(skills)).put("mcpServers", JSONArray(mcpServers))
    fun toggle(kind: PiResourceKind, id: String, checked: Boolean): PiResourceSelection {
        val current = if (kind == PiResourceKind.Skill) skills else mcpServers
        val next = if (checked) (current + id).distinct() else current.filterNot { it == id }
        return if (kind == PiResourceKind.Skill) copy(skills = next) else copy(mcpServers = next)
    }
    companion object {
        fun parse(value: JSONObject?): PiResourceSelection? = value?.let {
            PiResourceSelection(it.optJSONArray("skills").strings(), it.optJSONArray("mcpServers").strings())
        }
    }
}
enum class PiResourceKind { Skill, Mcp }
enum class PiSkillMode { Off, On, Locked }
data class PiResourceItem(val id: String, val name: String, val description: String)
data class PiResourcesResponse(
    val selection: PiResourceSelection?,
    val skills: List<PiResourceItem>,
    val mcpServers: List<PiResourceItem>,
    val supported: Boolean,
    val reason: String,
    val codemode: String = "follow",
    val codemodeAvailable: Boolean = false,
    val autoResources: Boolean = false,
    val autoResourcesAvailable: Boolean = false,
    val autoResourcesReason: String = "当前服务端不支持自动配置，请更新服务端。",
    val autoCodemodeAvailable: Boolean = false,
    val lockedSkills: List<String> = emptyList(),
    val skillLocksAvailable: Boolean = false,
) {
    fun skillMode(id: String): PiSkillMode = when {
        id !in selection?.skills.orEmpty() -> PiSkillMode.Off
        id in lockedSkills -> PiSkillMode.Locked
        else -> PiSkillMode.On
    }
    fun withSkillMode(id: String, mode: PiSkillMode): PiSkillSelectionAck = PiSkillSelectionAck(
        (selection ?: PiResourceSelection()).toggle(PiResourceKind.Skill, id, mode != PiSkillMode.Off),
        if (mode == PiSkillMode.Locked) (lockedSkills + id).distinct() else lockedSkills.filterNot { it == id },
    )
    companion object {
        fun parse(json: JSONObject): PiResourcesResponse {
            val catalog = json.optJSONObject("resourceCatalog")
            fun items(key: String): List<PiResourceItem> {
                val values = catalog?.optJSONArray(key) ?: return emptyList()
                return (0 until values.length()).mapNotNull { index ->
                    val item = values.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    if (id.isBlank()) null else PiResourceItem(id, item.optString("name"), item.optString("description"))
                }
            }
            return PiResourcesResponse(PiResourceSelection.parse(json.optJSONObject("settings")?.optJSONObject("resources")),
                items("skills"), items("mcpServers"), catalog?.optBoolean("supported", false) == true,
                if (catalog == null) "当前服务端尚不支持 Skills / MCP 选择，请更新服务端。" else catalog.optString("reason"),
                json.optJSONObject("settings")?.optString("codemodeOverride")?.takeIf { it in listOf("off", "on", "only") } ?: "follow",
                json.optJSONObject("controls")?.optBoolean("codemodeOverride", false) == true,
                json.optJSONObject("settings")?.bool("autoResources") == true,
                json.bool("autoResourcesAvailable") == true,
                json.str("autoResourcesReason")?.takeIf { it.isNotBlank() }
                    ?: if (json.bool("autoResourcesAvailable") == true) "" else "当前服务端不支持自动配置，请更新服务端。",
                json.bool("autoCodemodeAvailable") == true,
                json.optJSONObject("settings")?.optJSONArray("lockedSkills").strings(),
                json.bool("skillLocksAvailable") == true)
        }
    }
}
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.distinct()

data class PiAutoResourcesAck(val enabled: Boolean, val selection: PiResourceSelection?)

data class PiSkillSelectionAck(val selection: PiResourceSelection, val lockedSkills: List<String>) {
    fun toJson(): JSONObject = JSONObject().put("resources", selection.toJson()).put("lockedSkills", JSONArray(lockedSkills))
    companion object {
        fun parse(settings: JSONObject?): PiSkillSelectionAck? {
            val selection = PiResourceSelection.parse(settings?.optJSONObject("resources")) ?: return null
            val locks = settings?.optJSONArray("lockedSkills") ?: return null
            if (locks.length() > 64 || (0 until locks.length()).any { locks.opt(it) !is String || locks.optString(it).isBlank() }) return null
            val ids = locks.strings()
            if (ids.size != locks.length()) return null
            return PiSkillSelectionAck(selection, ids)
        }
    }
}

@Immutable
data class PiResourceSelectionNotice(val label: String, val status: String?) {
    companion object {
        fun parse(json: JSONObject?): PiResourceSelectionNotice? {
            val label = (json?.opt("label") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return PiResourceSelectionNotice(label.take(1024), json?.str("status"))
        }
    }
}

interface PiResourcesPort {
    suspend fun getPiResources(id: String): PiResourcesResponse
    suspend fun setPiResources(id: String, selection: PiResourceSelection): PiResourceSelection
    suspend fun setPiSkillSelection(id: String, selection: PiSkillSelectionAck): PiSkillSelectionAck =
        throw UnsupportedOperationException("当前服务端不支持 Skill 锁定")
    suspend fun setPiAutoResources(id: String, enabled: Boolean): PiAutoResourcesAck =
        throw UnsupportedOperationException("当前服务端不支持按提示词自动配置")
    suspend fun setPiCodemode(id: String, mode: String): String =
        throw UnsupportedOperationException("当前服务端不支持 CodeMode 会话设置")
}
