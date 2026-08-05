package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wand.app.data.AgentActivityItem
import com.wand.app.data.MissionAttempt
import com.wand.app.data.MissionDiff
import com.wand.app.data.MissionInfo
import com.wand.app.data.MissionsPort
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MissionProviders = listOf("claude", "codex", "opencode", "grok", "qoder", "pi")

private fun missionStateLabel(state: String): String = when (state) {
    "dispatching", "queued" -> "分派中"
    "running", "working" -> "执行中"
    "needs_input" -> "等待答复"
    "needs_permission" -> "等待授权"
    "completed", "done" -> "已完成"
    "failed" -> "失败"
    else -> state
}

@Composable
private fun missionStateColors(state: String): Pair<Color, Color> = when (state) {
    "running", "working" -> WandColors.info to WandColors.infoSoft
    "needs_input", "needs_permission" -> WandColors.warning to WandColors.warningSoft
    "completed", "done" -> WandColors.success to WandColors.successSoft
    "failed" -> WandColors.danger to WandColors.dangerSoft
    else -> WandColors.textSecondary to WandColors.surfaceSoft
}

@Composable
fun MissionsScreen(
    api: MissionsPort,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var missions by remember { mutableStateOf<List<MissionInfo>>(emptyList()) }
    var inbox by remember { mutableStateOf<List<AgentActivityItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var defaultCwd by remember { mutableStateOf("") }
    var diffState by remember { mutableStateOf<Pair<MissionInfo, MissionAttempt>?>(null) }
    var diff by remember { mutableStateOf<MissionDiff?>(null) }

    suspend fun refresh(showProgress: Boolean = false) {
        if (showProgress) loading = true
        try {
            val nextInbox = api.fetchInbox()
            val nextMissions = api.fetchMissions()
            inbox = nextInbox
            missions = nextMissions
            error = null
        } catch (e: Exception) {
            error = e.message ?: "无法加载 Agent Inbox"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        defaultCwd = runCatching { api.defaultMissionCwd() }.getOrDefault("")
        refresh(showProgress = true)
        while (true) {
            delay(4_000)
            refresh()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WandColors.bgElevated.copy(alpha = 0.94f))
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("返回", color = WandColors.brand) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Agent Inbox", style = MaterialTheme.typography.titleLarge, color = WandColors.textPrimary)
                        Text("并行任务与待处理会话", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                    }
                    WandButton(label = "新任务", onClick = { showCreate = true }, variant = WandButtonVariant.Secondary)
                }
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    MissionTab("Inbox", inbox.count { it.state == "needs_input" || it.state == "needs_permission" }, selectedTab == 0) { selectedTab = 0 }
                    Spacer(Modifier.width(8.dp))
                    MissionTab("任务", missions.size, selectedTab == 1) { selectedTab = 1 }
                }
                HorizontalDivider(color = WandColors.border)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && inbox.isEmpty() && missions.isEmpty() -> CircularProgressIndicator(
                    color = WandColors.brand,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                selectedTab == 0 -> InboxList(inbox, onOpenSession = { sessionId ->
                    scope.launch { runCatching { api.markInboxRead(sessionId) } }
                    onOpenSession(sessionId)
                })
                else -> MissionsList(
                    missions = missions,
                    onOpenSession = onOpenSession,
                    onOpenDiff = { mission, attempt ->
                        diffState = mission to attempt
                        diff = null
                        scope.launch {
                            runCatching { api.fetchMissionDiff(mission.id, attempt.id) }
                                .onSuccess { diff = it }
                                .onFailure { error = it.message }
                        }
                    },
                )
            }
            error?.let { message ->
                Surface(
                    color = WandColors.dangerSoft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) { Text(message, color = WandColors.danger, modifier = Modifier.padding(12.dp)) }
            }
        }
    }

    if (showCreate) {
        CreateMissionDialog(
            defaultCwd = defaultCwd,
            onDismiss = { showCreate = false },
            onCreate = { title, prompt, cwd, providers, baseRef, shared, copied ->
                scope.launch {
                    try {
                        api.createMission(title, prompt, cwd, providers, baseRef, shared, copied)
                        showCreate = false
                        selectedTab = 1
                        refresh(showProgress = true)
                    } catch (e: Exception) { error = e.message }
                }
            },
        )
    }

    diffState?.let { (missionSnapshot, attempt) ->
        val mission = missions.firstOrNull { it.id == missionSnapshot.id } ?: missionSnapshot
        MissionDiffDialog(
            mission = mission,
            attempt = attempt,
            diff = diff,
            onDismiss = { diffState = null; diff = null },
            onAddComment = { file, line, side, body ->
                scope.launch {
                    try {
                        api.addMissionReviewComment(mission.id, attempt.id, file, line, side, body)
                        refresh()
                    } catch (e: Exception) { error = e.message }
                }
            },
            onSendReview = {
                scope.launch {
                    try {
                        api.sendMissionReview(mission.id, attempt.id)
                        refresh()
                    } catch (e: Exception) { error = e.message }
                }
            },
        )
    }
}

