package com.wand.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ConversationTurn
import com.wand.app.data.TeamRunAction
import com.wand.app.data.TurnAuthor
import com.wand.app.data.WandApi
import com.wand.app.data.UploadedFile
import com.wand.app.data.aiTeamRunActive
import com.wand.app.data.aiTeamRunStatusLabel
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.SendPhase
import com.wand.app.ui.ChatComposer
import com.wand.app.ui.SessionDraftStore
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.WandTextPreview
import com.wand.app.ui.TextPreviewDialog
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.sendActionVisual
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.TeamMessageAvatar
import com.wand.app.ui.components.TeamMessageDocSheet
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 团队群聊页（对齐 Web `src/web-ui/react/ai-teams/team-chat-view.tsx` 的分层）：
 * 一行群公告承接任务入口，成员与完整输出按需展开；消息流和输入框保持主位。
 * 系统提示居中单行弱化，用户消息右侧气泡 + 乐观未确认。
 *
 * 数据只走 `GET /api/ai-team-runs/:id`（run / steps / chatTurns），
 * 发话走既有 `POST /api/sessions/{chatSessionId}/input`，动作走 `api.actOnTeamRun`：
 * 不加新端点、不绕开 relay。
 */
private val TeamChatReadableMaxWidth = 760.dp

/** 运行中 4s 一轮，结束后 15s 一轮：群聊是「有人在干活」的页面，比看板详情更敏感。 */
private const val TEAM_CHAT_POLL_ACTIVE_MS = 4_000L
private const val TEAM_CHAT_POLL_IDLE_MS = 15_000L

/**
 * 署名与步骤标题在署名行里的宽度上限：超了就省略号，把整行的空间让给状态芯片，
 * 让「工作中」在任何字号下都是完整一块（对应 Web 头部 `flex-wrap` 的让位行为）。
 */
private val TeamChatStepChipMaxWidth = 190.dp

/**
 * 外层列表的 contentPadding.bottom：贴底判定要把它扣掉（滚到底时条目底部离视口底部正好差这一个），
 * 所以布局与判定共用同一个值，不再各写各的。
 */
private val TeamChatListBottomPadding = 14.dp

