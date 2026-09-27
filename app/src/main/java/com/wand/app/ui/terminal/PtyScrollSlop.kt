package com.wand.app.ui.terminal

import androidx.annotation.Keep

/**
 * Scroll-vs-selection distance for the patched termlib 0.0.10 gesture loop.
 *
 * The stock loop compares each motion event's delta with the touch slop. A slow
 * scroll never moves far enough in one frame, so the long-press timer wins and
 * the drag becomes a text selection. The bytecode patch records the finger-down
 * point with [noteDown] and asks [displacementSquared] for the distance from
 * that point. Method names and descriptors are the patcher's contract.
 */
@Keep
internal object PtyScrollSlop {
    private var anchorOwner: Any? = null
    private var anchorX = 0f
    private var anchorY = 0f

    @JvmStatic
    fun noteDown(owner: Any, packedPosition: Long) {
        anchorOwner = owner
        anchorX = unpackOffsetX(packedPosition)
        anchorY = unpackOffsetY(packedPosition)
    }

    @JvmStatic
    fun displacementSquared(owner: Any, packedPosition: Long): Float {
        if (anchorOwner !== owner) return Float.MAX_VALUE
        val dx = unpackOffsetX(packedPosition) - anchorX
        val dy = unpackOffsetY(packedPosition) - anchorY
        return dx * dx + dy * dy
    }
}

/** Compose packs an Offset as x in the high 32 bits and y in the low 32 bits. */
internal fun unpackOffsetX(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())

internal fun unpackOffsetY(packed: Long): Float = Float.fromBits(packed.toInt())
