package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ModelInfo
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
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.secondaryBarGlass
import kotlinx.coroutines.launch

/**
 * 新建会话 —— **逐像素对齐 iOS NewSessionView**（区块顺序与控件形态完全一致）：
 * Provider（分段控件）→ 会话类型（分段控件）→ 模型与思考（两张菜单卡）→
 * 模式（两列网格，5 选 1，末张半宽）→ 工作目录（路径输入 + 内嵌浏览按钮 + 最近路径）
 * → 首条消息（可选）。卡片是 iOS 风格的**纯色 surface 平面卡**（无玻璃微光/投影），
 * 选中切 brand 软底 + brand 描边。底部通栏「创建会话」+ 顶栏「创建」双入口同 iOS。
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
    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var codexModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("") }
    var thinkingEffort by remember { mutableStateOf("off") }
    var firstMessage by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }

    val providerModels = if (provider == "codex") codexModels else availableModels
    val supportedModes = supportedModeIds(provider)

    LaunchedEffect(Unit) {
        val config = try {
            api.serverConfig()
        } catch (_: Exception) {
            null
        }
        mode = supportedModeFor(config?.defaultMode ?: "managed", provider)
        selectedModel = config?.defaultModel ?: ""
        thinkingEffort = config?.defaultThinkingEffort ?: "off"
        try {
            val response = api.models()
            availableModels = response.models
            codexModels = response.codexModels
        } catch (_: Exception) {
        }
        recentPaths = try {
            api.recentPaths()
        } catch (_: Exception) {
            emptyList()
        }
        if (cwd.isEmpty()) {
            cwd = recentPaths.firstOrNull()?.path ?: config?.defaultCwd ?: ""
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

    // 持久化默认项（对齐 iOS saveDefaults，失败仅记错不打断选择）。
    fun persistDefaults(mode: String? = null, model: String? = null, thinkingEffort: String? = null) {
        scope.launch {
            try {
                api.updateNewSessionDefaults(mode = mode, model = model, thinkingEffort = thinkingEffort)
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun create() {
        if (!canCreate) return
        creating = true
        errorMessage = null
        val path = cwd.trim()
        val prompt = firstMessage.trim().ifEmpty { null }
        // codex 仅支持 full-access，对齐 Web getSafeModeForTool 的 clamp。
        val effectiveMode = if (provider == "codex") "full-access" else mode
        val model = selectedModel.ifEmpty { null }
        scope.launch {
            try {
                val snapshot = if (isStructured) {
                    api.createStructuredSession(
                        cwd = path,
                        mode = effectiveMode,
                        prompt = prompt,
                        provider = provider,
                        model = model,
                        thinkingEffort = thinkingEffort,
                    )
                } else {
                    api.createPtySession(
                        cwd = path,
                        mode = effectiveMode,
                        initialInput = prompt,
                        provider = provider,
                        model = model,
                        thinkingEffort = thinkingEffort,
                    )
                }
                creating = false
                onCreated(snapshot)
            } catch (e: Exception) {
                creating = false
                errorMessage = e.message ?: "创建失败"
            }
        }
    }

    val selectedModelLabel = if (selectedModel.isEmpty() || selectedModel == "default") {
        "默认"
    } else {
        providerModels.firstOrNull { it.id == selectedModel }?.label ?: selectedModel
    }
    val thinkingLabel = THINKING_LEVELS.firstOrNull { it.first == thinkingEffort }?.second ?: "关闭"

    Scaffold(
        containerColor = WandColors.bgPrimary,
        modifier = Modifier.imePadding(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("新建会话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("取消", fontSize = 16.sp, color = WandColors.textSecondary)
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
                                color = if (canCreate) WandColors.brand else WandColors.textSecondary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WandColors.bgPrimary,
                    scrolledContainerColor = WandColors.bgPrimary,
                ),
            )
        },
        bottomBar = {
            // 底部通栏创建按钮（对齐 iOS createBar）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WandColors.bgPrimary)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Button(
                    onClick = { create() },
                    enabled = canCreate,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WandColors.brand,
                        disabledContainerColor = WandColors.brand.copy(alpha = 0.4f),
                        contentColor = Color.White,
                        disabledContentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            color = Color.White,
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
            // —— Provider（分段控件）——
            SectionHeader("Provider")
            WandSegmented(
                options = listOf("claude" to "Claude", "codex" to "Codex"),
                selected = provider,
                onSelect = { newProvider ->
                    provider = newProvider
                    mode = supportedModeFor(mode, newProvider)
                },
            )

            // —— 会话类型（分段控件）——
            SectionHeader("会话类型")
            WandSegmented(
                options = listOf(true to "结构化", false to "PTY"),
                selected = isStructured,
                onSelect = { isStructured = it },
            )
            FieldHint(sessionKindHint(provider, isStructured))

            // —— 模型与思考（两张菜单卡）——
            SectionHeader("模型与思考")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionMenuCard(
                    title = "模型",
                    value = selectedModelLabel,
                    icon = Icons.Outlined.Memory,
                    options = buildList {
                        add("" to "默认")
                        providerModels.filter { it.id != "default" }.forEach { add(it.id to it.label) }
                    },
                    selectedId = selectedModel,
                    onSelect = {
                        selectedModel = it
                        persistDefaults(model = it)
                    },
                    modifier = Modifier.weight(1f),
                )
                OptionMenuCard(
                    title = "思考深度",
                    value = thinkingLabel,
                    icon = WandIcons.thinking,
                    options = THINKING_LEVELS,
                    selectedId = thinkingEffort,
                    onSelect = {
                        thinkingEffort = it
                        persistDefaults(thinkingEffort = it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // —— 模式（两列网格，5 选 1；末张半宽，对齐 iOS LazyVGrid）——
            SectionHeader("模式")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SESSION_MODES.chunked(2).forEach { rowModes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowModes.forEach { m ->
                            ModeCard(
                                label = m.label,
                                description = m.desc,
                                selected = mode == m.id,
                                enabled = m.id in supportedModes,
                                onClick = {
                                    mode = m.id
                                    persistDefaults(mode = m.id)
                                },
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
            CwdCard(
                cwd = cwd,
                onCwdChange = { cwd = it },
                recentPaths = recentPaths,
                onBrowse = { showBrowser = true },
                onPickRecent = { cwd = it },
            )

            // —— 首条消息（可选）——
            SectionHeader("首条消息（可选）")
            FirstMessageCard(value = firstMessage, onValueChange = { firstMessage = it })

            // —— 错误提示 ——
            if (errorMessage != null) {
                ErrorBanner(errorMessage ?: "")
            }

            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}

/** iOS 风格选择卡底：纯色 surface 平面 + 1pt 描边；选中切 brand 软底 + brand 1.5pt 描边。 */
@Composable
private fun Modifier.selectCard(selected: Boolean): Modifier {
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(
        if (selected) WandColors.brand.copy(alpha = 0.10f) else WandColors.surface,
        WandMotion.tweenFast(),
        label = "selectCardBg",
    )
    val borderColor by animateColorAsState(
        if (selected) WandColors.brand else WandColors.border,
        WandMotion.tweenFast(),
        label = "selectCardBorder",
    )
    return this
        .clip(shape)
        .background(bg)
        .border(if (selected) 1.5.dp else 1.dp, borderColor, shape)
}

