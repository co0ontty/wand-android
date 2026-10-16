package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.activity.compose.BackHandler
import com.wand.app.data.ConversationOrganizationPort
import com.wand.app.ui.components.WandInPlaceSwap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wand.app.data.ConversationOrganizationJob
import com.wand.app.data.ConversationOrganizationMode
import com.wand.app.data.ConversationOrganizationPreview
import com.wand.app.data.ConversationOrganizationTarget
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 原位目录菜单的会话内容归组状态；离开页面只停轮询，不取消服务端作业。 */
class ConversationOrganizationController(
    private val api: ConversationOrganizationPort,
    private val target: ConversationOrganizationTarget,
    private val scope: CoroutineScope,
    private val onFinished: suspend () -> Unit = {},
) {
    enum class Phase { Idle, Loading, Prepared, Running, Done, Error }

    var phase by mutableStateOf(Phase.Idle)
        private set
    var mode by mutableStateOf(ConversationOrganizationMode.Auto)
        private set
    var preview by mutableStateOf<ConversationOrganizationPreview?>(null)
        private set
    var job by mutableStateOf<ConversationOrganizationJob?>(null)
        private set
    var feedback by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    private var pollJob: Job? = null
    private var revision = 0L
    private var submittedToken: String? = null
    private var cancelling = false
    val needsRecheck: Boolean get() = submittedToken != null || job != null
    val canAdjustScope: Boolean get() = phase == Phase.Prepared || phase == Phase.Done ||
        (phase == Phase.Error && job != null && job?.isRunning != true)

    fun selectMode(next: ConversationOrganizationMode) {
        if (!needsRecheck && (phase == Phase.Idle || phase == Phase.Error)) mode = next
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        revision++
        if (phase == Phase.Loading || phase == Phase.Running) {
            phase = Phase.Error
            feedback = "查询已暂停；后台已接受的归组不会停止。"
        }
    }

    fun previewScope() {
        if (needsRecheck || (phase != Phase.Idle && phase != Phase.Error)) return
        stopPolling()
        val current = ++revision
        phase = Phase.Loading
        preview = null
        job = null
        errorMessage = null
        feedback = "正在读取当前目录的会话正文并估算处理量…"
        pollJob = scope.launch {
            try {
                val next = api.previewConversationOrganization(target, mode)
                if (current != revision) return@launch
                if (next.activeJobId != null) {
                    feedback = "本范围已有作业，正在查询原作业；不会重复发起模型请求。"
                    val active = api.conversationOrganizationJob(next.activeJobId)
                    if (current != revision) return@launch
                    preview = next
                    watch(active, current)
                } else if (next.sessionCount == 0) {
                    preview = next
                    feedback = "没有符合范围且有正文的工作会话；跳过 ${next.manualSessionCount} 个人工任务会话、${next.skippedSessionCount} 个归档或无正文等会话。可调整范围，不会调用模型。"
                    phase = Phase.Done
                } else {
                    preview = next
                    feedback = previewDescription(next)
                    phase = Phase.Prepared
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current == revision) {
                    errorMessage = error.message ?: "无法预览会话自动分组"
                    feedback = "无法预览会话自动分组。"
                    phase = Phase.Error
                }
            }
        }
    }

    fun confirm() {
        val currentPreview = preview ?: return
        if (phase != Phase.Prepared) return
        stopPolling()
        val current = ++revision
        phase = Phase.Loading
        errorMessage = null
        feedback = "正在提交后台归组…"
        submittedToken = currentPreview.token
        pollJob = scope.launch {
            try {
                val accepted = api.startConversationOrganization(currentPreview.token, currentPreview.unchanged)
                if (current != revision) return@launch
                job = accepted
                preview = null
                watch(accepted, current)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Keep preview/token visible so caller can query the original request; never retry start.
                if (current == revision) {
                    errorMessage = error.message ?: "后台归组回执未确认"
                    feedback = "后台归组回执未确认；可重新查询原作业，不会自动重发。"
                    phase = Phase.Error
                }
            }
        }
    }

    fun reset() {
        if (phase == Phase.Loading || job?.isRunning == true || (submittedToken != null && job == null)) return
        stopPolling()
        submittedToken = null
        preview = null
        job = null
        feedback = ""
        errorMessage = null
        phase = Phase.Idle
    }

    fun recheck() {
        if (!needsRecheck || (phase != Phase.Done && phase != Phase.Error)) return
        val currentJob = job
        val token = submittedToken
        stopPolling()
        val current = ++revision
        phase = Phase.Loading
        errorMessage = null
        feedback = "正在查询原作业…"
        pollJob = scope.launch {
            try {
                val original = if (currentJob != null) api.conversationOrganizationJob(currentJob.id)
                    else api.conversationOrganizationJobByToken(token ?: return@launch)
                if (current != revision) return@launch
                watch(original, current)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current == revision) {
                    errorMessage = error.message ?: "暂时无法查询原作业"
                    phase = Phase.Error
                }
            }
        }
    }

    fun cancel() {
        val currentJob = job ?: return
        if (!currentJob.isRunning || cancelling) return
        cancelling = true
        scope.launch {
            try {
                val next = api.cancelConversationOrganization(currentJob.id)
                if (job?.id == next.id) {
                    job = next
                    feedback = "已请求取消后续归组；已完成部分会保留。"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (job?.id == currentJob.id) feedback = "取消回执未确认，仍可查询原作业；不会自动重发。"
            } finally {
                cancelling = false
            }
        }
    }

    private suspend fun watch(initial: ConversationOrganizationJob, expected: Long) {
        job = initial
        phase = when {
            initial.isRunning -> Phase.Running
            initial.isFailure -> Phase.Error
            else -> Phase.Done
        }
        feedback = if (initial.isRunning) initial.stage else resultDescription(initial)
        var current = initial
        var polls = 0
        while (current.isRunning && polls++ < MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            if (expected != revision) return
            current = api.conversationOrganizationJob(current.id)
            if (expected != revision) return
            job = current
            phase = if (current.isRunning) Phase.Running else if (current.isFailure) Phase.Error else Phase.Done
            feedback = if (current.isRunning) current.stage else resultDescription(current)
        }
        if (current.isRunning) {
            if (expected == revision) {
                errorMessage = "轮询已暂停，可稍后继续查询原作业。"
                feedback = "归组仍在后台运行；可稍后继续查询原作业。"
                phase = Phase.Error
            }
            return
        }
        if (expected != revision) return
        job = current
        phase = if (current.isFailure) Phase.Error else Phase.Done
        feedback = resultDescription(current)
        onFinished()
    }

    private fun previewDescription(value: ConversationOrganizationPreview): String =
        "将整理 ${value.sessionCount} 个工作会话，正文 ${value.contentCharacters} 字符，预计 ${value.estimatedModelRequests} 个系统模型请求；使用初二当前配置，失败可能按已配候选降级并产生费用；确认后创建或复用任务、移动会话，不执行会话中的工作。跳过 ${value.manualSessionCount} 个人工任务会话，另排除 ${value.skippedSessionCount} 个归档/私聊/系统记录或无正文会话。"

    private fun resultDescription(value: ConversationOrganizationJob): String = when {
        else -> {
            val summary = "新建 ${value.result.createdTasks.size} 个任务、复用 ${value.result.reusedTasks.size} 个任务，归入 ${value.result.movedSessions.size} 个会话；未分组 ${value.result.unassignedSessions.size} 个、拒绝 ${value.result.refused.size} 项。"
            val error = value.error ?: value.result.error
            when {
                !error.isNullOrBlank() -> "归组未完成：$error；$summary"
                value.isFailure -> "归组未完成；$summary"
                value.status == "cancelled" || value.result.cancelled == true -> "已取消后续归组，已完成部分已保留；$summary"
                value.result.eligibleSessions == 0 -> "没有符合范围且有正文的工作会话。"
                value.result.movedSessions.isEmpty() -> "本次未移动会话；$summary"
                else -> "归组完成：$summary"
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 800L
        private const val MAX_POLLS = 900
    }
}

@Composable
private fun OrganizationActions(controller: ConversationOrganizationController) {
    val phase = controller.phase
    val label = when (phase) {
        ConversationOrganizationController.Phase.Idle -> "读取范围"
        ConversationOrganizationController.Phase.Error -> if (controller.needsRecheck) "查询原作业" else "重新预览"
        ConversationOrganizationController.Phase.Prepared -> if (controller.preview?.unchanged == true) "确认重新分析" else "确认并开始"
        ConversationOrganizationController.Phase.Loading -> "处理中…"
        ConversationOrganizationController.Phase.Running -> "后台归组中…"
        ConversationOrganizationController.Phase.Done -> if (controller.job == null) "没有可整理会话" else "已完成 · 查看结果"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                when (phase) {
                    ConversationOrganizationController.Phase.Idle -> controller.previewScope()
                    ConversationOrganizationController.Phase.Error -> if (controller.needsRecheck) controller.recheck() else controller.previewScope()
                    ConversationOrganizationController.Phase.Prepared -> controller.confirm()
                    ConversationOrganizationController.Phase.Done -> controller.recheck()
                    else -> Unit
                }
            },
            enabled = phase != ConversationOrganizationController.Phase.Loading && phase != ConversationOrganizationController.Phase.Running &&
                (phase != ConversationOrganizationController.Phase.Done || controller.needsRecheck),
            modifier = Modifier.width(180.dp).height(40.dp),
        ) {
            WandInPlaceSwap(contentKey = label, enterScale = 1f, exitScale = 1f) { Text(it as? String ?: "") }
        }
        if (controller.job?.isRunning == true) {
            TextButton(onClick = controller::cancel) { Text("取消后续") }
        } else if (controller.canAdjustScope) {
            TextButton(onClick = controller::reset) { Text("调整范围") }
        }
    }
}

