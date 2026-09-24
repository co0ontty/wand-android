package com.wand.app.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.wand.app.data.PtyTerminalSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WandSocket
import com.wand.app.data.WsIncoming
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/** Render/legacy server checkpoints are ANSI plus ordered PTY operations, not xterm.js state. */
internal fun replayTerminalSnapshot(
    emulator: TerminalEmulator,
    snapshot: PtyTerminalSnapshot?,
    output: String?,
    cols: Int?,
    rows: Int?,
): Boolean {
    if (snapshot != null && snapshot.isReplayable) {
        emulator.writeInput("\u001bc".toByteArray(Charsets.UTF_8))
        emulator.resize(snapshot.rows, snapshot.cols)
        emulator.writeInput(snapshot.data.toByteArray(Charsets.UTF_8))
        snapshot.pending.forEach { operation ->
            when (operation) {
                is PtyTerminalSnapshot.Operation.Data ->
                    emulator.writeInput(operation.text.toByteArray(Charsets.UTF_8))
                is PtyTerminalSnapshot.Operation.Resize -> emulator.resize(operation.rows, operation.cols)
            }
        }
        return true
    }
    // Raw output belongs only to older servers; do not append to an existing terminal screen.
    if (snapshot != null || output == null) return false
    emulator.writeInput("\u001bc".toByteArray(Charsets.UTF_8))
    if (cols != null && rows != null && PtyTerminalSnapshot.validSize(cols, rows)) {
        emulator.resize(rows, cols)
    }
    emulator.writeInput(output.toByteArray(Charsets.UTF_8))
    return true
}

/** One native renderer and one ACK-enabled websocket per visible PTY session. */
internal class NativePtyTerminal(
    private val api: WandApi,
    private val sessionId: String,
    private val onError: (String) -> Unit,
) {
    val ready = mutableStateOf(false)
    val loading = mutableStateOf(true)
    private val socket = WandSocket(api.baseUrl, api.token)
    private var started = false
    private var restoring = false
    private var rejectedSnapshot = false
    private var lastSize: Pair<Int, Int>? = null
    private var viewSize: Pair<Int, Int>? = null
    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(TerminalPalette.foregroundArgb),
        defaultBackground = Color(TerminalPalette.backgroundArgb),
        onKeyboardInput = { data ->
            if (ready.value && !socket.sendPtyInput(String(data, Charsets.UTF_8), userInput = true)) {
                onError("终端输入未发送，请检查连接后重试")
            }
        },
        onResize = { dimensions ->
            if (!restoring) {
                val size = dimensions.columns to dimensions.rows
                if (PtyTerminalSnapshot.validSize(size.first, size.second)) {
                    viewSize = size
                    if (ready.value && size != lastSize) {
                        lastSize = size
                        socket.resizePty(size.first, size.second)
                    }
                }
            }
        },
        // OSC 52 must not copy remote data into the system clipboard.
        onClipboardCopy = {},
    ).also { created ->
        created.applyColorScheme(
            TerminalPalette.ansi,
            TerminalPalette.foregroundArgb,
            TerminalPalette.backgroundArgb,
        )
    }

    fun start() {
        if (started) return
        started = true
        loading.value = true
        socket.onPtyEvent = ::handle
        socket.onAuthenticationFailure = onError
        socket.onPtyResync = {
            ready.value = false
            loading.value = !rejectedSnapshot
            lastSize = null
        }
        socket.onConnectionChange = { connected ->
            if (!connected) {
                ready.value = false
                loading.value = !rejectedSnapshot
                lastSize = null
            }
        }
        socket.subscribe(sessionId, ptyAck = true)
        socket.connect()
    }

    fun stop() {
        started = false
        ready.value = false
        socket.close()
    }

    fun reconnect() {
        if (!started) return
        ready.value = false
        loading.value = true
        rejectedSnapshot = false
        socket.reconnectForForeground()
    }

    fun retry() {
        if (!started) return
        ready.value = false
        loading.value = true
        rejectedSnapshot = false
        socket.requestResync()
    }

    fun send(text: String, shortcutKey: String? = null): Boolean =
        ready.value && socket.sendPtyInput(text, shortcutKey = shortcutKey)

    private fun handle(event: WsIncoming) {
        if (!started || event.sessionId != sessionId) return
        when (event.type) {
            "init" -> {
                val data = event.data ?: return
                restoring = true
                val restored = try {
                    replayTerminalSnapshot(emulator, data.terminalState, data.output, data.ptyCols, data.ptyRows)
                } finally {
                    restoring = false
                }
                if (!restored) {
                    rejectedSnapshot = true
                    ready.value = false
                    loading.value = false
                    onError("服务端终端快照不受支持，请检查 Render 版本")
                    return
                }
                rejectedSnapshot = false
                val size = viewSize
                if (size != null && PtyTerminalSnapshot.validSize(size.first, size.second)) {
                    emulator.resize(size.second, size.first)
                }
                lastSize = null
                ready.value = true
                loading.value = false
                if (size != null && PtyTerminalSnapshot.validSize(size.first, size.second)) {
                    lastSize = size
                    socket.resizePty(size.first, size.second)
                }
            }
            "output" -> {
                // ACK only after synchronous libvterm consumption. Skipped frames are ACKed by WandSocket.
                try {
                    if (ready.value) event.data?.chunk?.let { emulator.writeInput(it.toByteArray(Charsets.UTF_8)) }
                } catch (error: Exception) {
                    ready.value = false
                    onError(error.message ?: "终端输出解析失败，请重新同步")
                    socket.requestResync()
                } finally {
                    socket.acknowledgePty(event.ptyBytes ?: 0)
                }
            }
            "pty_error" -> {
                ready.value = false
                loading.value = false
                onError("终端操作未确认。请核对输出并重新连接，避免重复执行。")
            }
            "error" -> {
                ready.value = false
                loading.value = false
                onError(event.error ?: "终端请求失败")
            }
        }
    }
}

@Composable
internal fun NativePtyTerminalSurface(
    terminal: NativePtyTerminal,
    fontSize: TextUnit,
    onTerminalTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val emulator = remember(terminal) { terminal.emulator }
    val typeface = remember { terminalTypeface(context.assets) }
    DisposableEffect(terminal) {
        terminal.start()
        onDispose { terminal.stop() }
    }
    Terminal(
        terminalEmulator = emulator,
        modifier = modifier.fillMaxSize(),
        typeface = typeface,
        initialFontSize = fontSize,
        minFontSize = TERMINAL_MIN_FONT_SP.sp,
        maxFontSize = TERMINAL_MAX_FONT_SP.sp,
        backgroundColor = Color(TerminalPalette.backgroundArgb),
        foregroundColor = Color(TerminalPalette.foregroundArgb),
        // Hardware keys reach the emulator when it is focused. The soft keyboard is a
        // separate field so IME composition can commit straight into the PTY.
        keyboardEnabled = terminal.ready.value,
        showSoftKeyboard = false,
        onTerminalTap = onTerminalTap,
    )
}
