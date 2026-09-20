package sk.tvhclient.android

import android.content.Context

/**
 * libVLC audio output module (phones). With drifting audio that grows
 * over time (drift), on some phones it helps to switch from the default
 * AudioTrack to OpenSL ES — that one has different, often more accurate latency handling, so
 * the audio does not lag behind the picture.
 *  - AUTO: android_audiotrack (default)
 *  - OPENSLES: opensles
 * Maps to MediaPlayer.setAudioOutput. Stored globally in SharedPreferences.
 */
object AudioModulePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_output_module"

    const val AUTO = "auto"
    const val OPENSLES = "opensles"

    val options = listOf(AUTO, OPENSLES)

    /** aout module for setAudioOutput; null = leave the default (AudioTrack). */
    fun module(context: Context): String? =
        if (get(context) == OPENSLES) "opensles" else null

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO) ?: AUTO

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
