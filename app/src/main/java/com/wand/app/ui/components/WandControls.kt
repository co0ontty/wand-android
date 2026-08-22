package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.wand.app.data.providerDisplayName

enum class WandButtonVariant {
    Primary,
    Secondary,
    Text,
    Danger,
    DangerText,
    Success,
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
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = when (variant) {
                    WandButtonVariant.Primary, WandButtonVariant.Danger, WandButtonVariant.Success -> Color.White
                    WandButtonVariant.Secondary, WandButtonVariant.Text -> WandColors.brand
                    WandButtonVariant.DangerText -> WandColors.danger
                },
                strokeWidth = 2.dp,
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (!loading && trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    val resolvedModifier = modifier
        .heightIn(min = if (compact) 40.dp else WandSizes.controlHeight)
        .defaultMinSize(minWidth = if (compact) 0.dp else 64.dp)

    when (variant) {
        WandButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = WandColors.brand,
                contentColor = Color.White,
                disabledContainerColor = WandColors.brand.copy(alpha = 0.34f),
                disabledContentColor = Color.White.copy(alpha = 0.82f),
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content,
        )
        WandButtonVariant.Danger -> Button(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = WandColors.danger,
                contentColor = Color.White,
                disabledContainerColor = WandColors.danger.copy(alpha = 0.34f),
                disabledContentColor = Color.White.copy(alpha = 0.82f),
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content,
        )
        WandButtonVariant.Success -> Button(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = WandColors.success,
                contentColor = Color.White,
                disabledContainerColor = WandColors.success.copy(alpha = 0.34f),
                disabledContentColor = Color.White.copy(alpha = 0.82f),
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content,
        )
        WandButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = WandColors.textPrimary,
                disabledContentColor = WandColors.textMuted,
            ),
            border = ButtonDefaults.outlinedButtonBorder(enabled && !loading).copy(
                brush = androidx.compose.ui.graphics.SolidColor(WandColors.borderStrong),
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            content = content,
        )
        WandButtonVariant.Text, WandButtonVariant.DangerText -> TextButton(
            onClick = onClick,
            enabled = enabled && !loading,
            modifier = resolvedModifier,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (variant == WandButtonVariant.DangerText) WandColors.danger else WandColors.brand,
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
    },
) {
    val touchSize = when (variant) {
        WandIconButtonVariant.Chrome, WandIconButtonVariant.Quiet -> 44.dp
        WandIconButtonVariant.Toolbar, WandIconButtonVariant.Accent -> WandSizes.minTouchTarget
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(touchSize),
    ) {
        val visualModifier = when (variant) {
            WandIconButtonVariant.Toolbar -> Modifier.size(touchSize)
            WandIconButtonVariant.Quiet -> Modifier
                .size(touchSize)
                .clip(WandShapes.sm)
            WandIconButtonVariant.Chrome -> Modifier
                .size(touchSize)
                .clip(CircleShape)
                .background(WandColors.surface.copy(alpha = 0.62f))
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
                modifier = Modifier.fillMaxWidth(),
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

/** 官方文本输入控件的品牌默认值。复杂输入仍可在其上组合 leading/trailing 内容。 */
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
        colors = OutlinedTextFieldDefaults.colors(
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
        ),
    )
}

/** 需要精确控制光标/选区的同款输入接口（例如“切换服务器”时全选地址）。 */
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
        colors = OutlinedTextFieldDefaults.colors(
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
        ),
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
) {
    val isCodex = provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val background = if (isCodex) WandColors.infoSoft else WandColors.brandSoft
    val logoSize = (if (variant == WandProviderMarkVariant.Tinted) 15.dp else 20.dp) *
        BrandLogos.opticalScale(provider)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(28.dp)
            .then(
                if (variant == WandProviderMarkVariant.Tinted) {
                    Modifier
                        .clip(WandShapes.sm)
                        .background(background.copy(alpha = 0.72f))
                } else Modifier
            ),
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(provider),
            contentDescription = providerDisplayName(provider),
            tint = BrandLogos.tintForProvider(provider, tint.copy(alpha = 0.94f)),
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
