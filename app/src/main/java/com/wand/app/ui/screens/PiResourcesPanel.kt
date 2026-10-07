package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wand.app.data.PiResourceItem
import com.wand.app.data.PiResourceKind
import com.wand.app.data.PiResourceSelection
import com.wand.app.data.PiResourcesResponse
import com.wand.app.data.PiSkillMode
import com.wand.app.ui.PiResourcesController
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandInlineSearchField
import com.wand.app.ui.components.WandSegmentedTrack
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

@Composable
internal fun PiResourcesPanel(controller: PiResourcesController, returnFocus: () -> Unit = {}) {
    BackHandler(enabled = controller.open) {
        if (controller.query.isNotEmpty()) controller.query = ""
        else { controller.dismiss(); returnFocus() }
    }
    WandInlinePanel(visible = controller.open, growFrom = Alignment.Bottom) {
        Column(Modifier.fillMaxWidth().clip(WandShapes.md).background(WandColors.surface)
            .border(1.dp, WandColors.border, WandShapes.md).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pi 会话设置", style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary,
                modifier = Modifier.semantics { heading() })
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(controller.message, color = when (controller.phase) {
                    "failed" -> WandColors.danger
                    "saved" -> WandColors.success
                    else -> WandColors.textSecondary
                }, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                if (controller.phase == "failed" && controller.data == null) {
                    TextButton(onClick = controller::load) { Text("重试") }
                }
            }
            if (controller.data?.supported == true) {
                WandInlineSearchField(expanded = true, query = controller.query,
                    onQueryChange = { controller.query = it }, onCollapse = null, autoFocus = true,
                    onClear = { controller.query = "" }, placeholder = "搜索 Skills / MCP")
            }
            key(controller) {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val data = controller.data
                    PiAutomaticConfiguration(controller, data)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CodeMode", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, color = WandColors.textPrimary,
                            modifier = Modifier.semantics { heading() })
                        Text("启用后可在脚本中组合工具；仅 CodeMode 将工具声明收进脚本入口。",
                            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                        val followLabel = if (data?.autoResources == true && data.autoCodemodeAvailable) "自动判断" else "跟随 Pi"
                        val modes = listOf("follow" to followLabel, "off" to "关闭", "on" to "启用", "only" to "仅脚本")
                        WandSegmentedTrack(itemCount = modes.size,
                            selectedIndex = modes.indexOfFirst { it.first == (data?.codemode ?: "follow") }.coerceAtLeast(0),
                            modifier = Modifier.selectableGroup(), indicatorColor = WandColors.brandSoft,
                            indicatorBorder = WandColors.border) {
                            modes.forEach { (mode, label) ->
                                val chosen = data?.codemode == mode
                                Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                    color = if (chosen) WandColors.brand else WandColors.textSecondary,
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                                        .selectable(selected = chosen, enabled = data?.codemodeAvailable == true && !controller.busy,
                                            role = Role.RadioButton, onClick = { controller.setCodemode(mode) })
                                        .padding(horizontal = 4.dp, vertical = 14.dp))
                            }
                        }
                        if (data != null && !data.codemodeAvailable) Text("当前服务端不支持本会话 CodeMode 设置，请更新服务端。",
                            color = WandColors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (data?.supported == true && data.selection == null) {
                        Text("旧会话仍沿用 Pi 自动发现", color = WandColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !controller.busy, onClick = { controller.save(PiResourceSelection()) }) {
                            Text("改为仅选定资源")
                        }
                    }
                    if (data?.supported == true) {
                        PiResourceGroup(controller, PiResourceKind.Skill, "Skills", data.skills)
                        PiResourceGroup(controller, PiResourceKind.Mcp, "MCP", data.mcpServers)
                        if (data.reason.isNotBlank()) {
                            Text(data.reason, style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                        }
                        Text(if (data.selection == null)
                            "旧会话仍自动发现已安装资源；勾选任意项后下一轮才按本会话选择加载。基础工具不受影响。"
                            else if (data.skillLocksAvailable) "Skills 三档：关 → 开 → 开并锁定。锁定项始终开启，其余由自动配置选择；左滑回「开」解锁。MCP 手选项保留。"
                            else "读写、命令等基础工具保持现有配置，无需逐项选择。未选中的 Skills 不会自动加载，MCP 不会连接。",
                            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                        Text("不是文件系统沙箱；旧历史中已读过的技能内容不会被删除。",
                            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PiAutomaticConfiguration(controller: PiResourcesController, data: PiResourcesResponse?) {
    val checked = data?.autoResources == true
    val enabled = data != null && data.supported && !controller.busy && (data.autoResourcesAvailable || checked)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                onValueChange = controller::setAutomaticResources)
            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("按提示词自动配置", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = WandColors.textPrimary)
                Text(if (data?.skillLocksAvailable == true) "锁定的 Skills 固定开启，其余自动选择；MCP 手选与 CodeMode 手动覆盖优先。"
                    else if (data?.autoCodemodeAvailable == true) "发送后选择 Skills / MCP 并判断 CodeMode；手选与手动覆盖优先。"
                    else "发送后选择 Skills / MCP，保留手选项。",
                    style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = WandColors.brand,
                    uncheckedThumbColor = WandColors.textMuted, uncheckedTrackColor = WandColors.surfaceSoft))
        }
        Text(when {
            data == null -> "正在读取自动配置能力…"
            !data.autoResourcesAvailable -> data.autoResourcesReason
            !data.autoCodemodeAvailable -> "当前服务端仅自动选择 Skills / MCP；CodeMode 自动判断需更新服务端。"
            else -> "本轮配置显示在消息小字中；失败沿用原配置，MCP 目前只按明确点名选择。"
        }, style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
    }
}

@Composable
private fun PiResourceGroup(controller: PiResourcesController, kind: PiResourceKind, title: String, items: List<PiResourceItem>) {
    val selected = if (kind == PiResourceKind.Skill) controller.data?.selection?.skills.orEmpty()
        else controller.data?.selection?.mcpServers.orEmpty()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, color = WandColors.textPrimary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        Text(if (controller.data?.selection == null) "${items.size} 项可选" else "${selected.size} / ${items.size}", color = WandColors.textSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
    val query = controller.query.trim()
    val filtered = items.filter { it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }
    if (filtered.isEmpty()) {
        Text(if (items.isEmpty()) "暂无已安装或已配置的$title" else "没有匹配的$title",
            color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
    filtered.forEach { item -> key(item.id) {
        PiResourceRow(item.name, item.description, item.id in selected,
            enabled = controller.data?.supported == true && !controller.busy,
            onToggle = { controller.toggleItem(kind, item.id, it) },
            skillMode = if (kind == PiResourceKind.Skill && controller.data?.skillLocksAvailable == true) controller.data?.skillMode(item.id) else null,
            saving = controller.phase == "saving", onModeChange = { controller.setSkillMode(item.id, it) })
    } }
    val references = if (kind == PiResourceKind.Skill) (selected + controller.data?.lockedSkills.orEmpty()).distinct() else selected
    references.filter { id -> items.none { it.id == id } }.forEach { id -> key(id) {
        PiResourceRow("资源已移除", "不会回退加载其他资源，取消此项后可重新选择。", id in selected,
            enabled = controller.data?.supported == true && !controller.busy,
            onToggle = {
                if (kind == PiResourceKind.Skill && controller.data?.skillLocksAvailable == true) controller.setSkillMode(id, PiSkillMode.Off)
                else controller.toggleItem(kind, id, false)
            })
    } }
}

@Composable
private fun PiResourceRow(name: String, description: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit,
    skillMode: PiSkillMode? = null, saving: Boolean = false, onModeChange: (PiSkillMode) -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(WandShapes.sm)
        .background(if (checked) WandColors.brandSoft else if (pressed) WandColors.surfaceSoft else WandColors.surface)
        .border(1.dp, if (focused) WandColors.focusRing else Color.Transparent, WandShapes.sm)
        .then(if (skillMode == null) Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle,
            interactionSource = interaction, indication = ripple()) else Modifier)
        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = WandColors.textPrimary, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
            if (description.isNotBlank()) Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis, color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (skillMode != null) PiSkillSwitch(name, skillMode, enabled, saving, onModeChange)
        else Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
