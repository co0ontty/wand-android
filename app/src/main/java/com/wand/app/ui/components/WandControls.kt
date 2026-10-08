package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.WandSizes
import com.wand.app.ui.theme.wandComposite
import com.wand.app.ui.theme.wandContrast
import com.wand.app.ui.theme.wandDisabledInk
import com.wand.app.data.providerDisplayName

enum class WandButtonVariant {
    Primary,
    Secondary,
    Text,
    Danger,
    DangerText,
    Success,
}

/** 实心变体的主色；Secondary/Text 走轮廓与文字色，不使用这里的值。 */
@Composable
private fun WandButtonVariant.solidColor(): Color = when (this) {
    WandButtonVariant.Danger -> WandColors.danger
    WandButtonVariant.Success -> WandColors.success
    else -> WandColors.brand
}

/**
 * 实心变体上的前景色。
 * 两套主题的强调色亮度不同，不能一律白字：暗色主题的 brand/danger 本身是提亮版本，
 * 白字压上去只有 2.2–3.3:1，必须换成深色墨；浅色主题的危险/成功底偏暗才用近白墨。
 * 具体取值由 Theme.kt 的 token 按实测对比度派生。
 */
@Composable
private fun WandButtonVariant.onSolidColor(): Color = when (this) {
    WandButtonVariant.Danger -> WandColors.onDanger
    WandButtonVariant.Success -> WandColors.onSuccess
    else -> WandColors.onBrand
}

/** 标准操作按钮。加载、禁用、图标和危险态的视觉都在此模块内部收口。 */
@Composable
fun WandButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: WandButtonVariant = WandButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    compact: Boolean = false,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (loading || icon != null) {
            WandStatusIconSlot(
                indicatorColor = when (variant) {
                    // 描边 / 文字变体的加载圈用品牌或危险色，按钮正文是另一套颜色。
                    WandButtonVariant.Secondary, WandButtonVariant.Text -> WandColors.brand
                    WandButtonVariant.DangerText -> WandColors.danger
                    // 实心变体跟着 Button 当前的正文色：加载中按钮处于禁用态，
                    // 那里的色值是按禁用软底重新派生的，写死白字会在暗色主题下消失。
                    else -> LocalContentColor.current
                },
                containerColor = Color.Transparent,
                running = loading,
                icon = icon ?: WandIcons.refresh,
                boxSize = 18.dp,
                iconSize = 18.dp,
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (!loading && trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    val resolvedModifier = modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = if (compact) 36.dp else 44.dp)
        .defaultMinSize(minWidth = if (compact) 0.dp else 64.dp)

    when (variant) {
        WandButtonVariant.Primary, WandButtonVariant.Danger, WandButtonVariant.Success -> {
            val accent = variant.solidColor()
            val disabledContainer = accent.copy(alpha = 0.34f)
            val enabledInk = variant.onSolidColor()
            // 禁用底只有 34% 不透明度，屏幕上实际是「强调色 + 页面底色」的合成色，
            // 所以顺序是：先合成出真底，再在次级墨的基础上淡出到「不抢可用态」。
            // 这里刻意不取最高对比：禁用字比启用字还醒目，等于把 inactive 信号反着画。
            val disabledInk = wandDisabledInk(
                mutedInk = WandColors.textSecondary,
                disabledContainer = wandComposite(disabledContainer, WandColors.bgPrimary),
                enabledContrast = wandContrast(enabledInk, accent),
            )
            Button(
                onClick = onClick,
                enabled = enabled && !loading,
                modifier = resolvedModifier,
                shape = WandShapes.full,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = enabledInk,
                    disabledContainerColor = disabledContainer,
                    disabledContentColor = disabledInk,
                ),
                contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 6.dp else 10.dp),
                content = content,
            )
        }
        WandButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            shape = WandShapes.full,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = WandColors.textPrimary,
                disabledContentColor = WandColors.textMuted,
            ),
            border = ButtonDefaults.outlinedButtonBorder(enabled && !loading).copy(
                brush = androidx.compose.ui.graphics.SolidColor(WandColors.borderStrong),
            ),
            contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 6.dp else 10.dp),
            content = content,
        )
        WandButtonVariant.Text, WandButtonVariant.DangerText -> TextButton(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            colors = ButtonDefaults.textButtonColors(
                // 文字按钮的正文就是文字：按 4.5:1 的专用墨色取，不直接用给图形准备的 accent。
                contentColor = if (variant == WandButtonVariant.DangerText) {
                    WandColors.dangerText
                } else {
                    WandColors.brandText
                },
                disabledContentColor = WandColors.textMuted,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            content = content,
        )
    }
}

enum class WandIconButtonVariant {
    Toolbar,
    Chrome,
    Quiet,
    Accent,
    Compact,
}

