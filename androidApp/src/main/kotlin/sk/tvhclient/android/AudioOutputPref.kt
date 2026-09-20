package sk.tvhclient.android

import android.content.Context

/**
 * libVLC audio output mode (handles drifting / delayed audio on
 * some boxes and TVs).
 *  - AUTO: detection (passthrough is used if the device supports it)
 *    CAUTION (M436): on Amlogic boxes (Strong) the passthrough path adds
 *    latency the app cannot see — the audio shifts by hundreds of ms. That is why it is
 *    NO LONGER the default.
 *  - STEREO: max 2 channels (compat mode)
 *  - PCM: the audio is decoded by the app (max 8 channels) — THE DEFAULT since 1.0.2 (M436):
 *    correct synchronisation everywhere, multichannel LPCM over HDMI still works;
 *    whoever wants a bitstream to an AVR switches to Passthrough/Auto.
 *  - PASSTHROUGH: "direct transfer" — the audio goes in its original format (AC3/EAC3/DTS)
 *    straight to the TV/AVR, which decodes it itself (aligns the sync if the box was adding latency)
 * Maps to MediaPlayer.setAudioOutputDevice(null/"stereo"/"pcm"/"encoded").
 * Stored globally in SharedPreferences.
 */
object AudioOutputPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_output_mode"

    const val AUTO = "auto"
    const val STEREO = "stereo"
    const val PCM = "pcm"
    const val PASSTHROUGH = "passthrough"

    val options = listOf(AUTO, PASSTHROUGH, PCM, STEREO)

    /** Value for MediaPlayer.setAudioOutputDevice; null = auto detection. */
    fun deviceId(context: Context): String? = when (get(context)) {
        STEREO -> "stereo"
        PCM -> "pcm"
        PASSTHROUGH -> "encoded"
        else -> null
    }

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, PCM) ?: PCM

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
