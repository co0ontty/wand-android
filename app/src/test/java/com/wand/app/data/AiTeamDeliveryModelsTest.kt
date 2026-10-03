package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic contract/projection tests, not live-service or file-availability acceptance. */
class AiTeamDeliveryModelsTest {
    private val earlier = "2026-10-03T10:00:00.000Z"
    private val later = "2026-10-03T10:01:00.000Z"
    private fun file(preview: Boolean = true) = JSONObject().put("stepId", "s1")
        .put("path", "/work/报告 #1.md").put("name", "报告 #1.md").put("size", 42)
        .also { if (preview) it.put("preview", JSONObject().put("title", "冻结标题").put("excerpt", "冻结摘要")) }
    private fun delivery(time: String = later) = JSONObject().put("runId", "r1").put("updatedAt", time)
        .put("headline", "本轮完成").put("conclusion", "负责人记录，不是验收")
        .put("files", JSONArray().put(JSONObject().put("stepId", "s1").put("seq", 1)
            .put("title", "实现").put("memberId", "m1").put("memberName", "历史名字").put("file", file())))
        .put("totalFiles", 25).put("handoffs", JSONArray().put(JSONObject().put("stepId", "s2")
            .put("seq", 2).put("title", "复核").put("memberId", "m2").put("memberName", "复核者")
            .put("status", "queued").put("state", "needs_input").put("sessionId", JSONObject.NULL)
            .put("waitingFor", JSONArray().put("实现"))))
        .put("totalHandoffs", 7).put("attention", JSONObject().put("kind", "reply").put("message", "补充范围"))
    private fun detail(time: String = later, projected: JSONObject? = delivery(time), runId: String = "r1"): AiTeamRunDetail {
        val json = JSONObject().put("run", JSONObject().put("id", runId).put("taskId", "task1")
            .put("updatedAt", time).put("status", "done").put("stepsUsed", 2))
        projected?.let { json.put("delivery", it) }
        return AiTeamRunDetail.parse(json)!!
    }

    @Test fun readsOnlyFixedServerProjectionAndPreservesFileIdentity() {
        val result = detail()
        val value = result.delivery!!
        assertEquals("r1", value.runId)
        assertEquals(later, result.run.updatedAt)
        assertEquals("负责人记录，不是验收", value.conclusion)
        assertEquals(25, value.totalFiles)
        assertEquals("/work/报告 #1.md", value.files.single().file.path)
        assertEquals("冻结摘要", value.files.single().file.preview!!.excerpt)
        assertEquals("m1", value.files.single().memberId)
        assertEquals("queued", value.handoffs.single().status)
        assertNull(value.handoffs.single().sessionId)
        assertEquals(listOf("实现"), value.handoffs.single().waitingFor)
        assertEquals("reply", value.attention!!.kind)
        assertEquals("done", result.run.status)
    }

    @Test fun oldServerAndInvalidOptionalDeliveryRetainOriginalDetail() {
        assertNull(detail(projected = null).delivery)
        for (invalid in listOf(JSONObject(), delivery().put("runId", "other"), delivery().put("updatedAt", false),
            delivery().put("headline", JSONObject()), delivery().put("updatedAt", "not-a-date"))) {
            val value = detail(projected = invalid)
            assertNull(value.delivery)
            assertEquals("done", value.run.status)
            assertEquals("task1", value.run.taskId)
        }
    }

    @Test fun missingPreviewDoesNotInventSummaryOrDiscardFile() {
        val payload = delivery()
        payload.getJSONArray("files").getJSONObject(0).put("file", file(false))
        val record = detail(projected = payload).delivery!!.files.single().file
        assertNull(record.preview)
        assertEquals("报告 #1.md", record.name)
        assertEquals("/work/报告 #1.md", record.path)
    }

    @Test fun malformedEntriesAreSkippedAndCollectionsBounded() {
        val payload = delivery().put("files", JSONArray().put(3).put(JSONObject())
            .put(JSONObject().put("stepId", "different").put("file", file())))
            .put("handoffs", JSONArray().put(false).put(JSONObject().put("stepId", "s").put("status", "done")))
            .put("attention", JSONObject().put("kind", "accepted").put("message", "unsupported"))
        assertTrue(detail(projected = payload).delivery!!.files.isEmpty())
        assertTrue(detail(projected = payload).delivery!!.handoffs.isEmpty())
        assertNull(detail(projected = payload).delivery!!.attention)
        val original = delivery()
        val entries = JSONArray()
        val handoffs = JSONArray()
        repeat(30) {
            entries.put(original.getJSONArray("files").getJSONObject(0))
            handoffs.put(original.getJSONArray("handoffs").getJSONObject(0))
        }
        val bounded = detail(projected = original.put("files", entries).put("handoffs", handoffs)
            .put("totalFiles", -1).put("totalHandoffs", "bad")).delivery!!
        assertEquals(20, bounded.files.size)
        assertEquals(6, bounded.handoffs.size)
        assertEquals(20, bounded.totalFiles)
        assertEquals(6, bounded.totalHandoffs)
    }

