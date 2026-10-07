package com.wand.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.wand.app.data.PiSkillMode
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandMorphingIcon
import com.wand.app.ui.components.WandSegmentedTrack
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlin.math.roundToInt

/** Three detents in a fixed box; dragging through On never starts an intermediate save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PiSkillSwitch(name: String, mode: PiSkillMode, enabled: Boolean, saving: Boolean,
    onModeChange: (PiSkillMode) -> Unit) {
    var preview by remember { mutableFloatStateOf(mode.ordinal.toFloat()) }
    LaunchedEffect(mode, saving) { if (!saving) preview = mode.ordinal.toFloat() }
    val index = preview.roundToInt().coerceIn(0, 2)
    val motion = !reduceMotionEnabled()
    val lockVisibility by animateFloatAsState(if (index > 0) 1f else 0f,
        animationSpec = WandMotion.respectMotion(motion, WandMotion.tweenFast()), label = "skillLockVisibility")
    val lockProgress by animateFloatAsState(if (index == 2) 1f else 0f,
        animationSpec = WandMotion.respectMotion(motion, WandMotion.morph()), label = "skillLockMorph")
    Box(Modifier.width(108.dp).height(48.dp).alpha(if (enabled || saving) 1f else 0.48f),
        contentAlignment = Alignment.Center) {
        WandSegmentedTrack(itemCount = 3, selectedIndex = index, minHeight = 32.dp,
            containerShape = WandShapes.full, indicatorShape = WandShapes.full,
            indicatorColor = if (index == 0) WandColors.surface else WandColors.brandSoft) {
            listOf("关", "开", "锁").forEachIndexed { position, label ->
                Box(Modifier.weight(1f).height(26.dp), contentAlignment = Alignment.Center) {
                    if (position < 2) Text(label, style = MaterialTheme.typography.labelSmall,
                        color = if (position == index && index > 0) WandColors.brand else WandColors.textSecondary)
                    else WandMorphingIcon(lockProgress, WandIcons.unlock, WandIcons.permission,
                        tint = if (index == 2) WandColors.brand else WandColors.textSecondary,
                        iconSize = 15.dp, rotationDegrees = 12f, modifier = Modifier.alpha(lockVisibility))
                }
            }
        }
        Slider(value = preview, valueRange = 0f..2f, steps = 1, enabled = enabled,
            onValueChange = { preview = it.roundToInt().toFloat() },
            onValueChangeFinished = {
                val next = PiSkillMode.entries[preview.roundToInt().coerceIn(0, 2)]
                if (next != mode) onModeChange(next)
            }, thumb = {}, track = {},
            modifier = Modifier.width(108.dp).semantics {
                contentDescription = "Skill $name 使用设置"
                stateDescription = when (index) { 0 -> "关闭"; 1 -> "开启"; else -> "开启并锁定" }
            })
    }
}
