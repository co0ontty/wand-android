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
    /** 系统 HR 嘟嘟的柴犬造型：token 与资产名逐字对齐 Web `plush-dog:v1:shiba:red-scarf`。 */
    const val dogToken = "plush-dog:v1:shiba:red-scarf"
    const val dogAsset = "plush_dog_shiba_red"
    private fun hash(seed: String): UInt {
        var value = 0x811c9dc5u
        seed.forEach { value = (value xor it.code.toUInt()) * 0x01000193u }
        value = (value xor (value shr 16)) * 0x85ebca6bu
        value = (value xor (value shr 13)) * 0xc2b2ae35u
        return value xor (value shr 16)
    }
    fun asset(id: String?, name: String?, avatar: String?): String? {
        fixedAsset(id)?.let { return it }
        val raw = avatar.orEmpty()
        if (raw.startsWith("data:image/") || Regex("^cat:[0-9]+$").matches(raw)) return null
        return tokenAsset(raw) ?: defaultAsset(id, name)
    }

    /** 两个锁定系统身份的猫：改名、上传图或过期取值都不换脸（对齐 Web `fixedEmployeeAvatar`）。 */
    fun fixedAsset(id: String?): String? = when (id) {
        "e_wand_default" -> "plush_cat_silver"
        "e_wand_ops" -> "plush_cat_orange"
        else -> null
    }

    /** 显式毛绒 token → 资产名；柴犬、猫毛色和六段捏脸都在这一处认。 */
    fun tokenAsset(avatar: String?): String? {
        val raw = avatar.orEmpty()
        explicitAsset(raw)?.let { return it }
        val fields = raw.split(":")
        val options = listOf(shapes, colors, glasses, hats)
        if (fields.size != 6 || fields[0] != "plush" || fields[1] != "v1") return null
        if (!options.indices.all { fields[it + 2] in options[it] }) return null
        return "plush_" + fields.drop(2).joinToString("_")
    }

    /** 没有任何可认取值时按身份生成的默认毛绒（与 Web `defaultPlushAvatar` 同种子、同哈希）。 */
    fun defaultAsset(id: String?, name: String?): String {
        val identity = id?.trim()?.takeIf { it.isNotEmpty() }
            ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: "member"
        val keys = listOf("shape", "color", "glasses", "hat")
        val options = listOf(shapes, colors, glasses, hats)
        return "plush_" + options.mapIndexed { index, choices ->
            choices[(hash("plush:v1:$identity:${keys[index]}") % choices.size.toUInt()).toInt()]
        }.joinToString("_")
    }

    /** 显式毛绒 token（柴犬 / 猫毛色）对应的资产名；六段捏脸 token 由 [tokenAsset] 一起解析。 */
    private fun explicitAsset(raw: String): String? = when (raw) {
        dogToken -> dogAsset
        "plush-cat:v1:silver" -> "plush_cat_silver"
        "plush-cat:v1:orange" -> "plush_cat_orange"
        else -> null
    }

    /**
     * 身份的规范毛绒 token，格式与 Web `encodePlushDogAvatar` / `encodePlushCatAvatar` /
     * `encodePlushAvatar` 一致；上传图和显式旧猫不编码（返回 null，调用方保留原值）。
     */
    fun token(id: String?, name: String?, avatar: String?): String? = asset(id, name, avatar)?.let { portrait ->
        when {
            portrait == dogAsset -> dogToken
            portrait.startsWith("plush_cat_") -> "plush-cat:v1:${portrait.removePrefix("plush_cat_")}"
            else -> "plush:v1:" + portrait.removePrefix("plush_").split("_").joinToString(":")
        }
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