    @Test fun sameRunOlderSourceAndLateIncompleteDeliveryCannotRegress() {
        val latest = detail()
        assertSame(latest, mergeAiTeamRunDetail(latest, detail(earlier), "r1"))
        assertEquals(latest.delivery, mergeAiTeamRunDetail(latest, detail(projected = null), "r1")!!.delivery)
        assertEquals(latest.delivery, mergeAiTeamRunDetail(latest, detail(projected = delivery(earlier)), "r1")!!.delivery)
        assertEquals(later, mergeAiTeamRunDetail(detail(earlier), latest, "r1")!!.delivery!!.updatedAt)
    }

    @Test fun newerSourceWithoutDeliveryClearsOldApprovalProjectionAndDoesNotReviveIt() {
        val approval = detail(earlier, delivery(earlier).put("attention",
            JSONObject().put("kind", "approval").put("message", "请批准旧计划")))
            .let { it.copy(run = it.run.copy(status = "awaiting_approval")) }
        val running = detail(later, projected = null)
            .let { it.copy(run = it.run.copy(status = "running")) }
        val merged = mergeAiTeamRunDetail(approval, running, "r1")!!
        assertSame(running, merged)
        assertEquals("running", merged.run.status)
        assertNull(merged.delivery)
        assertEquals("approval", approval.delivery!!.attention!!.kind)
        assertSame(merged, mergeAiTeamRunDetail(merged, approval, "r1"))
    }

    @Test fun sameRevisionOrOlderMissingDeliveryRetainsCompleteApprovalProjection() {
        val approval = detail(later, delivery(later).put("attention",
            JSONObject().put("kind", "approval").put("message", "请批准当前计划")))
            .let { it.copy(run = it.run.copy(status = "awaiting_approval")) }
        val sameRevision = approval.copy(delivery = null)
        val sameRevisionMerged = mergeAiTeamRunDetail(approval, sameRevision, "r1")!!
        assertEquals(approval.run, sameRevisionMerged.run)
        assertSame(approval.delivery, sameRevisionMerged.delivery)
        val older = sameRevision.copy(run = sameRevision.run.copy(updatedAt = earlier))
        assertSame(approval, mergeAiTeamRunDetail(approval, older, "r1"))
    }

    @Test fun sameRevisionFullFileWindowCannotRegressWhenLateSnapshotShiftsEntries() {
        fun projected(total: Int): JSONObject {
            val entries = JSONArray()
            for (seq in total downTo total - 19) {
                val stepId = "s$seq"
                entries.put(JSONObject().put("stepId", stepId).put("seq", seq).put("memberId", "m1")
                    .put("memberName", "成员").put("title", "步骤 $seq")
                    .put("file", file().put("stepId", stepId).put("path", "/work/$seq.md")))
            }
            return delivery().put("files", entries).put("totalFiles", total)
        }
        val complete = detail(projected = projected(25))
        for (latePayload in listOf(projected(24), projected(24).put("totalFiles", 25))) {
            val late = detail(projected = latePayload)
            assertEquals(20, late.delivery!!.files.size)
            assertSame(complete.delivery, mergeAiTeamRunDetail(complete, late, "r1")!!.delivery)
        }
    }

    @Test fun identicalSourceRevisionRetainsCompleteDeliveryWhenLateMetadataHasFewerFiles() {
        val completePayload = delivery()
        val second = JSONObject(completePayload.getJSONArray("files").getJSONObject(0).toString())
            .put("stepId", "s3").put("file", file().put("stepId", "s3").put("path", "/work/second.md"))
        completePayload.getJSONArray("files").put(second)
        val complete = detail(projected = completePayload)
        for (late in listOf(detail(projected = null), detail(), detail(projected = delivery().put("totalFiles", 1)))) {
            assertEquals(complete.run.updatedAt, late.run.updatedAt)
            assertEquals(complete.steps, late.steps)
            val merged = mergeAiTeamRunDetail(complete, late, "r1")!!
            assertSame(complete.delivery, merged.delivery)
            assertEquals(2, merged.delivery!!.files.size)
            assertEquals(complete.run, merged.run)
        }
    }

