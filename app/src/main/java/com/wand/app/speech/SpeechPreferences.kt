package com.wand.app.speech

import android.content.Context

enum class SpeechMode { LOCAL, SERVER }

object SpeechPreferences {
    private const val PREFS = "wand.speech"
    fun mode(context: Context): SpeechMode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", "local") == "server") SpeechMode.SERVER else SpeechMode.LOCAL
    fun select(context: Context, mode: SpeechMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", if (mode == SpeechMode.SERVER) "server" else "local").apply()
    }
}
