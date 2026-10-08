package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

const val MODEL_GROUP_PREFIX = "wand-model-group/"
/** 「智能分配」：按第一条提示词在本工具的模型分组里自动选一个（终端无提示词时回落默认分组）。 */
const val AUTO_ASSIGN_SELECTOR = "wand-auto-assign/auto"
const val AUTO_ASSIGN_LABEL = "智能分配"
const val FREE_MODEL_GROUP_ID = "openrouter-free"
const val FREE_MODEL_GROUP_SELECTOR = "wand-openrouter-free/auto"
const val FREE_MODEL_GROUP_NAME = "免费分组"
const val MODEL_GROUP_MAX_MEMBERS = 32
const val MODEL_GROUP_MAX_PER_PROVIDER = 32

fun isModelGroupSelector(value: String): Boolean = value.startsWith(MODEL_GROUP_PREFIX)

fun isAutoAssignSelector(value: String): Boolean = value.trim() == AUTO_ASSIGN_SELECTOR

/** Stable id survives renaming and reordering. The same selector is saved in chats/tasks/employees. */
data class ModelGroup(
    val id: String,
    val provider: String,
    val name: String,
    val models: List<String>,
) {
    val builtIn: Boolean get() = id == FREE_MODEL_GROUP_ID && provider == "pi"
    val selector: String get() = if (builtIn) FREE_MODEL_GROUP_SELECTOR else "$MODEL_GROUP_PREFIX$provider/$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("provider", provider)
        .put("name", name.trim()).put("models", JSONArray(models))

    companion object {
        fun parseList(array: JSONArray?): List<ModelGroup> = buildList {
            if (array == null) return@buildList
            for (index in 0 until minOf(array.length(), 225)) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.str("id") ?: continue
                val provider = row.str("provider") ?: continue
                val name = row.str("name") ?: continue
                if (WandProvider.fromId(provider) == null) continue
                val models = row.arr("models") ?: continue
                add(ModelGroup(id, provider, name, (0 until minOf(models.length(), 128))
                    .mapNotNull { models.opt(it) as? String }))
            }
        }
    }
}

fun modelGroupListError(groups: List<ModelGroup>): String? {
    val identities = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    for (group in groups) {
        if (!Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$").matches(group.id)
            || WandProvider.fromId(group.provider) == null) return "分组标识或工具无效。"
        val name = group.name.trim()
        if (name.isEmpty() || name.length > 40 || name.any { it.code < 32 || it.code == 127 })
            return "分组名称需为 1–40 个字符。"
        if (!identities.add("${group.provider}/${group.id}") || !names.add("${group.provider}/$name"))
            return "同一工具的分组名称不能重复。"
        if (group.id == FREE_MODEL_GROUP_ID && (!group.builtIn || name != FREE_MODEL_GROUP_NAME))
            return "免费分组的工具与名称不可修改。"
        if (!group.builtIn && group.provider == "pi" && name == FREE_MODEL_GROUP_NAME)
            return "免费分组为内置分组，请直接调整它的顺序。"
        if ((!group.builtIn && group.models.isEmpty()) || group.models.size > if (group.builtIn) 128 else MODEL_GROUP_MAX_MEMBERS)
            return "${group.name} 需包含 1–$MODEL_GROUP_MAX_MEMBERS 个模型。"
        if (group.models.toSet().size != group.models.size) return "同一分组中的模型不能重复。"
        for (model in group.models) {
            if (model.isBlank() || model.length > 256 || model.any { it.isWhitespace() || it.code < 32 || it.code == 127 }
                || model == "default" || model == FREE_MODEL_GROUP_SELECTOR || isModelGroupSelector(model)
                || isAutoAssignSelector(model) || model.startsWith("-"))
                return "组内需为具体模型 ID，不能嵌套分组或使用默认值。"
            if (model.startsWith("wand-openrouter-free/") && group.provider != "pi") return "免费模型只属于 Pi。"
            if (group.builtIn && !model.startsWith("wand-openrouter-free/")) return "免费分组只能包含免费池中的模型。"
        }
    }
    if (groups.filterNot { it.builtIn }.groupBy { it.provider }.any { it.value.size > MODEL_GROUP_MAX_PER_PROVIDER })
        return "每个工具最多 $MODEL_GROUP_MAX_PER_PROVIDER 个分组。"
    return null
}

/** Free membership is server-owned; saved priority first, new verified members append. */
fun modelGroupsDraft(saved: List<ModelGroup>, catalog: ModelsResponse): List<ModelGroup> {
    val free = saved.firstOrNull { it.builtIn }
    val members = catalog.freeModels.map { it.id }
    val ordered = (free?.models.orEmpty().filter { it in members } + members).distinct()
    val draft = saved.filterNot { it.builtIn }
    return if (!catalog.modelGroupsSupported) draft else draft + ModelGroup(FREE_MODEL_GROUP_ID, "pi", FREE_MODEL_GROUP_NAME, ordered)
}

fun moveGroupModel(group: ModelGroup, index: Int, delta: Int): ModelGroup {
    val target = index + delta
    if (index !in group.models.indices || target !in group.models.indices) return group
    val models = group.models.toMutableList()
    val current = models[index]
    models[index] = models[target]
    models[target] = current
    return group.copy(models = models)
}

fun groupEditorModels(catalog: ModelsResponse?, provider: String): List<ModelInfo> =
    (catalog?.modelsFor(provider).orEmpty() + if (provider == "pi") catalog?.freeModels.orEmpty() else emptyList())
        .filter { it.id != "default" && it.id != FREE_MODEL_GROUP_SELECTOR && !isModelGroupSelector(it.id) && !isAutoAssignSelector(it.id) }
        .distinctBy { it.id }

fun modelGroupsSaveBody(groups: List<ModelGroup>, expected: List<ModelGroup>): JSONObject {
    modelGroupListError(groups)?.let { throw IllegalArgumentException(it) }
    return JSONObject().put("modelGroups", JSONArray(groups.map { it.toJson() }))
        .put("expectedModelGroups", JSONArray(expected.map { it.toJson() }))
}

fun modelSelectionName(models: ModelsResponse?, provider: String, id: String): String = when {
    id == FREE_MODEL_GROUP_SELECTOR -> FREE_MODEL_GROUP_NAME
    isModelGroupSelector(id) -> models?.modelsFor(provider)?.firstOrNull { it.id == id }?.label ?: "模型分组"
    isAutoAssignSelector(id) -> models?.modelsFor(provider)?.firstOrNull { it.id == id }?.label ?: AUTO_ASSIGN_LABEL
    else -> id
}
