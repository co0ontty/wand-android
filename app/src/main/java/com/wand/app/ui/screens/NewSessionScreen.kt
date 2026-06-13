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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
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
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.SectionHeader
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.secondaryBarGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.launch

/**
 * 新建会话 —— 选项与区块顺序对齐 Web 端「新对话」弹窗（renderSessionModal）：
 * Provider（Claude / Codex，品牌 logo 卡）→ 会话类型（结构化 / PTY）→ 模式
 * （托管 / 全权限 / 自动编辑 / 标准 / 原生，codex 锁定全权限）→ 工作目录
 * （最近路径 / 内置目录浏览器）；Android 额外保留「首条消息」快捷输入。
 * 视觉：区块卡片化 + mode-card 选择器 + 动态 hint 文案 + 底部通栏创建按钮。
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
    var provider by remember { mutableStateOf("claude") }
    var isStructured by remember { mutableStateOf(true) }
    // 默认托管模式（claude 全自动完成）；codex 切换时 clamp 成全权限。
    var mode by remember { mutableStateOf("managed") }
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
        // codex 仅支持 full-access，对齐 Web getSafeModeForTool 的 clamp。
        val effectiveMode = if (provider == "codex") "full-access" else mode
        scope.launch {
            try {
                val snapshot = if (isStructured) {
                    api.createStructuredSession(path, effectiveMode, prompt, provider)
                } else {
                    api.createPtySession(path, effectiveMode, prompt, provider)
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
        containerColor = Color.Transparent,
        modifier = Modifier
            .ambientBackground()
            .imePadding(),
        topBar = {
            TopAppBar(
                modifier = Modifier.glassSurface(null, RoundedCornerShape(0.dp), secondaryBarGlass),
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
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
            // —— Provider（对齐 Web：按实际使用的 CLI 选择）——
            SectionHeader("Provider")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderCard(
                    logo = BrandLogos.claude,
                    accent = WandColors.brand,
                    accentSoft = WandColors.brandSoft,
                    label = "Claude",
                    description = "完整 Claude 会话能力",
                    selected = provider == "claude",
                    onClick = {
                        provider = "claude"
                        // 切回 claude 恢复默认托管模式（codex 把它 clamp 成了全权限）。
                        mode = "managed"
                    },
                    modifier = Modifier.weight(1f),
                )
                ProviderCard(
                    logo = BrandLogos.codex,
                    accent = WandColors.info,
                    accentSoft = WandColors.infoSoft,
                    label = "Codex",
                    description = "结构化 JSONL 或 PTY 会话",
                    selected = provider == "codex",
                    onClick = {
                        provider = "codex"
                        // codex 仅支持全权限，切换时同步 clamp 选中态。
                        mode = "full-access"
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // —— 会话类型 ——
            SectionHeader("会话类型")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionTypeCard(
                    icon = WandIcons.chat,
                    label = "结构化",
                    description = "智能对话模式",
                    selected = isStructured,
                    onClick = { isStructured = true },
                    modifier = Modifier.weight(1f),
                )
                SessionTypeCard(
                    icon = WandIcons.terminal,
                    label = "PTY",
                    description = "交互式终端会话",
                    selected = !isStructured,
                    onClick = { isStructured = false },
                    modifier = Modifier.weight(1f),
                )
            }
            FieldHint(sessionKindHint(provider, isStructured))

            // —— 模式（5 选 1，两列网格；codex 锁定全权限）——
            SectionHeader("模式")
            val supportedModes = supportedModeIds(provider)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SESSION_MODES.chunked(2).forEach { rowModes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowModes.forEach { m ->
                            ModeCard(
                                label = m.label,
                                description = m.desc,
                                selected = mode == m.id,
                                enabled = m.id in supportedModes,
                                onClick = { mode = m.id },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowModes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            FieldHint(modeHint(provider, mode))

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

/** Provider 选择卡：品牌 logo（圆形软底）+ 名称 + 一句话说明，2 张横排。 */
@Composable
private fun ProviderCard(
    logo: ImageVector,
    accent: Color,
    accentSoft: Color,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor by animateColorAsState(
        if (selected) WandColors.brand else WandColors.textPrimary,
        WandMotion.tweenFast(),
        label = "providerLabel",
    )
    WandCard(
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    logo,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = labelColor)
            Text(
                description,
                fontSize = 11.sp,
                color = WandColors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 模式 mode-card（两列网格单元，标签 + 一句话说明），不支持的模式降透明度且不可点。 */
@Composable
private fun ModeCard(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor by animateColorAsState(
        if (selected) WandColors.brand else WandColors.textPrimary,
        WandMotion.tweenFast(),
        label = "modeCardLabel",
    )
    WandCard(
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        onClick = if (enabled) onClick else null,
        selected = selected,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = labelColor, maxLines = 1)
            Text(
                description,
                fontSize = 11.sp,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 区块下方的说明文案，对应 Web 的 .field-hint。 */
@Composable
private fun FieldHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = WandColors.textMuted,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 模式选项：id / 标签 / 卡片内一句话说明，与 Web renderModeCards 完全一致。 */
private data class SessionMode(val id: String, val label: String, val desc: String)

private val SESSION_MODES = listOf(
    SessionMode("managed", "托管", "全自动完成任务"),
    SessionMode("full-access", "全权限", "自动确认权限"),
    SessionMode("auto-edit", "自动编辑", "自动确认修改"),
    SessionMode("default", "标准", "逐步确认操作"),
    SessionMode("native", "原生", "原生结构化输出"),
)

/** codex 仅支持 full-access，对齐 Web getSupportedModes。 */
private fun supportedModeIds(provider: String): Set<String> =
    if (provider == "codex") setOf("full-access")
    else SESSION_MODES.mapTo(mutableSetOf()) { it.id }

/** 会话类型动态说明，文案对齐 Web getSessionKindHint。 */
private fun sessionKindHint(provider: String, structured: Boolean): String =
    if (structured) {
        if (provider == "codex") "Codex JSONL 结构化聊天界面，支持多轮对话和工具调用展示。"
        else "结构化聊天界面，支持多轮对话、流式输出和工具调用展示。"
    } else {
        if (provider == "codex") "Codex PTY 终端会话；terminal 是原始输出，chat 是解析后的阅读视图。"
        else "原始 PTY 终端会话，支持持续交互、终端视图和权限流。"
    }

/** 模式动态说明，文案对齐 Web getToolModeHint。 */
private fun modeHint(provider: String, mode: String): String {
    if (provider == "codex") {
        return "Codex 支持 PTY 终端与结构化（JSONL）两种会话，结构化模式按 full-access 启动。"
    }
    return when (mode) {
        "full-access" -> "自动确认权限请求与高权限操作，适合你确认环境安全后的连续修改。"
        "auto-edit" -> "保留交互式会话，同时更偏向直接编辑代码。"
        "native" -> "调用 Claude 原生 API 输出，适合快速问答或一次性生成。"
        "managed" -> "AI 自动完成所有工作，无需中途确认，适合有明确目标的任务。"
        else -> "保留标准交互流程，适合手动确认每一步。"
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
        containerColor = Color.Transparent,
        modifier = Modifier.ambientBackground(),
        topBar = {
            TopAppBar(
                modifier = Modifier.glassSurface(null, RoundedCornerShape(0.dp), secondaryBarGlass),
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
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
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
