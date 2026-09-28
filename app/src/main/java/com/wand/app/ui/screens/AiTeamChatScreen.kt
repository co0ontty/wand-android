package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.data.AiTeamLiveStep
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TeamRunAction
import com.wand.app.data.TurnAuthor
import com.wand.app.data.WandApi
import com.wand.app.data.aiTeamRunActive
import com.wand.app.data.aiTeamRunStatusLabel
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.SendPhase
import com.wand.app.ui.sendActionVisual
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.WandBreadcrumb
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandCrumb
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 团队群聊页（对齐 Web `src/web-ui/react/ai-teams/team-chat-view.tsx` 的分层）：
 * 顶部钉住主任务公告，负责人发言是公告卡，成员发言是带状态芯片的步骤块，
 * 系统提示居中弱化且完整折行，用户消息右侧气泡 + 乐观未确认。
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
 * live 文本单独一档节奏（§4.9.1）：移动端没有可用的系统通知通道，只能轮询 `/live`。
 * 只在页面可见且有步骤在跑时跑，和上面 detail 的 4s/15s 互不影响。
 */
private const val TEAM_LIVE_POLL_MS = 1_200L

/** live 卡片固定高度，两端同值（Web `.team-chat-live-card { height: 200px }` 的 dp 版）。 */
private val TeamLiveCardHeight = 200.dp

/**
 * 署名与步骤标题在署名行里的宽度上限：超了就省略号，把整行的空间让给状态芯片，
 * 让「工作中」在任何字号下都是完整一块（对应 Web 头部 `flex-wrap` 的让位行为）。
 */
