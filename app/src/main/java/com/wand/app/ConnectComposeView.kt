package com.wand.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandTextField
import com.wand.app.data.ServerProfile
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandSpacing
import com.wand.app.ui.theme.WandTheme

interface ConnectUiListener {
    fun onConnect()
    fun onScanQr()
    fun onCancelAutoConnect()
    fun onSwitchServer()
    fun onPickServer(serverId: String)
    fun onRemoveServer(serverId: String)
    fun onClearServers()
}

/** Java 连接流程的 Compose 显示 adapter。业务状态仍由 ConnectActivity 掌管。 */
class ConnectComposeView(context: Context) : AbstractComposeView(context) {
    private var uiInputValue by mutableStateOf("")
    private var uiAutoConnecting by mutableStateOf(false)
    private var uiAutoStatus by mutableStateOf(context.getString(R.string.auto_connecting))
    private var uiConnecting by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var statusIsError by mutableStateOf(true)
    private var uiServerProfiles by mutableStateOf(emptyList<ServerProfile>())
    private var uiActiveServerId by mutableStateOf<String?>(null)
    private var uiConnectingServerId by mutableStateOf<String?>(null)
    private var focusGeneration by mutableIntStateOf(0)
    private var listener: ConnectUiListener? = null

    fun setListener(value: ConnectUiListener) {
        listener = value
    }

    fun setInputValue(value: String) {
        uiInputValue = value
    }

    fun getInputValue(): String = uiInputValue

    fun showAutoConnecting(status: String) {
        uiAutoStatus = status
        uiAutoConnecting = true
        statusMessage = null
    }

    fun setAutoStatus(status: String) {
        uiAutoStatus = status
    }

    fun showForm() {
        uiAutoConnecting = false
    }

    fun isAutoConnectVisible(): Boolean = uiAutoConnecting

    fun setConnecting(value: Boolean) {
        uiConnecting = value
        if (value) statusMessage = null
        if (!value) uiConnectingServerId = null
    }

    fun showStatus(message: String, isError: Boolean) {
        statusMessage = message
        statusIsError = isError
    }

    fun setConnectingServer(serverId: String?) {
        uiConnectingServerId = serverId
        setConnecting(serverId != null)
    }

    fun setServerProfiles(profiles: List<ServerProfile>, activeServerId: String?) {
        uiServerProfiles = profiles.toList()
        uiActiveServerId = activeServerId
    }

    fun focusInput() {
        focusGeneration += 1
    }

    @Composable
    override fun Content() {
        WandTheme {
            ConnectScreen(
                inputValue = uiInputValue,
                onInputValueChange = { uiInputValue = it },
                autoConnecting = uiAutoConnecting,
                autoStatus = uiAutoStatus,
                connecting = uiConnecting,
                statusMessage = statusMessage,
                statusIsError = statusIsError,
                serverProfiles = uiServerProfiles,
                activeServerId = uiActiveServerId,
                connectingServerId = uiConnectingServerId,
                focusGeneration = focusGeneration,
                listener = listener,
            )
        }
    }
}