@Composable
fun AiTeamChatScreen(
    api: WandApi,
    runId: String,
    sessionDrafts: SessionDraftStore,
    isHapticEnabled: () -> Boolean,
    taskIdentifier: String? = null,
    // 唯一调用点（WandApp 的 Screen.AiTeamChat 分支）恒传 showDetailBack，不留默认值兜一条假路径。
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenMemberSession: (String) -> Unit,
    onOpenFullSession: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentRunId by remember(runId) { mutableStateOf(runId) }
    var detail by remember(runId) { mutableStateOf<AiTeamRunDetail?>(null) }
    var loadError by remember(runId) { mutableStateOf<String?>(null) }
    var busy by remember(runId) { mutableStateOf(false) }
    var detailsOpen by remember(runId) { mutableStateOf(false) }
    var sendError by remember(runId) { mutableStateOf<String?>(null) }
    var localTurns by remember(runId) { mutableStateOf<List<LocalChatTurn>>(emptyList()) }
    /** 全文弹层（设计 §6）：同一时刻只开一条；正文是全文，不是预览。 */
    var docMessage by remember(runId) { mutableStateOf<TeamChatDoc?>(null) }
    var docClosing by remember(runId) { mutableStateOf(false) }
    var docTrigger by remember(runId) { mutableStateOf<FocusRequester?>(null) }
    val listFocus = remember(runId) { FocusRequester() }

    // 只接收成功的当前 run/chat 详情：失败/loading 不重置身份、更不能关全文层。
    val activeDetail = detail?.takeIf { it.run.id == currentRunId }
    val run = activeDetail?.run
    val projectionScope = "${currentRunId}\u0000${run?.chatSessionId.orEmpty()}"
    val committedProjection = remember(runId) { mutableStateOf<TeamChatProjection?>(null) }
    val projection = remember(projectionScope, activeDetail?.chatTurns) {
        activeDetail?.let { projectTeamTurns(committedProjection.value, projectionScope, it.chatTurns) }
    }
    SideEffect {
        if (projection != null && committedProjection.value !== projection) committedProjection.value = projection
    }
    var settledProjection by remember(runId) { mutableStateOf<TeamChatProjection?>(null) }
    var arrivalState by remember(runId) { mutableStateOf(TeamArrivalState(scope = projectionScope)) }
    val resumed = arrivalState.foreground
    val presentedTurns = projection?.rows.orEmpty()
    val status = run?.status ?: "running"
    val reducedMotion = reduceMotionEnabled()
    /** 当前运行的 roster：正文里的 @成员名 靠它识别（设计 v2.2.3；名单为空 → 不高亮）。 */
    val rosterNames = remember(activeDetail) {
        (activeDetail?.presentationTeam?.members.orEmpty().map { it.name } +
            run?.team?.members.orEmpty().map { it.name } +
            activeDetail?.chatTurns.orEmpty().mapNotNull { it.author?.name }).distinct()
    }
    val hint = chatInputHint(status)
    val chatSessionId = run?.chatSessionId

    // 详情轮询随页面可见性走：后台不烧请求，回前台立刻补一次。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentRunId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    arrivalState = teamArrivalResumed(arrivalState)
                    scope.launch { detail = runCatching { api.aiTeamRunDetail(currentRunId) }.getOrNull() ?: detail }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP,
                -> arrivalState = teamArrivalPaused(arrivalState)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(api, currentRunId, resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            val result = runCatching { api.aiTeamRunDetail(currentRunId) }
            // 拉失败不清空已有内容：群聊宁可停在旧消息上，也不整屏闪回加载态。
            detail = result.getOrNull() ?: detail
            val fetched = result.getOrNull()
            if (fetched != null) {
                localTurns = settleLocalTurns(localTurns, fetched.chatTurns)
            }
            if (fetched != null && !aiTeamRunActive(fetched.run.status) && fetched.run.taskId.isNotBlank()) {
                val runs = runCatching { api.teamRunsForTask(fetched.run.taskId) }.getOrNull()
                val newer = runs?.let { newestRunOnSameChat(fetched.run, it) }
                if (newer != null) {
                    currentRunId = newer
                    continue
                }
            }
            loadError = if (detail == null) {
                result.exceptionOrNull()?.message ?: "无法加载群聊"
            } else {
                null
            }
            delay(
                if (aiTeamRunActive(detail?.run?.status ?: "running")) {
                    TEAM_CHAT_POLL_ACTIVE_MS
                } else {
                    TEAM_CHAT_POLL_IDLE_MS
                },
            )
        }
    }

    val listState = rememberLazyListState()
    val tailId = projection?.rows?.lastOrNull()?.presentationId
    val density = LocalDensity.current.density
    /**
     * 外层列表是否贴底：与卡片内滚同一套「距底 ≤ [LIVE_TAIL_DP]（按 density 换算成像素）才跟」口径
     * （对齐 Web `team-chat-view.tsx` 的 `listPinnedRef`），上滚看历史时新消息不把他拽回尾部。
     */
    var listPinned by remember(runId) { mutableStateOf(true) }
    val context = LocalContext.current
    val composer = remember(runId, chatSessionId, api, sessionDrafts) {
        chatSessionId?.let { sessionId ->
            ChatComposer(
                sessionId = sessionId,
                drafts = sessionDrafts,
                parentScope = scope,
                ready = {
                    val prompt = buildAttachmentPrompt(
                        sessionDrafts.attachments(sessionId), sessionDrafts[sessionId],
                    ).trim()
                    !busy && localTurns.none { !it.accepted && it.unconfirmed && it.text == prompt }
                },
                send = { prompt ->
                    val sentAt = System.currentTimeMillis()
                    val targetRunId = currentRunId
                    val knownFingerprints = detail?.takeIf {
                        it.run.id == targetRunId && it.run.chatSessionId == sessionId
                    }?.chatTurns.orEmpty().mapNotNull(::teamTurnFingerprint)
                    localTurns = localTurns + LocalChatTurn(
                        prompt, sentAt, knownFingerprints = knownFingerprints,
                    )
                    listPinned = true
                    val acknowledgement = try {
                        api.sendInput(sessionId, prompt, respondImmediately = true)
                    } catch (cause: Exception) {
                        if (chatSendDefinitelyRejected(cause)) {
                            localTurns = localTurns.filterNot { it.sentAtMillis == sentAt }
                            sendError = "${cause.message ?: "发送失败"}，内容已保留在输入框"
                        } else {
                            localTurns = localTurns.map {
                                if (it.sentAtMillis == sentAt) it.copy(unconfirmed = true) else it
                            }
                            sendError = "送达状态未知，请先查看群聊记录，避免重复发送"
                        }
                        throw cause
                    }
                    val ackFingerprint = acknowledgedTeamChatFingerprint(
                        acknowledgement.messages, prompt, knownFingerprints,
                    )
                    localTurns = localTurns.map {
                        if (it.sentAtMillis == sentAt) it.copy(
                            accepted = true, ackFingerprint = ackFingerprint,
                        ) else it
                    }
                    val turns = runCatching { api.aiTeamRunDetail(targetRunId) }.getOrNull()?.chatTurns
                    localTurns = settleLocalTurns(localTurns, turns)
                    sendError = null
                },
                notice = { message -> if (sendError == null) sendError = message },
            )
        }
    }
    DisposableEffect(composer) { onDispose { composer?.shutdown() } }
    val voiceInput = rememberVoiceInputHandle(
        isHapticEnabled = isHapticEnabled,
        onToast = { sendError = it },
        onCommit = { text -> composer?.appendVoice(text) },
        sessionKey = composer,
        onCommitForPress = { composer?.voiceCommitForCurrentDraft() ?: {} },
    )
    val attachmentPickers = rememberAttachmentPickerActions { uris ->
        val target = composer
        if (target != null && uris.isNotEmpty()) {
            sendError = null
            target.upload { remainingSlots ->
                if (uris.size > remainingSlots) {
                    sendError = "最多添加 5 个附件，本次仅上传前 $remainingSlots 个"
                }
                uploadComposerAttachments(context, api, target.sessionId, uris, remainingSlots)
            }
        }
    }

    // 判定只由「用户自己在滚」更新：新消息插入也会让距底突然变大，那不是用户的意图。
    LaunchedEffect(listState, density) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            listPinnedFromLayout(
                lastVisibleIndex = last?.index ?: -1,
                totalItemsCount = info.totalItemsCount,
                lastVisibleOffset = last?.offset ?: 0,
                lastVisibleHeight = last?.size ?: 0,
                // 直接用 Compose 的视口底，不在这上面再减 viewportStartOffset 自算高度：
                // 条目 offset 与 viewportEndOffset 同原点（content 顶边），自算会把 paddingTop 混进距底。
                viewportEndOffset = info.viewportEndOffset,
                contentPaddingBottomDp = TeamChatListBottomPadding.value,
                density = density,
            )
        }.collect { follows ->
            if (listState.isScrollInProgress) listPinned = follows
        }
    }

    // 只有正式消息或本地发送改变列表；工具输出不会触发滚动。
    LaunchedEffect(projectionScope, tailId, localTurns.size, listPinned) {
        if (!listPinned) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last)
    }

    LaunchedEffect(projectionScope) {
        arrivalState = teamArrivalForScope(arrivalState, projectionScope)
    }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) arrivalState = teamArrivalReduced(arrivalState)
    }

    // 只在该成功快照首次布局后结算一次：前台、原贴尾且可见的新尾才入场。
    // 页面消费资格，回收/重组/主题更新不重播；失败快照根本不触发本 effect。
    LaunchedEffect(projection) {
        val batch = projection ?: return@LaunchedEffect
        if (settledProjection === batch) return@LaunchedEffect
        settledProjection = batch
        if (batch.candidates.isEmpty() || !resumed || !listPinned || reducedMotion) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last)
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        val eligible = batch.candidates.filter { "turn#${batch.scope}/$it" in visible }.toSet()
        arrivalState = teamArrivalAdmitted(arrivalState, batch.scope, eligible,
            pinned = listPinned, motionEnabled = !reducedMotion)
    }

    // owner 判断与 LazyColumn key 读同一投影；歧义重绑/裁尾/run 切换只退旧快照。
    LaunchedEffect(projection, projectionScope, docMessage, localTurns) {
        val doc = docMessage ?: return@LaunchedEffect
        val owned = if (doc.presentationId.startsWith("local#")) {
            localTurns.any { "local#${it.sentAtMillis}" == doc.presentationId }
        } else projection?.rows?.any { it.presentationId == doc.presentationId } == true
        if (doc.scope != projectionScope || (projection != null && !owned)) docClosing = true
    }

    fun act(action: TeamRunAction) {
        if (busy) return
        if (docMessage != null) docClosing = true
        busy = true
        scope.launch {
            val result = runCatching { api.actOnTeamRun(currentRunId, action) }
            // 结果原位呈现：成功就换上新 detail 并清掉红字，失败写在同一行，不弹 Toast。
            result.getOrNull()?.let {
                detail = it
                sendError = null
            } ?: run {
                sendError = result.exceptionOrNull()?.message ?: "团队操作失败"
            }
            busy = false
        }
    }

    fun send() {
        if (docMessage != null) docClosing = true
        if (composer?.submit() == true) sendError = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WandDetailTopBar(
            title = activeDetail?.presentationChatTitle ?: "任务处理群",
            subtitle = listOfNotNull(
                taskIdentifier?.let { "任务 $it" },
                "${activeDetail?.presentationTeam?.members?.size ?: 0} 位成员",
                aiTeamRunStatusLabel(status),
            ).joinToString(" · "),
            leading = if (showBack) {
                { WandDetailBackButton(onBack, contentDescription = "返回", icon = WandIcons.back) }
            } else null,
        )

        val current = activeDetail
        when {
            current == null && loadError != null -> TeamChatLine(loadError ?: "无法加载群聊", WandColors.danger)
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WandColors.brand, modifier = Modifier.size(26.dp))
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    TeamChatContextBar(
                        detail = current,
                        expanded = detailsOpen,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        onToggle = { detailsOpen = !detailsOpen },
                        onOpenMemberSession = onOpenMemberSession,
                        onOpenFullSession = onOpenFullSession,
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .widthIn(max = TeamChatReadableMaxWidth)
                            .align(Alignment.CenterHorizontally)
                            .focusRequester(listFocus)
                            .focusable(),
                        contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, TeamChatListBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val needsYou = current.run.status == "awaiting_approval" ||
                            current.run.status == "waiting_user"
                        if (needsYou) {
                            item(key = "team-actions") {
                                TeamRunActionBar(
                                    detail = current,
                                    busy = busy,
                                    draft = composer?.draft.orEmpty(),
                                    onAction = ::act,
                                )
                            }
                        }
                        if (current.chatTurns.isEmpty() && localTurns.isEmpty()) {
                            item(key = "team-empty") {
                                TeamChatLine("群聊还没有消息。", WandColors.textMuted)
                            }
                        }
                        presentedTurns.forEachIndexed { index, presented ->
                            val id = presented.presentationId
                            if (teamChatShowsTime(presentedTurns.getOrNull(index - 1)?.turn, presented.turn)) {
                                item(key = "time#${projectionScope}/$id") {
                                    Text(
                                        teamChatTimeLabel(presented.turn),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WandColors.textMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    )
                                }
                            }
                            item(key = "turn#${projectionScope}/$id") {
                                TeamTurnArrival(
                                    playing = arrivalState.scope == projectionScope && resumed && !reducedMotion
                                        && id in arrivalState.activeIds,
                                    onConsumed = {
                                        arrivalState = teamArrivalConsumed(arrivalState, projectionScope, id)
                                    },
                                ) {
                                    TeamTurnRow(
                                        turn = displayTeamTurn(teamChatDisplayTurn(presented.turn), current),
                                        presentationId = id,
                                        scope = projectionScope,
                                        baseUrl = api.baseUrl,
                                        steps = current.steps,
                                        rosterNames = rosterNames,
                                        onOpenMemberSession = onOpenMemberSession,
                                        onOpenDoc = { doc, trigger ->
                                            if (docMessage == null) {
                                                docTrigger = trigger; docMessage = doc; docClosing = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        localTurns.forEach { row ->
                            item(key = "local#${row.sentAtMillis}") {
                                TeamLocalTurn(row, scope = projectionScope, baseUrl = api.baseUrl,
                                    onOpenDoc = { doc, trigger ->
                                    if (docMessage == null) {
                                        docTrigger = trigger; docMessage = doc; docClosing = false
                                    }
                                })
                            }
                        }
                    }
                    TeamChatComposer(
                        hint = hint,
                        draft = composer?.draft.orEmpty(),
                        attachments = composer?.attachments.orEmpty(),
                        uploading = composer?.uploading == true,
                        sendPhase = composer?.sendPhase ?: SendPhase.Idle,
                        canSubmit = composer?.canSubmit == true,
                        busy = busy,
                        status = status,
                        chatSessionId = chatSessionId,
                        baseUrl = api.baseUrl,
                        error = sendError,
                        onDraftChange = { composer?.editDraft(it) },
                        onRemoveAttachment = { file -> composer?.removeAttachment(file) },
                        onPickPhoto = attachmentPickers.pickPhoto,
                        onPickFile = attachmentPickers.pickFile,
                        voice = voiceInput.voice,
                        onMicDown = voiceInput.onMicDown,
                        onSend = ::send,
                        onStop = { act(TeamRunAction.Stop) },
                    )
                }
            }
        }
        // 全文弹层：覆盖层，不换路由；关掉就回到原位（触发点与列表滚动都不动）。
        docMessage?.let { doc ->
            TeamMessageDocSheet(
                doc = doc,
                closing = docClosing,
                onRequestClose = { if (!docClosing) docClosing = true },
                onDismissed = {
                    if (docMessage === doc) {
                        val trigger = docTrigger
                        docMessage = null; docClosing = false; docTrigger = null
                        scope.launch {
                            // 等覆盖层真正卸载后再归焦，不滚列表；失效/回收的 owner 落到可见列表。
                            withFrameNanos { }
                            val sameScope = committedProjection.value?.scope == doc.scope
                            val owned = (sameScope && committedProjection.value?.rows?.any {
                                it.presentationId == doc.presentationId
                            } == true) || (sameScope && doc.presentationId.startsWith("local#") &&
                                localTurns.any { "local#${it.sentAtMillis}" == doc.presentationId })
                            val key = if (doc.presentationId.startsWith("local#")) doc.presentationId
                                else "turn#${doc.scope}/${doc.presentationId}"
                            val fullyVisible = listState.layoutInfo.visibleItemsInfo.any { item ->
                                item.key == key && item.offset >= listState.layoutInfo.viewportStartOffset &&
                                    item.offset + item.size <= listState.layoutInfo.viewportEndOffset
                            }
                            if (owned && fullyVisible && trigger != null) {
                                runCatching { trigger.requestFocus() }
                            } else {
                                // 切 run 时列表可能已卸载；不复活旧行、不抛焦点异常。
                                runCatching { listFocus.requestFocus() }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** 群公告保持一行；成员、任务和完整记录从原位展开，消息始终是页面主体。 */
@Composable
private fun TeamChatContextBar(
    detail: AiTeamRunDetail,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onOpenMemberSession: (String) -> Unit,
    onOpenFullSession: (String) -> Unit,
) {
    val motion = !reduceMotionEnabled()
    Column(
        modifier = modifier
            .widthIn(max = TeamChatReadableMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.sm)
                .background(WandColors.surfaceSoft)
                .clickable(onClickLabel = if (expanded) "收起群详情" else "查看群详情", onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("群公告", style = MaterialTheme.typography.labelSmall, color = WandColors.brand)
            Text(
                detail.run.objective.lineSequence().firstOrNull()?.ifBlank { "查看本次任务" } ?: "查看本次任务",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "详情",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
            )
        }
        TeamChatActivitySummary(
            activities = teamChatActivities(detail),
            onOpenDetails = { if (!expanded) onToggle() },
            onOpenMemberSession = onOpenMemberSession,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = if (motion) fadeIn(WandMotion.tweenEnter()) +
                expandVertically(WandMotion.tweenEnter()) else EnterTransition.None,
            exit = if (motion) fadeOut(WandMotion.tweenExit()) +
                shrinkVertically(WandMotion.tweenExit()) else ExitTransition.None,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TeamMainTaskCard(detail)
                TeamOfficeStrip(detail, onOpenMemberSession)
                detail.run.chatSessionId?.let { sessionId ->
                    WandButton(
                        label = "查看完整会话记录",
                        onClick = { onOpenFullSession(sessionId) },
                        variant = WandButtonVariant.Text,
                        compact = true,
                    )
                }
            }
        }
    }
}

/** 群详情中的完整任务目标，长内容可原位展开。 */
@Composable
private fun TeamMainTaskCard(detail: AiTeamRunDetail) {
    val run = detail.run
    WandCard(
        containerColor = WandColors.surfaceSoft.copy(alpha = 0.55f),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "主任务",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.brand,
                modifier = Modifier
                    .clip(WandShapes.xs)
                    .background(WandColors.brandSoft.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Text(
                "${run.stepsUsed}/${run.stepLimit} 步 · ${detail.presentationTeam?.name?.takeIf { it.isNotBlank() } ?: "AI 团队"}",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        TeamCollapsibleBody(run.objective.ifBlank { "（这次运行没有写目标）" })
    }
}

/** 成员工位与实时步骤来自同一份运行详情，点已开工的成员直达其会话。 */
@Composable
fun TeamOfficeStrip(detail: AiTeamRunDetail, onOpenSession: (String) -> Unit) {
    val members = teamOfficeMembers(detail)
    val working = members.count { it.state == TeamOfficeState.Working }
    val attention = members.count { it.state == TeamOfficeState.Attention }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "团队工位",
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    "$working 人工作中",
                    "$attention 人待处理".takeIf { attention > 0 },
                    "${members.size} 人在组",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(members.size, key = { members[it].member.id }) { index ->
                val entry = members[index]
                val tint = when (entry.state) {
                    TeamOfficeState.Working -> WandColors.info
                    TeamOfficeState.Attention -> WandColors.warning
                    TeamOfficeState.Done -> WandColors.success
                    TeamOfficeState.Failed -> WandColors.danger
                    else -> WandColors.textMuted
                }
                val open = entry.sessionId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .widthIn(min = 190.dp, max = 220.dp)
                        .clip(WandShapes.sm)
                        .background(WandColors.surfaceSoft)
                        .then(if (open != null) Modifier.clickable(onClickLabel = "查看${entry.member.name}的会话") {
                            onOpenSession(open)
                        } else Modifier)
                        .padding(8.dp),
                ) {
                    // 与消息署名行同一张脸（§5.3 最小对齐）：工位条不再画字母圈，
                    // 否则同一成员在工位条与消息里会是两张不同的头像。
                    TeamMessageAvatar(
                        spec = chatAvatarSpec(
                            TurnAuthor(id = entry.member.id, name = entry.member.name, avatar = entry.member.avatar),
                        ),
                        size = 26.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.member.name, style = MaterialTheme.typography.labelMedium,
                            color = WandColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.task, style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(entry.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

/** 运行状态与动作：沿用 actOnTeamRun 通道，结果写在原位，不弹 Toast。 */
@Composable
private fun TeamRunActionBar(
    detail: AiTeamRunDetail,
    busy: Boolean,
    draft: String,
    onAction: (TeamRunAction) -> Unit,
) {
    val run = detail.run
    val budgetSpent = run.stepsUsed >= run.stepLimit
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (run.status) {
            "awaiting_approval" -> {
                WandButton(
                    label = "批准计划",
                    onClick = { onAction(TeamRunAction.Approve) },
                    enabled = !busy,
                    variant = WandButtonVariant.Success,
                    compact = true,
                )
                WandButton(
                    // 退回意见直接取下方输入框：群聊里写一句「先补测试」比再开一个表单顺手。
                    label = if (draft.isNotBlank()) "退回并带上这句话" else "退回重做",
                    onClick = { onAction(TeamRunAction.Reject(draft.trim())) },
                    enabled = !busy,
                    variant = WandButtonVariant.Secondary,
                    compact = true,
                )
            }
            "waiting_user" -> {
                if (budgetSpent) {
                    WandButton(
                        label = "追加 10 步继续",
                        onClick = { onAction(TeamRunAction.Continue(10)) },
                        enabled = !busy,
                        variant = WandButtonVariant.Secondary,
                        compact = true,
                    )
                }
                Text(
                    "负责人的问题请在下方回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 完整行高从第一帧保留，alpha/+4dp 只动绘制层；回收时消费资格不补播。 */
@Composable
private fun TeamTurnArrival(
    playing: Boolean,
    onConsumed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = !reduceMotionEnabled()
    val progress = remember(playing) { Animatable(if (playing && motion) 0f else 1f) }
    val travelPx = with(LocalDensity.current) { 4.dp.toPx() }
    DisposableEffect(playing) {
        onDispose { if (playing) onConsumed() }
    }
    LaunchedEffect(playing, motion) {
        if (!playing) return@LaunchedEffect
        if (motion) progress.animateTo(1f, WandMotion.respectMotion(motion, WandMotion.tweenEnter()))
        else progress.snapTo(1f)
        onConsumed()
    }
    Box(Modifier.graphicsLayer {
        val frame = teamArrivalFrame(progress.value, motion, travelPx)
        alpha = frame.alpha
        translationY = frame.translationY
    }) { content() }
}

@Composable
private fun TeamTurnRow(
    turn: ConversationTurn,
    presentationId: String,
    scope: String,
    baseUrl: String,
    rosterNames: List<String>,
    steps: List<AiTeamStep>,
    onOpenMemberSession: (String) -> Unit,
    onOpenDoc: (TeamChatDoc, FocusRequester) -> Unit,
) {
    when (chatTurnKind(turn)) {
        TeamChatTurnKind.Notice -> TeamNoticeRow(turn)
        TeamChatTurnKind.User -> TeamUserRow(turn, presentationId, scope, baseUrl, onOpenDoc)
        TeamChatTurnKind.Leader -> TeamLeaderCard(turn, presentationId, scope, rosterNames,
            onOpenMemberSession, onOpenDoc)
        TeamChatTurnKind.Step -> TeamStepRow(turn, presentationId, scope, rosterNames, steps,
            onOpenMemberSession, onOpenDoc)
    }
}

/**
 * 一条消息的外框：头像是内容列外侧、署名行在上、正文块在下
 * （设计 §3.1 / §9.1）。自己的发言整行镜像靠右（对齐 Web 的 `flex-direction: row-reverse`）。
 * Compose 的 `Row` 没有 `reverseLayout`，所以按位置分两支摆放同一个头像；
 * `own` 在同一条消息上是恒定的（由发言角色决定），不会在两处各建一份状态。
 */
@Composable
private fun TeamMessageRow(
    own: Boolean,
    avatar: ChatAvatarSpec,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val avatarSlot: @Composable () -> Unit = { TeamMessageAvatar(avatar) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!own) avatarSlot()
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
            content = content,
        )
        if (own) avatarSlot()
    }
}

/**
 * 消息正文（设计 §3.3）：文档性质走全宽文档卡，短发言收进气泡；
 * 超阈值时**只渲染预览**（前 6 行 / 420 字），底部给「点击展开」打开全文弹层，
 * 不渲染第二份全文，也不把触发点换成第二个形态。
 */
@Composable
private fun TeamMessageBody(
    shape: TeamChatMessageShape,
    text: String,
    own: Boolean,
    names: List<String> = emptyList(),
    assignments: List<TeamAssignment> = emptyList(),
    onExpand: ((FocusRequester) -> Unit)? = null,
) {
    val triggerRequester = remember { FocusRequester() }
    val truncated = needsCollapse(text)
    val document = shape == TeamChatMessageShape.Document
    val mentionStyle = SpanStyle(color = WandColors.brand, background = WandColors.brandSoft,
        fontWeight = FontWeight.Medium)
    // 文档卡比气泡深一档的「纸面」；自己的气泡是品牌色淡底（现状保留）。
    val container = when {
        document -> Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .border(0.55.dp, WandColors.border.copy(alpha = 0.7f), WandShapes.md)
            .background(WandColors.surfaceSoft)
        own -> Modifier
            .widthIn(max = TeamMessageBubbleMaxWidth)
            .clip(bubbleShape(own = true))
            .background(WandColors.brand.copy(alpha = 0.13f))
        else -> Modifier
            .widthIn(max = TeamMessageBubbleMaxWidth)
            .clip(bubbleShape(own = false))
            .border(0.55.dp, WandColors.border.copy(alpha = 0.7f), bubbleShape(own = false))
            .background(WandColors.surface)
    }
    Column(
        modifier = container.padding(
            horizontal = 14.dp,
            vertical = if (document) 12.dp else 10.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (own && !document) Alignment.End else Alignment.Start,
    ) {
        when {
            // 空正文也要占住气泡/文档卡；只有派工清单的负责人发言不出空段落（设计 §8）。
            text.isBlank() && assignments.isEmpty() ->
                Text(CHAT_EMPTY_BODY, fontSize = if (document) 13.sp else 14.sp,
                    lineHeight = if (document) 20.sp else 21.sp, color = WandColors.textMuted)
            text.isBlank() -> Unit
            // 预览只渲染预览：行数由 collapsedPreview 决定，不靠 maxLines 再叠一层截断。
            truncated -> Text(
                mentionAnnotatedText(collapsedPreview(text), names, mentionStyle, source = text),
                fontSize = if (document) 13.sp else 14.sp,
                lineHeight = if (document) 20.sp else 21.sp,
                color = WandColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            // 文档卡用群聊既有的 markdown 渲染器（报告里有清单 / 代码块）；气泡是短发言，直接铺文本。
            document -> MarkdownText(text, inlineDecoration = teamChatMarkdownDecoration(names))
            else -> Text(
                mentionAnnotatedText(text, names, mentionStyle),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = WandColors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (assignments.isNotEmpty()) TeamAssignmentList(assignments)
        if (truncated && onExpand != null) {
            TeamExpandTrigger(
                onClick = { onExpand(triggerRequester) },
                requester = triggerRequester,
                modifier = Modifier.align(if (own && !document) Alignment.End else Alignment.Start),
            )
        }
    }
}

/** 气泡上限：`320.dp`（与 Web 宽屏 560px 同档，窄屏两端都是铺满剩余宽度）。 */
private val TeamMessageBubbleMaxWidth = 320.dp

/** 靠头像的那个上角收紧到 6dp，其余三角走常规圆角（设计 §3.3）。 */
private fun bubbleShape(own: Boolean): RoundedCornerShape = if (own) {
    RoundedCornerShape(topStart = 12.dp, topEnd = WandShapes.radiusXs, bottomEnd = 12.dp, bottomStart = 12.dp)
} else {
    RoundedCornerShape(topStart = WandShapes.radiusXs, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
}

/**
 * 「点击展开」：正文块内部底部的一个**单状态**入口（打开覆盖层，不是原位展开），
 * 所以没有第二套文案、也不带会变形的图标；触控区不小于 32dp（设计 §3.4）。
 */
@Composable
private fun TeamExpandTrigger(onClick: () -> Unit, requester: FocusRequester, modifier: Modifier = Modifier) {
    // 这一个入口只有「打开弹层」一个状态（不换成「收起」），所以没有可过渡的第二态：
    // 颜色写常量，不挂一个目标值永远不变的颜色动画（规范：动画只由状态驱动）。
    Text(
        CHAT_EXPAND_LABEL,
        fontSize = 12.sp,
        color = WandColors.brand,
        maxLines = 1,
        modifier = modifier
            .focusRequester(requester)
            .clip(WandShapes.xs)
            .clickable(onClickLabel = "点击展开全文", onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** 群聊单独启用的 Markdown inline 装饰；默认 MarkdownText 调用方不受影响。 */
@Composable
fun teamChatMarkdownDecoration(names: List<String>): InlineMarkdownDecoration? {
    val color = WandColors.brand
    val background = WandColors.brandSoft
    return remember(names, color, background) {
        if (names.isEmpty()) null else {
            val snapshot = names.toList()
            val style = SpanStyle(color = color, background = background, fontWeight = FontWeight.Medium)
            val decorator: InlineMarkdownDecoration = { value, protected ->
                decorateTeamMarkdown(value, snapshot, protected, style)
            }
            decorator
        }
    }
}

/** 负责人派工清单：结构化完整姓名仅布局省略，依据/旧等待都在 note 同一槽位。 */
@Composable
private fun TeamAssignmentList(assignments: List<TeamAssignment>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        assignments.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${index + 1}",
                    fontSize = 11.sp,
                    color = WandColors.textMuted,
                    modifier = Modifier.widthIn(min = 14.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "@${item.member}", fontSize = 12.sp, color = WandColors.brand,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp)
                                .clip(WandShapes.xs)
                                .background(WandColors.brandSoft)
                                .padding(horizontal = 4.dp),
                        )
                        Text(item.title, fontSize = 13.sp, color = WandColors.textPrimary)
                    }
                    if (item.note.isNotBlank()) {
                        Text(item.note, fontSize = 11.sp, color = WandColors.textMuted)
                    }
                }
            }
        }
    }
}

/** 弹层载荷：正文传**整条发言**（说明 + 派工清单），一定比卡片里被截断的那 6 行多。 */
private fun teamChatDoc(
    turn: ConversationTurn,
    presentationId: String,
    scope: String,
    names: List<String>,
    text: String,
    name: String,
    kind: TeamChatTurnKind,
    reportTitle: String? = null,
    chip: String = "",
    avatar: ChatAvatarSpec = ChatAvatarSpec.Brand,
): TeamChatDoc = TeamChatDoc(
    presentationId = presentationId,
    scope = scope,
    text = text,
    name = name,
    clock = conversationTurnClock(turn),
    typeLabel = teamChatDocTypeLabel(kind, reportTitle),
    chip = chip,
    avatar = avatar,
    mentionNames = names.toList(),
)

/** 自己发的消息：名字「我」+ 默认 APP logo 头像，气泡靠右。 */
@Composable
private fun TeamUserRow(
    turn: ConversationTurn,
    presentationId: String,
    scope: String,
    baseUrl: String,
    onOpenDoc: (TeamChatDoc, FocusRequester) -> Unit,
) {
    val parsed = remember(turn) { parseUserAttachmentText(chatTurnText(turn)) }
    val text = parsed.body
    val clock = conversationTurnClock(turn)
    val shape = teamChatMessageShape(TeamChatTurnKind.User, text)
    TeamMessageRow(own = true, avatar = ChatAvatarSpec.Brand) {
        TeamAuthorLine(
            author = null,
            fallbackName = CHAT_SELF_NAME,
            badge = null,
            clock = clock,
            own = true,
            onOpenMemberSession = null,
        )
        TeamMessageAttachments(parsed.paths, baseUrl)
        if (text.isNotBlank() || parsed.paths.isEmpty()) {
            TeamMessageBody(
                shape = shape,
                text = text,
                own = true,
                onExpand = { requester -> onOpenDoc(teamChatDoc(turn, presentationId, scope, emptyList(), text,
                    CHAT_SELF_NAME, TeamChatTurnKind.User), requester) },
            )
        }
    }
}

/** 本地乐观临时行使用独立句柄空间，不参与服务端身份对齐。 */
@Composable
private fun TeamLocalTurn(row: LocalChatTurn, scope: String, baseUrl: String,
    onOpenDoc: (TeamChatDoc, FocusRequester) -> Unit) {
    val parsed = remember(row.text) { parseUserAttachmentText(row.text) }
    val shape = teamChatMessageShape(TeamChatTurnKind.User, parsed.body)
    TeamMessageRow(own = true, avatar = ChatAvatarSpec.Brand) {
        TeamAuthorLine(
            author = null,
            fallbackName = CHAT_SELF_NAME,
            badge = null,
            clock = "",
            own = true,
            onOpenMemberSession = null,
        )
        TeamMessageAttachments(parsed.paths, baseUrl)
        if (parsed.body.isNotBlank() || parsed.paths.isEmpty()) {
            TeamMessageBody(
                shape = shape,
                text = parsed.body,
                own = true,
                onExpand = { requester ->
                    onOpenDoc(
                        TeamChatDoc(
                            presentationId = "local#${row.sentAtMillis}",
                            scope = scope,
                            text = parsed.body,
                            name = CHAT_SELF_NAME,
                            clock = "",
                            typeLabel = teamChatDocTypeLabel(TeamChatTurnKind.User),
                        ),
                        requester,
                    )
                },
            )
        }
        if (row.unconfirmed && !row.accepted) {
            Text("送达未确认", fontSize = 11.sp, color = WandColors.warning)
        } else if (row.accepted) {
            Text("已送达，等待同步", fontSize = 11.sp, color = WandColors.textMuted)
        }
    }
}

/** 已发送图片保持可点开预览；其他文件以文件芯片回显，不露出提示词前缀。 */
@Composable
private fun TeamMessageAttachments(paths: List<String>, baseUrl: String) {
    if (paths.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewPath by remember(paths) { mutableStateOf<String?>(null) }
    var downloadingPath by remember(paths) { mutableStateOf<String?>(null) }
    fun download(path: String) {
        if (baseUrl.isBlank() || downloadingPath != null) return
        downloadingPath = path
        scope.launch {
            try {
                WandServerFileLink.downloadAndOpen(context, baseUrl, path)
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    "文件下载失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                downloadingPath = null
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paths.forEach { path ->
            if (WandImage.isImagePath(path) && baseUrl.isNotBlank()) {
                WandAsyncImage(path = path, baseUrl = baseUrl)
            } else {
                WandFileChip(
                    path = path,
                    modifier = Modifier
                        .clip(WandShapes.sm)
                        .clickable(
                            enabled = baseUrl.isNotBlank() && downloadingPath == null,
                            role = Role.Button,
                            onClickLabel = "打开${path.substringAfterLast('/')}",
                        ) {
                            if (WandTextPreview.isPreviewableText(path)) previewPath = path
                            else download(path)
                        },
                )
            }
        }
    }
    previewPath?.let { path ->
        TextPreviewDialog(
            path = path,
            baseUrl = baseUrl,
            onDismiss = { previewPath = null },
            onOpenExternally = {
                previewPath = null
                download(path)
            },
        )
    }
}

/**
 * 署名行：名字（可点进该成员会话）+ 可选徽标 / 芯片 + 时刻。
 *
 * 用 `FlowRow` 而不是 `Row`：字号放大（font_scale 1.3）时这一行的内容会超出列宽，
 * `Row` 会把不收缩的状态芯片挤成十几像素的小方块。FlowRow 让它整块换到下一行，
 * 列内不裁切；步骤标题先让位，状态芯片保持原宽可读。
 * 头像不在这里：它在消息行上（自己的发言靠右），见 [TeamMessageRow]。
 */
@Composable
private fun TeamAuthorLine(
    author: TurnAuthor?,
    fallbackName: String,
    badge: String?,
    trailing: (@Composable (() -> Unit))? = null,
    clock: String,
    onOpenMemberSession: ((String) -> Unit)?,
    own: Boolean = false,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, alignment = if (own) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val sessionId = author?.sessionId?.takeIf { it.isNotBlank() }
        if (sessionId != null && onOpenMemberSession != null) {
            Text(
                author?.name ?: fallbackName,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 130.dp)
                    .clip(WandShapes.xs)
                    .clickable { onOpenMemberSession(sessionId) }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
            )
        } else {
            Text(
                author?.name ?: fallbackName,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
        }
        if (badge != null) TeamChatBadge(badge)
        trailing?.invoke()
        if (clock.isNotBlank()) {
            Text(
                clock,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 62.dp),
            )
        }
    }
}

/** 负责人仍有角色标记；说明与派工清单按形态走气泡或全宽文档卡。 */
@Composable
private fun TeamLeaderCard(
    turn: ConversationTurn,
    presentationId: String,
    scope: String,
    rosterNames: List<String>,
    onOpenMemberSession: (String) -> Unit,
    onOpenDoc: (TeamChatDoc, FocusRequester) -> Unit,
) {
    val text = chatTurnText(turn)
    val message = splitLeaderMessage(text)
    val name = turn.author?.name ?: "负责人"
    val avatar = chatAvatarSpec(turn.author)
    val shape = teamChatMessageShape(TeamChatTurnKind.Leader, message.head, message.assignments.size)
    TeamMessageRow(own = false, avatar = avatar) {
        TeamAuthorLine(
            author = turn.author,
            fallbackName = "负责人",
            badge = "负责人",
            clock = conversationTurnClock(turn),
            onOpenMemberSession = onOpenMemberSession,
        )
        TeamMessageBody(
            shape = shape,
            text = message.head,
            own = false,
            names = rosterNames,
            assignments = message.assignments,
            onExpand = { requester ->
                onOpenDoc(
                    teamChatDoc(
                        turn = turn,
                        presentationId = presentationId,
                        scope = scope,
                        names = rosterNames,
                        text = text,
                        name = name,
                        kind = TeamChatTurnKind.Leader,
                        avatar = avatar,
                    ),
                    requester,
                )
            },
        )
    }
}

/** 成员发言：步骤状态芯片 + 报告正文（气泡或全宽文档卡）。 */
@Composable
private fun TeamStepRow(
    turn: ConversationTurn,
    presentationId: String,
    scope: String,
    rosterNames: List<String>,
    steps: List<AiTeamStep>,
    onOpenMemberSession: (String) -> Unit,
    onOpenDoc: (TeamChatDoc, FocusRequester) -> Unit,
) {
    val report = parseStepReport(chatTurnText(turn))
    // 报告正文是剥掉 `✅ 完成「…」` 前缀之后的那一段（两端同口径）。
    val text = report?.body ?: chatTurnText(turn)
    val stepStatus = teamStepStatus(steps, report)
    val name = turn.author?.name ?: "成员"
    val avatar = chatAvatarSpec(turn.author)
    val shape = teamChatMessageShape(TeamChatTurnKind.Step, text)
    val chipText = report?.let { "${if (it.ok) "✅" else "❌"} ${it.title}" }.orEmpty()
    TeamMessageRow(own = false, avatar = avatar) {
        TeamAuthorLine(
            author = turn.author,
            fallbackName = "成员",
            badge = null,
            clock = conversationTurnClock(turn),
            onOpenMemberSession = onOpenMemberSession,
            trailing = {
                if (report != null) {
                    TeamStepChip(report.title, report.ok, stepStatus)
                }
            },
        )
        TeamMessageBody(
            shape = shape,
            text = text,
            own = false,
            names = rosterNames,
            onExpand = { requester ->
                onOpenDoc(
                    teamChatDoc(
                        turn = turn,
                        presentationId = presentationId,
                        scope = scope,
                        names = rosterNames,
                        text = text,
                        name = name,
                        kind = TeamChatTurnKind.Step,
                        reportTitle = report?.title,
                        chip = chipText,
                        avatar = avatar,
                    ),
                    requester,
                )
            },
        )
    }
}

@Composable
private fun TeamStepChip(title: String, ok: Boolean, status: String?) {
    val color = when {
        ok && status == "running" -> WandColors.info
        ok -> WandColors.success
        else -> WandColors.danger
    }
    Text(
        "${if (ok) "✅" else "❌"} $title${if (status == "running") " · 进行中" else ""}",
        fontSize = 11.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = TeamChatStepChipMaxWidth)
            .clip(WandShapes.xs)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 系统事件与时间分隔条各占一行；提示本身不重复时刻，也不占消息卡片。 */
@Composable
private fun TeamNoticeRow(turn: ConversationTurn) {
    val line = teamNoticeLine(turn)
    if (line.isBlank()) return
    Text(
        line,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = WandColors.textSecondary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = line },
    )
}

/**
 * 长正文原位展开 / 收起（动效要求 7）：同一份文本、同一个承载节点，
 * 高度用 animateContentSize 过渡，收起就是展开的倒放；关掉动画时瞬时切换。
 */
@Composable
private fun TeamCollapsibleBody(text: String) {
    val long = needsCollapse(text)
    var open by remember(text) { mutableStateOf(false) }
    val expanded = !long || open
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                WandMotion.respectMotion(
                    !reduceMotionEnabled(),
                    WandMotion.tweenNormal(),
                ),
            ),
    ) {
        MarkdownText(
            if (expanded) text else collapsedPreview(text),
        )
        if (long) {
            val tint by animateColorAsState(
                targetValue = if (open) WandColors.brand else WandColors.textSecondary,
                animationSpec = WandMotion.respectMotion(
                    !reduceMotionEnabled(),
                    WandMotion.tweenFast(),
                ),
                label = "teamCollapsibleToggle",
            )
            Text(
                if (open) "收起" else "展开全文",
                fontSize = 11.sp,
                color = tint,
                modifier = Modifier
                    .clip(WandShapes.xs)
                    .clickable { open = !open }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}


@Composable
private fun TeamChatBadge(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = WandColors.brand,
        maxLines = 1,
        modifier = Modifier
            .clip(WandShapes.xs)
            .background(WandColors.brandSoft.copy(alpha = 0.5f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun TeamChatLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 群聊和普通会话共用输入布局；团队状态与停止动作只保留在本页。 */
@Composable
private fun TeamChatComposer(
    hint: String,
    draft: String,
    attachments: List<UploadedFile>,
    uploading: Boolean,
    sendPhase: SendPhase,
    canSubmit: Boolean,
    busy: Boolean,
    status: String,
    chatSessionId: String?,
    baseUrl: String,
    error: String?,
    onDraftChange: (String) -> Unit,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var attachOpen by remember(chatSessionId) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 4.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(modifier = Modifier.widthIn(max = TeamChatReadableMaxWidth).fillMaxWidth()) {
            if (chatSessionId == null) {
                Text(
                    "这次运行没有群聊会话，只能在时间线里看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                return@Column
            }
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (voice.pressed) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    VoiceTranscriptBubble(backdrop = null, voice = voice)
                }
            }
            val active = aiTeamRunActive(status)
            val visual = sendActionVisual(
                phase = sendPhase,
                turnRunning = active,
                hasDraft = draft.isNotBlank() || attachments.isNotEmpty(),
            )
            SharedMessageComposer(
                backdrop = null,
                draft = draft,
                onDraftChange = onDraftChange,
                attachments = attachments,
                baseUrl = baseUrl,
                onRemoveAttachment = onRemoveAttachment,
                uploading = uploading,
                attachOpen = attachOpen,
                onAttachOpenChange = { attachOpen = it },
                onPickPhoto = onPickPhoto,
                onPickFile = onPickFile,
                canSubmit = canSubmit,
                onSend = onSend,
                allowRefocus = true,
                voicePressed = voice.pressed,
                onExpandedChange = {},
                trailingActions = { requestFocus, sendAndRefocus ->
                    VoiceMicButton(
                        voice = voice,
                        voiceMode = false,
                        onToggleMode = requestFocus,
                        onMicDown = onMicDown,
                    )
                    TeamChatSendStop(
                        visual = visual,
                        active = active,
                        busy = busy,
                        canSubmit = canSubmit,
                        onSend = sendAndRefocus,
                        onStop = onStop,
                    )
                },
                expandedControls = {
                    Box(modifier = Modifier.weight(1f)) {
                        ComposerActionsMenu(
                            backdrop = null,
                            uploading = uploading,
                            attachOpen = attachOpen,
                            onAttachOpenChange = { attachOpen = it },
                        )
                    }
                },
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error.startsWith("已上传")) WandColors.success else WandColors.danger,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 发送 ⇄ 停止：空草稿且团队还在跑时，同一枚按钮变成停止；有草稿时左侧再放一枚停止。
 * 形态与 `ChatScreen.TrailingSendStop` 同一套 `sendActionVisual` / `SubmitMorphButton`。
 */
@Composable
private fun TeamChatSendStop(
    visual: SendActionVisual,
    active: Boolean,
    busy: Boolean,
    canSubmit: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    if (visual == SendActionVisual.Stop) {
        SubmitMorphButton(
            visual = visual,
            contentDescription = "停止团队",
            onClick = onStop,
            enabled = !busy,
            fillColor = WandColors.textPrimary,
            contentTint = WandColors.surface,
        )
        return
    }
    if (active) {
        SubmitMorphButton(
            visual = SendActionVisual.Stop,
            contentDescription = "停止团队",
            onClick = onStop,
            enabled = !busy,
            fillColor = WandColors.dangerSoft,
            contentTint = WandColors.danger,
        )
    }
    SubmitMorphButton(
        visual = visual,
        contentDescription = when (visual) {
            SendActionVisual.Sending -> "发送中"
            SendActionVisual.Sent -> "已发送"
            SendActionVisual.Failed -> "发送失败，可重试"
            SendActionVisual.Blocked -> "当前没有可发送内容"
            else -> "发送消息"
        },
        onClick = onSend,
        enabled = visual == SendActionVisual.Send && canSubmit && !busy,
        fillColor = when (visual) {
            SendActionVisual.Send, SendActionVisual.Sending, SendActionVisual.Sent -> WandColors.brand
            SendActionVisual.Failed -> WandColors.dangerSoft
            else -> WandColors.textSecondary.copy(alpha = 0.16f)
        },
        contentTint = when (visual) {
            SendActionVisual.Send, SendActionVisual.Sending, SendActionVisual.Sent -> Color.White
            SendActionVisual.Failed -> WandColors.danger
            else -> WandColors.textMuted.copy(alpha = 0.45f)
        },
    )
}
