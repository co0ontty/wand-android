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
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import kotlin.math.roundToInt

/** 只给猫头定位留高度，不拿这块当正文避让带。正文可以从控件下穿过。 */
@Composable
internal fun topCompanionClearance(): Dp {
    val compact = LocalConfiguration.current.screenHeightDp < 420
    val narrow = LocalConfiguration.current.screenWidthDp < 344
    val size = if (compact || narrow) 64.dp else 88.dp
    val top = FloatingControlCenter - size / 2
    return top + size + 8.dp
}

/** The scene's safe slot is independent of the composer/IME and live execution state. */
@Composable
internal fun TopChatCompanion(
    employeeId: String?, name: String?, avatar: String?, status: String,
    activity: AvatarFileActivity, thinking: Boolean, speaking: Boolean,
    onEdit: () -> Unit, gesture: DraftVoiceGesture,
    onPauseSpeech: (() -> Unit)? = null,
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
    val currentGesture by rememberUpdatedState(gesture)
    val density = LocalDensity.current
    val compact = LocalConfiguration.current.screenHeightDp < 420
    BoxWithConstraints(Modifier.fillMaxWidth().height(topCompanionClearance())) {
        val narrow = maxWidth < 344.dp
        // A narrow tablet pane can have a wide window configuration. Keep its scene
        // inside the same reserved top viewport instead of moving it below the controls.
        val size = if (compact || narrow) 64.dp else 88.dp
        val left = 60.dp
        val iconRowStart = maxWidth - 164.dp
        val right = iconRowStart - size
        val start = (left + right) / 2
        val end = maxOf(start, right)
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
            contentDescription = "${name ?: "员工"}，点按编辑，长按说话，拖动左右换边"
            val place = if (side == CompanionSide.Left) "角色在左侧" else "角色在右侧"
            stateDescription = if (status.isBlank()) place else "$place，$status"
            if (status.isNotBlank()) liveRegion = LiveRegionMode.Polite
            onClick("编辑消息") { currentEdit(); true }
            customActions = listOf(CustomAccessibilityAction("切换角色位置") {
                side = if (side == CompanionSide.Left) CompanionSide.Right else CompanionSide.Left
                preference.write(side); true
            })
        }.focusable().pointerInput(Unit) {
            companionGesture(true, { currentEdit() }, { currentGesture.start() },
                { currentGesture.move(it) }, { currentGesture.end() }, { currentGesture.cancel() },
                { drag = it }, { currentSnap() }, { drag = 0f })
        }) {
            EmployeeAvatar(employeeId ?: "e_wand_default", name, avatar, size = size, animated = true,
                fileActivity = activity, thinking = thinking && activity == AvatarFileActivity.Idle, speaking = speaking)
        }
        if (onPauseSpeech != null) IconButton(onClick = onPauseSpeech,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).size(48.dp)) {
            Icon(WandIcons.pause, "暂停朗读，任务继续", tint = WandColors.textSecondary)
        }
    }
}

/** One accessible microphone, shared by the compact and expanded capsule. */
@Composable
internal fun CapsuleMicrophone(gesture: DraftVoiceGesture, pressed: Boolean, canceling: Boolean = false) {
    var hint by remember { mutableStateOf(false) }
    val currentGesture by rememberUpdatedState(gesture)
    Box(Modifier.size(48.dp).semantics(mergeDescendants = true) {
        role = Role.Button
        contentDescription = "按住说话，松手转写，上滑取消"
        stateDescription = if (pressed) "正在录音" else "长按录音"
        onClick("显示语音输入提示") { hint = true; true }
    }.focusable().pointerInput(Unit) {
        companionGesture(false, { hint = true }, { currentGesture.start() },
            { currentGesture.move(it) }, { currentGesture.end() }, { currentGesture.cancel() }, {}, {}, {})
    }, contentAlignment = Alignment.Center) {
        Icon(WandIcons.mic, null, modifier = Modifier.size(22.dp), tint = when {
            canceling -> WandColors.danger; pressed -> WandColors.brand; else -> WandColors.textPrimary
        })
        DropdownMenu(hint, { hint = false }) {
            DropdownMenuItem(text = { Text("按住猫头或麦克风说话，松手仅转写") }, onClick = { hint = false })
        }
    }
}
