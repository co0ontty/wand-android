package com.wand.app

import android.content.Context
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.AbstractComposeView
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
import com.wand.app.ui.components.WandTextField
import com.wand.app.data.WandAuth
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandSpacing
import com.wand.app.ui.theme.WandTheme

interface ConnectUiListener {
    fun onConnect()
    fun onScanQr()
    fun onCancelAutoConnect()
    fun onSwitchServer()
    fun onPickRecent(entry: String)
    fun onRemoveRecent(entry: String)
    fun onClearRecent()
}

/** Java 连接流程的 Compose 显示 adapter。业务状态仍由 ConnectActivity 掌管。 */
class ConnectComposeView(context: Context) : AbstractComposeView(context) {
    private var uiInputValue by mutableStateOf("")
    private var uiAutoConnecting by mutableStateOf(false)
    private var uiAutoStatus by mutableStateOf(context.getString(R.string.auto_connecting))
    private var uiConnecting by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var statusIsError by mutableStateOf(true)
    private var uiRecentEntries by mutableStateOf(emptyList<String>())
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
    }

    fun showStatus(message: String, isError: Boolean) {
        statusMessage = message
        statusIsError = isError
    }

    fun setRecentEntries(entries: List<String>) {
        uiRecentEntries = entries.toList()
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
                recentEntries = uiRecentEntries,
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
    recentEntries: List<String>,
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
    var pendingRemoval by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var confirmClear by androidx.compose.runtime.remember { mutableStateOf(false) }

    pendingRemoval?.let { entry ->
        WandDialog(
            title = "移除最近连接",
            onDismissRequest = { pendingRemoval = null },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "移除",
                destructive = true,
                onClick = {
                    pendingRemoval = null
                    listener?.onRemoveRecent(entry)
                },
            ),
            dismiss = WandDialogAction("取消", { pendingRemoval = null }),
        ) {
            Text("从最近连接中移除该服务器？", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (confirmClear) {
        WandDialog(
            title = "清除连接记录",
            onDismissRequest = { confirmClear = false },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = "清除",
                destructive = true,
                onClick = {
                    confirmClear = false
                    listener?.onClearRecent()
                },
            ),
            dismiss = WandDialogAction("取消", { confirmClear = false }),
        ) {
            Text("确定清除所有最近连接记录吗？", style = MaterialTheme.typography.bodyMedium)
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
                .padding(horizontal = WandSpacing.lg, vertical = WandSpacing.xl),
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
                                label = "切换服务器",
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
                        placeholder = "https://your-server.example",
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
                        label = if (connecting) "连接中…" else "连接",
                        onClick = { listener?.onConnect() },
                        loading = connecting,
                        enabled = inputValue.isNotBlank(),
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
                        "最近连接",
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.textMuted,
                        modifier = Modifier.padding(top = WandSpacing.xl, bottom = WandSpacing.xs),
                    )
                    if (recentEntries.isEmpty()) {
                        Text(
                            "暂无最近连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(vertical = WandSpacing.xs),
                        )
                    } else {
                        recentEntries.forEach { entry ->
                            val decoded = WandAuth.decodeConnectCode(entry)
                            val display = decoded?.first ?: entry
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 54.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { listener?.onPickRecent(entry) }
                                        .padding(vertical = WandSpacing.xs),
                                ) {
                                    Text(
                                        text = display,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = WandColors.brand,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.MiddleEllipsis,
                                    )
                                    if (decoded != null) {
                                        Text(
                                            "已绑定连接码",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = WandColors.textSecondary,
                                        )
                                    }
                                }
                                com.wand.app.ui.components.WandIconButton(
                                    icon = WandIcons.close,
                                    contentDescription = "移除最近连接",
                                    onClick = { pendingRemoval = entry },
                                    variant = com.wand.app.ui.components.WandIconButtonVariant.Quiet,
                                    tint = WandColors.textMuted,
                                )
                            }
                        }
                        WandButton(
                            label = "清除连接记录",
                            onClick = { confirmClear = true },
                            variant = WandButtonVariant.Text,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
            Spacer(Modifier.height(WandSpacing.xl))
        }
    }
}