/** 所有通用图标按钮的唯一视觉入口。 */
@Composable
fun WandIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: WandIconButtonVariant = WandIconButtonVariant.Toolbar,
    tint: Color = if (variant == WandIconButtonVariant.Accent) WandColors.brand else WandColors.textSecondary,
    enabled: Boolean = true,
    iconSize: Dp = when (variant) {
        WandIconButtonVariant.Toolbar -> WandSizes.toolbarIcon
        WandIconButtonVariant.Chrome -> 19.dp
        WandIconButtonVariant.Quiet -> 20.dp
        WandIconButtonVariant.Accent -> 20.dp
        WandIconButtonVariant.Compact -> 16.dp
    },
) {
    val touchSize = when (variant) {
        WandIconButtonVariant.Chrome, WandIconButtonVariant.Quiet -> WandSizes.minTouchTarget
        WandIconButtonVariant.Toolbar, WandIconButtonVariant.Accent -> WandSizes.minTouchTarget
        WandIconButtonVariant.Compact -> 32.dp
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(touchSize),
    ) {
        val visualModifier = when (variant) {
            WandIconButtonVariant.Toolbar -> Modifier.size(touchSize)
            WandIconButtonVariant.Quiet, WandIconButtonVariant.Compact -> Modifier
                .size(touchSize)
                .clip(WandShapes.sm)
            WandIconButtonVariant.Chrome -> Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(WandColors.surface)
                .border(0.5.dp, WandColors.border, CircleShape)
            WandIconButtonVariant.Accent -> Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(WandColors.brandSoft)
        }
        Box(modifier = visualModifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else WandColors.textMuted.copy(alpha = 0.48f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

data class WandDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

/** 标准 Wand 弹窗，统一容器、排版和操作颜色；正文允许承载下载进度等复杂内容。 */
@Composable
fun WandDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirm: WandDialogAction,
    modifier: Modifier = Modifier,
    dismiss: WandDialogAction? = null,
    icon: ImageVector? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = WandColors.bgElevated,
        shape = MaterialTheme.shapes.extraLarge,
        icon = icon?.let {
            {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(WandShapes.sm)
                        .background(if (confirm.destructive) WandColors.dangerSoft else WandColors.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (confirm.destructive) WandColors.danger else WandColors.brand,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, color = WandColors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                content = body,
            )
        },
        confirmButton = { WandDialogTextAction(confirm) },
        dismissButton = dismiss?.let { { WandDialogTextAction(it) } },
    )
}

@Composable
private fun WandDialogTextAction(action: WandDialogAction) {
    TextButton(onClick = action.onClick, enabled = action.enabled) {
        Text(
            action.label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !action.enabled -> WandColors.textMuted
                action.destructive -> WandColors.danger
                else -> WandColors.brand
            },
        )
    }
}

/** Wand 官方输入控件：统一的容器色、边框、光标与错误态，String 与 TextFieldValue 两种载体共用同一套外观。 */
@Composable
private fun wandFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = WandColors.surface,
    unfocusedContainerColor = WandColors.surface.copy(alpha = 0.90f),
    focusedBorderColor = WandColors.focusRing,
    unfocusedBorderColor = WandColors.borderStrong.copy(alpha = 0.72f),
    errorBorderColor = WandColors.danger,
    focusedTextColor = WandColors.textPrimary,
    unfocusedTextColor = WandColors.textPrimary,
    cursorColor = WandColors.brand,
    focusedLabelColor = WandColors.brand,
    unfocusedLabelColor = WandColors.textMuted,
)

@Composable
fun WandTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        textStyle = textStyle.copy(color = WandColors.textPrimary),
        shape = MaterialTheme.shapes.medium,
        visualTransformation = visualTransformation,
        label = label?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        placeholder = placeholder?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = WandColors.textMuted) }
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = wandFieldColors(),
    )
}

/** 同上，但载体是 [TextFieldValue]：用于需要精确控制光标/选区的场景（例如“切换服务器”时全选地址）。 */
@Composable
fun WandTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        textStyle = textStyle.copy(color = WandColors.textPrimary),
        shape = MaterialTheme.shapes.medium,
        visualTransformation = visualTransformation,
        label = label?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        placeholder = placeholder?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = WandColors.textMuted) }
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = wandFieldColors(),
    )
}


enum class WandProviderMarkVariant {
    Plain,
    Tinted,
}

/** Provider 品牌标识；所有页面共享 logo、语义色、尺寸和无障碍文字。 */
@Composable
fun WandProviderMark(
    provider: String?,
    modifier: Modifier = Modifier,
    variant: WandProviderMarkVariant = WandProviderMarkVariant.Plain,
    boxSize: Dp = 28.dp,
) {
    val logoSize = (if (variant == WandProviderMarkVariant.Tinted) 15.dp else 20.dp) *
        BrandLogos.opticalScale(provider)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(boxSize),
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(provider),
            contentDescription = providerDisplayName(provider),
            tint = BrandLogos.tintForProvider(provider, WandColors.textPrimary),
            modifier = Modifier.size(logoSize),
        )
    }
}

/** 底部弹层的统一 scrim、圆角、材质与拖动把手。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WandBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    gesturesEnabled: Boolean = true,
    showDragHandle: Boolean = true,
    transparent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = gesturesEnabled,
        containerColor = if (transparent) Color.Transparent else WandColors.bgElevated.copy(alpha = 0.98f),
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.46f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = if (showDragHandle) ({ BottomSheetDefaults.DragHandle() }) else null,
        content = content,
    )
}
