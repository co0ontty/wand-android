package com.wand.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors

/**
 * 会话详情里的应用内通知走 Material Snackbar，而不是网页顶栏胶囊。
 * Scaffold 会把它放在底栏上方，符合 Android 通知习惯。
 */
@Composable
fun WandSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(8.dp),
            containerColor = WandColors.textPrimary,
            contentColor = WandColors.bgElevated,
            actionColor = WandColors.brand,
        )
    }
}

internal fun wandNoticeIsLong(message: String): Boolean =
    message.contains("失败") || message.contains("错误") || message.contains("断开")

suspend fun SnackbarHostState.showWandNotice(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(
        message = message,
        duration = if (wandNoticeIsLong(message)) SnackbarDuration.Long else SnackbarDuration.Short,
    )
}
