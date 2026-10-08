package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.wand.app.ui.theme.WandColors

/** 较长的新建表单共用详情顶栏、滚动正文及不随键盘/提交状态替换的底部操作区。 */
@Composable
internal fun WandFormDialog(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit,
    overlays: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val lightBackground = WandColors.bgPrimary.luminance() > 0.5f
        DisposableEffect(window, lightBackground) {
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val previous = controller?.isAppearanceLightStatusBars
            controller?.isAppearanceLightStatusBars = lightBackground
            onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
        }
        Column(Modifier.fillMaxSize().background(WandColors.bgPrimary).navigationBarsPadding().imePadding()) {
            WandDetailTopBar(title = title,
                leading = { WandDetailBackButton(onDismiss, contentDescription = "取消$title", enabled = !busy) })
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), content = content)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 600.dp).fillMaxWidth()) { footer() }
            }
        }
        overlays()
    }
}
