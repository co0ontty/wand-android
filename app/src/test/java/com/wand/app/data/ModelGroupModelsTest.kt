package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelGroupModelsTest {
    private val group = ModelGroup("coding", "pi", "编程分组", listOf("first", "second"))

    @Test fun stableSelectorWorksForEveryToolAndNamesMayRepeatAcrossTools() {
        val groups = WandProvider.entries.map { group.copy(provider = it.id) }
        assertNull(modelGroupListError(groups))
        groups.forEach {
            assertEquals("wand-model-group/${it.provider}/coding", it.selector)
            assertEquals(it.selector, it.copy(name = "新名字", models = it.models.reversed()).selector)
        }
        assertEquals(FREE_MODEL_GROUP_SELECTOR, ModelGroup(FREE_MODEL_GROUP_ID, "pi", FREE_MODEL_GROUP_NAME, emptyList()).selector)
    }

    @Test fun parserAndSaveBodyPreserveModelOrderAndExpectedVersionWithoutDefaults() {
        val groups = ModelGroup.parseList(JSONArray().put(group.toJson()))
        assertEquals(listOf(group), groups)
        val next = moveGroupModel(group, 1, -1)
        assertEquals(listOf("second", "first"), next.models)
        assertEquals(listOf("first", "second"), group.models)
        val body = modelGroupsSaveBody(listOf(next), listOf(group))
        assertEquals(listOf(next), ModelGroup.parseList(body.getJSONArray("modelGroups")))
        assertEquals(listOf(group), ModelGroup.parseList(body.getJSONArray("expectedModelGroups")))
        assertEquals(setOf("modelGroups", "expectedModelGroups"), body.keys().asSequence().toSet())
    }

    @Test fun validationRejectsDefaultNestedDuplicateAndInvalidGroups() {
        listOf(group.copy(name = ""), group.copy(name = "x".repeat(41)), group.copy(models = emptyList()),
            group.copy(models = listOf("default")), group.copy(models = listOf(group.selector)),
            group.copy(models = listOf(FREE_MODEL_GROUP_SELECTOR)), group.copy(models = listOf("first", "first")),
            group.copy(provider = "shell"), group.copy(id = "../"), group.copy(models = listOf("has space")),
            group.copy(models = List(33) { "model$it" })).forEach { assertNotNull(modelGroupListError(listOf(it))) }
        assertNotNull(modelGroupListError(listOf(group, group.copy(id = "other"))))
        assertNotNull(modelGroupListError(listOf(group.copy(provider = "claude", models = listOf("wand-openrouter-free/free")))))
        assertNull(modelGroupListError(listOf(group.copy(models = listOf("custom-provider/custom-model")))))
    }

    @Test fun freeGroupCannotRenameRemoveOrReceivePaidModels() {
        val free = ModelGroup(FREE_MODEL_GROUP_ID, "pi", FREE_MODEL_GROUP_NAME, listOf("wand-openrouter-free/free"))
        assertNull(modelGroupListError(listOf(free)))
        assertNotNull(modelGroupListError(listOf(free.copy(name = "改名"))))
        assertNotNull(modelGroupListError(listOf(free.copy(models = listOf("paid")))))
        assertNotNull(modelGroupListError(listOf(free.copy(provider = "claude"))))
        assertNull(modelGroupListError(listOf(free.copy(models = emptyList()))))
    }

    @Test fun catalogProjectsGroupsAndConcreteFreeMembersIntoSeparateFields() {
        val parsed = ModelsResponse.parse(JSONObject().put("piModels", JSONArray().put(JSONObject()
            .put("id", group.selector).put("label", group.name).put("group", "模型分组")))
            .put("modelGroups", JSONArray().put(group.toJson()))
            .put("freeModels", JSONArray().put(JSONObject().put("id", "wand-openrouter-free/small").put("label", "Small"))))
        assertTrue(parsed.modelGroupsSupported)
        assertEquals(listOf(group), parsed.modelGroups)
        assertEquals("模型分组", parsed.piModels.first().group)
        assertEquals("编程分组", boardAgentModelName(parsed, "pi", group.selector))
        assertEquals("编程分组", boardAgentModelName(parsed.copy(defaultPiModel = group.selector), "pi", "default"))
        assertEquals(FREE_MODEL_GROUP_NAME, boardAgentModelName(null, "pi", FREE_MODEL_GROUP_SELECTOR))
        assertEquals("模型分组", boardAgentModelName(null, "pi", group.selector))
        assertEquals("custom-model", boardAgentModelName(parsed, "pi", "custom-model"))
        assertEquals(AUTO_ASSIGN_LABEL, boardAgentModelName(null, "pi", AUTO_ASSIGN_SELECTOR))
        assertEquals("智能分配中", boardAgentModelName(ModelsResponse.parse(JSONObject().put("piModels",
            JSONArray().put(JSONObject().put("id", AUTO_ASSIGN_SELECTOR).put("label", "智能分配中")))), "pi", AUTO_ASSIGN_SELECTOR))
        assertNotNull(modelGroupListError(listOf(group.copy(models = listOf(AUTO_ASSIGN_SELECTOR)))))
        assertFalse(ModelsResponse.parse(JSONObject()).modelGroupsSupported)
    }

    @Test fun freeEditorFollowsSavedOrderAppendsNewMembersAndLeavesOriginalUntouched() {
        val free = ModelGroup(FREE_MODEL_GROUP_ID, "pi", FREE_MODEL_GROUP_NAME,
            listOf("wand-openrouter-free/small", "wand-openrouter-free/gone", "wand-openrouter-free/large"))
        val models = ModelsResponse.parse(JSONObject().put("modelGroups", JSONArray()).put("freeModels", JSONArray(
            listOf("large", "small", "new").map { JSONObject().put("id", "wand-openrouter-free/$it").put("label", it) })))
        val draft = modelGroupsDraft(listOf(group, free), models)
        assertEquals(group, draft.first())
        assertEquals(listOf("wand-openrouter-free/small", "wand-openrouter-free/large", "wand-openrouter-free/new"), draft.last().models)
        assertTrue("wand-openrouter-free/gone" in free.models)
        assertSame(group, moveGroupModel(group, 0, -1))
        assertSame(group, moveGroupModel(group, 1, 1))
    }

    @Test fun groupEditorDoesNotOfferSentinelsOrNestedGroups() {
        val catalog = ModelsResponse.parse(JSONObject().put("piModels", JSONArray(listOf(
            "default", group.selector, FREE_MODEL_GROUP_SELECTOR, "real").map { JSONObject().put("id", it).put("label", it) }))
            .put("freeModels", JSONArray().put(JSONObject().put("id", "wand-openrouter-free/small"))))
        assertEquals(listOf("real", "wand-openrouter-free/small"), groupEditorModels(catalog, "pi").map { it.id })
        assertTrue(groupEditorModels(catalog, "claude").isEmpty())
    }
}
