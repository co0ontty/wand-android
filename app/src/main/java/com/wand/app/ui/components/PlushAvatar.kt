package com.wand.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.isActive

/** Exact src/plush-avatar.ts v1 IDs and UTF-16 hash. A render never rewrites avatar data. */
object PlushAvatarIdentity {
    val shapes = listOf("heart", "triangle", "diamond", "round", "square", "capsule")
    val colors = listOf("coral", "sage", "lavender", "cream", "blue", "ochre")
    val glasses = listOf("none", "gold", "ink")
    val hats = listOf("none", "beanie", "beret")
    private fun hash(seed: String): UInt {
        var value = 0x811c9dc5u
        seed.forEach { value = (value xor it.code.toUInt()) * 0x01000193u }
        value = (value xor (value shr 16)) * 0x85ebca6bu
        value = (value xor (value shr 13)) * 0xc2b2ae35u
        return value xor (value shr 16)
    }
    fun asset(id: String?, name: String?, avatar: String?): String? {
        // Fixed system IDs never inherit an uploaded or stale legacy avatar.
        if (id == "e_wand_default") return "plush_cat_silver"
        if (id == "e_wand_ops") return "plush_cat_orange"
        val raw = avatar.orEmpty()
        if (raw.startsWith("data:image/") || Regex("^cat:[0-9]+$").matches(raw)) return null
        if (raw == "plush-cat:v1:silver") return "plush_cat_silver"
        if (raw == "plush-cat:v1:orange") return "plush_cat_orange"
        val fields = raw.split(":")
        val options = listOf(shapes, colors, glasses, hats)
        if (fields.size == 6 && fields[0] == "plush" && fields[1] == "v1" &&
            options.indices.all { fields[it + 2] in options[it] }) {
            return "plush_" + fields.drop(2).joinToString("_")
        }
        val identity = id?.trim()?.takeIf { it.isNotEmpty() }
            ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: "member"
        val keys = listOf("shape", "color", "glasses", "hat")
        return "plush_" + options.mapIndexed { index, choices ->
            choices[(hash("plush:v1:$identity:${keys[index]}") % choices.size.toUInt()).toInt()]
        }.joinToString("_")
    }
}

private val portraits = LruCache<String, ImageBitmap>(16)

@Composable
fun PlushAvatar(asset: String, size: Dp, floating: Boolean) {
    val context = LocalContext.current
    val bitmap = remember(asset, context) {
        portraits.get(asset) ?: context.assets.open("plush/$asset.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap().also { image -> portraits.put(asset, image) }
        }
    }
    val reduced = reduceMotionEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lift = remember { Animatable(0f) }
    LaunchedEffect(floating, reduced, lifecycle) {
        lift.snapTo(0f)
        if (floating && !reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    lift.animateTo(-1.5f, tween(1800, easing = FastOutSlowInEasing))
                    lift.animateTo(0f, tween(1800, easing = FastOutSlowInEasing))
                }
            } finally { lift.snapTo(0f) }
        }
    }
    val shadow = WandColors.textPrimary.copy(alpha = .16f)
    Image(bitmap, contentDescription = null, modifier = Modifier.size(size)
        .drawBehind {
            if (floating) withTransform({ scale(1f, .24f, Offset(this.size.width / 2, this.size.height * .88f)) }) {
                val center = Offset(this.size.width / 2, this.size.height * .88f)
                drawCircle(Brush.radialGradient(listOf(shadow, Color.Transparent), center, this.size.width * .38f),
                    radius = this.size.width * .38f, center = center)
            }
        }
        .graphicsLayer { translationY = if (reduced) 0f else lift.value * density })
}
