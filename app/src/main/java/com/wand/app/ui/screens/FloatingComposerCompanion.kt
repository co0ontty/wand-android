package com.wand.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.components.AvatarFileActivity
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlin.math.roundToInt

/** Reserved geometry does not depend on tool/streaming state. One scene moves; it is never mirrored. */
@Composable
internal fun FloatingComposerCompanion(
    employeeId: String?, name: String?, avatar: String?, status: String,
    activity: AvatarFileActivity, thinking: Boolean,
    controls: CompanionComposerControls, running: Boolean,
    onStop: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preference = remember(context.applicationContext) { CompanionSidePreference(context) }
    var side by remember(preference) { mutableStateOf(preference.read()) }
    var dragX by remember { mutableFloatStateOf(0f) }
    DisposableEffect(preference) {
        val unsubscribe = preference.observe { side = preference.read() }
        onDispose { unsubscribe() }
    }
    val currentControls by rememberUpdatedState(controls)
    val density = LocalDensity.current
    val captionLineHeight = with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
    val dockHeight = maxOf(COMPANION_SCENE_SLOT_DP.dp, captionLineHeight * 4,
        captionLineHeight * 2 + ComposerActionTouchSize + 4.dp)
    val action = companionActionVisual(controls.hasText, running, controls.deliveryPhase)
    Column(Modifier.fillMaxWidth()) {
        // Status uses the space between scene and controls, not another reserved footer row.
        // Height follows font scale, never live tool/receipt/text state.
        BoxWithConstraints(Modifier.fillMaxWidth().height(dockHeight)) {
            val headSize = companionSceneSizeDp(maxWidth.value).dp
            val maxX = with(density) { (maxWidth - headSize - 24.dp).coerceAtLeast(0.dp).toPx() }
            val initialX = if (side == CompanionSide.Left) 0f else maxX
            val headX = (initialX + dragX).coerceIn(0f, maxX)
            val snap: () -> Unit = {
                val finalX = ((if (side == CompanionSide.Left) 0f else maxX) + dragX).coerceIn(0f, maxX)
                side = companionSnapSide(finalX + with(density) { (12.dp + headSize / 2).toPx() }, with(density) { maxWidth.toPx() })
                preference.write(side)
                dragX = 0f
            }
            val latestSnap by rememberUpdatedState(snap)
            // Reserve the send action's lane without drawing an empty button. The keyboard
            // button stays anchored while the same capsule grows rightwards for text or a receipt.
            Box(Modifier.align(if (side == CompanionSide.Left) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 8.dp).width(ComposerActionTouchSize * 2).height(ComposerActionTouchSize)) {
            Surface(shape = RoundedCornerShape(28.dp), color = WandColors.surface,
                modifier = Modifier.align(Alignment.CenterStart).animateContentSize(
                    WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenNormal()),
                    alignment = Alignment.CenterStart,
                )) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controls.onEdit() }, modifier = Modifier.size(48.dp)) {
                        Icon(WandIcons.keyboard, "展开键盘输入", tint = WandColors.textPrimary, modifier = Modifier.size(22.dp))
                    }
                    if (action != null) SubmitMorphButton(
                        visual = action,
                        enabled = action == SendActionVisual.Stop || action == SendActionVisual.Send && controls.canSubmit,
                        fillColor = when (action) {
                            SendActionVisual.Stop -> WandColors.textPrimary
                            SendActionVisual.Failed -> WandColors.dangerSoft
                            else -> WandColors.brand
                        },
                        contentTint = when (action) {
                            SendActionVisual.Stop -> WandColors.surface
                            SendActionVisual.Failed -> WandColors.danger
                            else -> WandColors.onBrand
                        },
                        contentDescription = when (action) {
                            SendActionVisual.Sending -> "发送中"
                            SendActionVisual.Sent -> "已发送"
                            SendActionVisual.Failed -> "发送失败，请核对结果"
                            SendActionVisual.Stop -> "停止当前任务"
                            else -> "发送消息"
                        },
                        onClick = if (action == SendActionVisual.Stop) onStop else controls.onSend,
                    )
                }
            }
            }
            val actionWidth = ComposerActionTouchSize * 2
            val hint = when {
                status.isNotBlank() -> status
                !controls.editing && (controls.hasText || controls.hasAttachments) -> "草稿已保留"
                else -> ""
            }
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(
                    start = if (side == CompanionSide.Left) headSize + 20.dp else actionWidth + 16.dp,
                    end = if (side == CompanionSide.Left) actionWidth + 16.dp else headSize + 20.dp,
                ),
                horizontalAlignment = if (side == CompanionSide.Left) Alignment.Start else Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (hint.isNotBlank()) Text(hint, Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = hint
                    liveRegion = LiveRegionMode.Polite
                }, style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary,
                    textAlign = if (side == CompanionSide.Right) TextAlign.End else TextAlign.Start,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp).offset { IntOffset(headX.roundToInt(), 0) }.zIndex(1f)
                .size(headSize).semantics {
                    role = Role.Button
                    contentDescription = "${name ?: "员工"}，点按编辑，拖动左右换边"
                    stateDescription = if (side == CompanionSide.Left) "角色在左侧" else "角色在右侧"
                    onClick("编辑消息") { currentControls.onEdit(); true }
                    customActions = listOf(CustomAccessibilityAction("把角色移到${if (side == CompanionSide.Left) "右" else "左"}侧") {
                        side = if (side == CompanionSide.Left) CompanionSide.Right else CompanionSide.Left
                        preference.write(side); true
                    })
                }.pointerInput(Unit) {
                    companionGesture({ currentControls.onEdit() },
                        { dragX = it }, { latestSnap() }, { dragX = 0f })
                }) {
                EmployeeAvatar(employeeId ?: "e_wand_default", name, avatar, size = headSize, animated = true,
                    fileActivity = activity, thinking = thinking && activity == AvatarFileActivity.Idle)
            }
        }
        content()
    }
}
