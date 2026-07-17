package com.wand.app.ui.screens

import com.wand.app.data.ModelInfo
import com.wand.app.data.ModelsResponse
import com.wand.app.data.NewSessionPort
import com.wand.app.data.ProviderDefaultModels
import com.wand.app.data.RecentPath
import com.wand.app.data.ServerConfigInfo
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.clampSessionMode
import com.wand.app.data.defaultFor
import com.wand.app.data.modelsForProvider
import com.wand.app.data.supportedSessionModeIds
import com.wand.app.ui.thinkingEffortOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NewSessionBootstrap(
    val provider: String,
    val structured: Boolean,
    val mode: String,
    val thinkingEffort: String,
    val cwd: String,
    val recentPaths: List<RecentPath>,
    val models: ModelsResponse?,
    val defaultModels: ProviderDefaultModels,
)

data class NewSessionDraft(
    val cwd: String,
    val provider: String,
    val structured: Boolean,
    val mode: String,
    val model: String,
    val thinkingEffort: String,
    val firstMessage: String,
)

class NewSessionWorkflow(private val port: NewSessionPort) {
    private val defaultsMutex = Mutex()

    suspend fun bootstrap(): NewSessionBootstrap {
        val config = runCatching { port.serverConfig() }.getOrNull()
        val models = runCatching { port.models() }.getOrNull()
        val recentPaths = runCatching { port.recentPaths() }.getOrDefault(emptyList())
        val provider = normalizeProvider(config?.defaultProvider)
        val defaults = models?.resolvedDefaults()
            ?: config?.resolvedDefaults()
            ?: EMPTY_DEFAULT_MODELS
        return NewSessionBootstrap(
            provider = provider,
            structured = config?.defaultSessionKind != "pty",
            mode = clampSessionMode(config?.defaultMode ?: "managed", provider),
            thinkingEffort = config?.defaultThinkingEffort ?: "off",
            cwd = recentPaths.firstOrNull()?.path ?: config?.defaultCwd.orEmpty(),
            recentPaths = recentPaths,
            models = models,
            defaultModels = defaults,
        )
    }

    suspend fun persistDefaults(
        mode: String? = null,
        model: String? = null,
        modelProvider: String = "claude",
        thinkingEffort: String? = null,
        defaultProvider: String? = null,
        defaultSessionKind: String? = null,
    ) = defaultsMutex.withLock {
        persistDefaultsUnlocked(
            mode = mode,
            model = model,
            modelProvider = modelProvider,
            thinkingEffort = thinkingEffort,
            defaultProvider = defaultProvider,
            defaultSessionKind = defaultSessionKind,
        )
    }

    private suspend fun persistDefaultsUnlocked(
        mode: String?,
        model: String?,
        modelProvider: String,
        thinkingEffort: String?,
        defaultProvider: String?,
        defaultSessionKind: String?,
    ) = port.updateNewSessionDefaults(
        mode = mode,
        model = model,
        modelProvider = modelProvider,
        thinkingEffort = thinkingEffort,
        defaultProvider = defaultProvider,
        defaultSessionKind = defaultSessionKind,
    )

    suspend fun create(draft: NewSessionDraft): SessionSnapshot = defaultsMutex.withLock {
        val cwd = draft.cwd.trim()
        require(cwd.isNotEmpty()) { "工作目录不能为空" }
        val provider = normalizeProvider(draft.provider)
        val mode = clampSessionMode(draft.mode, provider)
        val model = draft.model.ifBlank { null }
        val prompt = draft.firstMessage.trim().ifEmpty { null }

        // 创建前持久化完整最终选择；页面即时保存即使被取消，也不会留下半套默认值。
        persistDefaultsUnlocked(
            mode = mode,
            model = model,
            modelProvider = provider,
            thinkingEffort = draft.thinkingEffort,
            defaultProvider = provider,
            defaultSessionKind = if (draft.structured) "structured" else "pty",
        )
        if (draft.structured) {
            port.createStructuredSession(
                cwd = cwd,
                mode = mode,
                prompt = prompt,
                provider = provider,
                model = model,
                thinkingEffort = draft.thinkingEffort,
            )
        } else {
            port.createPtySession(
                cwd = cwd,
                mode = mode,
                initialInput = prompt,
                provider = provider,
                model = model,
                thinkingEffort = draft.thinkingEffort,
            )
        }
    }

    fun normalizeThinkingEffort(
        provider: String,
        selectedModel: String?,
        currentEffort: String,
        defaultModels: ProviderDefaultModels,
        claudeModels: List<ModelInfo>,
        codexModels: List<ModelInfo>,
        opencodeModels: List<ModelInfo>,
        qoderModels: List<ModelInfo> = emptyList(),
    ): String {
        val models = modelsForProvider(provider, claudeModels, codexModels, opencodeModels, qoderModels)
        if (provider == "codex" && models.isEmpty()) return currentEffort
        return if (thinkingEffortOptions(provider, selectedModel, defaultModels.defaultFor(provider), models)
                .any { it.id == currentEffort }
        ) {
            currentEffort
        } else {
            "off"
        }
    }

    fun supportedModes(provider: String): Set<String> = supportedSessionModeIds(provider)
    fun clampMode(mode: String, provider: String): String = clampSessionMode(mode, provider)

    companion object {
        val EMPTY_DEFAULT_MODELS = ProviderDefaultModels(null, null, null, null)

        fun normalizeProvider(provider: String?): String = when (provider) {
            "codex" -> "codex"
            "opencode" -> "opencode"
            "grok" -> "grok"
            "qoder" -> "qoder"
            else -> "claude"
        }
    }
}

internal fun ProviderDefaultModels.withDefault(provider: String, value: String?): ProviderDefaultModels =
    when (provider) {
        "codex" -> copy(codex = value)
        "opencode" -> copy(opencode = value)
        "qoder" -> copy(qoder = value)
        else -> copy(claude = value)
    }

private fun ModelsResponse.resolvedDefaults(): ProviderDefaultModels =
    defaultModels ?: ProviderDefaultModels(defaultModel, defaultCodexModel, defaultOpenCodeModel)

private fun ServerConfigInfo.resolvedDefaults(): ProviderDefaultModels =
    defaultModels ?: ProviderDefaultModels(defaultModel, defaultCodexModel, defaultOpenCodeModel)
