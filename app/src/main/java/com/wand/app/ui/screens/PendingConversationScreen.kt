package com.wand.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.wand.app.ui.components.WandTextField
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.WandAgentEngine
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.agentToolOption
import com.wand.app.ui.ChatComposer
import com.wand.app.ui.SessionDraftStore
import com.wand.app.ui.StagedComposerAttachment
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

@Composable
internal fun PendingConversationScreen(
    api: WandApi,
    taskState: TaskListState,
    draftKey: String,
    cwd: String,
    workspaceId: String?,
    workspaceTaskId: String? = null,
    taskName: String? = null,
    initialEmployeeId: String? = null,
    initialCreateMode: Boolean = false,
    drafts: SessionDraftStore,
    onCancel: () -> Unit,
    onCreated: (SessionSnapshot, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val composer = remember(draftKey, drafts) {
        ChatComposer(
            sessionId = draftKey,
            drafts = drafts,
            parentScope = scope,
            ready = { true },
            send = { error("pending composer cannot send before a session exists") },
            notice = { message -> taskState.reportMutationError(message) },
        )
    }
    DisposableEffect(composer) {
        onDispose { composer.shutdown() }
    }
    var target by remember(draftKey) { mutableStateOf(WorkspaceSessionTarget.fromPreference(taskState.defaultProvider, taskState.defaultEngine) ?: WorkspaceSessionTarget.Claude) }
    var model by remember(draftKey) { mutableStateOf("default") }
    var effort by remember(draftKey) { mutableStateOf(taskState.defaultThinkingEffort) }
    var executionMode by remember(draftKey) { mutableStateOf(taskState.defaultMode) }
    var selectedEmployeeId by remember(draftKey) { mutableStateOf(initialEmployeeId) }
    var employees by remember(draftKey) { mutableStateOf<List<com.wand.app.data.SiliconEmployee>?>(null) }
    var employeesLoading by remember(draftKey) { mutableStateOf(true) }
    var employeesError by remember(draftKey) { mutableStateOf<String?>(null) }
    var employeeRetry by remember(draftKey) { mutableStateOf(0) }
    var manuallySpecifiedExecution by remember(draftKey) { mutableStateOf(false) }
    val selectedEmployee = employees?.firstOrNull { it.id == selectedEmployeeId && !it.archived && it.agents.isNotEmpty() }
    var selectedEmployeeCandidate by remember(draftKey, selectedEmployeeId) {
        mutableStateOf(employees?.firstOrNull { it.id == selectedEmployeeId }?.agents?.firstOrNull())
    }
    var models by remember(draftKey) { mutableStateOf<com.wand.app.data.ModelsResponse?>(null) }
    var modelsError by remember(draftKey) { mutableStateOf<String?>(null) }
    var modelsLoading by remember(draftKey) { mutableStateOf(false) }
    var modelsRetry by remember(draftKey) { mutableStateOf(0) }
    val selectedAgent = remember(target, model, effort, selectedEmployeeId, selectedEmployeeCandidate, manuallySpecifiedExecution) {
        val employeeCandidate = selectedEmployeeCandidate
        val provider = target.provider
        when {
            selectedEmployeeId != null && !manuallySpecifiedExecution -> employeeCandidate
            selectedEmployeeId != null && employeeCandidate != null -> employeeCandidate.copy(
                provider = provider ?: employeeCandidate.provider,
                model = model,
                thinkingEffort = effort,
                engine = target.engine?.raw,
            )
            provider != null -> BoardTaskAgent(
                provider = provider,
                model = model,
                thinkingEffort = effort,
                engine = target.engine?.raw,
            )
            else -> null
        }
    }
    var selectedKind by remember(draftKey) { mutableStateOf(taskState.defaultSessionKind) }
    LaunchedEffect(target, selectedEmployeeId) {
        if (target.isShell) selectedKind = WorkspaceSessionKind.Pty
        else if (target.isSdk) selectedKind = WorkspaceSessionKind.Structured
        else if (selectedEmployeeId == null) selectedKind = taskState.defaultSessionKind
        if (selectedEmployeeId == null) {
            model = "default"
            effort = taskState.defaultThinkingEffort
            executionMode = taskState.defaultMode
        }
    }
    LaunchedEffect(employees, selectedEmployeeId) {
        if (selectedEmployeeId == null) return@LaunchedEffect
        val employee = employees?.firstOrNull { it.id == selectedEmployeeId && !it.archived }
        val candidate = employee?.agents?.firstOrNull()
        if (employee != null && candidate == null) {
            employees = employees.orEmpty().filterNot { it.id == selectedEmployeeId }
            selectedEmployeeId = null
            selectedEmployeeCandidate = null
            manuallySpecifiedExecution = false
        } else if (candidate != null) {
            selectedEmployeeCandidate = candidate
            if (!manuallySpecifiedExecution) {
                target = WorkspaceSessionTarget.fromPreference(candidate.provider, candidate.engine)
                    ?: WorkspaceSessionTarget.Claude
                model = candidate.model
                effort = candidate.thinkingEffort
                executionMode = candidate.mode
                selectedKind = WorkspaceSessionKind.Structured
            }
        }
    }
    val selectedMode = if (selectedEmployeeId != null && !manuallySpecifiedExecution) null else executionMode
    val selectedModel = if (selectedEmployeeId != null && !manuallySpecifiedExecution) null else model.takeUnless { it == "default" }
    val selectedEffort = if (selectedEmployeeId != null && !manuallySpecifiedExecution) null else effort
    var busy by remember(draftKey) { mutableStateOf(false) }
    var error by remember(draftKey) { mutableStateOf<String?>(null) }
    var createMode by remember(draftKey) { mutableStateOf(initialCreateMode) }
    var taskTitle by remember(draftKey) { mutableStateOf("") }
    var creationStarted by remember(draftKey) {
        mutableStateOf(drafts.pendingCreationUnconfirmed(draftKey) || drafts.pendingCreatedTask(draftKey) != null || drafts.pendingCreatedSession(draftKey) != null || drafts.pendingTaskWindow(draftKey) != null)
    }
    var directoryOpen by remember(draftKey) { mutableStateOf(false) }
    var selectedCwd by remember(draftKey) { mutableStateOf(cwd.ifBlank { taskState.defaultCwd.orEmpty() }) }
    val picker = rememberAttachmentPickerActions { uris ->
        uris.take((5 - composer.stagedAttachments.size - composer.attachments.size).coerceAtLeast(0)).forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull()?.takeIf(String::isNotBlank) ?: "attachment"
            composer.stageAttachments(listOf(StagedComposerAttachment(uri.toString(), name, context.contentResolver.getType(uri).orEmpty())))
        }
    }

    LaunchedEffect(draftKey, employeeRetry) {
        employeesLoading = true
        employeesError = null
        try {
            val requestedEmployeeId = initialEmployeeId ?: selectedEmployeeId
            val available = api.listSiliconEmployees(includeArchived = false)
                .filter { !it.archived && it.agents.isNotEmpty() }
            employees = available
            if (selectedEmployeeId == requestedEmployeeId && requestedEmployeeId != null && available.none { it.id == requestedEmployeeId }) {
                selectedEmployeeId = null
                selectedEmployeeCandidate = null
                manuallySpecifiedExecution = false
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            employeesError = failure.message ?: "无法加载员工，请重试。"
        } finally {
            employeesLoading = false
        }
    }
    LaunchedEffect(draftKey) {
        taskState.loadCreationDefaults()
        if (selectedCwd.isBlank()) selectedCwd = taskState.defaultCwd.orEmpty()
    }
    LaunchedEffect(target, selectedEmployeeCandidate, draftKey, modelsRetry, manuallySpecifiedExecution) {
        val provider = target.provider
        if (provider == null || (selectedEmployeeId != null && !manuallySpecifiedExecution)) { models = null; modelsError = null; return@LaunchedEffect }
        modelsLoading = true
        modelsError = null
        try {
            models = api.models()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            modelsError = failure.message ?: "无法加载模型目录，请重试。"
        } finally {
            modelsLoading = false
        }
    }

    val hasKnownSession = drafts.pendingCreatedSession(draftKey) != null || drafts.pendingTaskWindow(draftKey) != null
    val hasKnownTask = createMode && drafts.pendingCreatedTask(draftKey) != null
    val canResumeCreation = hasKnownSession || hasKnownTask
    val canCreateEmptyShell = target.isShell && composer.draft.isBlank() &&
        composer.attachments.isEmpty() && composer.stagedAttachments.isEmpty()
    val employeeReady = selectedEmployeeId == null || (!employeesLoading && selectedEmployee != null)
    val canCreate = employeeReady && if (target.isShell) canCreateEmptyShell else composer.canSubmit
    val canSubmit = canCreate && !busy && (!creationStarted || canResumeCreation)
    val submit = {
        if (!busy && (!creationStarted || canResumeCreation) && canCreate) {
            busy = true
            error = null
            creationStarted = true
            scope.launch {
                var creatingSession = false
                try {
                    val existingId = drafts.pendingCreatedSession(draftKey)
                    val existingTaskWindow = drafts.pendingTaskWindow(draftKey)
                    if (drafts.pendingCreationUnconfirmed(draftKey) && existingId == null && existingTaskWindow == null) {
                        error("创建结果仍未确认，请刷新任务列表和会话列表后核对；不要重复发送。")
                        creationStarted = true
                        return@launch
                    }
                    if (existingId != null || existingTaskWindow != null) creationStarted = true
                    val targetWorkspaceId = workspaceId?.takeUnless { it == GLOBAL_WORKSPACE_ID }
                    val session = when {
                        existingId != null -> api.getSession(existingId)
                        existingTaskWindow != null -> api.getSession(existingTaskWindow).also {
                            drafts.setPendingCreatedSession(draftKey, it.id)
                        }
                        else -> {
                            val cwdValue = selectedCwd.trim()
                            if (cwdValue.isBlank()) error("请选择运行目录")
                            val actualWorkspaceId = workspaceTaskId?.let { id -> taskState.groups.flatMap { it.tasks }.firstOrNull { it.id == id }?.task?.workspaceId }
                                ?: targetWorkspaceId
                            creatingSession = true
                            val taskId = if (createMode) {
                                val existingTaskId = drafts.pendingCreatedTask(draftKey)
                                if (existingTaskId != null) existingTaskId else {
                                    drafts.setPendingCreationUnconfirmed(draftKey, true)
                                    try {
                                        taskState.createTask(
                                            name = taskTitle.trim(), cwd = cwdValue, worktree = false,
                                            workspaceId = actualWorkspaceId,
                                        )?.task?.id?.also { drafts.setPendingCreatedTask(draftKey, it) }
                                            ?: run {
                                                if (!taskState.mutationOutcomeUnconfirmed && !taskState.consumeTaskCreationOutcomeUnconfirmed()) {
                                                    drafts.setPendingCreationUnconfirmed(draftKey, false)
                                                }
                                                error(taskState.mutationError ?: "创建任务失败")
                                            }
                                    } catch (failure: Exception) {
                                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                                        if (!taskState.mutationOutcomeUnconfirmed) drafts.setPendingCreationUnconfirmed(draftKey, false)
                                        throw failure
                                    }
                                }
                            } else workspaceTaskId
                            drafts.setPendingCreationUnconfirmed(draftKey, true)
                            val created = try {
                                if (taskId != null) {
                                    taskState.createTaskWindow(
                                        taskId, target, selectedKind,
                                        model = selectedModel, thinkingEffort = selectedEffort, mode = selectedMode,
                                        employeeId = selectedEmployeeId,
                                        overrideCli = selectedEmployeeId != null && manuallySpecifiedExecution,
                                    )
                                        ?: run {
                                            if (!taskState.mutationOutcomeUnconfirmed) drafts.setPendingCreationUnconfirmed(draftKey, false)
                                            error(taskState.mutationError ?: "创建任务会话失败")
                                        }
                                } else {
                                    taskState.createPendingConversationSession(
                                        workspaceId = actualWorkspaceId, cwd = cwdValue, target = target,
                                        kind = selectedKind,
                                        model = selectedModel, thinkingEffort = selectedEffort, mode = selectedMode,
                                        employeeId = selectedEmployeeId,
                                        overrideCli = selectedEmployeeId != null && manuallySpecifiedExecution,
                                    ) ?: run {
                                        if (!taskState.mutationOutcomeUnconfirmed) drafts.setPendingCreationUnconfirmed(draftKey, false)
                                        error(taskState.mutationError ?: "创建会话失败")
                                    }
                                }
                            } catch (failure: Exception) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                if (!taskState.mutationOutcomeUnconfirmed) drafts.setPendingCreationUnconfirmed(draftKey, false)
                                throw failure
                            }
                            drafts.setPendingCreatedSession(draftKey, created.id)
                            drafts.setPendingCreationUnconfirmed(draftKey, false)
                            created
                        }
                    }
                    val staged = composer.stagedAttachments
                    if (staged.isNotEmpty()) {
                        if (drafts.pendingUploadUnconfirmed(draftKey, staged)) {
                            error("附件上传结果未确认，请先进入会话核对附件；勿重复上传。")
                        }
                        try {
                            val files = uploadComposerAttachments(
                                context, api, session.id, staged.map { Uri.parse(it.uri) },
                                (5 - composer.attachments.size).coerceAtLeast(0),
                                onRequestStarted = { drafts.markPendingUploadUnconfirmed(draftKey, staged) },
                            )
                            if (!composer.acceptUploadedStagedAttachments(staged, files)) error("附件结果已过期，请重新打开会话核对")
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            if (com.wand.app.data.isDefiniteRequestRejection(failure)) {
                                drafts.releasePendingUploadAfterDefiniteRejection(draftKey, staged)
                            }
                            throw failure
                        }
                    }
                    if (!drafts.movePendingDraft(draftKey, session.id)) {
                        error = "新会话已有独立草稿，已保留本页输入；请先打开会话核对。"
                        creationStarted = true
                        onCreated(session, false)
                        return@launch
                    }
                    drafts.clearPendingCreation(draftKey)
                    onCreated(session, true)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    error = failure.message ?: "创建会话失败"
                    val knownSessionId = drafts.pendingCreatedSession(draftKey)
                    val unconfirmed = taskState.mutationOutcomeUnconfirmed
                    if (creatingSession && unconfirmed) drafts.setPendingCreationUnconfirmed(draftKey, true)
                    if (!unconfirmed && knownSessionId == null && drafts.pendingTaskWindow(draftKey) == null && drafts.pendingCreatedTask(draftKey) == null) {
                        drafts.setPendingCreationUnconfirmed(draftKey, false)
                    }
                    creationStarted = knownSessionId != null || drafts.pendingTaskWindow(draftKey) != null || drafts.pendingCreatedTask(draftKey) != null || drafts.pendingCreationUnconfirmed(draftKey)
                } finally {
                    busy = false
                }
            }
        }
    }

    if (directoryOpen) WorkspaceDirectoryPicker(
        api = api,
        currentCwd = selectedCwd,
        onSelect = { selection ->
            selectedCwd = selection.cwd
            directoryOpen = false
        },
        onDismiss = { directoryOpen = false },
    )

    Column(Modifier.fillMaxSize().imePadding()) {
        WandDetailTopBar(
            title = "新建对话",
            subtitle = taskName ?: "输入工作内容，发送后创建会话",
            actions = { TextButton(onClick = onCancel, enabled = !busy) { Text("取消") } },
        )
        if (employeesLoading) CircularProgressIndicator(Modifier.padding(8.dp))
        employeesError?.let { message ->
            Text(message, color = WandColors.danger, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { employeesError = null; employeeRetry += 1 }) { Text("重试加载员工") }
        }
        selectedEmployee?.let { employee ->
            Text(
                "员工候选执行：${employee.agents.joinToString(" → ") { candidate ->
                    com.wand.app.data.agentToolLabel(candidate.provider, candidate.engine)
                }}",
                color = WandColors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (employeesError == null && employees?.isNotEmpty() == true) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    selectedEmployeeId = null
                    selectedEmployeeCandidate = null
                    manuallySpecifiedExecution = false
                    target = WorkspaceSessionTarget.fromPreference(taskState.defaultProvider, taskState.defaultEngine)
                        ?: WorkspaceSessionTarget.Claude
                    model = "default"
                    effort = taskState.defaultThinkingEffort
                    executionMode = taskState.defaultMode
                    selectedKind = taskState.defaultSessionKind
                }, enabled = !busy) { Text(if (selectedEmployeeId == null) "● CLI" else "CLI") }
                employees.orEmpty().forEach { employee ->
                    TextButton(onClick = {
                        selectedEmployeeId = employee.id
                        selectedEmployeeCandidate = employee.agents.firstOrNull()
                        manuallySpecifiedExecution = false
                    }, enabled = !busy) {
                        Text(if (selectedEmployeeId == employee.id) "● ${employee.name}" else employee.name)
                    }
                }
            }
        }
        if (selectedEmployee != null && manuallySpecifiedExecution) {
            TextButton(onClick = {
                manuallySpecifiedExecution = false
                selectedEmployeeCandidate = selectedEmployee.agents.firstOrNull()
                selectedEmployeeCandidate?.let { candidate ->
                    target = WorkspaceSessionTarget.fromPreference(candidate.provider, candidate.engine) ?: WorkspaceSessionTarget.Claude
                    model = candidate.model
                    effort = candidate.thinkingEffort
                    executionMode = candidate.mode
                    selectedKind = WorkspaceSessionKind.Structured
                }
            }, enabled = !busy) { Text("恢复${selectedEmployee.name}的候选执行设置") }
        } else if (selectedEmployeeId != null && employees != null && selectedEmployee == null) {
            Text("所选员工当前不可用；已改用 CLI。", color = WandColors.danger, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (workspaceTaskId == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { createMode = false }) { Text(if (!createMode) "● 不归属任务" else "不归属任务") }
                TextButton(onClick = { createMode = true }) { Text(if (createMode) "● 新建任务" else "新建任务") }
            }
        }
        if (createMode) WandTextField(
            value = taskTitle, onValueChange = { taskTitle = it }, label = "任务名称",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
        )
        if (workspaceTaskId == null) TextButton(onClick = { directoryOpen = true }, enabled = !busy) {
            Text("运行目录：${selectedCwd.ifBlank { "请选择" }}")
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = {
                val options = WorkspaceSessionTarget.OPTIONS.filter { it != WorkspaceSessionTarget.Shell || selectedEmployeeId == null }
                val next = options[(options.indexOf(target).coerceAtLeast(0) + 1) % options.size]
                target = next
                model = "default"
                effort = "off"
                executionMode = "default"
                if (selectedEmployeeId != null) manuallySpecifiedExecution = true
            }, enabled = !busy) {
                val label = target.provider?.let { com.wand.app.data.agentToolLabel(it, target.engine?.raw) } ?: target.label
                Text("工具：$label")
            }
            if (target != WorkspaceSessionTarget.Shell) {
                val kinds = if (target.isSdk) listOf(WorkspaceSessionKind.Structured) else WorkspaceSessionKind.entries
                TextButton(onClick = {
                    selectedKind = kinds[(kinds.indexOf(selectedKind).coerceAtLeast(0) + 1) % kinds.size]
                    if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                }, enabled = !busy && kinds.size > 1) { Text("模式：${selectedKind.label}") }
            }
            if (target.isShell && (composer.draft.isNotBlank() || composer.attachments.isNotEmpty() || composer.stagedAttachments.isNotEmpty())) {
                Text("空白终端不接收首条输入；请移除文字和附件后打开。", color = WandColors.textSecondary)
            }
            selectedAgent?.let { agent ->
                val modelOptions = com.wand.app.data.boardAgentModelOptions(models, agent.provider)
                    .map { it.id to it.label }.distinctBy { it.first }.toMutableList()
                if (modelOptions.none { it.first == model }) modelOptions.add(model to if (model == "default") "默认模型" else model)
                TextButton(onClick = {
                val current = modelOptions.indexOfFirst { it.first == model }.coerceAtLeast(0)
                model = modelOptions[(current + 1) % modelOptions.size].first
                if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                val efforts = com.wand.app.ui.thinkingEffortOptions(
                    agent.provider, model, models?.defaultModelFor(agent.provider),
                    models?.modelsFor(agent.provider).orEmpty(), models?.thinkingEffortsFor(agent.provider).orEmpty(),
                )
                if (efforts.none { it.id == effort }) effort = "off"
            }, enabled = !busy && modelOptions.isNotEmpty()) {
                Text("模型：${modelOptions.firstOrNull { it.first == model }?.second ?: model}")
            }
                val effortOptions = com.wand.app.ui.thinkingEffortOptions(
                    agent.provider, model, models?.defaultModelFor(agent.provider),
                    models?.modelsFor(agent.provider).orEmpty(), models?.thinkingEffortsFor(agent.provider).orEmpty(),
                )
                TextButton(onClick = {
                    val current = effortOptions.indexOfFirst { it.id == effort }.coerceAtLeast(0)
                    effort = effortOptions[(current + 1) % effortOptions.size].id
                    if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                }, enabled = !busy && effortOptions.isNotEmpty()) {
                    Text("思考：${effortOptions.firstOrNull { it.id == effort }?.shortLabel ?: effort}")
                }
            }
        }
        if (modelsLoading) CircularProgressIndicator(Modifier.padding(8.dp))
        modelsError?.let { message ->
            Text(message, color = WandColors.danger, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { modelsError = null; modelsRetry += 1 }) { Text("重试加载模型") }
        }
        selectedAgent?.let { agent ->
            val options = com.wand.app.data.boardAgentModelOptions(models, agent.provider)
                .map { it.id to it.label }.distinctBy { it.first }.toMutableList()
            if (options.none { it.first == model }) options.add(model to if (model == "default") "默认模型" else model)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.take(8).forEach { (id, label) ->
                    TextButton(onClick = {
                        model = id
                        if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                    }, enabled = !busy) { Text(if (id == model) "● $label" else label) }
                }
            }
        }
        val attachmentPicker = picker
        SharedMessageComposer(
            backdrop = null,
            sessionKey = draftKey,
            draft = composer.draft,
            onDraftChange = composer::editDraft,
            attachments = composer.attachments,
            stagedAttachments = composer.stagedAttachments,
            onRemoveStagedAttachment = { item -> composer.stageAttachments(emptyList()); drafts.setStagedAttachments(draftKey, composer.stagedAttachments.filterNot { it == item }) },
            baseUrl = api.baseUrl,
            onRemoveAttachment = composer::removeAttachment,
            uploading = busy || picker.open,
            attachOpen = false,
            onAttachOpenChange = {},
            onPickPhoto = attachmentPicker.pickPhoto,
            onPickFile = attachmentPicker.pickFile,
            canSubmit = canSubmit,
            onSend = submit,
            allowRefocus = true,
            onExpandedChange = {},
            trailingActions = { _, _ -> },
            controls = {},
        )
        error?.let { Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        taskState.mutationError?.let { message -> if (error == null) Text(message, color = WandColors.danger, modifier = Modifier.padding(16.dp)) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            if (busy) CircularProgressIndicator()
            WandButton(label = if (target.isShell && canCreateEmptyShell) "打开终端" else if (creationStarted && !hasKnownSession) "正在确认…" else "发送", onClick = submit, enabled = canSubmit, icon = WandIcons.send)
        }
    }
}