private val TeamChatSignatureMaxWidth = 230.dp
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
    var draft by remember(runId) { mutableStateOf("") }
    // 署名里的模型名要把 `default` 哨兵换成一个具体的默认模型（同 Web 目录口径）；拉失败只影响这一段。
    var models by remember(runId) { mutableStateOf<ModelsResponse?>(null) }
    var sending by remember(runId) { mutableStateOf(false) }
    var sendError by remember(runId) { mutableStateOf<String?>(null) }
    var localTurns by remember(runId) { mutableStateOf<List<LocalChatTurn>>(emptyList()) }
    /** 正在输出的成员（§4.9.1）：一行一个 running 步骤，退场行留在原位倒放收回。 */
    var liveRows by remember(runId) { mutableStateOf<List<LiveChatRow>>(emptyList()) }
    var resumed by remember(runId) { mutableStateOf(true) }

    val run = detail?.run
    val status = run?.status ?: "running"
    val hint = chatInputHint(status)
    val chatSessionId = run?.chatSessionId

    // 详情轮询随页面可见性走：后台不烧请求，回前台立刻补一次。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentRunId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    scope.launch { detail = runCatching { api.aiTeamRunDetail(currentRunId) }.getOrNull() ?: detail }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP,
                -> resumed = false
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

    // 模型目录只拉一次：署名里的模型名不随轮询变化，失败就退化成不写模型段。
    LaunchedEffect(api, runId) {
        models = runCatching { api.models() }.getOrDefault(models)
    }

    val listState = rememberLazyListState()
    val turnCount = (detail?.chatTurns?.size ?: 0) + localTurns.size
    val density = LocalDensity.current.density
    /**
     * 外层列表是否贴底：与卡片内滚同一套「距底 ≤ [LIVE_TAIL_DP]（按 density 换算成像素）才跟」口径
     * （对齐 Web `team-chat-view.tsx` 的 `listPinnedRef`），上滚看历史时 live 行插入 / 摘除不把他拽回尾部。
     */
    var listPinned by remember(runId) { mutableStateOf(true) }

    // 判定只由「用户自己在滚」更新：live 行插入也会让距底突然变大，那不是用户的意图。
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

    /**
     * live 行退场计时结束后把它摘掉（`TeamLiveStepRow` 自己报，见那里的 `onRetire`）。
     * 只摘已经 `leaving` 的那一行，同一步重新开工时不会被误撤。
     */
    fun retireLiveRow(stepId: String) {
        liveRows = liveRows.filterNot { it.leaving && it.step.stepId == stepId }
    }
    /**
     * live 文本（§4.9.1）：只有「页面可见 + 本次运行还有 running 步骤」才轮询，
     * 两个条件任一不满足就停，并把留在列表里的卡片推成退场（不闪空白、不抢请求）。
     * 单次拉取失败保留上一份文本，不清空也不显示加载态。
     */
    val hasRunningStep = detail?.steps?.any { it.status == "running" } == true
    LaunchedEffect(api, currentRunId, resumed, hasRunningStep) {
        if (!resumed || !hasRunningStep) {
            liveRows = mergeLiveRows(liveRows, emptyList())
            return@LaunchedEffect
        }
        while (true) {
            val update = runCatching { api.aiTeamRunLive(currentRunId) }.getOrNull()
            if (update != null && update.runId == currentRunId) liveRows = mergeLiveRows(liveRows, update.steps)
            delay(TEAM_LIVE_POLL_MS)
        }
    }

    // 列表尾是 live 行，贴底要把它一起数进去；本来就贴着底才跟着新行滚到底（同 Web 口径）。
    LaunchedEffect(turnCount, liveRows.size, listPinned) {
        if (!listPinned) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last)
    }

    fun act(action: TeamRunAction) {
        if (busy) return
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
        val text = draft.trim()
        val sessionId = chatSessionId
        if (text.isEmpty() || sessionId == null || sending) return
        val sentAt = System.currentTimeMillis()
        // 乐观留在原位，服务端回合里出现同一条 user turn 才撤（对齐 Web settleLocalTurns）。
        localTurns = localTurns + LocalChatTurn(text, sentAt)
        // 自己刚发的话一定要看见：发送算一次明确的「回到底部」意图。
        listPinned = true
        draft = ""
        sending = true
        sendError = null
        scope.launch {
            val accepted = runCatching { api.sendInput(sessionId, text) }
            if (accepted.isFailure) {
                val cause = accepted.exceptionOrNull()
                if (cause != null && chatSendDefinitelyRejected(cause)) {
                    localTurns = localTurns.filterNot { it.sentAtMillis == sentAt }
                    draft = if (draft.isBlank()) text else "$text\n$draft"
                    sendError = cause.message ?: "发送失败，内容已放回输入框"
                } else {
                    localTurns = localTurns.map {
                        if (it.sentAtMillis == sentAt) it.copy(unconfirmed = true) else it
                    }
                    sendError = "送达状态未知，请先查看群聊记录，避免重复发送"
                }
                sending = false
                return@launch
            }
            // 服务端回包不通知客户端，这里自己补一次重拉；失败就把临时行留在原位标未确认。
            val turns = runCatching { api.aiTeamRunDetail(currentRunId) }.getOrNull()?.chatTurns
            localTurns = settleLocalTurns(localTurns, turns)
            sending = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WandDetailTopBar(
            title = "",
            // 返回入口只留一个：面包屑首段。原来的箭头与它同功能、同一条栏，属重复。
            leading = null,
            titleContent = {
                Column(modifier = Modifier.weight(1f)) {
                    WandBreadcrumb(
                        crumbs = listOf(
                            // 没有可回的去处时不给假入口：宽屏左栏点带「群聊」标记的会话行
                            // 走 setDetail，群聊落在 stack[1]、栈深 2，showDetailBack 就是 false，
                            // 那一层列表此刻正显示在左边。
                            WandCrumb(
                                if (taskIdentifier != null) "任务" else "群聊",
                                onClick = if (showBack) onBack else null,
                            ),
                            WandCrumb(
                                taskIdentifier
                                    ?: run?.team?.name?.takeIf { it.isNotBlank() }
                                    ?: "群聊",
                            ),
                        ),
                    )
                    Text(
                        listOfNotNull(
                            aiTeamRunStatusLabel(status),
                            run?.let { "步数 ${it.stepsUsed}/${it.stepLimit}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                if (run != null && aiTeamRunActive(status)) {
                    ToolbarIconButton(
                        icon = WandIcons.stop,
                        contentDescription = "停止团队",
                        onClick = { act(TeamRunAction.Stop) },
                        enabled = !busy,
                        tint = WandColors.danger,
                    )
                }
                chatSessionId?.let { sessionId ->
                    ToolbarIconButton(
                        icon = WandIcons.read,
                        contentDescription = "查看完整会话记录",
                        onClick = { onOpenFullSession(sessionId) },
                    )
                }
            },
        )

        val current = detail
        when {
            current == null && loadError != null -> TeamChatLine(loadError ?: "无法加载群聊", WandColors.danger)
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WandColors.brand, modifier = Modifier.size(26.dp))
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .widthIn(max = TeamChatReadableMaxWidth)
                            .align(Alignment.CenterHorizontally),
                        contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, TeamChatListBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "team-goal") {
                            TeamMainTaskCard(current)
                        }
                        item(key = "team-office") {
                            TeamOfficeStrip(current, onOpenMemberSession)
                        }
                        val needsYou = current.run.status == "awaiting_approval" ||
                            current.run.status == "waiting_user"
                        if (needsYou) {
                            item(key = "team-actions") {
                                TeamRunActionBar(
                                    detail = current,
                                    busy = busy,
                                    draft = draft,
                                    onAction = ::act,
                                )
                            }
                        }
                        if (current.chatTurns.isEmpty() && localTurns.isEmpty() && liveRows.isEmpty()) {
                            item(key = "team-empty") {
                                TeamChatLine("群聊还没有消息。", WandColors.textMuted)
                            }
                        }
                        current.chatTurns.forEachIndexed { index, turn ->
                            item(key = "turn#$index#${turn.createdAt.orEmpty()}") {
                                TeamTurnRow(
                                    turn = turn,
                                    steps = current.steps,
                                    models = models,
                                    onOpenMemberSession = onOpenMemberSession,
                                )
                            }
                        }
                        // 正在输出的成员排在历史回合之后、乐观临时行之前（同 Web）。
                        liveRows.forEach { row ->
                            item(key = "live#${row.step.stepId}") {
                                Box(
                                    modifier = if (reduceMotionEnabled()) {
                                        Modifier
                                    } else {
                                        Modifier.animateItem()
                                    },
                                ) {
                                    TeamLiveStepRow(
                                        row = row,
                                        steps = current.steps,
                                        memberStates = current.memberStates,
                                        models = models,
                                        onOpenMemberSession = onOpenMemberSession,
                                        onRetire = ::retireLiveRow,
                                    )
                                }
                            }
                        }
                        localTurns.forEach { row ->
                            item(key = "local#${row.sentAtMillis}") {
                                TeamUserBubble(row.text, row.unconfirmed)
                            }
                        }
                    }
                    TeamChatComposer(
                        hint = hint,
                        draft = draft,
                        sending = sending,
                        busy = busy,
                        status = status,
                        chatSessionId = chatSessionId,
                        error = sendError,
                        onDraftChange = { draft = it },
                        onSend = ::send,
                        onStop = { act(TeamRunAction.Stop) },
                    )
                }
            }
        }
    }
}

