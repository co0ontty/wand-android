package com.wand.app.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import kotlin.math.roundToInt

/** 顶部伙伴和按钮共同持有阅读上边界，正文不能进入任一控件的触控区域。 */
@Composable
internal fun topCompanionClearance(): Dp {
    val compact = LocalConfiguration.current.screenHeightDp < 420
    val density = LocalDensity.current
    val textHeight = with(density) {
        MaterialTheme.typography.labelMedium.lineHeight.toDp() * 2 + MaterialTheme.typography.labelSmall.lineHeight.toDp()
    } + 2.dp
    return maxOf(ChatFloatingControlsClearance, if (compact) textHeight + 8.dp else 56.dp + 4.dp + textHeight + 8.dp)
}

/** The scene's safe slot is independent of the composer/IME and live execution state. */
@Composable
internal fun TopChatCompanion(
    employeeId: String?, name: String?, avatar: String?, status: String,
    activity: AvatarFileActivity, thinking: Boolean,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val preference = remember(context) { CompanionSidePreference(context) }
    var side by remember { mutableStateOf(preference.read()) }
    var drag by remember { mutableFloatStateOf(0f) }
    DisposableEffect(preference) {
        val unsubscribe = preference.observe { side = preference.read() }
        onDispose { unsubscribe() }
    }
    val currentEdit by rememberUpdatedState(onEdit)
    val density = LocalDensity.current
    val compact = LocalConfiguration.current.screenHeightDp < 420
    BoxWithConstraints(Modifier.fillMaxWidth().height(topCompanionClearance())) {
        val size = if (compact) 48.dp else 56.dp
        val left = FloatingControlsBackInset
        val iconRowStart = maxWidth - FloatingControlsActionsInset
        val right = iconRowStart - size
        val start = if (compact) left else (left + right) / 2
        val end = if (compact) left else maxOf(start, right)
        val base = if (side == CompanionSide.Left) start else end
        val x = with(density) { (base.toPx() + drag).coerceIn(left.toPx().coerceAtMost(end.toPx()), end.toPx()) }
        val top = FloatingControlCenter - size / 2
        val snap: () -> Unit = {
            side = if (drag > 0) CompanionSide.Right else CompanionSide.Left
            preference.write(side); drag = 0f
        }
        val currentSnap by rememberUpdatedState(snap)
        Box(Modifier.offset { IntOffset(x.roundToInt(), with(density) { top.roundToPx() }) }.size(size).semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = "${name ?: "员工"}，点按编辑，拖动左右换边"
            val place = if (side == CompanionSide.Left) "角色在左侧" else "角色在右侧"
            stateDescription = if (status.isBlank()) place else "$place，$status"
            if (status.isNotBlank()) liveRegion = LiveRegionMode.Polite
            onClick("编辑消息") { currentEdit(); true }
            customActions = listOf(CustomAccessibilityAction("切换角色位置") {
                side = if (side == CompanionSide.Left) CompanionSide.Right else CompanionSide.Left
                preference.write(side); true
            })
        }.focusable().pointerInput(Unit) {
            companionGesture({ currentEdit() },
                { drag = it }, { currentSnap() }, { drag = 0f })
        }) {
            EmployeeAvatar(employeeId ?: "e_wand_default", name, avatar, size = size, animated = true,
                fileActivity = activity, thinking = thinking && activity == AvatarFileActivity.Idle)
        }
        val label = name?.takeIf { it.isNotBlank() } ?: "当前会话"
        val textHeight = with(density) {
            MaterialTheme.typography.labelMedium.lineHeight.toDp() * 2 + MaterialTheme.typography.labelSmall.lineHeight.toDp()
        } + 2.dp
        val labelWidth = if (compact) (maxWidth - FloatingControlsBackInset - FloatingControlsActionsInset - size - 8.dp).coerceAtLeast(24.dp)
            else minOf(with(density) { x.toDp() } + size / 2 - 12.dp,
                maxWidth - 12.dp - with(density) { x.toDp() } - size / 2).coerceAtLeast(24.dp) * 2
        val labelX = if (compact) with(density) { x.toDp() } + size + 8.dp
            else with(density) { x.toDp() } + size / 2 - labelWidth / 2
        Column(Modifier.offset(x = labelX, y = if (compact) 4.dp else top + size + 4.dp)
            .width(labelWidth).height(textHeight).semantics(mergeDescendants = true) { contentDescription = "$label，$status" },
            horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = WandColors.textPrimary, maxLines = 2,
                textAlign = if (compact) TextAlign.Start else TextAlign.Center,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(with(density) { MaterialTheme.typography.labelMedium.lineHeight.toDp() } * 2))
            Text(status, style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary, maxLines = 1,
                textAlign = if (compact) TextAlign.Start else TextAlign.Center,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}
