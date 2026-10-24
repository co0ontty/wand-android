package com.wand.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import com.wand.app.data.SESSION_MODE_OPTIONS
import com.wand.app.data.supportedSessionModeIds
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandInlinePanelAction
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
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.ui.sessionCwdLeaf
import com.wand.app.ui.ChatComposer
import com.wand.app.ui.SessionDraftStore
import com.wand.app.ui.StagedComposerAttachment
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

private enum class PendingChoice { Object, Tool, Kind, Task, TaskName, Model, Mode, Thinking }

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
    taskWorktree: Boolean = false,
    creationStore: com.wand.app.ui.PendingCreationStore,
    creationKey: String,
    drafts: SessionDraftStore,
    onCancel: () -> Unit,
    onCreated: (SessionSnapshot, Boolean) -> Unit,
    /**
     * 空任务页把这段空白对话内嵌进任务正文：任务页已有一条顶栏，
     * 这里不再重复标题栏与取消入口。
     */
    embedded: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val inputPresentation = remember(draftKey) { ChatInputPresentation() }
    var choice by remember(draftKey) { mutableStateOf<PendingChoice?>(null) }
    var attachOpen by remember(draftKey) { mutableStateOf(false) }
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
    var selectedWorktree by remember(draftKey) { mutableStateOf(taskWorktree) }
    var worktreeOverridden by remember(draftKey) { mutableStateOf(taskWorktree) }
    LaunchedEffect(taskState.defaultTaskWorktree) {
        if (!worktreeOverridden) selectedWorktree = taskState.defaultTaskWorktree
    }
    var persistedCreation by remember(creationStore, creationKey) {
        mutableStateOf(creationStore.state(creationKey))
    }
    var acceptedTaskDetail by remember(creationStore, creationKey) {
        mutableStateOf<com.wand.app.data.WorkspaceTaskDetail?>(null)
    }
    var creationStarted by remember(draftKey, creationKey) {
        mutableStateOf(
            persistedCreation.unconfirmed || persistedCreation.taskId != null || persistedCreation.sessionId != null ||
                drafts.pendingCreationUnconfirmed(draftKey) || drafts.pendingCreatedTask(draftKey) != null ||
                drafts.pendingCreatedSession(draftKey) != null || drafts.pendingTaskWindow(draftKey) != null,
        )
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
    var defaultsReady by remember(draftKey) { mutableStateOf(false) }
    var defaultsError by remember(draftKey) { mutableStateOf(false) }
    var defaultsRetry by remember(draftKey) { mutableStateOf(0) }
    LaunchedEffect(draftKey, defaultsRetry) {
        defaultsReady = false
        defaultsError = false
        defaultsReady = taskState.loadCreationDefaults()
        defaultsError = !defaultsReady
        if (defaultsReady) {
            if (selectedCwd.isBlank()) selectedCwd = taskState.defaultCwd
                ?: taskState.recentPaths.firstOrNull()?.path.orEmpty()
            if (!manuallySpecifiedExecution && selectedEmployeeId == null) {
                target = WorkspaceSessionTarget.fromPreference(taskState.defaultProvider, taskState.defaultEngine)
                    ?: WorkspaceSessionTarget.Claude
                selectedKind = taskState.defaultSessionKind
                effort = taskState.defaultThinkingEffort
                executionMode = taskState.defaultMode
            }
        }
    }
    LaunchedEffect(target, selectedEmployeeCandidate, draftKey, modelsRetry, manuallySpecifiedExecution) {
        val provider = target.provider
        if (provider == null) { models = null; modelsError = null; modelsLoading = false; return@LaunchedEffect }
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

    val hasKnownSession = drafts.pendingCreatedSession(draftKey) != null || drafts.pendingTaskWindow(draftKey) != null || persistedCreation.sessionId != null
    val hasKnownTask = drafts.pendingCreatedTask(draftKey) != null || persistedCreation.taskId != null
    val canResumeCreation = hasKnownSession || hasKnownTask
    val canCreateEmptyShell = target.isShell && composer.draft.isBlank() &&
        composer.attachments.isEmpty() && composer.stagedAttachments.isEmpty()
    val employeeReady = selectedEmployeeId == null || (!employeesLoading && selectedEmployee != null)
    val canCreate = (hasKnownSession || (defaultsReady && employeeReady)) &&
        if (target.isShell) canCreateEmptyShell else composer.canSubmit
    val canSubmit = canCreate && !busy && (!creationStarted || canResumeCreation)
    val submit = {
        if (!busy && (!creationStarted || canResumeCreation) && canCreate) {
            busy = true
            error = null
            creationStarted = true
            val submittedDescription = composer.draft.trim()
            scope.launch {
                var creatingSession = false
                var acceptedTaskNotPersisted = false
                var acceptedSessionNotPersisted = false
                try {
                    persistedCreation = creationStore.state(creationKey)
                    val existingId = drafts.pendingCreatedSession(draftKey) ?: persistedCreation.sessionId
                    val existingTaskWindow = drafts.pendingTaskWindow(draftKey)
                    val existingTaskId = drafts.pendingCreatedTask(draftKey) ?: persistedCreation.taskId
                    if (persistedCreation.blocksCreation(
                            volatileUnconfirmed = drafts.pendingCreationUnconfirmed(draftKey),
                            hasKnownSession = existingId != null || existingTaskWindow != null,
                        )) {
                        error("创建结果仍未确认，请刷新任务列表和会话列表后核对；不要重复发送。")
                    }
                    if (existingId != null || existingTaskWindow != null || existingTaskId != null) creationStarted = true
                    val targetWorkspaceId = workspaceId?.takeUnless { it == GLOBAL_WORKSPACE_ID }
                    val resumedTaskDetail = if (existingTaskId != null && existingTaskWindow == null && existingId == null) {
                        try {
                            acceptedTaskDetail ?: api.workspaceTask(existingTaskId).also { acceptedTaskDetail = it }
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            if (failure is com.wand.app.data.WandApiException && failure.status == 404) {
                                creationStore.definiteRejection(creationKey)
                                persistedCreation = creationStore.state(creationKey)
                                drafts.setPendingCreatedTask(draftKey, null)
                                drafts.setPendingCreationUnconfirmed(draftKey, false)
                            }
                            throw failure
                        }
                    } else null
                    val session = when {
                        existingId != null -> api.getSession(existingId).also {
                            creationStore.sessionAccepted(creationKey, it.id)
                            persistedCreation = creationStore.state(creationKey)
                        }
                        existingTaskWindow != null -> api.getSession(existingTaskWindow).also {
                            creationStore.sessionAccepted(creationKey, it.id)
                            persistedCreation = creationStore.state(creationKey)
                            drafts.setPendingCreatedSession(draftKey, it.id)
                        }
                        else -> {
                            val cwdValue = (
                                resumedTaskDetail?.cwd
                                    ?: persistedCreation.taskCwd
                                    ?: selectedCwd.ifBlank { taskState.defaultCwd.orEmpty() }
                            ).trim()
                            if (cwdValue.isBlank()) error("请选择运行目录")
                            val actualWorkspaceId = resumedTaskDetail?.workspaceId
                                ?: persistedCreation.taskWorkspaceId
                                ?: workspaceTaskId?.let { id -> taskState.groups.flatMap { it.tasks }.firstOrNull { it.id == id }?.task?.workspaceId }
                                ?: targetWorkspaceId
                            creatingSession = true
                            val taskId = if (existingTaskId != null || createMode) {
                                if (existingTaskId != null) {
                                    existingTaskId
                                } else {
                                    creationStore.markUnconfirmed(creationKey, com.wand.app.ui.PendingCreationOperation.TASK)
                                    drafts.setPendingCreationUnconfirmed(draftKey, true)
                                    try {
                                        val acceptedTask = taskState.createTask(
                                            name = taskTitle.trim(), cwd = cwdValue, worktree = selectedWorktree,
                                            workspaceId = actualWorkspaceId,
                                            description = submittedDescription,
                                        )
                                        acceptedTask?.task?.id?.also { taskId ->
                                            try {
                                                creationStore.recordTaskAccepted(
                                                    creationKey,
                                                    taskId,
                                                    taskCwd = acceptedTask.task.cwd.ifBlank { acceptedTask.workspace.cwd },
                                                    taskWorkspaceId = acceptedTask.task.workspaceId,
                                                )
                                            } catch (failure: Exception) {
                                                acceptedTaskNotPersisted = true
                                                throw failure
                                            }
                                            drafts.setPendingCreatedTask(draftKey, taskId)
                                            persistedCreation = creationStore.state(creationKey)
                                        }
                                            ?: run {
                                                if (!taskState.mutationOutcomeUnconfirmed && !taskState.consumeTaskCreationOutcomeUnconfirmed()) {
                                                    creationStore.definiteRejection(creationKey)
                                                    persistedCreation = creationStore.state(creationKey)
                                                    drafts.setPendingCreationUnconfirmed(draftKey, false)
                                                }
                                                error(taskState.mutationError ?: "创建任务失败")
                                            }
                                    } catch (failure: Exception) {
                                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                                        if (!acceptedTaskNotPersisted && !taskState.mutationOutcomeUnconfirmed && creationStore.state(creationKey).operation == com.wand.app.ui.PendingCreationOperation.TASK) {
                                            creationStore.definiteRejection(creationKey)
                                            persistedCreation = creationStore.state(creationKey)
                                            drafts.setPendingCreationUnconfirmed(draftKey, false)
                                        }
                                        throw failure
                                    }
                                }
                            } else workspaceTaskId
                            creationStore.markUnconfirmed(creationKey, com.wand.app.ui.PendingCreationOperation.SESSION)
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
                                            if (!taskState.mutationOutcomeUnconfirmed) {
                                                creationStore.definiteRejection(creationKey)
                                                persistedCreation = creationStore.state(creationKey)
                                                drafts.setPendingCreationUnconfirmed(draftKey, false)
                                            }
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
                                        if (!taskState.mutationOutcomeUnconfirmed) {
                                            creationStore.definiteRejection(creationKey)
                                            persistedCreation = creationStore.state(creationKey)
                                            drafts.setPendingCreationUnconfirmed(draftKey, false)
                                        }
                                        error(taskState.mutationError ?: "创建会话失败")
                                    }
                                }
                            } catch (failure: Exception) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                if (!taskState.mutationOutcomeUnconfirmed && creationStore.state(creationKey).operation == com.wand.app.ui.PendingCreationOperation.SESSION) {
                                    creationStore.definiteRejection(creationKey)
                                    persistedCreation = creationStore.state(creationKey)
                                    drafts.setPendingCreationUnconfirmed(draftKey, false)
                                }
                                throw failure
                            }
                            try {
                                creationStore.sessionAccepted(creationKey, created.id)
                            } catch (failure: Exception) {
                                acceptedSessionNotPersisted = true
                                throw failure
                            }
                            persistedCreation = creationStore.state(creationKey)
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
                    creationStore.clear(creationKey)
                    drafts.clearPendingCreation(draftKey)
                    persistedCreation = com.wand.app.ui.PendingCreationState()
                    onCreated(session, true)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    error = failure.message ?: "创建会话失败"
                    val knownSessionId = drafts.pendingCreatedSession(draftKey)
                    val unconfirmed = taskState.mutationOutcomeUnconfirmed
                    if (creatingSession && unconfirmed) drafts.setPendingCreationUnconfirmed(draftKey, true)
                    if (!unconfirmed && !acceptedTaskNotPersisted && !acceptedSessionNotPersisted && knownSessionId == null && drafts.pendingTaskWindow(draftKey) == null && drafts.pendingCreatedTask(draftKey) == null) {
                        drafts.setPendingCreationUnconfirmed(draftKey, false)
                    }
                    if (acceptedTaskNotPersisted || acceptedSessionNotPersisted) {
                        drafts.setPendingCreationUnconfirmed(draftKey, true)
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

    val optionsEnabled = !busy && !creationStarted
    val modelOptions = selectedAgent?.let { agent ->
        com.wand.app.data.boardAgentModelOptions(models, agent.provider)
            .map { it.id to it.label }.distinctBy { it.first }.toMutableList().apply {
                if (none { it.first == model }) add(model to if (model == "default") "默认模型" else model)
            }
    }.orEmpty()
    val effortOptions = selectedAgent?.let { agent ->
        com.wand.app.ui.thinkingEffortOptions(
            agent.provider, model, models?.defaultModelFor(agent.provider),
            models?.modelsFor(agent.provider).orEmpty(), models?.thinkingEffortsFor(agent.provider).orEmpty(),
        )
    }.orEmpty()
    val openChoice: (PendingChoice) -> Unit = { next ->
        if (optionsEnabled) {
            attachOpen = false
            focusManager.clearFocus()
            choice = next
        }
    }
    val useTarget: (WorkspaceSessionTarget) -> Unit = { next ->
        target = next
        model = "default"
        effort = taskState.defaultThinkingEffort
        executionMode = taskState.defaultMode
        selectedKind = when {
            next.isShell -> WorkspaceSessionKind.Pty
            next.isSdk -> WorkspaceSessionKind.Structured
            else -> taskState.defaultSessionKind
        }
    }
    val restoreEmployee = {
        selectedEmployee?.agents?.firstOrNull()?.let { candidate ->
            manuallySpecifiedExecution = false
            selectedEmployeeCandidate = candidate
            target = WorkspaceSessionTarget.fromPreference(candidate.provider, candidate.engine) ?: WorkspaceSessionTarget.Claude
            model = candidate.model
            effort = candidate.thinkingEffort
            executionMode = candidate.mode
            selectedKind = WorkspaceSessionKind.Structured
        }
        Unit
    }
    val closeChoice = { choice = null }
    when (choice) {
        PendingChoice.Object -> ComposerChoiceSheet(
            title = "执行对象",
            options = employees.orEmpty().map { "employee:${it.id}" to it.name } +
                WorkspaceSessionTarget.OPTIONS.map { "tool:${it.name}" to it.label },
            selected = selectedEmployeeId?.let { "employee:$it" } ?: "tool:${target.name}",
            searchable = true, searchPlaceholder = "搜索员工或工具",
            onSelect = { id ->
                if (id.startsWith("employee:")) {
                    selectedEmployeeId = id.removePrefix("employee:")
                    selectedEmployeeCandidate = employees.orEmpty().firstOrNull { it.id == selectedEmployeeId }?.agents?.firstOrNull()
                    manuallySpecifiedExecution = false
                } else {
                    selectedEmployeeId = null
                    selectedEmployeeCandidate = null
                    manuallySpecifiedExecution = false
                    WorkspaceSessionTarget.OPTIONS.firstOrNull { "tool:${it.name}" == id }?.let(useTarget)
                }
                closeChoice()
            }, onDismiss = closeChoice,
        )
        PendingChoice.Tool -> ComposerChoiceSheet(
            title = "执行工具",
            options = WorkspaceSessionTarget.OPTIONS.filter { !it.isShell || selectedEmployeeId == null }.map { it.name to it.label },
            selected = target.name,
            onSelect = { id ->
                WorkspaceSessionTarget.OPTIONS.firstOrNull { it.name == id }?.let(useTarget)
                if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                closeChoice()
            }, onDismiss = closeChoice,
        )
        PendingChoice.Kind -> ComposerChoiceSheet(
            title = "会话模式",
            options = (if (target.isSdk) listOf(WorkspaceSessionKind.Structured) else WorkspaceSessionKind.entries).map { it.name to it.label },
            selected = selectedKind.name,
            onSelect = { id ->
                WorkspaceSessionKind.entries.firstOrNull { it.name == id }?.let { selectedKind = it }
                if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                closeChoice()
            }, onDismiss = closeChoice,
        )
        PendingChoice.Task -> ComposerChoiceSheet(
            title = "任务归属",
            options = buildList {
                add("none" to "不归属任务")
                add("new" to "新建任务")
                if (createMode) {
                    add("name" to "任务名称 · ${taskTitle.ifBlank { "按首条消息命名" }}")
                    add("shared" to if (!selectedWorktree) "共用目录 · 当前" else "共用目录")
                    add("worktree" to if (selectedWorktree) "独立工作树 · 当前" else "独立工作树")
                }
            },
            selected = if (createMode) "new" else "none",
            onSelect = { id ->
                when (id) {
                    "none" -> createMode = false
                    "new" -> createMode = true
                    "name" -> choice = PendingChoice.TaskName
                    "shared", "worktree" -> { selectedWorktree = id == "worktree"; worktreeOverridden = true }
                }
                if (id != "name") closeChoice()
            }, onDismiss = closeChoice,
        )
        PendingChoice.TaskName -> WandDialog(
            title = "任务名称", onDismissRequest = closeChoice,
            confirm = WandDialogAction("完成", closeChoice),
        ) {
            WandTextField(value = taskTitle, onValueChange = { taskTitle = it },
                placeholder = "留空时按首条消息命名", modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        PendingChoice.Model -> ComposerChoiceSheet(
            title = "模型", options = modelOptions, selected = model, searchable = true,
            onSelect = { id ->
                model = id
                if (selectedEmployeeId != null) manuallySpecifiedExecution = true
                selectedAgent?.let { agent ->
                    val available = com.wand.app.ui.thinkingEffortOptions(
                        agent.provider, id, models?.defaultModelFor(agent.provider),
                        models?.modelsFor(agent.provider).orEmpty(), models?.thinkingEffortsFor(agent.provider).orEmpty(),
                    )
                    if (available.none { it.id == effort }) effort = "off"
                }
                closeChoice()
            }, onDismiss = closeChoice,
        )
        PendingChoice.Mode -> ComposerChoiceSheet(
            title = "权限", options = SESSION_MODE_OPTIONS.filter { it.id in supportedSessionModeIds(target.provider) }
                .map { it.id to "${it.label} · ${it.description}" }, selected = executionMode,
            onSelect = { executionMode = it; if (selectedEmployeeId != null) manuallySpecifiedExecution = true; closeChoice() },
            onDismiss = closeChoice,
        )
        PendingChoice.Thinking -> ComposerChoiceSheet(
            title = "思考深度", options = effortOptions.map { it.id to it.menuLabel }, selected = effort,
            onSelect = { effort = it; if (selectedEmployeeId != null) manuallySpecifiedExecution = true; closeChoice() },
            onDismiss = closeChoice,
        )
        null -> Unit
    }

    ChatDetailScaffold(bottomBar = {
        Column(Modifier.imePadding()) {
            SharedMessageComposer(
                backdrop = null,
                sessionKey = draftKey,
                draft = composer.draft,
                onDraftChange = composer::editDraft,
                attachments = composer.attachments,
                stagedAttachments = composer.stagedAttachments,
                onRemoveStagedAttachment = { item -> drafts.setStagedAttachments(draftKey, composer.stagedAttachments.filterNot { it == item }) },
                baseUrl = api.baseUrl,
                onRemoveAttachment = composer::removeAttachment,
                uploading = busy || picker.open,
                presentation = inputPresentation,
                editorOverlayOpen = directoryOpen || choice != null,
                deliveryPhase = if (busy) com.wand.app.ui.SendPhase.Sending else com.wand.app.ui.SendPhase.Idle,
                actionCount = 1,
                exteriorAction = { send ->
                    ComposerSendStopActions(
                        presentation = composerActionPresentation(if (busy) com.wand.app.ui.SendPhase.Sending else com.wand.app.ui.SendPhase.Idle, false, true, canSubmit),
                        stopDescription = "停止", onSend = send, onStop = {}, canSubmit = canSubmit,
                        sendDescription = if (target.isShell && canCreateEmptyShell) "打开终端" else "发送",
                    )
                },
                attachOpen = attachOpen,
                onAttachOpenChange = { attachOpen = it; if (it) choice = null },
                onPickPhoto = picker.pickPhoto,
                onPickFile = picker.pickFile,
                canSubmit = canSubmit,
                onSend = { choice = null; attachOpen = false; submit() },
                allowRefocus = true,
                onExpandedChange = {},
                trailingActions = { _, _ -> },
                menuContent = {
                    WandInlinePanelAction(WandIcons.agent, "执行对象 · ${selectedEmployee?.name ?: target.label}", enabled = optionsEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { openChoice(PendingChoice.Object) })
                    if (workspaceTaskId == null) {
                        WandInlinePanelAction(WandIcons.todo, "任务 · ${if (createMode) taskTitle.ifBlank { "新建任务" } else "不归属任务"}", enabled = optionsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { openChoice(PendingChoice.Task) })
                        WandInlinePanelAction(WandIcons.folder, "目录 · ${sessionCwdLeaf(selectedCwd).orEmpty().ifBlank { "请选择" }}", enabled = optionsEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = { attachOpen = false; focusManager.clearFocus(); directoryOpen = true })
                    }
                    WandInlinePanelAction(WandIcons.settings, "执行工具 · ${target.label}", enabled = optionsEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { openChoice(PendingChoice.Tool) })
                    if (!target.isShell) WandInlinePanelAction(WandIcons.terminal, "会话模式 · ${selectedKind.label}", enabled = optionsEnabled && !target.isSdk,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { openChoice(PendingChoice.Kind) })
                    if (selectedEmployee != null && manuallySpecifiedExecution) WandInlinePanelAction(
                        WandIcons.refresh, "恢复${selectedEmployee.name}的候选设置", enabled = optionsEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = { restoreEmployee(); attachOpen = false },
                    )
                },
                controls = {
                    ComposerSettingsRow(
                        model = modelOptions.firstOrNull { it.first == model }?.second ?: model,
                        mode = SESSION_MODE_OPTIONS.firstOrNull { it.id == executionMode }?.label ?: executionMode,
                        thinking = effortOptions.firstOrNull { it.id == effort }?.shortLabel ?: effort,
                        enabled = optionsEnabled && !target.isShell,
                        onModel = { openChoice(PendingChoice.Model) },
                        onMode = { openChoice(PendingChoice.Mode) },
                        onThinking = { openChoice(PendingChoice.Thinking) },
                    )
                },
            )
        }
    }) { padding ->
        val direction = LocalLayoutDirection.current
        val topClearance = if (embedded) 0.dp else chatTopIdentityClearance()
        Column(
            Modifier.fillMaxSize().padding(
                start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
                top = padding.calculateTopPadding() + topClearance, bottom = padding.calculateBottomPadding(),
            ).verticalScroll(rememberScrollState()),
        ) {
            if ((!defaultsReady && !defaultsError && !hasKnownSession) || employeesLoading || modelsLoading) {
                CircularProgressIndicator(Modifier.padding(16.dp))
            }
            if (defaultsError && !hasKnownSession) {
                Text("无法加载创建配置，请重试。", color = WandColors.danger, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { defaultsRetry += 1 }) { Text("重试加载配置") }
            }
            employeesError?.let { message ->
                Text(message, color = WandColors.danger, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { employeesError = null; employeeRetry += 1 }) { Text("重试加载员工") }
            }
            modelsError?.let { message ->
                Text(message, color = WandColors.danger, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { modelsError = null; modelsRetry += 1 }) { Text("重试加载模型") }
            }
            if (target.isShell && !canCreateEmptyShell) {
                Text("空白终端不接收首条输入；请移除文字和附件后打开。", color = WandColors.textSecondary,
                    modifier = Modifier.padding(16.dp))
            }
            error?.let { Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            taskState.mutationError?.let { message -> if (error == null) Text(message, color = WandColors.danger, modifier = Modifier.padding(16.dp)) }
        }
        if (!embedded) Box(Modifier.fillMaxSize().padding(padding)) {
            ChatTopIdentityBar(
                identity = chatTopIdentity(taskName, if (createMode) "新建任务" else "新对话", selectedEmployee?.name, selectedCwd),
                attention = ChatTopAttention.Quiet, statusText = "发送后开始工作",
                avatarId = selectedEmployee?.id, avatarName = selectedEmployee?.name, avatar = selectedEmployee?.avatar,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = FloatingControlRowInset),
                horizontalArrangement = Arrangement.SpaceBetween) {
                FloatingChatIconButton(WandIcons.back, "返回") { if (!busy) onCancel() }
                FloatingChatIconButton(WandIcons.more, "对话选项") { if (optionsEnabled) { choice = null; attachOpen = true } }
            }
        }
    }

}
