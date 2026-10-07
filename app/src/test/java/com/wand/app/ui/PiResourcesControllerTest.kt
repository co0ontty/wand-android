package com.wand.app.ui

import com.wand.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiResourcesControllerTest {
    private val first = PiResourcesResponse(PiResourceSelection(),
        listOf(PiResourceItem("skill-a", "radix-colors", "palette")), emptyList(), true, "")
    private class Port(var response: PiResourcesResponse) : PiResourcesPort {
        val reads = mutableListOf<String>()
        val saves = mutableListOf<Pair<String, PiResourceSelection>>()
        var failure = false
        var wrongAck = false
        var pending: CompletableDeferred<PiResourcesResponse>? = null
        var pendingAutomatic: CompletableDeferred<PiAutoResourcesAck>? = null
        val automaticSaves = mutableListOf<Pair<String, Boolean>>()
        val skillSaves = mutableListOf<Pair<String, PiSkillSelectionAck>>()
        var pendingSkill: CompletableDeferred<PiSkillSelectionAck>? = null
        override suspend fun setPiSkillSelection(id: String, selection: PiSkillSelectionAck): PiSkillSelectionAck {
            skillSaves += id to selection
            pendingSkill?.let { return withContext(NonCancellable) { it.await() } }
            if (failure) throw Exception("保存失败")
            if (wrongAck) return selection.copy(lockedSkills = emptyList())
            response = response.copy(selection = selection.selection, lockedSkills = selection.lockedSkills)
            return selection
        }
        override suspend fun setPiAutoResources(id: String, enabled: Boolean): PiAutoResourcesAck {
            automaticSaves += id to enabled
            pendingAutomatic?.let { return withContext(NonCancellable) { it.await() } }
            if (failure) throw Exception("保存失败")
            if (wrongAck) return PiAutoResourcesAck(!enabled, response.selection)
            val selection = response.selection ?: if (enabled) PiResourceSelection() else null
            response = response.copy(autoResources = enabled, selection = selection)
            return PiAutoResourcesAck(enabled, selection)
        }
        override suspend fun getPiResources(id: String): PiResourcesResponse {
            reads += id
            return pending?.let { withContext(NonCancellable) { it.await() } } ?: response
        }
        override suspend fun setPiCodemode(id: String, mode: String): String {
            if (failure) throw Exception("保存失败")
            response = response.copy(codemode = mode)
            return mode
        }
        override suspend fun setPiResources(id: String, selection: PiResourceSelection): PiResourceSelection {
            saves += id to selection
            if (failure) throw Exception("保存失败")
            if (wrongAck) return PiResourceSelection()
            response = response.copy(selection = selection)
            return selection
        }
    }
    @Test fun loadsOnlyAfterOpeningAndReloadsOnReopen() = runTest {
        val port = Port(first)
        val controller = PiResourcesController("a", port, this)
        runCurrent(); assertTrue(port.reads.isEmpty())
        controller.toggle(); runCurrent()
        assertEquals(listOf("a"), port.reads)
        assertEquals("ready", controller.phase)
        controller.dismiss(); controller.toggle(); runCurrent()
        assertEquals(2, port.reads.size)
        controller.dismiss()
    }
    @Test fun savesOnlyResourcesForTheCapturedSessionAndFailureKeepsSelection() = runTest {
        val port = Port(first)
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent()
        controller.toggleItem(PiResourceKind.Skill, "skill-a", true); runCurrent()
        assertEquals("a", port.saves.single().first)
        assertEquals(listOf("skill-a"), controller.data?.selection?.skills)
        port.failure = true
        controller.toggleItem(PiResourceKind.Skill, "skill-a", false); runCurrent()
        assertEquals("failed", controller.phase)
        assertEquals(listOf("skill-a"), controller.data?.selection?.skills)
        controller.dismiss(); assertEquals("idle", controller.phase)
    }
    @Test fun lateReadCannotReopenClosedPanelOrOverwriteAnotherSession() = runTest {
        val port = Port(first)
        val waiting = CompletableDeferred<PiResourcesResponse>(); port.pending = waiting
        val a = PiResourcesController("a", port, this)
        a.toggle(); runCurrent(); a.dismiss()
        val otherPort = Port(first.copy(selection = PiResourceSelection(listOf("skill-b"))))
        val b = PiResourcesController("b", otherPort, this)
        b.toggle(); runCurrent()
        waiting.complete(first); runCurrent()
        assertFalse(a.open); assertNull(a.data)
        assertEquals(listOf("skill-b"), b.data?.selection?.skills)
        b.dismiss()
    }
    @Test fun mismatchedAckDoesNotPretendResourcesWereSaved() = runTest {
        val port = Port(first); port.wrongAck = true
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent()
        controller.toggleItem(PiResourceKind.Skill, "skill-a", true); runCurrent()
        assertEquals("failed", controller.phase)
        assertTrue(controller.message.contains("未确认"))
        assertEquals(PiResourceSelection(), controller.data?.selection)
        controller.dismiss()
    }
    @Test fun codeModeChangesPreserveResourceChoicesAndFailurePreservesMode() = runTest {
        val port = Port(first.copy(codemodeAvailable = true, selection = PiResourceSelection(listOf("skill-a"))))
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent()
        controller.setCodemode("only"); runCurrent()
        assertEquals("only", controller.data?.codemode)
        assertEquals(listOf("skill-a"), controller.data?.selection?.skills)
        port.failure = true
        controller.setCodemode("off"); runCurrent()
        assertEquals("only", controller.data?.codemode)
        assertEquals("failed", controller.phase)
        controller.dismiss()
    }
    @Test fun automaticSwitchSavesOnlyForThisSessionAndPreservesPinsAndManualCodeMode() = runTest {
        val selected = PiResourceSelection(listOf("skill-a"), listOf("mcp-b"))
        val port = Port(first.copy(selection = selected, codemode = "off", autoResourcesAvailable = true, autoCodemodeAvailable = true))
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent()
        controller.setAutomaticResources(true); runCurrent()
        assertTrue(controller.data?.autoResources == true)
        assertEquals(selected, controller.data?.selection)
        assertEquals("off", controller.data?.codemode)
        assertEquals(listOf("a" to true), port.automaticSaves)
        port.failure = true
        controller.setAutomaticResources(false); runCurrent()
        assertEquals("failed", controller.phase)
        assertTrue(controller.data?.autoResources == true)
        assertEquals(selected, controller.data?.selection)
        controller.dismiss()
    }
    @Test fun automaticSwitchRejectsWrongAckAndCanDisableWhenRuntimeIsUnavailable() = runTest {
        val port = Port(first.copy(autoResourcesAvailable = true)); port.wrongAck = true
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent()
        controller.setAutomaticResources(true); runCurrent()
        assertEquals("failed", controller.phase)
        assertFalse(controller.data?.autoResources == true)
        controller.dismiss()
        val unavailable = Port(first.copy(autoResources = true, autoResourcesAvailable = false))
        val other = PiResourcesController("b", unavailable, this)
        other.toggle(); runCurrent(); other.setAutomaticResources(false); runCurrent()
        assertEquals("saved", other.phase)
        assertFalse(other.data?.autoResources == true)
        other.setAutomaticResources(true); runCurrent()
        assertEquals(listOf("b" to false), unavailable.automaticSaves)
        other.dismiss()
    }
    @Test fun unsupportedOrArchivedSessionCannotMutateAnAlreadyEnabledSwitch() = runTest {
        val port = Port(first.copy(supported = false, autoResources = true))
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent(); controller.setAutomaticResources(false); runCurrent()
        assertTrue(port.automaticSaves.isEmpty())
        assertTrue(controller.data?.autoResources == true)
        controller.dismiss()
    }

    @Test fun lateAutomaticSaveDoesNotReopenClosedPanelOrOverwriteAnotherSession() = runTest {
        val port = Port(first.copy(autoResourcesAvailable = true))
        val waiting = CompletableDeferred<PiAutoResourcesAck>(); port.pendingAutomatic = waiting
        val a = PiResourcesController("a", port, this)
        a.toggle(); runCurrent(); a.setAutomaticResources(true); runCurrent(); a.dismiss()
        val other = PiResourcesController("b", Port(first.copy(autoResourcesAvailable = true)), this)
        other.toggle(); runCurrent()
        waiting.complete(PiAutoResourcesAck(true, PiResourceSelection())); runCurrent()
        assertFalse(a.open)
        assertFalse(a.data?.autoResources == true)
        assertFalse(other.data?.autoResources == true)
        assertEquals(listOf("a" to true), port.automaticSaves)
        other.dismiss()
    }
    @Test fun enablingAutomaticConfigurationConvertsLegacyDiscoveryToAnEmptyManualList() = runTest {
        val port = Port(first.copy(selection = null, autoResourcesAvailable = true))
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent(); controller.setAutomaticResources(true); runCurrent()
        assertEquals("saved", controller.phase)
        assertEquals(PiResourceSelection(), controller.data?.selection)
        controller.dismiss()
    }

    @Test fun skillDetentsSaveSelectionAndLockTogetherAndPreserveOtherResources() = runTest {
        val port = Port(first.copy(skillLocksAvailable = true, selection = PiResourceSelection(listOf("skill-b"), listOf("mcp-c")),
            lockedSkills = listOf("skill-b")))
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent()
        controller.setSkillMode("skill-a", PiSkillMode.On); runCurrent()
        assertEquals(PiSkillMode.On, controller.data?.skillMode("skill-a"))
        controller.setSkillMode("skill-a", PiSkillMode.Locked); runCurrent()
        assertEquals(PiSkillMode.Locked, controller.data?.skillMode("skill-a"))
        assertEquals(listOf("skill-b", "skill-a"), controller.data?.lockedSkills)
        controller.setSkillMode("skill-a", PiSkillMode.On); runCurrent()
        assertEquals(listOf("skill-b"), controller.data?.lockedSkills)
        assertTrue("skill-a" in controller.data?.selection?.skills.orEmpty())
        controller.setSkillMode("skill-a", PiSkillMode.Off); runCurrent()
        assertEquals(listOf("skill-b"), controller.data?.selection?.skills)
        assertEquals(listOf("mcp-c"), controller.data?.selection?.mcpServers)
        assertEquals(4, port.skillSaves.size)
        assertTrue(port.skillSaves.all { it.first == "a" })
        controller.dismiss()
    }
    @Test fun failedOrUnconfirmedSkillSaveKeepsTheRealDetentAndLegacyServerNeverSaves() = runTest {
        val port = Port(first.copy(skillLocksAvailable = true)); port.failure = true
        val controller = PiResourcesController("a", port, this)
        controller.toggle(); runCurrent(); controller.setSkillMode("skill-a", PiSkillMode.Locked); runCurrent()
        assertEquals("failed", controller.phase)
        assertEquals(PiSkillMode.Off, controller.data?.skillMode("skill-a"))
        port.failure = false; port.wrongAck = true
        controller.setSkillMode("skill-a", PiSkillMode.Locked); runCurrent()
        assertEquals("failed", controller.phase)
        assertTrue(controller.message.contains("未确认"))
        assertEquals(PiSkillMode.Off, controller.data?.skillMode("skill-a"))
        controller.dismiss()
        val oldPort = Port(first)
        val old = PiResourcesController("b", oldPort, this)
        old.toggle(); runCurrent(); old.setSkillMode("skill-a", PiSkillMode.Locked); runCurrent()
        assertTrue(oldPort.skillSaves.isEmpty()); old.dismiss()
    }
    @Test fun lateSkillSaveCannotReopenOrOverwriteAnotherSessionAndBusyDoesNotDoubleSave() = runTest {
        val port = Port(first.copy(skillLocksAvailable = true))
        val waiting = CompletableDeferred<PiSkillSelectionAck>(); port.pendingSkill = waiting
        val a = PiResourcesController("a", port, this)
        a.toggle(); runCurrent(); a.setSkillMode("skill-a", PiSkillMode.Locked); runCurrent()
        a.setSkillMode("skill-a", PiSkillMode.On); runCurrent()
        assertEquals(1, port.skillSaves.size)
        a.dismiss()
        val b = PiResourcesController("b", Port(first.copy(skillLocksAvailable = true)), this)
        b.toggle(); runCurrent()
        waiting.complete(port.skillSaves.single().second); runCurrent()
        assertFalse(a.open)
        assertEquals(PiSkillMode.Off, a.data?.skillMode("skill-a"))
        assertEquals(PiSkillMode.Off, b.data?.skillMode("skill-a"))
        b.dismiss()
    }

    @Test fun oldServerIsUnsupportedRatherThanPretendingSelectionWasApplied() {
        val response = PiResourcesResponse.parse(JSONObject("""{"settings":{},"engine":"cli"}"""))
        assertFalse(response.supported)
        assertTrue(response.reason.contains("更新服务端"))
        assertNull(response.selection)
    }
    @Test fun resourcePayloadDoesNotContainCommandsPathsOrCommonToolSettings() {
        val selection = PiResourceSelection(listOf("skill-a"), listOf("mcp-b"))
        val json = selection.toJson()
        assertEquals(setOf("skills", "mcpServers"), json.keys().asSequence().toSet())
        assertEquals(selection, PiResourceSelection.parse(json))
        assertEquals(listOf("skill-a"), selection.toggle(PiResourceKind.Skill, "skill-a", true).skills)
        assertTrue(selection.toggle(PiResourceKind.Skill, "skill-a", false).skills.isEmpty())
    }
}
