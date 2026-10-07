package com.wand.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.wand.app.data.ServerProfile
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.WandListItem
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandSpacing

@Composable
internal fun ConnectionPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    focusRevision: Int,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var passwordVisible by remember { mutableStateOf(false) }
    LaunchedEffect(focusRevision, enabled) {
        if (focusRevision > 0 && enabled) focusRequester.requestFocus()
    }
    WandTextField(
        value = value,
        onValueChange = onValueChange,
        label = "服务器密码",
        placeholder = "输入密码即可连接",
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            WandIconButton(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (passwordVisible) "隐藏密码" else "显示密码", onClick = { passwordVisible = !passwordVisible },
                enabled = enabled, variant = WandIconButtonVariant.Toolbar)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (enabled) onSubmit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

@Composable
internal fun LanServerSection(
    servers: List<ServerProfile>,
    status: String,
    discovering: Boolean,
    enabled: Boolean,
    connectingServerId: String?,
    feedbackServerId: String?,
    statusMessage: String?,
    statusIsError: Boolean,
    onDiscover: () -> Unit,
    onPick: (ServerProfile) -> Unit,
    passwordContent: @Composable (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("附近的服务器", style = MaterialTheme.typography.labelMedium, color = WandColors.textMuted,
                modifier = Modifier.weight(1f))
            WandButton(label = "重新发现", onClick = onDiscover, loading = discovering,
                enabled = enabled && !discovering, variant = WandButtonVariant.Text)
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
        if (servers.isNotEmpty()) WandCard {
            servers.forEachIndexed { index, server ->
                if (index > 0) HorizontalDivider(color = WandColors.border, modifier = Modifier.padding(start = 52.dp))
                WandListItem(
                    modifier = Modifier.heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button) { onPick(server) },
                    headlineContent = { Text(server.displayName.ifBlank { server.baseUrl }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (server.displayName.isNotBlank() && server.displayName != server.baseUrl) ({ Text(server.baseUrl, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }) else null,
                    leadingContent = { Icon(WandIcons.server, null, Modifier.size(24.dp), tint = WandColors.textSecondary) },
                    trailingContent = {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (connectingServerId == server.id) CircularProgressIndicator(Modifier.size(20.dp), color = WandColors.brand, strokeWidth = 2.dp)
                            else Icon(WandIcons.chevronRight, null, Modifier.size(20.dp), tint = WandColors.textMuted)
                        }
                    },
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    passwordContent(server.id)
                    if (feedbackServerId == server.id && statusMessage != null) {
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall,
                            color = if (statusIsError) WandColors.dangerText else WandColors.textSecondary,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
}