@Composable
internal fun ConversationOrganizationPanel(
    controller: ConversationOrganizationController,
    group: TaskDirectoryGroup,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    DisposableEffect(controller) {
        onDispose { controller.stopPolling() }
    }
    WandInlinePanel(visible = true, growFrom = Alignment.Top) {
        WandCard(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = WandShapes.md,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(WandIcons.sparkle, contentDescription = null, tint = WandColors.brand)
                    Text(
                        "根据会话内容自动分组",
                        style = MaterialTheme.typography.titleSmall,
                        color = WandColors.textPrimary,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    WandIconButton(WandIcons.close, "收起自动分组", onClose)
                }
                Text(
                    group.workspaceCwd.ifBlank { group.workspaceName },
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    maxLines = 1,
                )
                OrganizationActions(controller)
                if (!controller.needsRecheck && (controller.phase == ConversationOrganizationController.Phase.Idle ||
                    controller.phase == ConversationOrganizationController.Phase.Error)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConversationOrganizationMode.entries.forEach { option ->
                            TextButton(onClick = { controller.selectMode(option) }) {
                                Text(
                                    option.label,
                                    color = if (controller.mode == option) WandColors.brand else WandColors.textMuted,
                                )
                            }
                        }
                    }
                    Text(controller.mode.detail, style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                }
                controller.preview?.takeIf {
                    controller.phase == ConversationOrganizationController.Phase.Prepared
                }?.let { preview ->
                    Text(
                        "会话 ${preview.sessionCount} 个 · 正文 ${preview.contentCharacters} 字符 · 预计模型请求 ${preview.estimatedModelRequests} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textSecondary,
                    )
                    Text(
                        "已有任务候选 ${preview.existingTaskCount} 个 · 跳过人工任务会话 ${preview.manualSessionCount} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textSecondary,
                    )
                }
                controller.job?.takeIf { controller.phase == ConversationOrganizationController.Phase.Running }?.let { running ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                        Text(
                            "${running.processedSessions}/${running.totalSessions} 个会话 · ${running.modelRequests} 个模型请求",
                            style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textSecondary,
                        )
                    }
                }
                if (controller.feedback.isNotBlank()) {
                    Text(
                        controller.errorMessage?.let { "${controller.feedback} $it" } ?: controller.feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (controller.phase == ConversationOrganizationController.Phase.Error) WandColors.danger else WandColors.textSecondary,
                    )
                }

            }
        }
    }
}
