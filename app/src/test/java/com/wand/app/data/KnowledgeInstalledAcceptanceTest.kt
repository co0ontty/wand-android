package com.wand.app.data

import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit read-only acceptance. Ordinary JVM tests never read the installed service or credentials. */
class KnowledgeInstalledAcceptanceTest {
    @Test fun installedKnowledgeOwnersAndLibrariesRespectScope() = runBlocking {
        assumeTrue(System.getenv("WAND_KNOWLEDGE_ACCEPTANCE") == "1")
        val repository = File(requireNotNull(System.getenv("WAND_KNOWLEDGE_REPO_CWD"))).canonicalFile
        val connection = JSONObject(File(System.getProperty("user.home"), ".wand/acceptance-connection.json").readText())
        val endpoint = WandHttp.normalizeBaseUrl(connection.getString("serverURL"))
        val decoded = String(Base64.getDecoder().decode(connection.getString("connectionCode")), Charsets.UTF_8)
        val separator = decoded.lastIndexOf('#')
        check(separator > 0 && separator < decoded.lastIndex) { "Invalid private acceptance connection" }
        val token = decoded.substring(separator + 1)
        val login = Request.Builder().url("$endpoint/api/login")
            .post(JSONObject().put("appToken", token).toString().toRequestBody("application/json".toMediaType())).build()
        WandHttp.clientFor(endpoint).newCall(login).execute().use { assertTrue("Native acceptance login failed", it.isSuccessful) }
        val api = WandApi(endpoint, token)
        val owners = api.knowledgeOwners()
        val results = JSONArray()
        for (scope in KnowledgeScope.entries) {
            val owner = owners.forScope(scope).firstOrNull() ?: continue
            val library = api.knowledgeLibrary(scope, owner.id)
            assertEquals(scope, library.scope); assertEquals(owner.id, library.ownerId)
            assertTrue(library.entries.all { it.scope == scope && it.ownerId == owner.id })
            assertTrue(library.limit > 0 && library.entries.size <= library.limit)
            assertTrue(library.entries.all { it.revision > 0 })
            results.put(JSONObject().put("scope", scope.wire).put("entries", library.entries.size).put("limit", library.limit))
        }
        assertEquals("Installed service must provide both configured owner types", 2, results.length())
        File(repository, "output/knowledge-library-20261027/implementation/android-installed-service.json").writeText(
            JSONObject().put("passed", true).put("readOnly", true).put("libraries", results)
                .put("deviceInstalled", false).put("nativeTouchVerified", false).toString(2) + "\n")
    }
}
