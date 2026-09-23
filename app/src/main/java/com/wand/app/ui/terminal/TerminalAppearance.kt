package com.wand.app.ui.terminal

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily

/**
 * Warm terminal surface aligned with the web PTY theme (`#17120f` / `#f4eee6`).
 * ANSI entries are ARGB so indexed colors stay readable on that brown background.
 */
internal object TerminalPalette {
    val backgroundArgb: Int = 0xFF17120F.toInt()
    val foregroundArgb: Int = 0xFFF4EEE6.toInt()
    val trayArgb: Int = 0xFF211A16.toInt()
    val capsuleArgb: Int = 0xFF2A221E.toInt()
    val keyArgb: Int = 0xFF352C27.toInt()
    val keyPressedArgb: Int = 0xFF463C36.toInt()
    val mutedArgb: Int = 0xFFB7A99A.toInt()
    val hairlineArgb: Int = 0x33F4EEE6.toInt()

    val ansi: IntArray = intArrayOf(
        0xFF2A2420.toInt(),
        0xFFE06A62.toInt(),
        0xFF8FBF7A.toInt(),
        0xFFE2B15A.toInt(),
        0xFF7AA2C4.toInt(),
        0xFFC58AD4.toInt(),
        0xFF6BB5B0.toInt(),
        0xFFD7CBBE.toInt(),
        0xFF6E6258.toInt(),
        0xFFF28C82.toInt(),
        0xFF91D39D.toInt(),
        0xFFF0C56A.toInt(),
        0xFF9EC0DC.toInt(),
        0xFFD7A4E4.toInt(),
        0xFF8ED0CB.toInt(),
        0xFFF4EEE6.toInt(),
    )
}

internal const val TERMINAL_FONT_SP = 14
internal const val TERMINAL_MIN_FONT_SP = 8
internal const val TERMINAL_MAX_FONT_SP = 28
internal const val TERMINAL_SCALE_MIN = 0.5f
internal const val TERMINAL_SCALE_MAX = 2f
internal const val TERMINAL_SCALE_STEP = 0.25f

/** Snap a terminal zoom onto the shared 50%–200% quarter-step range. */
internal fun normalizeTerminalScale(scale: Float): Float {
    val clamped = scale.coerceIn(TERMINAL_SCALE_MIN, TERMINAL_SCALE_MAX)
    return kotlin.math.round(clamped / TERMINAL_SCALE_STEP) * TERMINAL_SCALE_STEP
}

/** `delta == 0` restores 100%. Any other delta steps from the current zoom. */
internal fun stepTerminalScale(current: Float, delta: Float): Float =
    if (delta == 0f) 1f else normalizeTerminalScale(current + delta)

internal fun terminalFontSp(scale: Float): Int =
    (TERMINAL_FONT_SP * normalizeTerminalScale(scale)).toInt()
        .coerceIn(TERMINAL_MIN_FONT_SP, TERMINAL_MAX_FONT_SP)

private const val TERMINAL_FONT_ASSET = "fonts/JetBrainsMono-Regular.ttf"

/**
 * JetBrains Mono for Latin, with the system sans stack filling CJK.
 * The face is loaded from assets because "sans-serif-mono" is not an Android font family.
 */
internal fun terminalTypeface(assets: AssetManager): Typeface = try {
    val font = Font.Builder(assets, TERMINAL_FONT_ASSET).build()
    Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
        .setSystemFallback("sans-serif")
        .build()
} catch (_: Exception) {
    Typeface.MONOSPACE
}

/** Soft keyboard is the direct PTY target. The composer drawer takes the IME while it is open. */
internal fun terminalSoftKeyboardEnabled(
    ready: Boolean,
    composerOpen: Boolean,
    requested: Boolean,
): Boolean = ready && !composerOpen && requested

/**
 * Live PTY typing. Text still inside an IME composition stays on screen;
 * a finished commit is sent to the PTY and cleared from the field.
 */
internal data class DirectPtyInput(
    val text: String,
    val send: String?,
)

internal fun directPtyInput(nextText: String, composing: Boolean): DirectPtyInput = when {
    nextText.isEmpty() || composing -> DirectPtyInput(nextText, null)
    else -> DirectPtyInput("", nextText)
}