@Composable
private fun MissionTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) WandColors.brandSoft else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            "$label  $count",
            color = if (selected) WandColors.brand else WandColors.textSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InboxList(items: List<AgentActivityItem>, onOpenSession: (String) -> Unit) {
    val groups = listOf(
        "需要你" to items.filter { it.state == "needs_input" || it.state == "needs_permission" },
        "执行中" to items.filter { it.state == "working" },
        "已结束" to items.filter { it.state == "done" || it.state == "failed" },
    )
    LazyColumn(
        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        groups.forEach { (title, entries) ->
            if (entries.isNotEmpty()) {
                item(key = "header-$title") { Text(title, color = WandColors.textMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                items(entries, key = { it.sessionId }) { item ->
                    WandCard(onClick = { onOpenSession(item.sessionId) }, contentPadding = PaddingValues(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val colors = missionStateColors(item.state)
                            Box(Modifier.size(9.dp).background(colors.first, RoundedCornerShape(50)))
                            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                                Text(item.title, color = WandColors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.summary ?: item.cwd ?: "暂无摘要", color = WandColors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            StatePill(item.state)
                        }
                    }
                }
            }
        }
        if (items.isEmpty()) item { Text("目前没有 Agent 活动", color = WandColors.textMuted, modifier = Modifier.fillMaxWidth().padding(40.dp)) }
    }
}

@Composable
private fun MissionsList(
    missions: List<MissionInfo>,
    onOpenSession: (String) -> Unit,
    onOpenDiff: (MissionInfo, MissionAttempt) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(missions, key = { it.id }) { mission ->
            WandCard(contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mission.title, color = WandColors.textPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("${mission.attempts.size} 个 Agent · ${mission.baseRef ?: "当前分支"}", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    StatePill(mission.status)
                }
                Text(mission.prompt, color = WandColors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 10.dp))
                mission.attempts.forEach { attempt ->
                    HorizontalDivider(color = WandColors.border.copy(alpha = 0.55f))
                    Row(modifier = Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = BrandLogos.painterForProvider(attempt.provider),
                            contentDescription = null,
                            tint = BrandLogos.tintForProvider(attempt.provider, WandColors.brand),
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                            Text(attempt.provider, color = WandColors.textPrimary, fontWeight = FontWeight.Medium)
                            Text(attempt.summary ?: attempt.error ?: attempt.branch ?: "准备 worktree…", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(enabled = attempt.sessionId != null, onClick = { attempt.sessionId?.let(onOpenSession) }) { Text("会话") }
                        TextButton(enabled = attempt.worktreePath != null, onClick = { onOpenDiff(mission, attempt) }) { Text("Diff") }
                    }
                }
            }
        }
        if (missions.isEmpty()) item { Text("创建任务后，每个 Provider 会在独立 worktree 中并行执行。", color = WandColors.textMuted, modifier = Modifier.padding(36.dp)) }
    }
}

@Composable
private fun StatePill(state: String) {
    val (foreground, background) = missionStateColors(state)
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Text(missionStateLabel(state), color = foreground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun CreateMissionDialog(
    defaultCwd: String,
    onDismiss: () -> Unit,
    onCreate: (String?, String, String, List<String>, String?, List<String>, List<String>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var cwd by remember(defaultCwd) { mutableStateOf(defaultCwd) }
    var baseRef by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf(setOf("claude", "codex")) }
    WandDialog(
        onDismissRequest = onDismiss,
        title = "并行任务",
        confirm = WandDialogAction(
            label = "分派给 ${providers.size} 个 Agent",
            enabled = prompt.isNotBlank() && cwd.isNotBlank() && providers.isNotEmpty(),
            onClick = {
                val split: (String) -> List<String> = { value -> value.split(',').map(String::trim).filter(String::isNotBlank) }
                onCreate(title.trim().ifBlank { null }, prompt.trim(), cwd.trim(), providers.toList(), baseRef.trim().ifBlank { null }, split(shared), split(copied))
            },
        ),
        dismiss = WandDialogAction(label = "取消", onClick = onDismiss),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("每个 Provider 会获得独立 branch 与 worktree。", color = WandColors.textSecondary, style = MaterialTheme.typography.bodySmall)
            WandTextField(title, { title = it }, label = "标题（可选）", singleLine = true, modifier = Modifier.fillMaxWidth())
            WandTextField(prompt, { prompt = it }, label = "任务目标", minLines = 4, modifier = Modifier.fillMaxWidth())
            WandTextField(cwd, { cwd = it }, label = "项目目录", singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MissionProviders.forEach { provider ->
                    FilterChip(selected = provider in providers, onClick = {
                        providers = if (provider in providers) providers - provider else providers + provider
                    }, label = { Text(provider) })
                }
            }
            WandTextField(baseRef, { baseRef = it }, label = "基线 ref（可选）", singleLine = true, modifier = Modifier.fillMaxWidth())
            WandTextField(shared, { shared = it }, label = "共享 gitignored 目录", placeholder = "node_modules, .venv", singleLine = true, modifier = Modifier.fillMaxWidth())
            WandTextField(copied, { copied = it }, label = "复制 gitignored 路径", placeholder = ".env.local", singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class ReviewTarget(val file: String, val line: Int?, val side: String)
private data class RenderedDiffLine(val text: String, val target: ReviewTarget?)

private fun renderDiffLines(patch: String): List<RenderedDiffLine> {
    var oldFile: String? = null
    var newFile: String? = null
    var oldLine = 0
    var newLine = 0
    return patch.lineSequence().take(5_000).map { text ->
        if (text.startsWith("--- ")) {
            val path = text.removePrefix("--- ").removePrefix("a/")
            oldFile = path.takeUnless { it == "/dev/null" }
        }
        if (text.startsWith("+++ ")) {
            val path = text.removePrefix("+++ ").removePrefix("b/")
            newFile = path.takeUnless { it == "/dev/null" }
        }
        Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(text)?.let { match ->
            oldLine = match.groupValues[1].toInt()
            newLine = match.groupValues[2].toInt()
            return@map RenderedDiffLine(text, null)
        }
        when {
            text.startsWith("+") && !text.startsWith("+++") -> RenderedDiffLine(text, (newFile ?: oldFile)?.let { ReviewTarget(it, newLine++, "new") })
            text.startsWith("-") && !text.startsWith("---") -> RenderedDiffLine(text, (oldFile ?: newFile)?.let { ReviewTarget(it, oldLine++, "old") })
            text.startsWith(" ") -> RenderedDiffLine(text, (newFile ?: oldFile)?.let { ReviewTarget(it, newLine++, "new") }).also { oldLine++ }
            else -> RenderedDiffLine(text, null)
        }
    }.toList()
}

@Composable
private fun MissionDiffDialog(
    mission: MissionInfo,
    attempt: MissionAttempt,
    diff: MissionDiff?,
    onDismiss: () -> Unit,
    onAddComment: (String, Int?, String, String) -> Unit,
    onSendReview: () -> Unit,
) {
    var target by remember(diff) { mutableStateOf<ReviewTarget?>(null) }
    var body by remember(diff) { mutableStateOf("") }
    val lines = remember(diff?.patch) { diff?.let { renderDiffLines(it.patch) } ?: emptyList() }
    val pending = mission.comments.count { it.attemptId == attempt.id && it.status == "pending" }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = WandColors.bgPrimary, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Column(Modifier.weight(1f)) {
                        Text("${attempt.provider} Diff", color = WandColors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(diff?.let { "${it.fileCount} 个文件 · ${it.baseRef}" } ?: "正在读取…", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (pending > 0) WandButton(label = "发送 $pending 条", onClick = onSendReview)
                }
                HorizontalDivider(color = WandColors.border)
                if (diff == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WandColors.brand) }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101214))) {
                        items(lines) { line ->
                            Text(
                                line.text.ifEmpty { " " },
                                color = when {
                                    line.text.startsWith("+") && !line.text.startsWith("+++") -> Color(0xFF91D39D)
                                    line.text.startsWith("-") && !line.text.startsWith("---") -> Color(0xFFF29B94)
                                    else -> Color(0xFFC9D1D9)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                softWrap = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = line.target != null) { target = line.target }
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                    target?.let { reviewTarget ->
                        Column(Modifier.fillMaxWidth().background(WandColors.bgElevated).padding(12.dp)) {
                            Text("${reviewTarget.file}:${reviewTarget.line ?: ""}", color = WandColors.textMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            WandTextField(body, { body = it }, placeholder = "写下具体、可执行的修改意见…", minLines = 2, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp))
                            WandButton(
                                label = "加入 Review",
                                enabled = body.isNotBlank(),
                                onClick = { onAddComment(reviewTarget.file, reviewTarget.line, reviewTarget.side, body.trim()); body = ""; target = null },
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }
                }
            }
        }
    }
}
