package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.BOARD_TASK_EFFORTS
import com.wand.app.data.BOARD_TASK_KINDS
import com.wand.app.data.BOARD_TASK_PROVIDERS
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.data.boardTaskEffortLabel
import com.wand.app.data.boardTaskKindLabel
import com.wand.app.data.boardTaskModeLabel
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.normalizeBoardTaskAgentMode
import com.wand.app.data.supportedBoardTaskModes
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.WandSizes

/**
 * 一行下拉选择：任务看板（优先级 / 工作区 / 指派参数）与团队编辑器的成员候选共用同一份实现。
 * 触发区就在原位展开菜单（动效总则 1：不跳页），`chip` 形态用于横向紧凑排布。
 */
@Composable
fun WandChoice(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    chip: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .then(if (chip) Modifier.widthIn(min = WandSizes.minTouchTarget) else Modifier.fillMaxWidth())
                .heightIn(min = WandSizes.minTouchTarget)
                .clip(WandShapes.sm)
                .background(if (chip) WandColors.surfaceSoft else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button) { expanded = true }
                .padding(horizontal = if (chip) 12.dp else 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = WandColors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                label,
                color = if (enabled) WandColors.textPrimary else WandColors.textMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (chip) Modifier else Modifier.weight(1f),
            )
            Icon(
                WandIcons.expand,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WandColors.bgElevated,
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/**
 * 指派参数五连：CLI 工具 / 模型 / 思考深度 / 会话类型 / 运行模式。
 * 任务看板的「再指派」与新建任务弹窗、团队编辑器的成员候选共用，避免多处下拉选项漂移。
 *
 * 会话类型与运行模式始终可选：只创建的任务也会把形态 / 模式记进服务端全局默认，
 * 否则下次派发会退回结构化与标准模式，用户在下拉里的选择会静默丢失。
 */
@Composable
fun WandAgentFields(
    models: ModelsResponse?,
    agent: BoardTaskAgent,
    providerLabel: String,
    onChange: (BoardTaskAgent) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowPty: Boolean = true,
) {
    val modelOptions = boardAgentModelOptions(models, agent.provider)
    WandChoice(
        label = "$providerLabel · ${boardTaskProviderLabel(agent.provider)}",
        options = BOARD_TASK_PROVIDERS.map { it to boardTaskProviderLabel(it) },
        onSelect = { provider ->
            val nextModels = boardAgentModelOptions(models, provider)
            val model = nextModels.firstOrNull { it.id == agent.model }?.id
                ?: nextModels.firstOrNull()?.id
                ?: "default"
            onChange(
                agent.copy(
                    provider = provider,
                    model = model,
                    mode = normalizeBoardTaskAgentMode(provider, agent.mode),
                ),
            )
        },
        modifier = modifier,
        enabled = enabled,
    )
    WandChoice(
        label = "模型 · ${modelOptions.firstOrNull { it.id == agent.model }?.label ?: agent.model}",
        options = modelOptions.map { it.id to it.label },
        onSelect = { onChange(agent.copy(model = it)) },
        modifier = modifier,
        enabled = enabled,
    )
    WandChoice(
        label = "思考深度 · ${boardTaskEffortLabel(agent.thinkingEffort)}",
        options = BOARD_TASK_EFFORTS.map { it to boardTaskEffortLabel(it) },
        onSelect = { onChange(agent.copy(thinkingEffort = it)) },
        modifier = modifier,
        enabled = enabled,
    )
    if (allowPty) {
        WandChoice(
            label = "会话类型 · ${boardTaskKindLabel(agent.kind)}",
            options = BOARD_TASK_KINDS.map { it to boardTaskKindLabel(it) },
            onSelect = { onChange(agent.copy(kind = it)) },
            modifier = modifier,
            enabled = enabled,
        )
    }
    WandChoice(
        label = "运行模式 · ${boardTaskModeLabel(agent.mode)}",
        options = supportedBoardTaskModes(agent.provider).map { it to boardTaskModeLabel(it) },
        onSelect = { onChange(agent.copy(mode = it)) },
        modifier = modifier,
        enabled = enabled,
    )
}
