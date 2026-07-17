package com.wand.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.theme.WandColors
import kotlin.math.roundToInt

/**
 * 共享文本工具函数。
 */

/**
 * 路径专用单行文本：溢出时先停在末尾，优先让用户看到最底层目录；短暂停留后低速扫动完整路径。
 * 系统关闭动画时只保留末尾静态态，避免无障碍设置下持续运动。
 */
@Composable
fun TailMarqueePathText(
    path: String,
    modifier: Modifier = Modifier,
    fallback: String = "未设置工作目录",
    color: Color = WandColors.textMuted,
    fontSize: TextUnit = 11.sp,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily = FontFamily.Monospace,
    initialDelayMillis: Long = 2_400L,
    pauseMillis: Long = 1_000L,
    velocity: Dp = 18.dp,
) {
    val text = path.ifBlank { fallback }
    val density = LocalDensity.current
    val motionEnabled = rememberSystemMotionEnabled()
    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        color = color,
    )
    val textWidthPx = remember(text, fontSize, fontWeight, fontFamily) {
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = textStyle,
            maxLines = 1,
            softWrap = false,
        ).size.width
    }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val overflowPx = if (containerWidthPx > 0) {
        (textWidthPx - containerWidthPx).coerceAtLeast(0)
    } else {
        0
    }
    val offset = remember { Animatable(0f) }
    val velocityPxPerSecond = with(density) { velocity.toPx() }.coerceAtLeast(1f)

    LaunchedEffect(text, overflowPx, motionEnabled, velocityPxPerSecond) {
        if (overflowPx <= 0) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val tailOffset = -overflowPx.toFloat()
        offset.snapTo(tailOffset)
        if (!motionEnabled) return@LaunchedEffect

        val durationMillis = ((overflowPx / velocityPxPerSecond) * 1000f)
            .roundToInt()
            .coerceAtLeast(1_800)
        kotlinx.coroutines.delay(initialDelayMillis)
        while (true) {
            offset.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing),
            )
            kotlinx.coroutines.delay(pauseMillis)
            offset.animateTo(
                targetValue = tailOffset,
                animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing),
            )
            kotlinx.coroutines.delay(initialDelayMillis)
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width },
    ) {
        Text(
            text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier.graphicsLayer {
                translationX = if (overflowPx > 0) offset.value else 0f
            },
        )
    }
}

@Composable
private fun rememberSystemMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        } catch (_: Exception) {
            true
        }
    }
}
