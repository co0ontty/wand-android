package com.wand.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.SessionCreationCoordinator
import com.wand.app.data.ModelInfo
import com.wand.app.data.ModelsResponse
import com.wand.app.data.ProviderDefaultModels
import com.wand.app.data.RecentPath
import com.wand.app.data.WandApi
import com.wand.app.data.WandApiException
import com.wand.app.data.defaultFor
import com.wand.app.data.modelsForProvider
import com.wand.app.data.providerDisplayName
import com.wand.app.ui.HomeServerConnection
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.wandInputSurface
import com.wand.app.ui.thinkingEffortOptions
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import com.wand.app.ui.theme.secondaryBarGlass
import kotlinx.coroutines.launch

/**
 * 新建会话按用户决策顺序组织：服务器 → 工具 → 项目 → 会话形式 → 运行方式。
 * 空白终端是与 AI Provider 并列的工具，不是每个 Provider 下的会话形式。
 * 每次切换服务器都会重新 bootstrap，避免跨机器复用模型、默认值或工作目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    api: WandApi,
    servers: List<HomeServerConnection>,
    activeServerId: String,
    initialCwd: String? = null,
    creating: Boolean,
    onReconnectServer: (serverId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availableServers = remember(api, servers) {
        servers.ifEmpty {
            listOf(
                HomeServerConnection(
                    serverId = activeServerId,
                    displayName = api.baseUrl,
                    serverUrl = api.baseUrl,
                    hasToken = !api.token.isNullOrEmpty(),
                    api = api,
                ),
            )
        }
    }
    var selectedServerId by rememberSaveable(activeServerId) { mutableStateOf(activeServerId) }
    val selectedServer = availableServers.firstOrNull { it.serverId == selectedServerId }
        ?: availableServers.first()
    val selectedApi = selectedServer.api
    val workflows = remember(availableServers) {
        availableServers.associate { server ->
            server.serverId to NewSessionWorkflow(server.api)
        }
    }
    // Reuse each endpoint's workflow/Mutex when switching A → B → A. Otherwise a slow write from
    // the first A instance can race a newer write from the second A instance and win last.
    val workflow = workflows.getValue(selectedServer.serverId)
    var serverSelectionGeneration by remember { mutableIntStateOf(0) }
    val defaultModelGenerations = remember(selectedServer.serverId) { mutableMapOf<String, Int>() }
    var defaultsUpdateGeneration by remember(selectedServer.serverId) { mutableIntStateOf(0) }

    var cwd by remember(initialCwd) { mutableStateOf(initialCwd?.trim().orEmpty()) }
    var recentPaths by remember { mutableStateOf<List<RecentPath>>(emptyList()) }
    var provider by remember { mutableStateOf("claude") }
    var sessionKind by remember { mutableStateOf(NewSessionKind.Structured) }
    var assistantSessionKind by remember { mutableStateOf(NewSessionKind.Structured) }
    // 默认托管模式（Claude / OpenCode 全自动完成）；Codex 切换时 clamp 成全权限。
    var mode by remember { mutableStateOf("managed") }
    var modelsResponse by remember { mutableStateOf<ModelsResponse?>(null) }
    var serverDefaultModels by remember {
        mutableStateOf(NewSessionWorkflow.EMPTY_DEFAULT_MODELS)
    }
    var confirmedDefaultModels by remember {
        mutableStateOf(NewSessionWorkflow.EMPTY_DEFAULT_MODELS)
    }
    var selectedModel by remember { mutableStateOf("") }
    var thinkingEffort by remember { mutableStateOf("off") }
    var bootstrapping by remember { mutableStateOf(true) }
    var bootstrapRetryKey by remember { mutableIntStateOf(0) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var bootstrapAuthFailure by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }

    val providerModels = modelsResponse?.let { response ->
        modelsForProvider(
            provider = provider,
            claude = response.models,
            codex = response.codexModels,
            opencode = response.opencodeModels,
            qoder = response.qoderModels,
            grok = response.grokModels,
            pi = response.piModels,
        )
    }.orEmpty()
    val serverDefaultModel = serverDefaultModels.defaultFor(provider)
    val thinkingLevels = thinkingEffortOptions(provider, selectedModel, serverDefaultModel, providerModels)
    val thinkingLevelIds = thinkingLevels.map { it.id }
    val canValidateThinkingEffort = provider != "codex" || providerModels.isNotEmpty()
    val selectedThinkingLabel = thinkingLevels
        .firstOrNull { it.id == thinkingEffort }
        ?.label
        ?: thinkingLevels.firstOrNull()?.label
        ?: "自动"
    val supportedModes = workflow.supportedModes(provider)

    LaunchedEffect(availableServers.map { it.serverId }) {
        if (availableServers.none { it.serverId == selectedServerId }) {
            serverSelectionGeneration += 1
            selectedServerId = availableServers.first().serverId
        }
    }

    LaunchedEffect(
        workflow,
        initialCwd,
        selectedServer.serverId,
        serverSelectionGeneration,
        bootstrapRetryKey,
    ) {
        val bootstrapServerId = selectedServer.serverId
        val bootstrapSelectionGeneration = serverSelectionGeneration
        bootstrapping = true
        bootstrapError = null
        bootstrapAuthFailure = false
        errorMessage = null
        showBrowser = false
        cwd = ""
        recentPaths = emptyList()
        modelsResponse = null
        selectedModel = ""
        try {
            val initial = workflow.bootstrap()
            if (selectedServerId != bootstrapServerId ||
                serverSelectionGeneration != bootstrapSelectionGeneration
            ) {
                return@LaunchedEffect
            }
            provider = initial.provider
            sessionKind = initial.kind
            assistantSessionKind = initial.kind
            mode = initial.mode
            serverDefaultModels = initial.defaultModels
            confirmedDefaultModels = initial.defaultModels
            thinkingEffort = initial.thinkingEffort
            modelsResponse = initial.models
            recentPaths = initial.recentPaths
            cwd = if (selectedServer.serverId == activeServerId) {
                initialCwd?.trim().takeUnless { it.isNullOrEmpty() } ?: initial.cwd
            } else {
                initial.cwd
            }
        } catch (error: Exception) {
            if (selectedServerId == bootstrapServerId &&
                serverSelectionGeneration == bootstrapSelectionGeneration
            ) {
                bootstrapAuthFailure = error is WandApiException && error.status == 401
                bootstrapError = "无法连接到「${selectedServer.displayName}」：${error.message ?: "请求失败"}"
            }
        } finally {
            if (selectedServerId == bootstrapServerId &&
                serverSelectionGeneration == bootstrapSelectionGeneration
            ) {
                bootstrapping = false
            }
        }
    }

    BackHandler(enabled = creating) {
        Toast.makeText(context, "会话正在所选服务器上创建，请稍候", Toast.LENGTH_SHORT).show()
    }

    if (showBrowser) {
        DirectoryBrowserScreen(
            api = selectedApi,
            startPath = cwd,
            onPick = { picked ->
                cwd = picked
                showBrowser = false
            },
            onCancel = { showBrowser = false },
        )
        return
    }

    val canCreate = cwd.trim().isNotEmpty() && !creating && !bootstrapping && bootstrapError == null

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
        val requestServerId = selectedServer.serverId
        val requestSelectionGeneration = serverSelectionGeneration
        scope.launch {
            try {
                workflow.persistDefaults(
                    mode = mode,
                    model = model,
                    modelProvider = modelProvider,
                    thinkingEffort = thinkingEffort,
                    defaultProvider = defaultProvider,
                    defaultSessionKind = defaultSessionKind,
                )
                if (selectedServerId == requestServerId &&
                    serverSelectionGeneration == requestSelectionGeneration
                ) {
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                if (selectedServerId == requestServerId &&
                    serverSelectionGeneration == requestSelectionGeneration
                ) {
                    onFailure?.invoke()
                }
                // 后续操作已经排队时，不让旧请求的迟到错误覆盖当前选择反馈。
                if (selectedServerId == requestServerId &&
                    serverSelectionGeneration == requestSelectionGeneration &&
                    generation == defaultsUpdateGeneration
                ) {
                    errorMessage = e.message
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
            claudeModels = modelsResponse?.models.orEmpty(),
            codexModels = modelsResponse?.codexModels.orEmpty(),
            opencodeModels = modelsResponse?.opencodeModels.orEmpty(),
            qoderModels = modelsResponse?.qoderModels.orEmpty(),
            grokModels = modelsResponse?.grokModels.orEmpty(),
            piModels = modelsResponse?.piModels.orEmpty(),
        )
        if (normalized != thinkingEffort) {
            thinkingEffort = normalized
            persistDefaults(thinkingEffort = "off")
        }
    }

    LaunchedEffect(
        provider,
        selectedModel,
        thinkingLevelIds,
        thinkingEffort,
        canValidateThinkingEffort,
        bootstrapping,
    ) {
        if (!bootstrapping && canValidateThinkingEffort && thinkingEffort !in thinkingLevelIds) {
            thinkingEffort = "off"
            persistDefaults(thinkingEffort = "off")
        }
    }

    fun create() {
        if (!canCreate) return
        val requestServer = selectedServer
        val requestWorkflow = workflow
        val requestDraft = NewSessionDraft(
            cwd = cwd,
            provider = provider,
            kind = sessionKind,
            mode = mode,
            model = selectedModel,
            thinkingEffort = thinkingEffort,
            firstMessage = "",
        )
        errorMessage = null
        ++defaultsUpdateGeneration
        val started = SessionCreationCoordinator.start(
            hostServerId = activeServerId,
            targetServerId = requestServer.serverId,
        ) {
            requestWorkflow.create(requestDraft)
        }
        if (!started) {
            Toast.makeText(context, "已有会话正在创建，请稍候", Toast.LENGTH_SHORT).show()
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
    val glassBackdrop = rememberGlassBackdrop()
    val creationBlockerInteraction = remember { MutableInteractionSource() }
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier
            .imePadding()
            .ambientBackground(),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.glassSurface(
                    glassBackdrop,
                    RoundedCornerShape(0.dp),
                    secondaryBarGlass,
                    edgeToEdge = true,
                ),
                title = { Text("新建会话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !creating) {
                        Text(
                            if (creating) "创建中…" else "取消",
                            fontSize = 16.sp,
                            color = if (creating) WandColors.textMuted else WandColors.textSecondary,
                        )
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(
                        glassBackdrop,
                        RoundedCornerShape(0.dp),
                        secondaryBarGlass,
                        edgeToEdge = true,
                    )
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = when {
                            bootstrapping -> "正在读取 ${selectedServer.displayName} 的配置…"
                            bootstrapError != null -> "所选服务器暂时不可用"
                            canCreate -> {
                                val sessionSummary = when (sessionKind) {
                                    NewSessionKind.Structured -> "${providerDisplayName(provider)} · 聊天 · ${modeLabel(mode)}"
                                    NewSessionKind.Pty -> "${providerDisplayName(provider)} · CLI 终端 · ${modeLabel(mode)}"
                                    NewSessionKind.Shell -> "空白终端 · Shell"
                                }
                                "${selectedServer.displayName} · $sessionSummary"
                            }
                            else -> "选择工作目录后即可创建"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                    )
                    PrimaryCreateButton(
                        onClick = ::create,
                        enabled = canCreate,
                        creating = creating,
                        label = if (sessionKind == NewSessionKind.Shell) {
                            "创建空白终端"
                        } else {
                            "创建 ${providerDisplayName(provider)} 会话"
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(glassBackdrop),
        ) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                PageIntro()

                SetupSectionHeader(
                    number = "01",
                    title = "选择服务器",
                    description = "会话与工作目录会保存在所选服务器。",
                )
                ServerPicker(
                    servers = availableServers,
                    selectedServerId = selectedServer.serverId,
                    enabled = !creating,
                    onSelect = {
                        if (it != selectedServerId) {
                            bootstrapping = true
                            serverSelectionGeneration += 1
                            selectedServerId = it
                        }
                    },
                )

                if (bootstrapping) {
                    ServerBootstrapLoading(selectedServer.displayName)
                } else if (bootstrapError != null) {
                    ErrorBanner(bootstrapError ?: "服务器暂时不可用")
                    WandButton(
                        label = "重试连接",
                        onClick = { bootstrapRetryKey += 1 },
                        variant = com.wand.app.ui.components.WandButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                    if (bootstrapAuthFailure) {
                        WandButton(
                            label = "重新连接此服务器",
                            onClick = { onReconnectServer(selectedServer.serverId) },
                            variant = com.wand.app.ui.components.WandButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                } else {
                SetupSectionHeader(
                    number = "02",
                    title = "选择工具",
                    description = "选择 AI 助手，或直接启动一个空白终端。",
                )
                ToolPicker(
                    options = TOOL_OPTIONS,
                    selected = if (sessionKind == NewSessionKind.Shell) SHELL_TOOL_ID else provider,
                    onSelect = { toolId ->
                        if (toolId == SHELL_TOOL_ID) {
                            sessionKind = NewSessionKind.Shell
                        } else {
                            provider = toolId
                            sessionKind = assistantSessionKind
                            persistDefaults(defaultProvider = toolId)
                            mode = workflow.clampMode(mode, toolId)
                            selectedModel = ""
                            normalizeThinkingEffort(toolId, "")
                        }
                    },
                )

                SetupSectionHeader(
                    number = "03",
                    title = "选择项目",
                    description = "新会话将从这个工作目录开始。",
                )
                CwdCard(
                    cwd = cwd,
                    onCwdChange = { cwd = it },
                    recentPaths = recentPaths,
                    onBrowse = { if (!creating) showBrowser = true },
                    onPickRecent = { cwd = it },
                )

                if (sessionKind != NewSessionKind.Shell) {
                    SetupSectionHeader(
                        number = "04",
                        title = "选择会话形式",
                        description = "聊天适合阅读与协作，终端保留 CLI 的原始交互。",
                    )
                    SessionKindPicker(
                        selected = sessionKind,
                        onSelect = { kind ->
                            sessionKind = kind
                            assistantSessionKind = kind
                            kind.preferenceValue?.let { persistDefaults(defaultSessionKind = it) }
                        },
                    )
                    InlineHint(sessionKindHint(provider, sessionKind))
                }

                SetupSectionHeader(
                    number = if (sessionKind == NewSessionKind.Shell) "04" else "05",
                    title = if (sessionKind == NewSessionKind.Shell) "确认终端环境" else "配置运行方式",
                    description = if (sessionKind == NewSessionKind.Shell) {
                        "将使用服务端配置的登录 Shell，不加载任何 AI CLI。"
                    } else {
                        "默认设置已经适合多数任务，需要时再调整。"
                    },
                )
                if (sessionKind != NewSessionKind.Shell) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    )
                    }

                    Text(
                        "权限模式",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textSecondary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 7.dp),
                    )
                    ModePicker(
                        modes = SESSION_MODES,
                        supportedModes = supportedModes,
                        providerName = providerDisplayName(provider),
                        selected = mode,
                        onSelect = { selectedMode ->
                            mode = selectedMode
                            persistDefaults(mode = selectedMode)
                        },
                    )
                    InlineHint(modeHint(provider, mode))
                } else {
                    InlineHint("创建后会直接进入可输入命令的空白终端，工作目录保持为上方所选项目。")
                }

                if (errorMessage != null) {
                    ErrorBanner(errorMessage ?: "")
                }
                }

                Spacer(modifier = Modifier.size(32.dp))
            }
            if (creating) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = creationBlockerInteraction,
                            indication = null,
                        ) {
                            Toast.makeText(
                                context,
                                "会话正在所选服务器上创建，请稍候",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                )
            }
        }
    }
}

@Composable
private fun PageIntro() {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
    ) {
        Text(
            "在哪里，交给谁",
            fontSize = 27.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            color = WandColors.textPrimary,
            letterSpacing = (-0.6).sp,
        )
        Text(
            "选择服务器、工具与项目，再决定会话如何运行。",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = WandColors.textSecondary,
        )
    }
}

@Composable
private fun ServerBootstrapLoading(serverName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .selectCard(selected = false)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = WandColors.brand,
            strokeWidth = 2.dp,
        )
        Text(
            "正在读取 $serverName 的配置…",
            fontSize = 13.sp,
            color = WandColors.textSecondary,
        )
    }
}

@Composable
private fun SetupSectionHeader(number: String, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 10.dp),
    ) {
        Text(
            number,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.brand,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = WandColors.textMuted,
            )
        }
    }
}

@Composable
private fun PrimaryCreateButton(
    onClick: () -> Unit,
    enabled: Boolean,
    creating: Boolean,
    label: String,
) {
    WandButton(
        label = if (creating) "创建中…" else label,
        onClick = onClick,
        enabled = enabled,
        loading = creating,
        trailingIcon = if (creating) null else WandIcons.arrowUp,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
    )
}

@Composable
private fun ToolPicker(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(WandColors.surfaceSoft.copy(alpha = 0.46f))
            .border(0.55.dp, WandColors.borderStrong.copy(alpha = 0.26f), RoundedCornerShape(18.dp))
            .padding(4.dp),
    ) {
        options.chunked(4).forEach { rowOptions ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowOptions.forEach { (value, label) ->
                    val active = value == selected
                    val iconColor by animateColorAsState(
                        if (active) WandColors.brand else WandColors.textSecondary,
                        WandMotion.tweenFast(),
                        label = "toolIconColor",
                    )
                    val itemBackground by animateColorAsState(
                        if (active) WandColors.surface.copy(alpha = 0.96f) else Color.Transparent,
                        WandMotion.tweenFast(),
                        label = "toolItemBg",
                    )
                    val itemBorder by animateColorAsState(
                        if (active) WandColors.borderStrong.copy(alpha = 0.24f) else Color.Transparent,
                        WandMotion.tweenFast(),
                        label = "toolItemBorder",
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(itemBackground)
                            .border(0.8.dp, itemBorder, RoundedCornerShape(14.dp))
                            .semantics {
                                contentDescription = label
                                stateDescription = if (active) "已选择" else "未选择"
                            }
                            .selectable(selected = active, role = Role.Tab) { onSelect(value) },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                painter = BrandLogos.painterForProvider(value),
                                contentDescription = null,
                                tint = BrandLogos.tintForProvider(value, iconColor),
                                modifier = Modifier.size(21.dp * BrandLogos.opticalScale(value)),
                            )
                            Text(
                                label,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) WandColors.brand else WandColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
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
private fun SessionKindPicker(selected: NewSessionKind, onSelect: (NewSessionKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.height(IntrinsicSize.Max),
    ) {
        SessionKindCard(
            title = "聊天",
            technicalLabel = "结构化",
            description = "清晰呈现消息与工具调用",
            icon = WandIcons.chat,
            selected = selected == NewSessionKind.Structured,
            onClick = { onSelect(NewSessionKind.Structured) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        SessionKindCard(
            title = "CLI 终端",
            technicalLabel = "PTY",
            description = "保留 AI CLI 的原始交互",
            icon = WandIcons.terminal,
            selected = selected == NewSessionKind.Pty,
            onClick = { onSelect(NewSessionKind.Pty) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun SessionKindCard(
    title: String,
    technicalLabel: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .heightIn(min = 112.dp)
            .selectCard(selected)
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) "已选择" else "未选择"
            }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) WandColors.brandSoft else WandColors.surfaceSoft),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) WandColors.brand else WandColors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                technicalLabel,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) WandColors.brand else WandColors.textMuted,
            )
        }
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) WandColors.brand else WandColors.textPrimary,
        )
        Text(
            description,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = WandColors.textSecondary,
        )
    }
}

/** 服务器是新建流程的第一层作用域；名称与地址保持两行，避免相似主机被误选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(
    servers: List<HomeServerConnection>,
    selectedServerId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val selected = servers.firstOrNull { it.serverId == selectedServerId } ?: servers.first()
    var expanded by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .selectCard(selected = false)
            .semantics(mergeDescendants = true) {
                stateDescription = "当前服务器 ${selected.displayName}，地址 ${selected.serverUrl}"
            }
            .clickable(
                enabled = enabled && servers.size > 1,
                role = Role.DropdownList,
            ) { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(WandColors.brand.copy(alpha = 0.10f)),
        ) {
            Icon(
                WandIcons.server,
                contentDescription = null,
                tint = WandColors.brand,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                selected.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                selected.serverUrl,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Text(
            if (servers.size > 1) "${servers.size} 台" else if (selected.hasToken) "已认证" else "直接连接",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textMuted,
        )
        if (servers.size > 1) {
            Icon(
                WandIcons.chevronRight,
                contentDescription = "选择其他服务器",
                tint = WandColors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (expanded) {
        WandBottomSheet(onDismissRequest = { expanded = false }) {
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
                        "选择服务器",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    servers.forEach { server ->
                        val isSelected = server.serverId == selected.serverId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 58.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) WandColors.brandSoft else Color.Transparent)
                                .semantics(mergeDescendants = true) {
                                    stateDescription = if (isSelected) "已选择" else "未选择"
                                }
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                ) {
                                    onSelect(server.serverId)
                                    expanded = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                if (isSelected) WandIcons.check else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) WandColors.brand else WandColors.textMuted,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    server.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) WandColors.brand else WandColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    server.serverUrl,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = WandColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                            Text(
                                if (server.hasToken) "已认证" else "直接连接",
                                fontSize = 10.sp,
                                color = WandColors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 模型 / 思考深度设置行；整行可点，当前值右对齐，避免两列窄卡截断长模型名。 */
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
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .selectCard(selected = false)
                .semantics(mergeDescendants = true) {
                    stateDescription = "当前为$value"
                }
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(WandColors.brand.copy(alpha = 0.10f)),
            ) {
                Icon(icon, contentDescription = null, tint = WandColors.brand, modifier = Modifier.size(18.dp))
            }
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textPrimary,
                modifier = Modifier.weight(0.72f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1.28f),
            ) {
                Text(
                    value,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    WandIcons.chevronRight,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            WandBottomSheet(
                onDismissRequest = { expanded = false },
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

/** 完整展示权限模式；支持项可选，不支持项保留上下文并明确标记，避免选择器看起来损坏。 */
@Composable
private fun ModePicker(
    modes: List<SessionMode>,
    supportedModes: Set<String>,
    providerName: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    WandCard(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val enabled = mode.id in supportedModes
            val active = mode.id == selected
            val background by animateColorAsState(
                if (active) WandColors.brandSoft else Color.Transparent,
                WandMotion.tweenFast(),
                label = "modeRowBackground",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .background(background)
                    .semantics(mergeDescendants = true) {
                        stateDescription = when {
                            active -> "已选择"
                            enabled -> "未选择"
                            else -> "$providerName 不支持此模式"
                        }
                    }
                    .selectable(
                        selected = active,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode.id) },
                    )
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            when {
                                active -> WandColors.brand
                                enabled -> WandColors.surfaceSoft
                                else -> WandColors.surfaceSoft.copy(alpha = 0.42f)
                            },
                        ),
                ) {
                    if (active) {
                        Icon(
                            WandIcons.check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        mode.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            active -> WandColors.brand
                            enabled -> WandColors.textPrimary
                            else -> WandColors.textMuted.copy(alpha = 0.62f)
                        },
                    )
                    Text(
                        mode.desc,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = if (enabled) {
                            WandColors.textSecondary
                        } else {
                            WandColors.textMuted.copy(alpha = 0.54f)
                        },
                    )
                }
                if (!enabled) {
                    Text(
                        "不可用",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = WandColors.textMuted.copy(alpha = 0.62f),
                    )
                }
            }
            if (index < modes.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = WandColors.border,
                    modifier = Modifier.padding(start = 46.dp),
                )
            }
        }
    }
}