    @Test fun identicalSourceRevisionCannotEraseFrozenPreviewButNewRevisionCanReplaceSnapshot() {
        val complete = detail()
        for (preview in listOf(null, JSONObject().put("title", "冻结标题").put("excerpt", ""),
            JSONObject().put("title", "").put("excerpt", ""))) {
            val latePayload = delivery()
            latePayload.getJSONArray("files").getJSONObject(0).getJSONObject("file")
                .put("preview", preview ?: JSONObject.NULL)
            val late = detail(projected = latePayload)
            assertEquals(complete.run.updatedAt, late.run.updatedAt)
            assertEquals(complete.steps, late.steps)
            assertSame(complete.delivery, mergeAiTeamRunDetail(complete, late, "r1")!!.delivery)
            val newTime = "2026-10-03T10:02:00Z"
            val newer = detail(newTime, latePayload.put("updatedAt", newTime))
            assertSame(newer.delivery, mergeAiTeamRunDetail(complete, newer, "r1")!!.delivery)
        }
        val incomplete = detail(projected = delivery().also {
            it.getJSONArray("files").getJSONObject(0).put("file", file(false))
        })
        assertSame(complete.delivery, mergeAiTeamRunDetail(incomplete, complete, "r1")!!.delivery)
    }

    @Test fun differentTaskOrChatScopeNeverInheritsPreviousFrozenDelivery() {
        val complete = detail()
        for (differentScope in listOf(
            detail(projected = null).copy(run = complete.run.copy(taskId = "task2")),
            detail(projected = null).copy(run = complete.run.copy(chatSessionId = "chat2")),
        )) {
            val merged = mergeAiTeamRunDetail(complete, differentScope, "r1")!!
            assertSame(differentScope, merged)
            assertNull(merged.delivery)
        }
        val otherPayload = delivery().put("runId", "r2")
        val other = detail(runId = "r2", projected = otherPayload)
        assertSame(other, mergeAiTeamRunDetail(complete, other, "r2"))
        assertSame(complete, mergeAiTeamRunDetail(complete, other, "r1"))
    }

    @Test fun legacyStepProgressCannotRegressAndNewTimestampCanAdvance() {
        val latest = detail(time = "", projected = null).copy(steps = listOf(AiTeamStep("s", 1, "work", "m", "", "done")))
        val old = latest.copy(run = latest.run.copy(stepsUsed = 1), steps = listOf(latest.steps.single().copy(status = "running")))
        assertSame(latest, mergeAiTeamRunDetail(latest, old, "r1"))
        assertSame(latest, mergeAiTeamRunDetail(latest, old.copy(run = latest.run), "r1"))
    }

    @Test fun equalTimestampCannotReopenTerminalSourceButNewerTimestampCan() {
        val completed = detail()
        val oldRunning = completed.copy(run = completed.run.copy(status = "running"))
        assertSame(completed, mergeAiTeamRunDetail(completed, oldRunning, "r1"))
        val restarted = oldRunning.copy(run = oldRunning.run.copy(updatedAt = "2026-10-03T10:02:00Z"))
        assertEquals("running", mergeAiTeamRunDetail(completed, restarted, "r1")!!.run.status)
    }

    @Test fun differentRunRequiresExplicitSelectionAndDoesNotInheritFiles() {
        val first = detail()
        val other = detail(runId = "r2", projected = null)
        assertSame(first, mergeAiTeamRunDetail(first, other, "r1"))
        assertSame(other, mergeAiTeamRunDetail(first, other, "r2"))
        assertNull(mergeAiTeamRunDetail(first, other, "r2")!!.delivery)
    }

    @Test fun requestGenerationAndDisposedTaskScopeRejectLateResponses() {
        val taskOne = AiTeamDetailRequestGuard()
        val first = taskOne.begin()
        val newer = taskOne.begin()
        assertFalse(taskOne.accepts(first))
        assertTrue(taskOne.accepts(newer))
        taskOne.invalidate()
        assertFalse(taskOne.accepts(newer))
        val taskTwo = AiTeamDetailRequestGuard()
        assertTrue(taskTwo.accepts(taskTwo.begin()))
        assertFalse(taskOne.accepts(taskOne.begin()))
    }

    @Test fun explicitExpansionSurvivesRefreshStatusAndLateResultPerRun() {
        var expansion = AiTeamDeliveryExpansion()
        assertFalse(expansion.expanded("r1"))
        expansion = expansion.toggle("r1")
        val refreshed = detail().copy(run = detail().run.copy(status = "running"))
        assertTrue(expansion.expanded(refreshed.run.id))
        assertFalse(expansion.expanded("r2"))
        expansion = expansion.toggle("r1").toggle("r2")
        assertFalse(expansion.expanded(detail().run.id))
        assertTrue(expansion.expanded("r2"))
    }

    @Test fun authorProjectionUsesStableIdWithoutRewritingPathOrFrozenRecord() {
        val source = detail()
        val team = AiTeam("t", "团队", "", listOf(AiTeamMember("m1", "当前名字", "", emptyList(), false)))
        val renamed = source.copy(displayTeam = team)
        val record = renamed.delivery!!.files.single()
        assertEquals("当前名字", aiTeamDeliveryMemberName(renamed, record.memberId, record.memberName))
        assertEquals("历史名字", source.delivery!!.files.single().memberName)
        assertEquals(source.delivery, renamed.delivery)
        assertEquals("未知成员", aiTeamDeliveryMemberName(renamed, "missing", "未知成员"))
    }
}
