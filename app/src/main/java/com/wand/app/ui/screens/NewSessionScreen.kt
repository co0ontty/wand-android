package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.RecentPath
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.SectionHeader
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.launch

/**
 * 新建会话 —— 对称 iOS NewSessionView：
 * 选择工作目录（最近路径 / 内置目录浏览器）、会话类型与权限模式，可附带首条消息。
 * 视觉对标 Web 端「新对话」弹窗（重设计规范 v1 第 3.1 节）：
 * 区块卡片化 + mode-card 选择器 + 底部通栏创建按钮。
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

    // 退场动画期间 errorMessage 已变 null，缓存最后一条文案避免内容闪空。
    var lastErrorText by remember { mutableStateOf("") }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { lastErrorText = it }
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
                            tint = WandColors.textSecondary,
                        )
                    }
                },
                actions = {
                    if (creating) {
                        CircularProgressIndicator(
                            color = WandColors.brand,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 16.dp).size(20.dp),
                        )
                    } else {
                        TextButton(onClick = { create() }, enabled = canCreate) {
                            Text(
                                "创建",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (canCreate) WandColors.brand else WandColors.textMuted,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WandColors.bgPrimary)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = { create() },
                    enabled = cwd.trim().isNotEmpty(),
                    shape = WandShapes.lg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("创建中…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("创建会话", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // —— 工作目录 ——
            SectionHeader("工作目录")
            WandCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
            ) {
                CwdTextField(value = cwd, onValueChange = { cwd = it })
                Spacer(modifier = Modifier.size(10.dp))
                FilledTonalButton(
                    onClick = { showBrowser = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = WandColors.brandSoft,
                        contentColor = WandColors.brand,
                    ),
                ) {
                    Icon(
                        WandIcons.folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("浏览目录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (recentPaths.isNotEmpty()) {
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        "最近使用",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = WandColors.textMuted,
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    WandCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = WandShapes.sm,
                        containerColor = WandColors.surfaceSoft,
                    ) {
                        val shown = recentPaths.take(5)
                        shown.forEachIndexed { index, recent ->
                            RecentPathRow(
                                recent = recent,
                                selected = cwd == recent.path,
                                onClick = { cwd = recent.path },
                            )
                            if (index < shown.lastIndex) {
                                HorizontalDivider(
                                    color = WandColors.border,
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(start = 40.dp),
                                )
                            }
                        }
                    }
                }
            }

            // —— 会话类型 ——
            SectionHeader("会话类型")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionTypeCard(
                    icon = WandIcons.chat,
                    label = "聊天",
                    description = "结构化对话视图",
                    selected = isStructured,
                    onClick = { isStructured = true },
                    modifier = Modifier.weight(1f),
                )
                SessionTypeCard(
                    icon = WandIcons.terminal,
                    label = "终端",
                    description = "交互式 Claude CLI",
                    selected = !isStructured,
                    onClick = { isStructured = false },
                    modifier = Modifier.weight(1f),
                )
            }

            // —— 权限模式 ——
            SectionHeader("权限模式")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionModeCard(
                    icon = WandIcons.permission,
                    label = "默认",
                    description = "逐步确认操作",
                    selected = modeIndex == 0,
                    onClick = { modeIndex = 0 },
                )
                PermissionModeCard(
                    icon = WandIcons.edit,
                    label = "自动编辑",
                    description = "自动确认文件修改",
                    selected = modeIndex == 1,
                    onClick = { modeIndex = 1 },
                )
                PermissionModeCard(
                    icon = WandIcons.shield,
                    label = "完全访问",
                    description = "自动确认全部权限",
                    selected = modeIndex == 2,
                    onClick = { modeIndex = 2 },
                )
            }

            // —— 首条消息 ——
            SectionHeader("首条消息（可选）")
            OutlinedTextField(
                value = firstMessage,
                onValueChange = { firstMessage = it },
                placeholder = {
                    Text("想让它做什么…", fontSize = 15.sp, color = WandColors.textMuted)
                },
                textStyle = TextStyle(fontSize = 15.sp),
                minLines = 1,
                maxLines = 4,
                shape = WandShapes.md,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WandColors.brand,
                    unfocusedBorderColor = WandColors.border,
                    cursorColor = WandColors.brand,
                    focusedContainerColor = WandColors.surface,
                    unfocusedContainerColor = WandColors.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // —— 错误提示 ——
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(WandMotion.tweenNormal()) + expandVertically(WandMotion.tweenNormal()),
                exit = fadeOut(WandMotion.tweenNormal()) + shrinkVertically(WandMotion.tweenNormal()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .clip(WandShapes.md)
                        .background(WandColors.dangerSoft)
                        .border(1.dp, WandColors.danger.copy(alpha = 0.4f), WandShapes.md)
                        .padding(12.dp),
                ) {
                    Icon(
                        WandIcons.error,
                        contentDescription = null,
                        tint = WandColors.danger,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        errorMessage ?: lastErrorText,
                        fontSize = 13.sp,
                        color = WandColors.danger,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}

/** 工作目录输入框：聚焦时 brand 边框 + 外圈 focusRing 光晕。 */
@Composable
private fun CwdTextField(value: String, onValueChange: (String) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val ringColor by animateColorAsState(
        if (focused) WandColors.focusRing else Color.Transparent,
        WandMotion.tweenFast(),
        label = "cwdRing",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, ringColor, RoundedCornerShape(WandShapes.radiusSm + 3.dp))
            .padding(3.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "/path/to/project",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = WandColors.textMuted,
                )
            },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            singleLine = true,
            shape = WandShapes.sm,
            interactionSource = interactionSource,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WandColors.brand,
                unfocusedBorderColor = WandColors.border,
                cursorColor = WandColors.brand,
                focusedContainerColor = WandColors.surface,
                unfocusedContainerColor = WandColors.surfaceSoft,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 最近路径行：History 图标 + 目录名（粗）+ 完整路径（mono muted），选中 brandSoft 底 + 行尾勾。 */