/**
 * iOS 风格分段控件（对齐 SwiftUI `.pickerStyle(.segmented)`）：
 * 弱底轨道 + 选中段为浮起的 surface 胶囊（brand 文字），未选中透明（次级文字）。
 */
@Composable
private fun <T> WandSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WandColors.textPrimary.copy(alpha = 0.06f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg by animateColorAsState(
                if (isSelected) WandColors.surface else Color.Transparent,
                WandMotion.tweenFast(),
                label = "segBg",
            )
            val fg by animateColorAsState(
                if (isSelected) WandColors.brand else WandColors.textSecondary,
                WandMotion.tweenFast(),
                label = "segFg",
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onSelect(value) }
                    .padding(vertical = 7.dp),
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = fg,
                )
            }
        }
    }
}

/**
 * 模型 / 思考深度菜单卡（对齐 iOS optionMenuCard）：
 * brand 软底圆形图标 + 标题（11）/ 当前值（13 半粗）+ 上下箭头；点开下拉选项。
 */
@Composable
private fun OptionMenuCard(
    title: String,
    value: String,
    icon: ImageVector,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .selectCard(selected = false)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(WandColors.brand.copy(alpha = 0.10f)),
            ) {
                Icon(icon, contentDescription = null, tint = WandColors.brand, modifier = Modifier.size(15.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = WandColors.textSecondary)
                Text(
                    value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.UnfoldMore,
                contentDescription = null,
                tint = WandColors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WandColors.surface,
        ) {
            options.forEach { (id, label) ->
                val isSel = selectedId == id || (selectedId == "default" && id == "")
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSel) WandColors.brand else WandColors.textPrimary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (isSel) WandIcons.check else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSel) WandColors.brand else WandColors.textMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 模式卡（对齐 iOS modeCard）：标签 + 一句话说明，纯色平面卡；不支持的模式降透明度且不可点。 */
@Composable
private fun ModeCard(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .selectCard(selected)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) WandColors.brand else WandColors.textPrimary,
            maxLines = 1,
        )
        Text(
            description,
            fontSize = 11.sp,
            color = WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 工作目录卡（对齐 iOS cwdCard）：路径输入 + 右侧浏览按钮 + 最近路径，整体一张平面卡。 */
@Composable
private fun CwdCard(
    cwd: String,
    onCwdChange: (String) -> Unit,
    recentPaths: List<RecentPath>,
    onBrowse: () -> Unit,
    onPickRecent: (String) -> Unit,
) {
    Column(modifier = Modifier.selectCard(selected = false)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = cwd,
                onValueChange = onCwdChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = WandColors.textPrimary,
                ),
                singleLine = true,
                cursorBrush = SolidColor(WandColors.brand),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (cwd.isEmpty()) {
                            Text(
                                "/path/to/project",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = WandColors.textMuted,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 11.dp, bottom = 11.dp),
            )
            IconButton(onClick = onBrowse, modifier = Modifier.size(44.dp)) {
                Icon(
                    WandIcons.folder,
                    contentDescription = "浏览目录",
                    tint = WandColors.brand,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (recentPaths.isNotEmpty()) {
            HorizontalDivider(color = WandColors.border, thickness = 0.5.dp)
            recentPaths.take(5).forEach { recent ->
                val isSelected = cwd == recent.path
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickRecent(recent.path) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        WandIcons.clock,
                        contentDescription = null,
                        tint = if (isSelected) WandColors.brand else WandColors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            recent.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSelected) WandColors.brand else WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            recent.path,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isSelected) {
                        Icon(
                            WandIcons.check,
                            contentDescription = "已选中",
                            tint = WandColors.brand,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 首条消息输入卡（对齐 iOS firstMessageCard）：纯色平面卡内的无边框输入。 */
@Composable
private fun FirstMessageCard(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontSize = 15.sp, color = WandColors.textPrimary),
        cursorBrush = SolidColor(WandColors.brand),
        maxLines = 4,
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectCard(selected = false)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text("想让它做什么…", fontSize = 15.sp, color = WandColors.textMuted)
                }
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 错误提示条（对齐 iOS errorBanner）：danger 软底 + 三角图标 + 文案。 */
@Composable
private fun ErrorBanner(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WandColors.danger.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Icon(
            WandIcons.error,
            contentDescription = null,
            tint = WandColors.danger,
            modifier = Modifier.size(18.dp),
        )
        Text(message, fontSize = 13.sp, color = WandColors.danger, modifier = Modifier.weight(1f))
    }
}

/** 区块下方的说明文案（对齐 iOS fieldHint）。 */
@Composable
private fun FieldHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = WandColors.textSecondary.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 6.dp),
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

/** 思考深度档位（对齐 iOS thinkingLevels）：id 与服务端一致，标签同 iOS 中文。 */
private val THINKING_LEVELS = listOf(
    "off" to "关闭",
    "standard" to "标准",
    "deep" to "深入",
    "max" to "最大",
)

/** codex 仅支持 full-access，对齐 Web getSupportedModes。 */
private fun supportedModeIds(provider: String): Set<String> =
    if (provider == "codex") setOf("full-access")
    else SESSION_MODES.mapTo(mutableSetOf()) { it.id }

/** 切换 provider 时把当前 mode clamp 到该 provider 支持的集合（对齐 iOS supportedMode）。 */
private fun supportedModeFor(value: String, provider: String): String {
    if (provider == "codex") return "full-access"
    return if (SESSION_MODES.any { it.id == value }) value else "managed"
}

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
