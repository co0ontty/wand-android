package com.wand.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandTerminal
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decorative connection story adapted from the Web login illustration: three sources flow into
 * one Wand workspace, then a signal reaches the phone. [progress] is a one-shot value in 0..1;
 * the owning screen controls its timing. With reduced motion the completed scene is shown at once.
 */
@Composable
fun WandConnectionScene(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 184.dp,
) {
    val shownProgress = if (reduceMotionEnabled()) 1f else progress.coerceIn(0f, 1f)
    val brand = WandColors.brand
    val border = WandColors.border
    val surface = WandColors.surface
    val surfaceSoft = WandColors.surfaceSoft
    val ink = WandColors.textSecondary
    val success = WandColors.success
    val terminal = WandTerminal.background
    val workspaceReveal = reveal(shownProgress, 0.20f, 0.60f)
    val phoneReveal = reveal(shownProgress, 0.58f, 0.96f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {},
    ) {
        val sceneScale = min(maxWidth.value / 360f, height.value / 184f)
        val left = (maxWidth.value - 360f * sceneScale) / 2f
        val top = (height.value - 184f * sceneScale) / 2f

        Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width / 360f, size.height / 184f)
            val originX = (size.width - 360f * scale) / 2f
            val originY = (size.height - 184f * scale) / 2f
            fun point(x: Float, y: Float) = Offset(originX + x * scale, originY + y * scale)
            fun dimensions(width: Float, height: Float) = Size(width * scale, height * scale)
            fun corner(radius: Float) = CornerRadius(radius * scale)

            // The faint routes stay visible, so the story remains understandable without motion.
            val sources = floatArrayOf(54f, 180f, 306f)
            sources.forEachIndexed { index, x ->
                val route = Path().apply {
                    moveTo(point(x, 53f).x, point(x, 53f).y)
                    cubicTo(
                        point(x, 76f).x, point(x, 76f).y,
                        point(180f, 65f).x, point(180f, 65f).y,
                        point(180f, 85f).x, point(180f, 85f).y,
                    )
                }
                drawPath(route, border.copy(alpha = 0.85f), style = Stroke(1.2f * scale))

                val signal = reveal(shownProgress, index * 0.075f, 0.43f + index * 0.075f)
                if (signal > 0f && signal < 1f) {
                    val center = cubicPoint(
                        point(x, 53f), point(x, 76f), point(180f, 65f), point(180f, 85f), signal,
                    )
                    drawCircle(brand.copy(alpha = 0.18f), 6.5f * scale, center)
                    drawCircle(brand, 2.7f * scale, center)
                }

                drawRoundRect(
                    color = surface,
                    topLeft = point(x - 20f, 14f),
                    size = dimensions(40f, 39f),
                    cornerRadius = corner(11f),
                )
                drawRoundRect(
                    color = border,
                    topLeft = point(x - 20f, 14f),
                    size = dimensions(40f, 39f),
                    cornerRadius = corner(11f),
                    style = Stroke(1f * scale),
                )
                when (index) {
                    0 -> {
                        // Terminal prompt.
                        drawLine(ink, point(x - 8f, 29f), point(x - 3f, 33f), 1.7f * scale)
                        drawLine(ink, point(x - 3f, 33f), point(x - 8f, 37f), 1.7f * scale)
                        drawLine(brand, point(x + 1f, 38f), point(x + 9f, 38f), 1.7f * scale)
                    }
                    1 -> {
                        // Conversation.
                        drawRoundRect(
                            ink, point(x - 9f, 26f), dimensions(18f, 12f), corner(4f),
                            style = Stroke(1.5f * scale),
                        )
                        drawCircle(brand, 1.5f * scale, point(x - 4f, 32f))
                        drawCircle(brand, 1.5f * scale, point(x + 1f, 32f))
                        drawCircle(brand, 1.5f * scale, point(x + 6f, 32f))
                    }
                    else -> {
                        // Git branch.
                        drawLine(ink, point(x - 6f, 27f), point(x - 6f, 39f), 1.5f * scale)
                        drawLine(ink, point(x + 6f, 27f), point(x + 6f, 33f), 1.5f * scale)
                        drawLine(ink, point(x + 6f, 33f), point(x - 6f, 37f), 1.5f * scale)
                        drawCircle(brand, 2.5f * scale, point(x - 6f, 27f))
                        drawCircle(brand, 2.5f * scale, point(x + 6f, 27f))
                        drawCircle(brand, 2.5f * scale, point(x - 6f, 40f))
                    }
                }
            }

            // Three offset sheets suggest a workspace instead of a generic loading spinner.
            drawRoundRect(
                surfaceSoft.copy(alpha = 0.35f + 0.65f * workspaceReveal),
                point(96f, 96f), dimensions(174f, 79f), corner(11f),
            )
            drawRoundRect(
                border.copy(alpha = 0.24f + 0.50f * workspaceReveal),
                point(96f, 96f), dimensions(174f, 79f), corner(11f),
                style = Stroke(1f * scale),
            )
            drawRoundRect(
                surface.copy(alpha = 0.28f + 0.72f * workspaceReveal),
                point(86f, 86f), dimensions(174f, 79f), corner(11f),
            )
            drawRoundRect(
                border.copy(alpha = 0.35f + 0.65f * workspaceReveal),
                point(86f, 86f), dimensions(174f, 79f), corner(11f),
                style = Stroke(1f * scale),
            )
            drawLine(
                border.copy(alpha = workspaceReveal), point(86f, 117f), point(260f, 117f),
                1f * scale,
            )
            drawCircle(brand.copy(alpha = workspaceReveal), 2f * scale, point(241f, 102f))
            drawRoundRect(
                terminal.copy(alpha = workspaceReveal),
                point(100f, 125f), dimensions(111f, 27f), corner(5f),
            )
            drawLine(
                brand.copy(alpha = workspaceReveal), point(109f, 133f), point(131f, 133f),
                2f * scale,
            )
            drawLine(
                Color.White.copy(alpha = 0.45f * workspaceReveal),
                point(109f, 141f), point(167f, 141f), 2f * scale,
            )
            val outputReveal = reveal(shownProgress, 0.42f, 0.75f)
            drawLine(
                success.copy(alpha = outputReveal), point(109f, 148f),
                point(109f + 66f * outputReveal, 148f), 2f * scale,
            )

            // A single outbound route resolves in the small phone, matching the Web scene.
            val destinationRoute = Path().apply {
                moveTo(point(260f, 133f).x, point(260f, 133f).y)
                cubicTo(
                    point(273f, 133f).x, point(273f, 133f).y,
                    point(277f, 113f).x, point(277f, 113f).y,
                    point(289f, 113f).x, point(289f, 113f).y,
                )
            }
            drawPath(destinationRoute, border.copy(alpha = phoneReveal), style = Stroke(1.2f * scale))
            val outbound = reveal(shownProgress, 0.64f, 0.92f)
            if (outbound > 0f && outbound < 1f) {
                val center = cubicPoint(
                    point(260f, 133f), point(273f, 133f), point(277f, 113f),
                    point(289f, 113f), outbound,
                )
                drawCircle(brand.copy(alpha = 0.18f), 6f * scale, center)
                drawCircle(brand, 2.6f * scale, center)
            }
            drawRoundRect(
                ink.copy(alpha = phoneReveal),
                point(289f, 84f), dimensions(48f, 79f), corner(10f),
            )
            drawRoundRect(
                surface.copy(alpha = phoneReveal),
                point(293f, 89f), dimensions(40f, 69f), corner(7f),
            )
            drawLine(
                border.copy(alpha = phoneReveal), point(305f, 96f), point(320f, 96f),
                1.4f * scale,
            )
            drawRoundRect(
                terminal.copy(alpha = phoneReveal),
                point(299f, 108f), dimensions(28f, 24f), corner(3f),
            )
            drawLine(
                brand.copy(alpha = phoneReveal), point(303f, 115f), point(315f, 115f),
                1.5f * scale,
            )
            drawLine(
                success.copy(alpha = phoneReveal), point(303f, 122f), point(319f, 122f),
                1.5f * scale,
            )
            drawCircle(success.copy(alpha = phoneReveal), 2.5f * scale, point(313f, 144f))
        }

        Box(
            modifier = Modifier
                .offset(x = (left + 100f * sceneScale).dp, y = (top + 92f * sceneScale).dp)
                .graphicsLayer { alpha = workspaceReveal },
        ) {
            WandBrandMark(size = (22f * sceneScale).roundToInt().coerceAtLeast(18))
        }
    }
}

private fun reveal(progress: Float, start: Float, end: Float): Float =
    ((progress - start) / (end - start)).coerceIn(0f, 1f)

private fun cubicPoint(a: Offset, b: Offset, c: Offset, d: Offset, time: Float): Offset {
    val inverse = 1f - time
    return Offset(
        inverse * inverse * inverse * a.x +
            3f * inverse * inverse * time * b.x +
            3f * inverse * time * time * c.x + time * time * time * d.x,
        inverse * inverse * inverse * a.y +
            3f * inverse * inverse * time * b.y +
            3f * inverse * time * time * c.y + time * time * time * d.y,
    )
}
