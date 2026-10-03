package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/** 新建群聊的模板面板：一行一个模板（名字 + 能干什么），点即进编辑器。 */
@Composable
internal fun AiTeamTemplatePanel(onPick: (String) -> Unit) {
    WandCard(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        Text(
            "新建 AI 团队 · 选择起步模板",
            color = WandColors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        AI_TEAM_TEMPLATES.forEach { template ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.sm)
                    .clickable(role = Role.Button) { onPick(template.id) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.name,
                        color = WandColors.textPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        template.summary,
                        color = WandColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Icon(
                    WandIcons.chevronRight,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.padding(start = 10.dp).size(18.dp),
                )
            }
        }
    }
}
