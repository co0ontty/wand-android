package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

/**
 * 任务内复用的七选一工作窗口 Bottom Sheet（空任务欢迎态与任务页「+」共用同一入口）。
 * 对齐 Web WorkspaceAgentDialog：六个 Agent + 空白终端。
 *
 * 可访问性：选项使用 Role.RadioButton、selected、清晰的 contentDescription/stateDescription，
 * 触摸目标至少 48dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTargetSheet(
    selected: WorkspaceSessionTarget,
    creating: Boolean,
    error: String?,
    onSelect: (WorkspaceSessionTarget) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp, max = 560.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "新建工作窗口",
                style = MaterialTheme.typography.titleLarge,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "在当前任务的同一 worktree 中选择 Agent，或直接启动空白终端。",
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = WorkspaceSessionTarget.OPTIONS,
                    key = { it.raw },
                ) { target ->
                    WorkspaceTargetOption(
                        target = target,
                        isSelected = target == selected,
                        enabled = !creating,
                        onClick = { onSelect(target) },
                    )
                }
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WandButton(
                    label = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = WandButtonVariant.Secondary,
                )
                WandButton(
                    label = if (creating) "正在创建…" else "创建 ${selected.label}",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTargetOption(
    target: WorkspaceSessionTarget,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val icon: Painter = if (target.isShell) {
        rememberVectorPainter(WandIcons.terminal)
    } else {
        BrandLogos.painterForProvider(target.raw)
    }
    val iconTint = if (target.isShell) {
        WandColors.textSecondary
    } else {
        BrandLogos.tintForProvider(target.raw, WandColors.brand)
    }
    val borderColor = if (isSelected) WandColors.brand else WandColors.border.copy(alpha = 0.5f)
    val background = if (isSelected) WandColors.brand.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics {
                role = Role.RadioButton
                this.selected = isSelected
                contentDescription = "${target.label} ${target.description}"
                stateDescription = if (isSelected) "已选中" else "未选中"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                target.label,
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                target.description,
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isSelected) {
            Icon(
                WandIcons.check,
                contentDescription = null,
                tint = WandColors.brand,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
