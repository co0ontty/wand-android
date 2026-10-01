package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeam
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/** Conversation partners remain visible above recent conversations on the home screen. */
@Composable
internal fun HomeContactsStrip(
    employees: List<SiliconEmployee>,
    teams: List<AiTeam>,
    enabled: Boolean,
    onEmployee: (SiliconEmployee) -> Unit,
    onTeam: (AiTeam) -> Unit,
    onCli: (String) -> Unit,
    onManageEmployees: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("发起对话", color = WandColors.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("管理员工", color = WandColors.brand,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(enabled = enabled, onClick = onManageEmployees)
                    .padding(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            employees.forEach { employee ->
                HomeContact("员工", employee.name, WandIcons.agent, enabled,
                    employee = employee) { onEmployee(employee) }
            }
            teams.forEach { team ->
                HomeContact("团队", team.name, WandIcons.agent, enabled) { onTeam(team) }
            }
            if (employees.isEmpty() && teams.isEmpty()) {
                HomeContact("员工", "创建员工", WandIcons.add, enabled,
                    onClick = onManageEmployees)
            }
            WorkspaceSessionTarget.OPTIONS.filterNot { it.isShell }.forEach { target ->
                HomeContact("CLI", target.label, WandIcons.terminal, enabled) { onCli(target.raw) }
            }
        }
    }
}

@Composable
private fun HomeContact(
    kind: String,
    name: String,
    icon: ImageVector,
    enabled: Boolean,
    employee: SiliconEmployee? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.background(WandColors.surfaceSoft, WandShapes.sm)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (employee != null) EmployeeAvatar(employee.id, employee.name, employee.avatar,
            size = 24.dp, provider = employee.agents.firstOrNull()?.provider)
        else Icon(icon, contentDescription = null, tint = WandColors.brand, modifier = Modifier.size(20.dp))
        Column {
            Text(kind, color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
            Text(name, color = WandColors.textPrimary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
