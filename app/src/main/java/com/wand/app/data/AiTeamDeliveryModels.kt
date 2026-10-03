package com.wand.app.data

import org.json.JSONObject
import java.time.Instant

/** Mirrors src/ai-team-delivery-types.ts: historical delivery, not task/completion state. */
data class AiTeamDeliveryFile(
    val stepId: String, val seq: Int, val title: String,
    val memberId: String, val memberName: String, val file: TeamReportFile,
)
data class AiTeamDeliveryHandoff(
    val stepId: String, val seq: Int, val title: String,
    val memberId: String, val memberName: String, val status: String,
    val state: String?, val sessionId: String?, val waitingFor: List<String>,
)
data class AiTeamDeliveryAttention(val kind: String, val message: String)
data class AiTeamDeliverySummary(
    val runId: String, val updatedAt: String, val headline: String, val conclusion: String?,
    val files: List<AiTeamDeliveryFile>, val totalFiles: Int,
    val handoffs: List<AiTeamDeliveryHandoff>, val totalHandoffs: Int,
    val attention: AiTeamDeliveryAttention?,
) {
    companion object {
        fun parse(source: JSONObject?): AiTeamDeliverySummary? {
            source ?: return null
            val runId = source.deliveryString("runId")?.takeIf { it.isNotBlank() } ?: return null
            val updatedAt = source.deliveryString("updatedAt")?.takeIf { deliveryInstant(it) != null } ?: return null
            val headline = source.deliveryString("headline") ?: return null
            val files = source.arr("files")?.parseEachSafely { item ->
                val stepId = item.deliveryString("stepId")?.takeIf { it.isNotBlank() } ?: return@parseEachSafely null
                val file = TeamReportFile.parse(item.obj("file"))?.takeIf { it.stepId == stepId }
                    ?: return@parseEachSafely null
                AiTeamDeliveryFile(stepId, item.int("seq") ?: 0, item.deliveryString("title").orEmpty(),
                    item.deliveryString("memberId").orEmpty(), item.deliveryString("memberName").orEmpty(), file)
            }.orEmpty().take(20)
            val handoffs = source.arr("handoffs")?.parseEachSafely { item ->
                val stepId = item.deliveryString("stepId")?.takeIf { it.isNotBlank() } ?: return@parseEachSafely null
                val status = item.deliveryString("status")?.takeIf { it == "running" || it == "queued" }
                    ?: return@parseEachSafely null
                val waiting = item.arr("waitingFor")
                AiTeamDeliveryHandoff(stepId, item.int("seq") ?: 0, item.deliveryString("title").orEmpty(),
                    item.deliveryString("memberId").orEmpty(), item.deliveryString("memberName").orEmpty(), status,
                    item.deliveryString("state"), item.deliveryString("sessionId")?.takeIf { it.isNotBlank() },
                    (0 until (waiting?.length() ?: 0)).mapNotNull { waiting?.opt(it) as? String }.take(6))
            }.orEmpty().take(6)
            val attention = source.obj("attention")?.let { item ->
                val kind = item.deliveryString("kind")?.takeIf { it in setOf("approval", "reply", "failure", "stopped") }
                val message = item.deliveryString("message")
                if (kind != null && message != null) AiTeamDeliveryAttention(kind, message) else null
            }
            return AiTeamDeliverySummary(runId, updatedAt, headline, source.deliveryString("conclusion"), files,
                (source.int("totalFiles") ?: files.size).coerceAtLeast(files.size), handoffs,
                (source.int("totalHandoffs") ?: handoffs.size).coerceAtLeast(handoffs.size), attention)
        }
    }
}

private fun JSONObject.deliveryString(key: String): String? = opt(key) as? String
private fun deliveryInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

/** Only merge within an explicitly selected source run; a caller must authorize switching runs. */
fun mergeAiTeamRunDetail(previous: AiTeamRunDetail?, incoming: AiTeamRunDetail, expectedRunId: String): AiTeamRunDetail? {
    if (incoming.run.id != expectedRunId) return previous?.takeIf { it.run.id == expectedRunId }
    val old = previous?.takeIf {
        it.run.id == expectedRunId && it.run.taskId == incoming.run.taskId &&
            it.run.chatSessionId == incoming.run.chatSessionId
    } ?: return incoming
    val oldTime = deliveryInstant(old.run.updatedAt) ?: old.delivery?.let { deliveryInstant(it.updatedAt) }
    val newTime = deliveryInstant(incoming.run.updatedAt) ?: incoming.delivery?.let { deliveryInstant(it.updatedAt) }
    if (oldTime != null && newTime != null && newTime < oldTime) return old
    if ((oldTime == null || newTime == null || oldTime == newTime) &&
        (incoming.run.stepsUsed < old.run.stepsUsed ||
            (old.run.status in setOf("done", "failed", "stopped") && incoming.run.status == "running") || old.steps.any { step ->
            step.status in setOf("done", "failed", "skipped") && incoming.steps.any { it.id == step.id && it.status in setOf("queued", "running") }
        })) return old
    val delivery = incoming.delivery
    // An explicitly newer source without the optional field must fall back to the original UI,
    // rather than attaching an earlier revision's attention/files to the new run facts.
    if (delivery == null && oldTime != null && newTime != null && newTime > oldTime) return incoming
    val previousDelivery = old.delivery
    val retained = if (previousDelivery != null && (delivery == null ||
            deliveryInstant(delivery.updatedAt)!! < deliveryInstant(previousDelivery.updatedAt)!! ||
            (deliveryInstant(delivery.updatedAt) == deliveryInstant(previousDelivery.updatedAt) &&
                deliveryDropsFrozenFiles(previousDelivery, delivery)))) previousDelivery else delivery
    return incoming.copy(delivery = retained)
}

/** Same source revision can arrive before complete relay metadata. Keep the richer snapshot,
 * rather than constructing a union or inventing file availability/verification facts. */
private fun deliveryDropsFrozenFiles(previous: AiTeamDeliverySummary, incoming: AiTeamDeliverySummary): Boolean =
    incoming.totalFiles < previous.totalFiles || previous.files.any { old ->
        val current = incoming.files.firstOrNull { it.stepId == old.stepId && it.file.path == old.file.path }
        current == null || (old.file.preview != null &&
            (current.file.preview == null ||
                (old.file.preview.excerpt.isNotBlank() && current.file.preview.excerpt.isBlank())))
    }

/** Request tickets belong to one visible scope. Disposal invalidates callbacks even after a task switch. */
class AiTeamDetailRequestGuard {
    private var generation = 0L
    private var active = true
    fun begin(): Long = ++generation
    fun accepts(ticket: Long): Boolean = active && ticket == generation
    fun invalidate() { active = false; generation++ }
}

/** Explicit choices survive refresh/status/result changes and remain separate for each source run. */
data class AiTeamDeliveryExpansion(val choices: Map<String, Boolean> = emptyMap()) {
    fun expanded(runId: String): Boolean = choices[runId] ?: false
    fun toggle(runId: String): AiTeamDeliveryExpansion = copy(choices = choices + (runId to !expanded(runId)))
}

/** Display names are projected by stable member id; paths and frozen previews never change. */
fun aiTeamDeliveryMemberName(detail: AiTeamRunDetail, memberId: String, recordedName: String): String =
    detail.presentationTeam?.members?.firstOrNull { it.id == memberId }?.name
        ?: recordedName.ifBlank { memberId.ifBlank { "成员" } }