@Composable
private fun ConnectScreen(
    inputValue: String,
    onInputValueChange: (String) -> Unit,
    autoConnecting: Boolean,
    autoStatus: String,
    connecting: Boolean,
    statusMessage: String?,
    statusIsError: Boolean,
    serverProfiles: List<ServerProfile>,
    activeServerId: String?,
    connectingServerId: String?,
    focusGeneration: Int,
    listener: ConnectUiListener?,
) {
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    var fieldValue by androidx.compose.runtime.remember { mutableStateOf(TextFieldValue(inputValue)) }
    androidx.compose.runtime.LaunchedEffect(inputValue) {
        if (inputValue != fieldValue.text) fieldValue = TextFieldValue(inputValue)
    }
    androidx.compose.runtime.LaunchedEffect(focusGeneration) {
        if (focusGeneration > 0) {
            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
            focusRequester.requestFocus()
        }
    }
    var pendingRemoval by androidx.compose.runtime.remember { mutableStateOf<ServerProfile?>(null) }
    var confirmClear by androidx.compose.runtime.remember { mutableStateOf(false) }

    pendingRemoval?.let { profile ->
        WandDialog(
            title = "移除服务器？",
            onDismissRequest = { pendingRemoval = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "移除",
                destructive = true,
                onClick = {
                    pendingRemoval = null
                    listener?.onRemoveServer(profile.id)
                },
            ),
            dismiss = WandDialogAction("取消", { pendingRemoval = null }),
        ) {
            Text(
                "仅从这台设备移除「${profile.visibleName()}」及其连接凭据，不会删除服务器上的会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }
    if (confirmClear) {
        WandDialog(
            title = "移除所有服务器？",
            onDismissRequest = { confirmClear = false },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "全部移除",
                destructive = true,
                onClick = {
                    confirmClear = false
                    listener?.onClearServers()
                },
            ),
            dismiss = WandDialogAction("取消", { confirmClear = false }),
        ) {
            Text(
                "仅清除此设备保存的服务器地址和连接凭据，不会删除任何服务器上的会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = WandSpacing.lg, vertical = WandSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            WandBrandMark(size = 58)
            Text(
                "Wand",
                style = MaterialTheme.typography.headlineSmall,
                color = WandColors.textPrimary,
                modifier = Modifier.padding(top = WandSpacing.sm),
            )
            Text(
                "远程 CLI 控制台",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
                modifier = Modifier.padding(top = WandSpacing.xxs, bottom = WandSpacing.xxl),
            )

            if (autoConnecting) {
                WandCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(WandSpacing.xl),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(WandSpacing.md),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = WandColors.brand,
                            strokeWidth = 3.dp,
                        )
                        Text(autoStatus, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(WandSpacing.xs)) {
                            WandButton(
                                label = "取消",
                                onClick = { listener?.onCancelAutoConnect() },
                                variant = WandButtonVariant.Text,
                            )
                            WandButton(
                                label = "管理服务器",
                                onClick = { listener?.onSwitchServer() },
                                variant = WandButtonVariant.Secondary,
                            )
                        }
                    }
                }
            } else {
                WandCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(WandSpacing.md),
                ) {
                    WandTextField(
                        value = fieldValue,
                        onValueChange = {
                            fieldValue = it
                            onInputValueChange(it.text)
                        },
                        label = "连接码或服务器地址",
                        placeholder = "https://your-server.example 或粘贴连接码",
                        singleLine = true,
                        enabled = !connecting,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { listener?.onConnect() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                    statusMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusIsError) WandColors.danger else WandColors.textSecondary,
                            modifier = Modifier.padding(top = WandSpacing.xs),
                        )
                    }
                    WandButton(
                        label = if (connecting && connectingServerId == null) "连接中…" else "连接并保存",
                        onClick = { listener?.onConnect() },
                        loading = connecting && connectingServerId == null,
                        enabled = inputValue.isNotBlank() && !connecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WandSpacing.md),
                    )
                    WandButton(
                        label = "扫描二维码",
                        icon = Icons.Outlined.QrCodeScanner,
                        onClick = { listener?.onScanQr() },
                        variant = WandButtonVariant.Secondary,
                        enabled = !connecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = WandSpacing.xs),
                    )

                    Text(
                        "已保存的服务器",
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(top = WandSpacing.xl),
                    )
                    Text(
                        "选择一台服务器即可连接；每台服务器的认证信息独立保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(top = WandSpacing.xxs, bottom = WandSpacing.xs),
                    )
                    if (serverProfiles.isEmpty()) {
                        Text(
                            "暂无已保存的服务器",
                            style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(vertical = WandSpacing.xs),
                        )
                    } else {
                        serverProfiles.forEachIndexed { index, profile ->
                            if (index > 0) {
                                HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
                            }
                            SavedServerRow(
                                profile = profile,
                                active = profile.id == activeServerId,
                                connecting = profile.id == connectingServerId,
                                enabled = !connecting,
                                onClick = { listener?.onPickServer(profile.id) },
                                onRemove = { pendingRemoval = profile },
                            )
                        }
                        WandButton(
                            label = "移除所有服务器",
                            onClick = { confirmClear = true },
                            variant = WandButtonVariant.DangerText,
                            enabled = !connecting,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
            Spacer(Modifier.height(WandSpacing.xl))
            }
        }
    }
}

@Composable
private fun SavedServerRow(
    profile: ServerProfile,
    active: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val authenticationLabel = if (profile.hasToken) "已认证" else "直接连接"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) WandColors.brandSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = 0.5.dp,
                color = if (active) WandColors.brand.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            WandIcons.server,
            contentDescription = null,
            tint = if (active) WandColors.brand else WandColors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    stateDescription = buildString {
                        append(authenticationLabel)
                        if (active) append("，当前服务器")
                        if (connecting) append("，正在连接")
                    }
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    profile.visibleName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) WandColors.brand else WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    ServerStateBadge("当前", WandColors.brand, WandColors.brandSoft)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    profile.baseUrl,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                ServerStateBadge(
                    label = authenticationLabel,
                    tint = if (profile.hasToken) WandColors.success else WandColors.textSecondary,
                    background = if (profile.hasToken) WandColors.successSoft else WandColors.surfaceSoft,
                )
            }
        }
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 11.dp)
                    .size(18.dp),
                color = WandColors.brand,
                strokeWidth = 2.dp,
            )
        } else {
            WandIconButton(
                icon = WandIcons.close,
                contentDescription = "移除服务器 ${profile.visibleName()}",
                onClick = onRemove,
                enabled = enabled,
                variant = WandIconButtonVariant.Quiet,
                tint = WandColors.textMuted,
            )
        }
    }
}

@Composable
private fun ServerStateBadge(label: String, tint: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun ServerProfile.visibleName(): String = displayName.ifBlank { baseUrl }
