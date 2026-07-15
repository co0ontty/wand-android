package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ModelInfo
import com.wand.app.data.ProviderDefaultModels
import com.wand.app.data.RecentPath
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.SectionHeader
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandChoiceStrip
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.thinkingEffortOptions
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.secondaryBarGlass
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 新建会话：
 * Provider（分段控件）→ 会话类型（分段控件）→ 工作目录（路径输入 + 内嵌浏览按钮 + 最近路径）
 * → 模型与思考（两张菜单卡）→ 模式（两列网格，5 选 1，末张半宽）
 * → 首条消息（可选）。卡片是 iOS 风格的**纯色 surface 平面卡**（无玻璃微光/投影），
 * 选中切 brand 软底 + brand 描边。底部通栏「创建会话」作为唯一主操作，避免上下重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    api: WandApi,
    onBack: () -> Unit,
    onCreated: (SessionSnapshot) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val workflow = remember(api) { NewSessionWorkflow(api) }
    val defaultsUpdateMutex = remember { Mutex() }
    val defaultModelGenerations = remember { mutableMapOf<String, Int>() }
    var defaultsUpdateGeneration by remember { mutableIntStateOf(0) }

    var cwd by remember { mutableStateOf("") }
    var recentPaths by remember { mutableStateOf<List<RecentPath>>(emptyList()) }
    var provider by remember { mutableStateOf("claude") }
    var isStructured by remember { mutableStateOf(true) }
    // 默认托管模式（Claude / OpenCode 全自动完成）；Codex 切换时 clamp 成全权限。
    var mode by remember { mutableStateOf("managed") }
    var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var codexModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var opencodeModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var serverDefaultModels by remember {
        mutableStateOf(NewSessionWorkflow.EMPTY_DEFAULT_MODELS)
    }
    var confirmedDefaultModels by remember {
        mutableStateOf(NewSessionWorkflow.EMPTY_DEFAULT_MODELS)
    }
    var selectedModel by remember { mutableStateOf("") }
    var thinkingEffort by remember { mutableStateOf("off") }
    var firstMessage by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }

    val providerModels = when (provider) {
        "codex" -> codexModels
        "opencode" -> opencodeModels
        else -> availableModels
    }
    val serverDefaultModel = when (provider) {
        "codex" -> serverDefaultModels.codex
        "opencode" -> serverDefaultModels.opencode
        else -> serverDefaultModels.claude
    }
    val thinkingLevels = thinkingEffortOptions(provider, selectedModel, serverDefaultModel, providerModels)
    val thinkingLevelIds = thinkingLevels.map { it.id }
    val canValidateThinkingEffort = provider != "codex" || providerModels.isNotEmpty()
    val selectedThinkingLabel = thinkingLevels
        .firstOrNull { it.id == thinkingEffort }
        ?.label
        ?: thinkingLevels.firstOrNull()?.label
        ?: "自动"
    val supportedModes = workflow.supportedModes(provider)

    LaunchedEffect(Unit) {
        val initial = workflow.bootstrap()
        provider = initial.provider
        isStructured = initial.structured
        mode = initial.mode
        serverDefaultModels = initial.defaultModels
        confirmedDefaultModels = initial.defaultModels
        selectedModel = ""
        thinkingEffort = initial.thinkingEffort
        initial.models?.let { response ->
            availableModels = response.models
            codexModels = response.codexModels
            opencodeModels = response.opencodeModels
        }
        recentPaths = initial.recentPaths
        if (cwd.isEmpty()) cwd = initial.cwd
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

    // 持久化到服务端偏好；失败仅提示，不打断当前页面上的选择。
    fun persistDefaults(
        mode: String? = null,
        model: String? = null,
        modelProvider: String = provider,
        thinkingEffort: String? = null,
        defaultProvider: String? = null,
        defaultSessionKind: String? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) {
        val generation = ++defaultsUpdateGeneration
        scope.launch {
            defaultsUpdateMutex.withLock {
                try {
                    workflow.persistDefaults(
                        mode = mode,
                        model = model,
                        modelProvider = modelProvider,
                        thinkingEffort = thinkingEffort,
                        defaultProvider = defaultProvider,
                        defaultSessionKind = defaultSessionKind,
                    )
                    onSuccess?.invoke()
                } catch (e: Exception) {
                    onFailure?.invoke()
                    // 后续操作已经排队时，不让旧请求的迟到错误覆盖当前选择反馈。
                    if (generation == defaultsUpdateGeneration) errorMessage = e.message
                }
            }
        }
    }

    fun normalizeThinkingEffort(nextProvider: String, nextModel: String?) {
        val normalized = workflow.normalizeThinkingEffort(
            provider = nextProvider,
            selectedModel = nextModel,
            currentEffort = thinkingEffort,
            defaultModels = serverDefaultModels,
            claudeModels = availableModels,
            codexModels = codexModels,
            opencodeModels = opencodeModels,
        )
        if (normalized != thinkingEffort) {
            thinkingEffort = normalized
            persistDefaults(thinkingEffort = "off")
        }
    }

    LaunchedEffect(provider, selectedModel, thinkingLevelIds, thinkingEffort, canValidateThinkingEffort) {
        if (canValidateThinkingEffort && thinkingEffort !in thinkingLevelIds) {
            thinkingEffort = "off"
            persistDefaults(thinkingEffort = "off")
        }
    }

    fun create() {
        if (!canCreate) return
        creating = true
        errorMessage = null
        ++defaultsUpdateGeneration
        scope.launch {
            try {
                val snapshot = defaultsUpdateMutex.withLock {
                    workflow.create(
                        NewSessionDraft(
                            cwd = cwd,
                            provider = provider,
                            structured = isStructured,
                            mode = mode,
                            model = selectedModel,
                            thinkingEffort = thinkingEffort,
                            firstMessage = firstMessage,
                        ),
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

    val defaultModelLabel = if (!serverDefaultModel.isNullOrBlank()) {
        providerModels.firstOrNull { it.id == serverDefaultModel }?.label ?: serverDefaultModel
    } else {
        providerModels.firstOrNull { it.id == "default" }?.label ?: "默认"
    }
    val selectedModelLabel = if (selectedModel.isEmpty() || selectedModel == "default") {
        defaultModelLabel
    } else {
        providerModels.firstOrNull { it.id == selectedModel }?.label ?: selectedModel
    }
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier
            .imePadding()
            .ambientBackground(),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.glassSurface(
                    null,
                    RoundedCornerShape(0.dp),
                    secondaryBarGlass,
                    edgeToEdge = true,
                ),
                title = { Text("新建会话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("取消", fontSize = 16.sp, color = WandColors.textSecondary)
                    }
                },
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = {
            // 底部通栏创建按钮（对齐 iOS createBar）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(
                        null,
                        RoundedCornerShape(0.dp),
                        secondaryBarGlass,
                        edgeToEdge = true,
                    )
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                options = listOf(
                    "claude" to "Claude",
                    "codex" to "Codex",
                    "opencode" to "OpenCode",
                ),
                selected = provider,
                onSelect = { newProvider ->
                    provider = newProvider
                    persistDefaults(defaultProvider = newProvider)
                    mode = workflow.clampMode(mode, newProvider)
                    selectedModel = ""
                    normalizeThinkingEffort(newProvider, "")
                },
            )

            // —— 会话类型（分段控件）——
            SectionHeader("会话类型")
            WandSegmented(
                options = listOf(true to "结构化", false to "PTY"),
                selected = isStructured,
                onSelect = {
                    isStructured = it
                    persistDefaults(defaultSessionKind = if (it) "structured" else "pty")
                },
            )
            FieldHint(sessionKindHint(provider, isStructured))

            // —— 工作目录 ——
            SectionHeader("工作目录")
            CwdCard(
                cwd = cwd,
                onCwdChange = { cwd = it },
                recentPaths = recentPaths,
                onBrowse = { showBrowser = true },
                onPickRecent = { cwd = it },
            )
            FieldHint("创建前先确认目录，支持输入绝对路径，或点文件夹图标打开目录浏览器。")

            // —— 模型与思考（两张菜单卡）——
            SectionHeader("模型与思考")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionMenuCard(
                    title = "模型",
                    value = selectedModelLabel,
                    icon = Icons.Outlined.Memory,
                    options = buildList {
                        add("" to "默认 · $defaultModelLabel")
                        providerModels.filter { it.id != "default" }.forEach { add(it.id to it.label) }
                    },
                    selectedId = selectedModel,
                    onSelect = {
                        val modelProvider = provider
                        val newDefault = it
                        val modelGeneration = (defaultModelGenerations[modelProvider] ?: 0) + 1
                        defaultModelGenerations[modelProvider] = modelGeneration
                        selectedModel = it
                        serverDefaultModels = serverDefaultModels.withDefault(modelProvider, newDefault)
                        persistDefaults(
                            model = newDefault,
                            modelProvider = modelProvider,
                            onSuccess = {
                                confirmedDefaultModels = confirmedDefaultModels.withDefault(
                                    modelProvider,
                                    newDefault,
                                )
                                if (defaultModelGenerations[modelProvider] == modelGeneration) {
                                    serverDefaultModels = serverDefaultModels.withDefault(
                                        modelProvider,
                                        newDefault,
                                    )
                                }
                            },
                            onFailure = {
                                // 只回退该 Provider 的最新请求；旧失败不能覆盖更新的选择。
                                // explicit selectedModel 仍作为“本次会话选择”保留；失败的只是默认偏好保存。
                                // 切走 Provider 时 explicit 会被清空，切回后即展示这里回退的已确认默认值。
                                if (defaultModelGenerations[modelProvider] == modelGeneration) {
                                    serverDefaultModels = serverDefaultModels.withDefault(
                                        modelProvider,
                                        confirmedDefaultModels.defaultFor(modelProvider),
                                    )
                                }
                            },
                        )
                        normalizeThinkingEffort(modelProvider, newDefault)
                    },
                    modifier = Modifier.weight(1f),
                )
                OptionMenuCard(
                    title = "思考深度",
                    value = selectedThinkingLabel,
                    icon = WandIcons.thinking,
                    options = thinkingLevels.map { it.id to it.menuLabel },
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

            // —— 首条消息（可选）——
            SectionHeader("首条消息（可选）")
            FirstMessageCard(value = firstMessage, onValueChange = { firstMessage = it })

            // —— 错误提示 ——
            if (errorMessage != null) {
                ErrorBanner(errorMessage ?: "")
            }

            Spacer(modifier = Modifier.size(40.dp))
        }
    }
}

/** iOS 风格选择卡底：纯色 surface 平面 + 1pt 描边；选中切 brand 软底 + brand 1.5pt 描边。 */
@Composable
private fun Modifier.selectCard(selected: Boolean): Modifier {
    val shape = RoundedCornerShape(12.dp)
    val bg by animateColorAsState(
        if (selected) WandColors.brandSoft else WandColors.surface.copy(alpha = 0.94f),
        WandMotion.tweenFast(),
        label = "selectCardBg",
    )
    val borderColor by animateColorAsState(
        if (selected) WandColors.brand.copy(alpha = 0.46f) else WandColors.border.copy(alpha = 0.86f),
        WandMotion.tweenFast(),
        label = "selectCardBorder",
    )
    return this
        .clip(shape)
        .background(bg)
        .border(1.dp, borderColor, shape)
}

@Composable
private fun <T> WandSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    WandChoiceStrip(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        // 非 flat 形态有上下各 3dp 的容器内边距；54dp 可确保内部每一项仍有 48dp 触控高度。
        minHeight = 54.dp,
        labelFontSize = 14.sp,
    )
}

/**
 * 模型 / 思考深度菜单卡（对齐 iOS optionMenuCard）：
 * brand 软底圆形图标 + 标题（11）/ 当前值（13 半粗）+ 上下箭头；点开下拉选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                .heightIn(min = 48.dp)
                .selectCard(selected = false)
                .semantics(mergeDescendants = true) {
                    stateDescription = "当前为$value"
                }
                .clickable(role = Role.DropdownList) { expanded = true }
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
        if (expanded) {
            ModalBottomSheet(
                onDismissRequest = { expanded = false },
                containerColor = WandColors.bgElevated,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            ) {
                NoOverscroll {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .navigationBarsPadding()
                            .padding(bottom = 18.dp),
                    ) {
                        Text(
                            "选择$title",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        options.forEach { (id, label) ->
                            val isSel = selectedId == id || (selectedId == "default" && id == "")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) WandColors.brandSoft else Color.Transparent)
                                    .selectable(
                                        selected = isSel,
                                        role = Role.RadioButton,
                                    ) {
                                        onSelect(id)
                                        expanded = false
                                    }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                            ) {
                                Icon(
                                    if (isSel) WandIcons.check else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSel) WandColors.brand else WandColors.textMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSel) WandColors.brand else WandColors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
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
            .heightIn(min = 48.dp)
            .selectCard(selected)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
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
                    .heightIn(min = 48.dp)
                    .padding(start = 12.dp, top = 11.dp, bottom = 11.dp),
            )
            IconButton(onClick = onBrowse, modifier = Modifier.size(48.dp)) {
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
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onPickRecent(recent.path) }
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
                        TailMarqueePathText(
                            path = recent.path,
                            color = WandColors.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
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
                    .heightIn(min = 96.dp)
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

/** 会话类型动态说明，文案对齐 Web getSessionKindHint。 */
private fun sessionKindHint(provider: String, structured: Boolean): String =
    if (structured) {
        when (provider) {
            "codex" -> "Codex JSONL 结构化聊天界面，支持多轮对话和工具调用展示。"
            "opencode" -> "OpenCode JSON 结构化聊天界面，支持多轮对话和工具调用展示。"
            else -> "结构化聊天界面，支持多轮对话、流式输出和工具调用展示。"
        }
    } else {
        when (provider) {
            "codex" -> "Codex PTY 终端会话；terminal 是原始输出，chat 是解析后的阅读视图。"
            "opencode" -> "OpenCode TUI 终端会话，支持持续交互和终端视图。"
            else -> "原始 PTY 终端会话，支持持续交互、终端视图和权限流。"
        }
    }

/** 模式动态说明，文案对齐 Web getToolModeHint。 */
private fun modeHint(provider: String, mode: String): String {
    if (provider == "codex") {
        return "Codex 支持 PTY 终端与结构化（JSONL）两种会话，结构化模式按 full-access 启动。"
    }
    if (provider == "opencode") {
        return if (mode == "full-access" || mode == "managed") {
            "OpenCode 将自动批准未显式拒绝的权限；支持 TUI 与 JSON 结构化会话。"
        } else {
            "OpenCode 使用自身权限配置；结构化模式会自动拒绝未批准的权限请求。"
        }
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
                modifier = Modifier.glassSurface(
                    null,
                    RoundedCornerShape(0.dp),
                    secondaryBarGlass,
                    edgeToEdge = true,
                ),
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
                    TailMarqueePathText(
                        path = currentPath,
                        fontSize = 12.sp,
                        color = WandColors.textMuted,
                        modifier = Modifier.fillMaxWidth(),
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
