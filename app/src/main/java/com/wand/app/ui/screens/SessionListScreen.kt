package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 会话列表状态。提升到导航栈外层持有（remember 于 WandApp），
 * 进聊天后返回不重新加载。对称 iOS SessionListView 的 @State。
 */
class SessionListState(val api: WandApi) {
    var sessions by mutableStateOf<List<SessionSnapshot>>(emptyList())
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)

    val visibleSessions: List<SessionSnapshot>
        get() = sessions.filter { (it.archived ?: false) == false }

    suspend fun load(silent: Boolean = false) {
        if (!silent) loading = true
        try {
            sessions = api.listSessions()
            loadError = null
        } catch (e: Exception) {
            if (!silent || sessions.isEmpty()) {
                loadError = e.message ?: "加载失败"
            }
        }
        loading = false
    }

    fun prepend(snapshot: SessionSnapshot) {
        sessions = listOf(snapshot) + sessions.filter { it.id != snapshot.id }
    }

    fun removeLocally(sessionId: String) {
        sessions = sessions.filter { it.id != sessionId }
    }
}

/** 会话列表：原生渲染 /api/sessions，下拉刷新 + 10s 轮询，滑动删除，新建入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: SessionListState,
    onOpenChat: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.load(silent = state.sessions.isNotEmpty())
        while (true) {
            delay(10_000)
            state.load(silent = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Wand", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "菜单",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("打开网页版") },
                                onClick = { menuOpen = false; onOpenWeb() },
                            )
                            DropdownMenuItem(
                                text = { Text("切换服务器") },
                                onClick = { menuOpen = false; onSwitchServer() },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNewSession) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新建会话",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.sessions.isEmpty() -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.loadError != null && state.sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            state.loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(onClick = { scope.launch { state.load() } }) {
                            Text("重试")
                        }
                    }
                }
                state.visibleSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "还没有会话",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Button(onClick = onNewSession) { Text("新建会话") }
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                state.load(silent = true)
                                refreshing = false
                            }
                        },
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.visibleSessions, key = { it.id }) { session ->
                                SwipeDeleteRow(
                                    onDelete = {
                                        state.removeLocally(session.id)
                                        scope.launch {
                                            try {
                                                state.api.deleteSession(session.id)
                                            } catch (_: Exception) {
                                                state.load(silent = true)
                                            }
                                        }
                                    },
                                ) {
                                    SessionRow(
                                        session = session,
                                        onClick = { onOpenChat(session.id) },
                                    )
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "删除",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun SessionRow(session: SessionSnapshot, onClick: () -> Unit) {
    val statusTint = statusTint(session)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(statusTint),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                Text(
                    if (session.isStructured) "聊天" else "终端",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                val cwdTail = session.cwd
                    ?.trimEnd('/')
                    ?.substringAfterLast('/')
                    .orEmpty()
                if (cwdTail.isNotEmpty()) {
                    Text(
                        cwdTail,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            statusLabel(session),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = statusTint,
        )
    }
}

@Composable
private fun statusTint(session: SessionSnapshot): Color {
    if (session.hasPendingPermission) return WandColors.permission
    return when (session.status ?: "") {
        "running" -> if (session.isResponding) WandColors.running else WandColors.brand
        "idle" -> WandColors.brand.copy(alpha = 0.6f)
        else -> Color.Gray
    }
}

private fun statusLabel(session: SessionSnapshot): String {
    if (session.hasPendingPermission) return "待授权"
    if (session.isResponding) return "回复中"
    return when (session.status ?: "") {
        "running" -> "运行中"
        "idle" -> "空闲"
        "exited", "stopped" -> "已结束"
        "failed" -> "失败"
        else -> session.status ?: ""
    }
}
