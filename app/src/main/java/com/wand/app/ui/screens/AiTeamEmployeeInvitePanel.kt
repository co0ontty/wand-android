package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeamMember
import com.wand.app.data.SiliconEmployee
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandInlineSearchField
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.theme.WandColors

/** 嵌在邀请触发按钮下方；加载/失败/列表共用固定窗口，失败不冒充空通讯录。 */
@Composable
internal fun AiTeamEmployeeInvitePanel(
    employees: List<SiliconEmployee>?,
    loading: Boolean,
    error: String?,
    members: List<AiTeamMember>,
    replacingIndex: Int?,
    enabled: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onPick: (SiliconEmployee) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WandInlineSearchField(
            expanded = true,
            query = query,
            onQueryChange = { query = it },
            onCollapse = onClose,
            placeholder = "搜索员工名字、职责或标签",
            modifier = Modifier.fillMaxWidth(),
        )
        WandInPlaceSwap(
            contentKey = when { loading -> "loading"; error != null -> "error"; else -> "list" },
            modifier = Modifier.fillMaxWidth().height(240.dp),
            enterScale = 1f,
            exitScale = 1f,
        ) { state ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (state) {
                    "loading" -> {
                        WandStatusIconSlot(running = true, icon = WandIcons.refresh,
                            indicatorColor = WandColors.brand, containerColor = WandColors.surfaceSoft,
                            boxSize = 32.dp, iconSize = 18.dp)
                        Text("正在加载通讯录…", color = WandColors.textMuted)
                    }
                    "error" -> {
                        WandButton("重试通讯录", onClick = onRetry, compact = true,
                            variant = WandButtonVariant.Secondary, enabled = enabled)
                        Text(error ?: "通讯录加载失败，草稿已保留。", color = WandColors.danger)
                    }
                    else -> {
                        val matches = employees.orEmpty().filter { employee ->
                            query.isBlank() || listOf(employee.name, employee.duty,
                                employee.displayTags.joinToString(" ")).any { it.contains(query, ignoreCase = true) }
                        }
                        if (matches.isEmpty()) Text("没有匹配的员工", color = WandColors.textMuted)
                        matches.forEach { employee ->
                            val unavailable = teamEmployeeInviteError(employee, members, replacingIndex)
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = enabled && unavailable == null) { onPick(employee) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                EmployeeAvatar(employee.id, employee.name, employee.avatar,
                                    provider = employee.agents.firstOrNull()?.provider)
                                Column {
                                    Text(employee.name, color = WandColors.textPrimary,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(unavailable ?: employee.duty.ifBlank { "加入后填写团队职责" },
                                        color = if (unavailable == null) WandColors.textMuted else WandColors.danger,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
