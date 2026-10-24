package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class HomeRowAction(val id: String, val title: String, val icon: ImageVector,
    val tint: Color, val perform: () -> Unit)

internal val LocalHomeSwipeOwner = compositionLocalOf<MutableState<String?>?> { null }

@Composable
internal fun HomeSwipeRow(id: String, actions: List<HomeRowAction>, enabled: Boolean = true,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fallback = remember { mutableStateOf<String?>(null) }
    val owner = LocalHomeSwipeOwner.current ?: fallback
    HomeActionSwipeCard(if (enabled) actions else emptyList(), owner.value == id,
        { open -> if (open) owner.value = id else if (owner.value == id) owner.value = null },
        WandColors.bgPrimary, modifier, content)
    DisposableEffect(id) { onDispose { if (owner.value == id) owner.value = null } }
    LaunchedEffect(enabled) { if (!enabled && owner.value == id) owner.value = null }
}

/** One horizontal-only, reversible gesture owner; reveal never submits or deletes. */
@Composable
internal fun HomeActionSwipeCard(actions: List<HomeRowAction>, revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit, cardBackground: Color,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (actions.isEmpty()) { Box(modifier) { content() }; return }
    BackHandler(revealed) { onRevealedChange(false) }
    BoxWithConstraints(modifier.fillMaxWidth().clip(WandShapes.sm)) {
        val density = LocalDensity.current
        val revealPx = with(density) { minOf((60.dp * actions.size).toPx(), maxWidth.toPx()) }
        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val latestChange = rememberUpdatedState(onRevealedChange)
        val reduced = reduceMotionEnabled()
        suspend fun settle(target: Float) {
            if (reduced) offset.snapTo(target) else offset.animateTo(target, WandMotion.tweenFast())
        }
        LaunchedEffect(revealed, revealPx) { settle(if (revealed) -revealPx else 0f) }
        Box(Modifier.fillMaxWidth().draggable(orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> scope.launch {
                offset.snapTo((offset.value + delta).coerceIn(-revealPx, 0f))
            } },
            onDragStopped = { velocity ->
                val open = boardTaskSwipeShouldReveal(offset.value, revealPx, velocity)
                latestChange.value(open); settle(if (open) -revealPx else 0f)
            })) {
            Row(Modifier.matchParentSize().then(if (!revealed) Modifier.clearAndSetSemantics {} else Modifier), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                actions.forEach { action ->
                    Column(Modifier.width(56.dp).heightIn(min = 64.dp)
                        .clickable(enabled = revealed, role = Role.Button, onClickLabel = action.title) {
                            latestChange.value(false); action.perform()
                        }.semantics { contentDescription = action.title },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(action.tint), contentAlignment = Alignment.Center) {
                            Icon(action.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text(action.title, style = MaterialTheme.typography.labelSmall, color = action.tint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }.background(cardBackground)
                .semantics { customActions = actions.map { action -> CustomAccessibilityAction(action.title) {
                    latestChange.value(false); action.perform(); true
                } } }) {
                content()
                if (revealed) Box(Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) { latestChange.value(false) })
            }
        }
    }
}

@Composable
internal fun homeExecutionTint(state: HomeExecutionState): Color = when (state) {
    HomeExecutionState.Running -> WandColors.success
    HomeExecutionState.Queued -> WandColors.info
    HomeExecutionState.NeedsYou -> WandColors.warning
    HomeExecutionState.Failed -> WandColors.danger
    HomeExecutionState.Completed -> WandColors.brand
    else -> WandColors.textSecondary
}

@Composable
internal fun HomeExecutionBadge(state: HomeExecutionState) {
    val color = homeExecutionTint(state)
    val icon = when (state) {
        HomeExecutionState.Running -> WandIcons.play
        HomeExecutionState.Queued -> WandIcons.history
        HomeExecutionState.NeedsYou, HomeExecutionState.Failed -> WandIcons.error
        HomeExecutionState.Completed -> WandIcons.check
        HomeExecutionState.Archived -> WandIcons.archive
        HomeExecutionState.Idle -> WandIcons.stop
    }
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = .1f)).padding(horizontal = 7.dp, vertical = 4.dp)
        .semantics { contentDescription = "执行状态：${state.label}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Text(state.label, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
