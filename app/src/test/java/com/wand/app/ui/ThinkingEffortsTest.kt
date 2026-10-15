package com.wand.app.ui

import com.wand.app.data.ModelInfo
import com.wand.app.data.ModelsResponse
import com.wand.app.data.ReasoningEffortInfo
import com.wand.app.data.WandApi
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThinkingEffortsTest {
    private fun model(id: String, vararg efforts: String) = ModelInfo(
        id = id,
        label = id,
        alias = null,
        reasoningEfforts = efforts.map { ReasoningEffortInfo(it, null) },
        defaultReasoningEffort = null,
    )

    private fun options(provider: String, vararg efforts: String) =
        thinkingEffortOptions(provider, "chosen", null, listOf(model("chosen", *efforts)))

    @Test
    fun piUsesEveryModelReportedLevelWithTheSameIdsAsWeb() {
        val choices = options("pi", "off", "minimal", "low", "medium", "high", "xhigh")
        assertEquals(listOf("off", "pi:minimal", "standard", "pi:medium", "deep", "pi:xhigh"), choices.map { it.id })
        assertEquals(listOf("自动", "最低", "低", "中", "高", "超高"), choices.map { it.label })
        assertEquals("中（medium）", choices.first { it.id == "pi:medium" }.menuLabel)
    }

    @Test
    fun piMaxKeepsTheLegacyMaxIdInsteadOfConfusingItWithXhigh() {
        assertEquals(listOf("off", "deep", "pi:xhigh", "max"), options("pi", "high", "xhigh", "max").map { it.id })
        assertEquals("极高", options("pi", "max").last().label)
    }

    @Test
    fun nonReasoningModelsOnlyOfferAutomatic() {
        for (provider in listOf("pi", "codex", "opencode")) {
            assertEquals(listOf("off"), options(provider, "off").map { it.id })
        }
    }

    @Test
    fun switchingModelsUsesTheSelectedModelsCapabilitiesNotTheDefaultUnion() {
        val models = listOf(model("default", "off", "low", "high", "xhigh"),
            model("deep-model", "off", "low", "medium", "high", "xhigh"), model("plain-model", "off"))
        assertEquals(listOf("off", "standard", "pi:medium", "deep", "pi:xhigh"),
            thinkingEffortOptions("pi", "deep-model", "plain-model", models).map { it.id })
        assertEquals(listOf("off"), thinkingEffortOptions("pi", "plain-model", "deep-model", models).map { it.id })
    }

    @Test
    fun automaticModelUsesConcreteDefaultBeforeTheDefaultAlias() {
        val models = listOf(model("default", "off", "low", "high", "xhigh"), model("plain-model", "off"))
        for (selected in listOf(null, "", "default")) {
            assertEquals(listOf("off"), thinkingEffortOptions("pi", selected, "plain-model", models).map { it.id })
        }
        assertEquals(listOf("off", "standard", "deep", "pi:xhigh"),
            thinkingEffortOptions("pi", null, null, models).map { it.id })
    }

    @Test
    fun missingModelFallsBackToTheDefaultAliasNotAnUnrelatedModelsLevels() {
        val models = listOf(model("another", "off", "xhigh"), model("default", "off", "low", "high"))
        assertEquals(listOf("off", "standard", "deep"),
            thinkingEffortOptions("pi", "missing", null, models).map { it.id })
    }

    @Test
    fun groupedModelOnlyOffersTheReportedIntersection() {
        val models = listOf(model("default", "off", "low", "medium", "high", "xhigh"),
            model("wand-model-group/pi/coding", "off", "low", "high"))
        assertEquals(listOf("off", "standard", "deep"),
            thinkingEffortOptions("pi", "wand-model-group/pi/coding", null, models).map { it.id })
    }

    @Test
    fun codexDynamicIdsAndLabelsRemainCompatible() {
        val choices = options("codex", "low", "medium", "high", "xhigh", "max", "ultra")
        assertEquals(listOf("off", "standard", "deep", "codex:high", "max", "codex:max", "codex:ultra"), choices.map { it.id })
        assertEquals(listOf("自动", "低", "中", "高", "超高", "极高", "极限"), choices.map { it.label })
    }

    @Test
    fun otherProvidersUseTheirOwnLegacyMappingAndNativeExtras() {
        assertEquals(listOf("off", "standard", "deep", "claude:high", "max"),
            options("claude", "low", "medium", "high", "max").map { it.id })
        for (provider in listOf("opencode", "qoder")) {
            assertEquals(listOf("off", "standard", "$provider:medium", "deep", "max", "$provider:ultracode"),
                options(provider, "low", "medium", "high", "max", "ultracode").map { it.id })
        }
        assertEquals(listOf("off", "standard", "deep", "max"), options("grok", "low", "high", "xhigh").map { it.id })
    }

    @Test
    fun levelsAreNormalizedValidatedAndDeduplicatedWithoutDuplicatingAutomatic() {
        assertEquals(listOf("off", "standard", "pi:medium", "deep", "pi:custom-depth"),
            options("pi", " OFF ", " Low ", "low", " MEDIUM ", "high", "custom-depth", "", "bad value",
                "pi:xhigh", "../high", "h".repeat(33)).map { it.id })
    }

    @Test
    fun providerCatalogIsUsedOnlyWhenTheModelHasNoReportedLevels() {
        val providerEfforts = listOf("off", "minimal", "low", "medium", "high", "xhigh")
            .map { ReasoningEffortInfo(it, null) }
        assertEquals(listOf("off", "pi:minimal", "standard", "pi:medium", "deep", "pi:xhigh"),
            thinkingEffortOptions("pi", "chosen", null, listOf(model("chosen")), providerEfforts).map { it.id })
        assertEquals(listOf("off"),
            thinkingEffortOptions("pi", "chosen", null, listOf(model("chosen", "off")), providerEfforts).map { it.id })
        assertEquals(listOf("off", "pi:medium"),
            thinkingEffortOptions("pi", "chosen", null, listOf(model("chosen", "medium")), providerEfforts).map { it.id })
    }

    @Test
    fun modelsResponseParsesModelAndProviderMetadataWithoutInventingCapabilities() {
        val response = ModelsResponse.parse(JSONObject()
            .put("piModels", JSONArray().put(JSONObject().put("id", "chosen")
                .put("reasoningEfforts", JSONArray().put(JSONObject().put("effort", "medium").put("description", "模型中档")))))
            .put("thinkingEfforts", JSONObject().put("pi", JSONArray()
                .put(JSONObject().put("effort", "off"))
                .put(JSONObject().put("effort", "xhigh").put("description", "最高可用档"))
                .put(JSONObject().put("description", "缺少档位")))
                .put("codex", JSONObject())))
        assertEquals(listOf("off", "xhigh"), response.thinkingEffortsFor("pi").map { it.effort })
        assertEquals("最高可用档", response.thinkingEffortsFor("pi").last().description)
        assertTrue(response.thinkingEffortsFor("codex").isEmpty())
        assertTrue(response.thinkingEffortsFor("claude").isEmpty())
        assertEquals("模型中档", response.modelsFor("pi").single().reasoningEfforts.single().description)
        val oldServer = ModelsResponse.parse(JSONObject())
        assertTrue(oldServer.thinkingEffortsFor("pi").isEmpty())
        assertEquals(listOf("off", "standard", "deep", "max"),
            thinkingEffortOptions("pi", null, null, oldServer.modelsFor("pi"), oldServer.thinkingEffortsFor("pi")).map { it.id })
    }

    @Test
    fun missingCatalogIsUnknownRatherThanEvidenceToResetNativeSelections() {
        assertNull(dynamicThinkingEffortOptions("pi", "chosen", null, emptyList()))
        assertNull(dynamicThinkingEffortOptions("pi", "chosen", null, listOf(model("chosen"))))
        assertNull(dynamicThinkingEffortOptions("pi", "chosen", null, listOf(model("chosen", "bad value"))))
        assertEquals(listOf("off"),
            dynamicThinkingEffortOptions("pi", "chosen", null, listOf(model("chosen", "off")))!!.map { it.id })
    }

    @Test
    fun generatedPiSelectionsAreSubmittedAsExactProtocolIds() = runBlocking {
        val choices = options("pi", "off", "minimal", "low", "medium", "high", "xhigh", "max")
        val server = ServerSocket(0).apply { soTimeout = 5_000 }
        val requests = LinkedBlockingQueue<Pair<String, JSONObject>>()
        val worker = thread(isDaemon = true) {
            repeat(choices.size) { server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val body = CharArray(length)
                var received = 0
                while (received < length) received += reader.read(body, received, length - received)
                val request = JSONObject(String(body))
                requests.put(requestLine to request)
                val response = JSONObject().put("id", "session-1").put("provider", "pi")
                    .put("sessionKind", "structured").put("status", "idle")
                    .put("thinkingEffort", request.getString("thinkingEffort")).toString()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${response.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$response").toByteArray())
            } }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            for (choice in choices) {
                assertEquals(choice.id, api.setThinkingEffort("session-1", choice.id).thinkingEffort)
                val request = requests.poll(5, TimeUnit.SECONDS)!!
                assertEquals("POST /api/sessions/session-1/thinking-effort HTTP/1.1", request.first)
                assertEquals(setOf("thinkingEffort"), request.second.keys().asSequence().toSet())
                assertEquals(choice.id, request.second.getString("thinkingEffort"))
            }
        } finally { server.close(); worker.join(5_000) }
    }

    @Test
    fun legacyFallbackIsPreservedWhenThereIsNoUsableMetadata() {
        for (provider in listOf("pi", "codex", "claude", "opencode")) {
            assertEquals(listOf("off", "standard", "deep", "max"), options(provider).map { it.id })
            assertEquals(listOf("off", "standard", "deep", "max"), options(provider, "", "bad value").map { it.id })
        }
    }
}
