package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.RecentPath
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import kotlinx.coroutines.launch

/**
 * 新建会话 —— 对称 iOS NewSessionView：
 * 选择工作目录（最近路径 / 内置目录浏览器）、会话类型与权限模式，可附带首条消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    api: WandApi,
    onBack: () -> Unit,
    onCreated: (SessionSnapshot) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var cwd by remember { mutableStateOf("") }
    var recentPaths by remember { mutableStateOf<List<RecentPath>>(emptyList()) }
    var isStructured by remember { mutableStateOf(true) }
    var modeIndex by remember { mutableStateOf(0) } // 0=默认 1=自动编辑 2=完全访问
    var firstMessage by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        recentPaths = try {
            api.recentPaths()
        } catch (_: Exception) {
            emptyList()
        }
        if (cwd.isEmpty()) {
            cwd = recentPaths.firstOrNull()?.path
                ?: try {
                    api.serverConfig().defaultCwd ?: ""
                } catch (_: Exception) {
                    ""
                }
        }
    }

    if (showBrowser) {
        DirectoryBrowserScreen(
            api = api,
            startPath = cwd,
            onPick = { picked ->
                cwd = picked
                showBrowser = false
            },
            onCancel = { showBrowser = false },
        )
        return
    }

    val canCreate = cwd.trim().isNotEmpty() && !creating

    fun create() {
        if (!canCreate) return
        creating = true
        errorMessage = null
        val path = cwd.trim()
        val prompt = firstMessage.trim().ifEmpty { null }
        val mode = when (modeIndex) {
            1 -> "auto-edit"
            2 -> "full-access"
            else -> null
        }
        scope.launch {
            try {
                val snapshot = if (isStructured) {
                    api.createStructuredSession(path, mode, prompt)
                } else {
                    api.createPtySession(path, mode, prompt)
                }
                creating = false
                onCreated(snapshot)
            } catch (e: Exception) {
                creating = false
                errorMessage = e.message ?: "创建失败"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("新建会话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (creating) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 16.dp).size(20.dp),
                        )
                    } else {
                        TextButton(onClick = { create() }, enabled = canCreate) {
                            Text(
                                "创建",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (canCreate) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // —— 工作目录 ——
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("工作目录")
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    placeholder = { Text("/path/to/project", fontFamily = FontFamily.Monospace) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showBrowser = true }) {
                    Text("📁 浏览目录…", fontSize = 14.sp)
                }
                recentPaths.take(5).forEach { recent ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { cwd = recent.path }
                            .padding(vertical = 6.dp),
                    ) {
                        Text("🕐", fontSize = 12.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                recent.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                recent.path,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (cwd == recent.path) {
                            Text(
                                "✓",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // —— 会话类型 ——
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("会话类型")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isStructured,
                        onClick = { isStructured = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("聊天", fontSize = 13.sp) }
                    SegmentedButton(
                        selected = !isStructured,
                        onClick = { isStructured = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("终端 (Claude CLI)", fontSize = 13.sp, maxLines = 1) }
                }
                SectionTitle("权限模式")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("默认", "自动编辑", "完全访问").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = modeIndex == index,
                            onClick = { modeIndex = index },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // —— 首条消息 ——
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("首条消息（可选）")
                OutlinedTextField(
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                    placeholder = { Text("想让它做什么…", fontSize = 15.sp) },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            errorMessage?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 极简目录浏览器 —— 对称 iOS DirectoryBrowserView：
 * 基于 /api/directory 逐层进入，选中当前目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryBrowserScreen(
    api: WandApi,
    startPath: String,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // 服务端不解析 ~（按 defaultCwd 相对路径处理会 ENOENT），兜底用根目录。
    var currentPath by remember { mutableStateOf(startPath.trim().ifEmpty { "/" }) }
    var items by remember { mutableStateOf<List<com.wand.app.data.DirectoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadKey by remember { mutableStateOf(0) }

    LaunchedEffect(loadKey) {
        loading = true
        errorMessage = null
        try {
            val listing = api.listDirectory(currentPath)
            items = listing.items
        } catch (e: Exception) {
            errorMessage = e.message ?: "加载失败"
        }
        loading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("选择目录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onCancel) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    TextButton(onClick = { onPick(currentPath) }) {
                        Text(
                            "选择此目录",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 路径头：上一级 + 当前路径。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                TextButton(
                    onClick = {
                        val parent = currentPath.trimEnd('/').substringBeforeLast('/')
                        if (parent.isNotEmpty() && parent != currentPath) {
                            currentPath = parent
                            loadKey++
                        } else if (currentPath != "/") {
                            currentPath = "/"
                            loadKey++
                        }
                    },
                ) { Text("⬆ 上一级", fontSize = 13.sp) }
                Text(
                    currentPath,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            when {
                loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                errorMessage != null -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        errorMessage ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.filter { it.isDirectory }.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPath = item.path
                                    loadKey++
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("📁", fontSize = 14.sp)
                            Text(
                                item.name,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "›",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}
