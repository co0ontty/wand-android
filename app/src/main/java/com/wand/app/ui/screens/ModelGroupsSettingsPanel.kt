package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wand.app.data.*
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** Native settings editor. It uses the same admin REST contract as Web, never changes permissions. */
@Composable
fun ModelGroupsSettingsPanel(api: WandApi, initiallyExpanded: Boolean = false) {
    var expanded by remember(api) { mutableStateOf(initiallyExpanded) }
    var loaded by remember(api) { mutableStateOf(false) }
    var busy by remember(api) { mutableStateOf(false) }
    var needsAdmin by remember(api) { mutableStateOf(false) }
    var password by remember(api) { mutableStateOf("") }
    var catalog by remember(api) { mutableStateOf<ModelsResponse?>(null) }
    var base by remember(api) { mutableStateOf<List<ModelGroup>>(emptyList()) }
    var groups by remember(api) { mutableStateOf<List<ModelGroup>>(emptyList()) }
    var provider by remember(api) { mutableStateOf("claude") }
    var dirty by remember(api) { mutableStateOf(false) }
    var discard by remember(api) { mutableStateOf(false) }
    var status by remember(api) { mutableStateOf<String?>(null) }
    var error by remember(api) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load(discardDraft: Boolean = false) {
        if (busy) return
        busy = true
        status = null
        try {
            val saved = api.modelGroupSettings()
            val directory = api.models()
            catalog = directory
            if (!dirty || discardDraft) {
                base = saved
                groups = modelGroupsDraft(saved, directory)
                dirty = false
            }
            loaded = true
            needsAdmin = false
            discard = false
            error = false
            if (dirty) status = "目录已刷新，当前草稿保留。若其他设备已修改，请放弃草稿并重载后再编辑。"
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            needsAdmin = (failure as? WandApiException)?.status == 403
            error = true
            status = if (needsAdmin) "配置分组需要管理员权限，请输入服务器管理密码。" else failure.message ?: "加载失败，已有草稿保留。"
        } finally { busy = false }
    }
    fun edit(next: List<ModelGroup>) { if (!busy) { groups = next; dirty = true; status = null; discard = false } }

    LaunchedEffect(expanded, api) { if (expanded && !loaded && !needsAdmin) load() }

    WandCard(contentPadding = PaddingValues(12.dp)) {
        if (!initiallyExpanded) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("配置模型分组", modifier = Modifier.weight(1f), color = WandColors.textPrimary,
                style = MaterialTheme.typography.titleSmall)
            WandIconButton(if (expanded) WandIcons.close else WandIcons.add, if (expanded) "收起模型分组" else "展开模型分组",
                onClick = { expanded = !expanded; password = "" }, enabled = !busy, variant = WandIconButtonVariant.Quiet)
        }
        WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (needsAdmin) {
                    WandTextField(value = password, onValueChange = { password = it }, label = "服务器管理密码",
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    WandButton("解锁分组配置", enabled = !busy && password.isNotBlank(), loading = busy, onClick = {
                        if (!busy) scope.launch {
                            busy = true
                            val submitted = password
                            password = ""
                            try {
                                api.loginModelGroupAdmin(submitted)
                                needsAdmin = false
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = true; status = failure.message ?: "登录失败"
                            } finally { busy = false }
                            if (!needsAdmin) load()
                        }
                    })
                }
                if (loaded && !needsAdmin) {
                    WandChoice(label = "工具 · ${groupProviderLabel(provider)}", enabled = !busy,
                        options = WandProvider.entries.map { it.id to groupProviderLabel(it.id) }, onSelect = { provider = it })
                    Text("首选在前；仅明确未接受输入的启动失败才尝试下一项。已开始执行或送达未知时不自动重发；终端仅用首选。",
                        color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
                    groups.filter { it.provider == provider }.forEach { group ->
                        androidx.compose.runtime.key(group.provider, group.id) {
                            ModelGroupEditor(group, catalog, busy,
                                onChange = { next -> edit(groups.map { if (it.id == group.id && it.provider == group.provider) next else it }) },
                                onRemove = { edit(groups.filterNot { it.id == group.id && it.provider == group.provider }) })
                        }
                    }
                    WandButton("添加分组", variant = WandButtonVariant.Secondary, icon = WandIcons.add,
                        enabled = !busy && groups.count { it.provider == provider && !it.builtIn } < MODEL_GROUP_MAX_PER_PROVIDER,
                        onClick = {
                            var number = 1
                            while (groups.any { it.provider == provider && it.name == "分组 $number" }) number++
                            edit(groups + ModelGroup("g_${UUID.randomUUID()}", provider, "分组 $number", emptyList()))
                        })
                    WandButton(if (!busy && !dirty && status != null && !error) "已保存" else "保存模型分组",
                        enabled = dirty && !busy, loading = busy, icon = if (!dirty && !error) WandIcons.check else null,
                        onClick = {
                            if (!busy) scope.launch {
                                val problem = modelGroupListError(groups)
                                if (problem != null) { error = true; status = problem; return@launch }
                                busy = true; status = null
                                try {
                                    val saved = api.saveModelGroups(groups, base)
                                    base = saved
                                    dirty = false
                                    error = false
                                    try {
                                        val fresh = api.models()
                                        catalog = fresh; groups = modelGroupsDraft(saved, fresh)
                                        status = "分组与顺序已保存，聊天、任务和员工可直接选组。"
                                    } catch (failure: Exception) {
                                        if (failure is CancellationException) throw failure
                                        groups = saved
                                        status = "已保存；目录刷新失败，请刷新后查看选择器。"
                                    }
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    error = true
                                    status = failure.message ?: "保存失败，当前草稿已保留。"
                                } finally { busy = false }
                            }
                        })
                    if (dirty) {
                        WandButton(if (discard) "取消放弃" else "放弃草稿并重载", enabled = !busy,
                            variant = WandButtonVariant.Text, onClick = { discard = !discard })
                        if (discard) WandButton("确认放弃当前草稿", enabled = !busy, variant = WandButtonVariant.DangerText,
                            onClick = { scope.launch { load(discardDraft = true) } })
                    }
                }
                if (!needsAdmin) WandButton("刷新分组与目录", enabled = !busy, loading = busy,
                    variant = WandButtonVariant.Text, onClick = { scope.launch { load() } })
                // Fixed in-place feedback; errors never erase drafts or become a toast-only result.
                Text(status ?: if (busy) "处理中…" else "", color = if (error) WandColors.danger else WandColors.textMuted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun groupProviderLabel(provider: String): String =
    if (provider == "pi") "Pi" else providerDisplayName(provider)

@Composable
private fun ModelGroupEditor(group: ModelGroup, catalog: ModelsResponse?, busy: Boolean,
    onChange: (ModelGroup) -> Unit, onRemove: () -> Unit) {
    var expanded by remember(group.provider, group.id) { mutableStateOf(!group.builtIn && group.models.isEmpty()) }
    var custom by remember(group.provider, group.id) { mutableStateOf("") }
    var remove by remember(group.provider, group.id) { mutableStateOf(false) }
    val models = if (group.builtIn) catalog?.freeModels.orEmpty() else groupEditorModels(catalog, group.provider)
    WandCard(contentPadding = PaddingValues(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { expanded = !expanded; remove = false },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name.ifBlank { "未命名分组" }, color = WandColors.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("${group.models.size} 个模型", color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            WandIconButton(if (expanded) WandIcons.close else WandIcons.expand, "展开或收起${group.name}",
                onClick = { expanded = !expanded; remove = false }, enabled = !busy, variant = WandIconButtonVariant.Quiet)
        }
        WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!group.builtIn) WandTextField(group.name, { onChange(group.copy(name = it)) },
                    label = "分组名称", singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                else Text("仅用已验证且仍免费的模型；新成员追加到末尾，收费或下架的自动跳过。",
                    color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
                group.models.forEachIndexed { index, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (index == 0) "首选" else "候选 ${index + 1}"} · ${models.firstOrNull { it.id == id }?.label ?: id}",
                            modifier = Modifier.weight(1f), color = WandColors.textPrimary, style = MaterialTheme.typography.bodySmall)
                        WandIconButton(WandIcons.arrowUp, "上移模型 ${index + 1}", variant = WandIconButtonVariant.Quiet,
                            enabled = !busy && index > 0, onClick = { onChange(moveGroupModel(group, index, -1)) })
                        WandIconButton(WandIcons.expand, "下移模型 ${index + 1}", variant = WandIconButtonVariant.Quiet,
                            enabled = !busy && index < group.models.lastIndex, onClick = { onChange(moveGroupModel(group, index, 1)) })
                        if (!group.builtIn) WandIconButton(WandIcons.close, "移除模型 ${index + 1}", variant = WandIconButtonVariant.Quiet,
                            enabled = !busy, onClick = { onChange(group.copy(models = group.models.filterNot { it == id })) })
                    }
                }
                if (group.models.isEmpty()) Text(if (group.builtIn) "尚无已验证的免费模型，请先在 Web 设置 OpenRouter Key 并同步。" else "请添加至少一个具体模型。",
                    color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
                if (!group.builtIn) {
                    val available = models.filterNot { it.id in group.models }
                    WandChoice("从目录添加模型…", available.map { it.id to it.label },
                        enabled = !busy && available.isNotEmpty() && group.models.size < MODEL_GROUP_MAX_MEMBERS,
                        onSelect = { onChange(group.copy(models = group.models + it)) })
                    WandTextField(custom, { custom = it }, label = "自定义模型 ID", singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    WandButton("添加模型", variant = WandButtonVariant.Secondary,
                        enabled = !busy && custom.isNotBlank() && custom.trim() !in group.models
                            && !isModelGroupSelector(custom.trim()) && custom.trim() != FREE_MODEL_GROUP_SELECTOR
                            && group.models.size < MODEL_GROUP_MAX_MEMBERS,
                        onClick = { onChange(group.copy(models = group.models + custom.trim())); custom = "" })
                    WandButton(if (remove) "取消删除" else "删除分组", variant = WandButtonVariant.DangerText,
                        enabled = !busy, onClick = { remove = !remove })
                    if (remove) {
                        Text("不会改写历史；仍引用此分组的会话与员工需重新选择。", color = WandColors.textMuted,
                            style = MaterialTheme.typography.bodySmall)
                        WandButton("确认删除分组", enabled = !busy, variant = WandButtonVariant.Danger, onClick = onRemove)
                    }
                }
            }
        }
    }
}
