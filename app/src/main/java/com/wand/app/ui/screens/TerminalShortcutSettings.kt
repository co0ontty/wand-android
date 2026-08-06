package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.terminal.BuiltInTerminalShortcuts
import com.wand.app.ui.terminal.TerminalKeyBinding
import com.wand.app.ui.terminal.TerminalModifier
import com.wand.app.ui.terminal.TerminalShortcut
import com.wand.app.ui.terminal.TerminalShortcutPreferenceStore
import com.wand.app.ui.terminal.TerminalSpecialKeys
import com.wand.app.ui.terminal.buildTerminalShortcut
import com.wand.app.ui.terminal.normalizeTerminalKeyInput
import com.wand.app.ui.terminal.rememberTerminalShortcutPreferences
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalShortcutSettingsSection() {
    val (store, snapshot) = rememberTerminalShortcutPreferences()
    var showCustomDialog by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    SettingsSection(
        title = "终端快捷键",
        description = "选择 PTY 快捷键条中的按键，并创建自己的 Ctrl / Alt / Shift 组合。",
    ) {
        WandCard(modifier = Modifier.fillMaxWidth(), shape = WandShapes.lg) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingSummary(
                    title = "快捷键条",
                    detail = "点按开关显示；方向键和编辑键支持长按连发。",
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    BuiltInTerminalShortcuts.forEach { shortcut ->
                        TerminalSettingKeycap(
                            shortcut = shortcut,
                            selected = shortcut.id in snapshot.visibleBuiltInIds,
                            onClick = {
                                store.setBuiltInVisible(
                                    shortcut.id,
                                    shortcut.id !in snapshot.visibleBuiltInIds,
                                )
                            },
                        )
                    }
                }
                WandButton(
                    label = "恢复推荐按键",
                    onClick = store::resetBuiltIns,
                    variant = WandButtonVariant.Text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingSummary(
                    title = "自定义映射",
                    detail = "组合一个字符或特殊键，保存后会立即出现在 PTY 快捷键条末尾。",
                )
                if (snapshot.customShortcuts.isEmpty()) {
                    Text(
                        "还没有自定义按键",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    snapshot.customShortcuts.forEach { shortcut ->
                        CustomShortcutRow(
                            shortcut = shortcut,
                            onDelete = { store.deleteCustomShortcut(shortcut.id) },
                        )
                    }
                }
                WandButton(
                    label = "添加自定义按键",
                    onClick = { showCustomDialog = true },
                    icon = WandIcons.add,
                    variant = WandButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { showGuide = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Icon(
                    WandIcons.question,
                    contentDescription = null,
                    tint = WandColors.info,
                    modifier = Modifier.size(19.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "查看 PTY 快速上手",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = WandColors.textPrimary,
                    )
                    Text(
                        "重新了解输入栏、快捷键和硬件键盘操作",
                        fontSize = 12.sp,
                        color = WandColors.textSecondary,
                    )
                }
                Icon(
                    WandIcons.chevronRight,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }

    if (showCustomDialog) {
        CustomTerminalShortcutDialog(
            store = store,
            onDismiss = { showCustomDialog = false },
        )
    }
    if (showGuide) {
        PtyQuickStartGuideDialog(
            onDismiss = { showGuide = false },
            onFinished = {
                store.markGuideSeen()
                showGuide = false
            },
        )
    }
}

@Composable
private fun SettingSummary(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = WandColors.textPrimary,
        )
        Text(
            detail,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = WandColors.textSecondary,
        )
    }
}

@Composable
private fun TerminalSettingKeycap(
    shortcut: TerminalShortcut,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .background(
                if (selected) WandColors.brandSoft else WandColors.surfaceSoft,
                shape,
            )
            .border(
                0.7.dp,
                if (selected) WandColors.brand.copy(alpha = 0.48f) else WandColors.border,
                shape,
            )
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shortcut.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = if (selected) WandColors.brand else WandColors.textSecondary,
        )
    }
}

@Composable
private fun CustomShortcutRow(shortcut: TerminalShortcut, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WandColors.surfaceSoft, RoundedCornerShape(11.dp))
            .padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .background(WandColors.surface, RoundedCornerShape(8.dp))
                .border(0.6.dp, WandColors.borderStrong, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text(
                shortcut.label,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textPrimary,
            )
        }
        Text(
            shortcut.accessibilityLabel,
            style = MaterialTheme.typography.bodySmall,
            color = WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        WandIconButton(
            icon = WandIcons.delete,
            contentDescription = "删除 ${shortcut.label}",
            onClick = onDelete,
            variant = WandIconButtonVariant.Quiet,
            tint = WandColors.danger,
        )
    }
}

@Composable
private fun CustomTerminalShortcutDialog(
    store: TerminalShortcutPreferenceStore,
    onDismiss: () -> Unit,
) {
    var typedKey by remember { mutableStateOf("") }
    var selectedSpecialKey by remember { mutableStateOf<String?>(null) }
    var modifiers by remember { mutableStateOf<Set<TerminalModifier>>(emptySet()) }
    val binding = remember(typedKey, selectedSpecialKey, modifiers) {
        val key = selectedSpecialKey ?: normalizeTerminalKeyInput(typedKey)
        key?.let { TerminalKeyBinding(it, modifiers) }
    }
    val preview = remember(binding) { binding?.let(::buildTerminalShortcut) }

    WandDialog(
        title = "添加自定义按键",
        icon = WandIcons.keyboard,
        onDismissRequest = onDismiss,
        dismiss = WandDialogAction("取消", onDismiss),
        confirm = WandDialogAction(
            label = "添加",
            enabled = preview != null,
            onClick = {
                val validBinding = binding ?: return@WandDialogAction
                if (store.addCustomShortcut(validBinding) != null) onDismiss()
            },
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text(
                "先选择修饰键，再输入一个字符或选择特殊键。组合会按 xterm 规则编码后直接写入 PTY。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = WandColors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TerminalModifier.entries.forEach { modifier ->
                    ModifierChoiceChip(
                        label = modifier.label,
                        selected = modifier in modifiers,
                        onClick = {
                            modifiers = if (modifier in modifiers) modifiers - modifier else modifiers + modifier
                        },
                    )
                }
            }
            WandTextField(
                value = typedKey,
                onValueChange = { value ->
                    typedKey = value.takeLast(1)
                    if (typedKey.isNotEmpty()) selectedSpecialKey = null
                },
                label = "字符键",
                placeholder = "例如 C、K 或 /",
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                TerminalSpecialKeys.forEach { specialKey ->
                    ModifierChoiceChip(
                        label = specialKey.label,
                        selected = selectedSpecialKey == specialKey.id,
                        onClick = {
                            selectedSpecialKey = specialKey.id
                            typedKey = ""
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WandColors.surfaceSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("预览", fontSize = 12.sp, color = WandColors.textMuted)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    preview?.label ?: "请选择一个有效组合",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (preview == null) WandColors.textMuted else WandColors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun ModifierChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .background(if (selected) WandColors.textPrimary else WandColors.surfaceSoft, shape)
            .border(0.7.dp, if (selected) Color.Transparent else WandColors.border, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = if (selected) WandColors.surface else WandColors.textSecondary,
        )
    }
}