/** 顶部钉住的「主任务」：群公告位，永远在群聊第一屏，长目标原位展开。 */
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
                "${run.stepsUsed}/${run.stepLimit} 步 · ${run.team?.name?.takeIf { it.isNotBlank() } ?: "AI 团队"}",
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(26.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                    ) {
                        Text(entry.member.name.take(1), color = tint, fontSize = 12.sp)
                    }
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

@Composable
private fun TeamTurnRow(
    turn: ConversationTurn,
    steps: List<AiTeamStep>,
    models: ModelsResponse?,
    onOpenMemberSession: (String) -> Unit,
) {
    when (chatTurnKind(turn)) {
        TeamChatTurnKind.Notice -> TeamNoticeRow(turn)
        TeamChatTurnKind.User -> TeamUserBubble(chatTurnText(turn), unconfirmed = false)
        TeamChatTurnKind.Leader -> TeamLeaderCard(turn, models, onOpenMemberSession)
        TeamChatTurnKind.Step -> TeamStepRow(turn, steps, models, onOpenMemberSession)
    }
}

/**
 * 署名行：头像 + 名字（可点进该成员会话）+ 「CLI · 模型 · 思考深度」+ 可选芯片 + 时刻。
 *
 * 用 `FlowRow` 而不是 `Row`：字号放大（font_scale 1.3）时这一行的内容会超出列宽，
 * `Row` 会把不收缩的状态芯片挤成十几像素的小方块。FlowRow 让它整块换到下一行，
 * 列内不裁切；先让位的是签名与步骤标题（各自有宽度上限 + 省略号），状态芯片保持原宽可读。
 */
