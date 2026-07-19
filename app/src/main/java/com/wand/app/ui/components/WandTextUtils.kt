package com.wand.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * 路径专用单行文本。默认保留旧版往返模式；[revealOnce] 会从根目录向末级目录揭示一次，
 * [repeatTailReveal] 则在末级目录贴右停留后回到开头，按固定间隔重复揭示。
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
    staggerWindowMillis: Long = 0L,
    velocity: Dp = 18.dp,
    revealOnce: Boolean = false,
    repeatTailReveal: Boolean = false,
) {
    val text = path.ifBlank { fallback }
    val emphasizedPathColor = lerp(color, WandColors.textPrimary, 0.46f)
    val styledText = remember(text, color, emphasizedPathColor, revealOnce, repeatTailReveal) {
        if (!revealOnce && !repeatTailReveal) return@remember AnnotatedString(text)
        val separator = maxOf(text.lastIndexOf('/'), text.lastIndexOf('\\'))
        buildAnnotatedString {
            if (separator in 0 until text.lastIndex) {
                append(text.substring(0, separator + 1))
                withStyle(
                    SpanStyle(
                        color = emphasizedPathColor,
                        fontWeight = FontWeight.Medium,
                    ),
                ) {
                    append(text.substring(separator + 1))
                }
            } else {
                append(text)
            }
        }
    }
    val density = LocalDensity.current
    val motionEnabled = rememberSystemMotionEnabled()
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var textWidthPx by remember { mutableIntStateOf(0) }
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
        if (!motionEnabled || revealOnce || repeatTailReveal) {
            if (motionEnabled) {
                offset.snapTo(0f)
            } else {
                offset.snapTo(tailOffset)
                return@LaunchedEffect
            }
        } else {
            offset.snapTo(tailOffset)
        }

        val durationMillis = ((overflowPx / velocityPxPerSecond) * 1000f)
            .roundToInt()
            .coerceAtLeast(1_800)
        val staggerMillis = if (staggerWindowMillis > 0) {
            (text.hashCode().toLong() and 0x7fff_ffffL) % staggerWindowMillis
        } else {
            0L
        }
        kotlinx.coroutines.delay(initialDelayMillis + staggerMillis)
        if (revealOnce) {
            offset.animateTo(
                targetValue = tailOffset,
                animationSpec = tween(
                    durationMillis = durationMillis.coerceAtMost(8_000),
                    easing = LinearEasing,
                ),
            )
        } else if (repeatTailReveal) {
            while (true) {
                offset.animateTo(
                    targetValue = tailOffset,
                    animationSpec = tween(
                        durationMillis = durationMillis.coerceAtMost(8_000),
                        easing = LinearEasing,
                    ),
                )
                kotlinx.coroutines.delay(pauseMillis)
                offset.snapTo(0f)
                kotlinx.coroutines.delay(initialDelayMillis + staggerMillis)
            }
        } else {
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
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width },
    ) {
        Text(
            styledText,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .onSizeChanged { textWidthPx = it.width }
                .graphicsLayer {
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