@Composable
private fun RecentPathRow(
    recent: RecentPath,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) WandColors.brandSoft else Color.Transparent,
        WandMotion.tweenFast(),
        label = "recentBg",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            WandIcons.history,
            contentDescription = null,
            tint = if (selected) WandColors.brand else WandColors.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                recent.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) WandColors.brand else WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                recent.path,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                WandIcons.check,
                contentDescription = "已选中",
                tint = WandColors.brand,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 会话类型 mode-card（2 张横排，竖向内容居中）。 */
@Composable
private fun SessionTypeCard(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor by animateColorAsState(
        if (selected) WandColors.brand else WandColors.textPrimary,
        WandMotion.tweenFast(),
        label = "typeLabel",
    )
    val iconTint by animateColorAsState(
        if (selected) WandColors.brand else WandColors.textSecondary,
        WandMotion.tweenFast(),
        label = "typeIcon",
    )
    WandCard(
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = labelColor)
            Text(
                description,
                fontSize = 12.sp,
                color = WandColors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 权限模式 mode-card（3 张竖排，横向内容）。 */
@Composable
private fun PermissionModeCard(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val labelColor by animateColorAsState(
        if (selected) WandColors.brand else WandColors.textPrimary,
        WandMotion.tweenFast(),
        label = "modeLabel",
    )
    val iconTint by animateColorAsState(
        if (selected) WandColors.brand else WandColors.textSecondary,
        WandMotion.tweenFast(),
        label = "modeIcon",
    )
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = labelColor)
                Text(description, fontSize = 12.sp, color = WandColors.textMuted)
            }
            if (selected) {
                Icon(
                    WandIcons.check,
                    contentDescription = "已选中",
                    tint = WandColors.brand,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
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
                        Text("取消", color = WandColors.textSecondary)
                    }
                },
                actions = {
                    TextButton(onClick = { onPick(currentPath) }) {
                        Text(
                            "选择此目录",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.brand,
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
            // 面包屑头：上一级按钮 + 当前路径（mono 弱底胶囊）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FilledTonalButton(
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
                    shape = WandShapes.full,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = WandColors.brandSoft,
                        contentColor = WandColors.brand,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("上一级", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(WandShapes.sm)
                        .background(WandColors.surfaceSoft)
                        .border(1.dp, WandColors.border, WandShapes.sm)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        currentPath,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when {
                loading -> LoadingState("加载目录中…")
                errorMessage != null -> ErrorState(
                    message = errorMessage ?: "加载失败",
                    onRetry = { loadKey++ },
                )
                else -> {
                    val dirs = items.filter { it.isDirectory }
                    if (dirs.isEmpty()) {
                        EmptyState(
                            icon = WandIcons.folder,
                            title = "没有子目录",
                            subtitle = "可点击右上角「选择此目录」使用当前目录",
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            WandCard(modifier = Modifier.fillMaxWidth()) {
                                dirs.forEachIndexed { index, item ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable {
                                                currentPath = item.path
                                                loadKey++
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    ) {
                                        Icon(
                                            WandIcons.folder,
                                            contentDescription = null,
                                            tint = WandColors.brand,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            item.name,
                                            fontSize = 14.sp,
                                            color = WandColors.textPrimary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(
                                            WandIcons.chevronRight,
                                            contentDescription = null,
                                            tint = WandColors.textMuted,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (index < dirs.lastIndex) {
                                        HorizontalDivider(
                                            color = WandColors.border,
                                            thickness = 0.5.dp,
                                            modifier = Modifier.padding(start = 44.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