@Composable
private fun TeamAuthorLine(
    author: TurnAuthor?,
    fallbackName: String,
    badge: String?,
    models: ModelsResponse?,
    trailing: (@Composable (() -> Unit))? = null,
    clock: String,
    onOpenMemberSession: (String) -> Unit,
) {
    val signature = agentSignatureLabel(author?.provider, author?.model, author?.thinkingEffort, models)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TeamMemberAvatar(author)
        val sessionId = author?.sessionId?.takeIf { it.isNotBlank() }
        if (sessionId != null) {
            Text(
                author?.name ?: fallbackName,
                style = MaterialTheme.typography.titleSmall,
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
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
        }
        if (badge != null) TeamChatBadge(badge)
        if (signature.isNotBlank()) {
            Text(
                signature,
                fontSize = 11.sp,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = TeamChatSignatureMaxWidth),
            )
        }
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

/** 负责人发言：公告卡 + 派工清单条目，和成员气泡在形态上分开。 */
@Composable
private fun TeamLeaderCard(turn: ConversationTurn, models: ModelsResponse?, onOpenMemberSession: (String) -> Unit) {
    val message = splitLeaderMessage(chatTurnText(turn))
    WandCard(
        containerColor = WandColors.brandSoft.copy(alpha = 0.30f),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TeamAuthorLine(
            author = turn.author,
            fallbackName = "负责人",
            badge = "负责人",
            models = models,
            clock = conversationTurnClock(turn),
            onOpenMemberSession = onOpenMemberSession,
        )
        if (message.head.isNotBlank()) {
            TeamCollapsibleBody(message.head)
        }
        if (message.assignments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message.assignments.forEachIndexed { index, item ->
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
                        Text(
                            "@${item.member}",
                            fontSize = 12.sp,
                            color = WandColors.brand,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                fontSize = 13.sp,
                                color = WandColors.textPrimary,
                            )
                            if (item.wait.isNotBlank()) {
                                Text(item.wait, fontSize = 11.sp, color = WandColors.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 成员发言：步骤状态芯片 + 报告正文，长报告原位展开。 */
@Composable
private fun TeamStepRow(
    turn: ConversationTurn,
    steps: List<AiTeamStep>,
    models: ModelsResponse?,
    onOpenMemberSession: (String) -> Unit,
) {
    val text = chatTurnText(turn)
    val report = parseStepReport(text)
    val stepStatus = teamStepStatus(steps, report)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TeamAuthorLine(
            author = turn.author,
            fallbackName = "成员",
            badge = null,
            models = models,
            clock = conversationTurnClock(turn),
            onOpenMemberSession = onOpenMemberSession,
            trailing = {
                if (report != null) {
                    TeamStepChip(report.title, report.ok, stepStatus)
                }
            },
        )
        TeamCollapsibleBody((report?.body ?: text).ifBlank { "（这条消息没有正文）" })
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

/**
 * 一行 live 输出（§4.9.1）：成员一开始干活署名行就出现，卡片随后从头像那侧长出；
 * 步骤收工（或页面不可见）时整行倒放收回 —— 收起是展开的倒放。关掉动效时只剩淡入淡出。
 */
@Composable
private fun TeamLiveStepRow(
    row: LiveChatRow,
    steps: List<AiTeamStep>,
    memberStates: Map<String, String>,
    models: ModelsResponse?,
    onOpenMemberSession: (String) -> Unit,
    onRetire: (String) -> Unit,
) {
    val step = row.step
    val motion = !reduceMotionEnabled()
    val enter = fadeIn(WandMotion.tweenEnter()) +
        if (motion) slideInHorizontally(WandMotion.tweenEnter()) { -it / 4 } else EnterTransition.None
    val exit = fadeOut(WandMotion.tweenExit()) +
        if (motion) slideOutHorizontally(WandMotion.tweenExit()) { -it / 4 } else ExitTransition.None
    var cardShown by remember(step.stepId) { mutableStateOf(false) }
    LaunchedEffect(step.stepId) { cardShown = true }
    // Compose 的 AnimatedVisibility 不派发「动画结束」，退场行按退场时长自己计时摘除；
    // 计时挂在行上，所以下一次轮询不会把它腰斩（同 Web 的 animationend → onRetire）。
    LaunchedEffect(step.stepId, row.leaving) {
        if (!row.leaving) return@LaunchedEffect
        delay(WandMotion.fast.toLong())
        onRetire(step.stepId)
    }
    AnimatedVisibility(visible = !row.leaving, enter = enter, exit = exit) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val state = memberStates[step.sessionId] ?: step.state
            TeamAuthorLine(
                author = TurnAuthor(
                    id = step.memberId,
                    name = step.memberName.ifBlank { "成员" },
                    provider = step.provider,
                    model = step.model,
                    thinkingEffort = step.thinkingEffort,
                    sessionId = step.sessionId,
                ),
                fallbackName = "成员",
                badge = null,
                models = models,
                clock = formatChatClock(step.updatedAt),
                onOpenMemberSession = onOpenMemberSession,
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeamLiveStepChip(liveStepChip(step, steps), Modifier.weight(1f, fill = false))
                        val label = liveStateLabel(state)
                        if (label.isNotBlank()) TeamLiveStateChip(label, state)
                    }
                },
            )
            AnimatedVisibility(visible = cardShown, enter = enter, exit = exit) {
                TeamLiveStepCard(step, onOpenMemberSession)
            }
        }
    }
}

/**
 * live 气泡卡：固定高 [TeamLiveCardHeight]、宽度撑满列（列本身已被 TeamChatReadableMaxWidth 夹住），
 * 正文在里面 `verticalScroll`。所以输出变多时卡片尺寸与位置都不变，不会顶下面的行。
 * 整卡点开该成员的完整会话；Compose 里滚动由 `verticalScroll` 消费，不会被误判成点击。
 */
@Composable
private fun TeamLiveStepCard(
    step: AiTeamLiveStep,
    onOpenMemberSession: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val tailThresholdPx = liveTailThresholdPx(LocalDensity.current.density)
    var viewportPx by remember(step.stepId) { mutableIntStateOf(0) }
    var contentPx by remember(step.stepId) { mutableIntStateOf(0) }
    var pinned by remember(step.stepId) { mutableStateOf(true) }
    val omitted = liveOmittedText(step.omittedChars)
    // 贴尾判定只看用户滚到哪：自己滚到底的那次算回来仍然是贴尾，不会把开关弄反。
    LaunchedEffect(scroll.value, viewportPx, contentPx, tailThresholdPx) {
        pinned = shouldFollowTail(scroll.value, contentPx, viewportPx, tailThresholdPx)
    }
    LaunchedEffect(step.text) {
        if (pinned) scroll.scrollTo(Int.MAX_VALUE)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(TeamLiveCardHeight)
            .clip(WandShapes.md)
            .border(1.dp, WandColors.border.copy(alpha = 0.55f), WandShapes.md)
            .background(WandColors.surfaceSoft.copy(alpha = 0.55f))
            .clickable(onClickLabel = "查看该成员的完整会话") {
                if (step.sessionId.isNotBlank()) onOpenMemberSession(step.sessionId)
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (omitted.isNotBlank()) {
            Text(omitted, fontSize = 11.sp, color = WandColors.textMuted)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .onSizeChanged { viewportPx = it.height },
        ) {
            Text(
                step.text.ifBlank { LIVE_EMPTY_TEXT },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { contentPx = it.height },
            )
        }
    }
}

@Composable
private fun TeamLiveStepChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 11.sp,
        color = WandColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            // 步骤标题先让位：宽度封顶 + 省略号，旁边的状态芯片才不会被挤成小方块。
            .widthIn(max = TeamChatStepChipMaxWidth)
            .clip(WandShapes.xs)
            .background(WandColors.surfaceSoft.copy(alpha = 0.8f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 状态芯片：等人类操作的两态要更显眼（warning），和 Web 的 `.team-chat-live-state` 同一套配色逻辑。 */
@Composable
private fun TeamLiveStateChip(label: String, state: String) {
    val tint = when (state) {
        "needs_input", "needs_permission" -> WandColors.warning
        "failed" -> WandColors.danger
        "done" -> WandColors.success
        else -> WandColors.info
    }
    Text(
        label,
        fontSize = 11.sp,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(WandShapes.xs)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 系统提示行：居中弱化，正文完整折行，时刻不会被挤出屏幕。 */
@Composable
private fun TeamNoticeRow(turn: ConversationTurn) {
    val text = chatTurnText(turn)
    if (text.isBlank()) return
    val clock = conversationTurnClock(turn)
    val author = turn.author?.name?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .clip(WandShapes.sm)
                .background(WandColors.surfaceSoft.copy(alpha = 0.58f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (author != null) {
                Text(author, fontSize = 11.sp, color = WandColors.textSecondary)
            }
            Text(
                text,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = WandColors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (clock.isNotBlank()) {
                Text(clock, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = WandColors.textMuted)
            }
        }
    }
}

/** 用户消息：右对齐气泡；乐观临时行的「未确认」写在气泡下方同一位置。 */
@Composable
private fun TeamUserBubble(text: String, unconfirmed: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = WandColors.textPrimary,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(WandShapes.md)
                .background(WandColors.brand.copy(alpha = 0.13f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (unconfirmed) {
            Text("未确认", fontSize = 11.sp, color = WandColors.warning)
        }
    }
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
private fun TeamMemberAvatar(author: TurnAuthor?) {
    val variant = memberAvatarVariant(author?.id, author?.name ?: "", author?.avatar)
    val palette = listOf(
        WandColors.brand,
        WandColors.info,
        WandColors.success,
        WandColors.warning,
        WandColors.thinking,
        WandColors.permission,
    )
    val tint = palette[variant % palette.size]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
    ) {
        Text(
            (author?.name?.firstOrNull() ?: 'W').toString(),
            fontSize = 12.sp,
            color = tint,
        )
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

/** 底部输入：引导语按运行状态给，发送结果在按钮原位（加载 → 完成）。 */
@Composable
private fun TeamChatComposer(
    hint: String,
    draft: String,
    sending: Boolean,
    busy: Boolean,
    status: String,
    chatSessionId: String?,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WandColors.surface.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (chatSessionId == null) {
            Text(
                "这次运行没有群聊会话，只能在时间线里看。",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textMuted,
            )
            return
        }
        if (hint.isNotBlank()) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        val visual = sendActionVisual(
            phase = if (sending) SendPhase.Sending else SendPhase.Idle,
            turnRunning = aiTeamRunActive(status),
            hasDraft = draft.isNotBlank(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WandTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = "在群里说点什么…",
                minLines = 1,
                maxLines = 4,
                enabled = !sending,
                modifier = Modifier.weight(1f),
            )
            TeamChatSendStop(
                visual = visual,
                active = aiTeamRunActive(status),
                busy = busy,
                onSend = onSend,
                onStop = onStop,
            )
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = WandColors.danger)
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
        enabled = visual == SendActionVisual.Send && !busy,
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