/** 工作目录：当前路径是主信息，浏览入口有明确按钮形态；最近目录最多展示三条。 */
@Composable
private fun CwdCard(
    cwd: String,
    onCwdChange: (String) -> Unit,
    recentPaths: List<RecentPath>,
    onBrowse: () -> Unit,
    onPickRecent: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.wandInputSurface(focused = focused)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 13.dp, top = 9.dp, end = 8.dp, bottom = 9.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(WandColors.brandSoft),
            ) {
                Icon(
                    WandIcons.folder,
                    contentDescription = null,
                    tint = WandColors.brand,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "项目路径",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.textMuted,
                )
                BasicTextField(
                    value = cwd,
                    onValueChange = onCwdChange,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
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
                                    fontSize = 13.sp,
                                    color = WandColors.textMuted,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            WandIconButton(
                icon = WandIcons.chevronRight,
                contentDescription = "浏览目录",
                onClick = onBrowse,
                variant = WandIconButtonVariant.Chrome,
                tint = WandColors.brand,
                iconSize = 18.dp,
            )
        }
        if (recentPaths.isNotEmpty()) {
            HorizontalDivider(color = WandColors.border, thickness = 0.5.dp)
            Text(
                "最近使用",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textMuted,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 13.dp, top = 10.dp, bottom = 4.dp),
            )
            recentPaths.take(3).forEach { recent ->
                val isSelected = cwd == recent.path
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .background(if (isSelected) WandColors.brandSoft else Color.Transparent)
                        .clickable(role = Role.Button) { onPickRecent(recent.path) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
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
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
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

/** 紧贴控件的动态说明，不额外套卡，保持主次层级。 */
@Composable
private fun InlineHint(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(WandColors.brand.copy(alpha = 0.78f)),
        )
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = WandColors.textSecondary.copy(alpha = 0.88f),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 模式选项：id / 标签 / 卡片内一句话说明，与 Web renderModeCards 完全一致。 */
private data class SessionMode(val id: String, val label: String, val desc: String)

private const val SHELL_TOOL_ID = "terminal"

private val TOOL_OPTIONS = listOf(
    "claude" to "Claude",
    "codex" to "Codex",
    "opencode" to "OpenCode",
    "grok" to "Grok",
    "qoder" to "Qoder",
    "pi" to "Pi",
    SHELL_TOOL_ID to "空白终端",
)

private val SESSION_MODES = listOf(
    SessionMode("managed", "托管", "全自动完成任务"),
    SessionMode("full-access", "全权限", "自动确认权限"),
    SessionMode("auto-edit", "自动编辑", "自动确认修改"),
    SessionMode("default", "标准", "逐步确认操作"),
    SessionMode("native", "原生", "原生结构化输出"),
)

private fun modeLabel(mode: String): String =
    SESSION_MODES.firstOrNull { it.id == mode }?.label ?: "标准"

/** 会话类型动态说明，文案对齐 Web getSessionKindHint。 */
private fun sessionKindHint(provider: String, kind: NewSessionKind): String =
    if (kind == NewSessionKind.Shell) {
        "启动当前工作目录下的交互式登录 Shell，不自动运行任何 CLI 工具。"
    } else if (kind == NewSessionKind.Structured) {
        when (provider) {
            "codex" -> "Codex JSONL 结构化聊天界面，支持多轮对话和工具调用展示。"
            "opencode" -> "OpenCode JSON 结构化聊天界面，支持多轮对话和工具调用展示。"
            "grok" -> "Grok streaming-json 结构化聊天界面，支持多轮续聊与思考过程展示。"
            "qoder" -> "Qoder stream-json 结构化聊天界面，支持续聊、思考过程和工具调用展示。"
            "pi" -> "Pi JSON 结构化聊天界面，支持续聊、思考过程和工具调用展示。"
            else -> "结构化聊天界面，支持多轮对话、流式输出和工具调用展示。"
        }
    } else {
        when (provider) {
            "codex" -> "Codex PTY 终端会话；terminal 是原始输出，chat 是解析后的阅读视图。"
            "opencode" -> "OpenCode TUI 终端会话，支持持续交互和终端视图。"
            "grok" -> "Grok Build TUI 的原始 PTY 终端会话。"
            "qoder" -> "Qoder CLI TUI 的原始 PTY 终端会话。"
            "pi" -> "Pi TUI 的原始 PTY 终端会话。"
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
    if (provider == "grok") {
        return if (mode == "full-access" || mode == "managed") {
            "Grok 将以 always-approve 运行；支持 TUI 与 streaming-json 结构化会话。"
        } else {
            "Grok 使用自身权限确认；支持 TUI 与 streaming-json 结构化会话。"
        }
    }
    if (provider == "qoder") {
        return when (mode) {
            "full-access", "managed" -> "Qoder 将以 bypass_permissions 运行；支持 TUI 与 stream-json 结构化会话。"
            "auto-edit" -> "Qoder 将自动批准工作区内的安全编辑。"
            else -> "Qoder 使用自身权限确认；结构化模式下未批准的操作会被拒绝。"
        }
    }
    if (provider == "pi") return "Pi 支持标准与托管模式；模型和 thinking 会传给 Pi CLI。"
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
    var loadKey by remember { mutableIntStateOf(0) }

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

    val directoryBackdrop = rememberGlassBackdrop()

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.ambientBackground(),
        topBar = {
            TopAppBar(
                modifier = Modifier.glassSurface(
                    directoryBackdrop,
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .glassBackdropSource(directoryBackdrop),
        ) {
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
                loading -> LoadingState(text = "加载目录中…")
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
