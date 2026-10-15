package sk.tvhclient.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for the full player (PlayerActivity), following the same rules as RadioPlayerService.
 *
 * Without it another media app (a music player, a car's own radio app) kept playing over the
 * TV/radio stream. Used on Android Automotive OS only (see PlayerActivity), phone/TV are unchanged.
 * [onPlaying] takes the focus when playback really starts (libVLC Playing event); [onPaused] gives
 * it back when playback stops for any other reason than a transient loss, so the source that was
 * playing before can continue (e.g. during the drive); [release] gives it back when the player closes.
 * A permanent loss (another app started playing) pauses us; a transient one (a call, a voice
 * assistant) pauses and resumes when the focus comes back, but only via [canResume]: while the player
 * is covered (driving) the resume is handed to [deferResume] instead. If the system makes us wait
 * (delayed gain, e.g. during a call) playback pauses until the focus is granted; pressing Play meanwhile
 * pauses again. Ducking (a navigation prompt) is left to the system: pausing would restart a live
 * channel without timeshift after every prompt.
 */
internal class PlayerAudioFocus(
    context: Context,
    private val isPlaying: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val canResume: () -> Boolean,
    private val deferResume: () -> Unit
) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /** A focus request is active (granted or delayed). */
    private var held = false
    private var request: AudioFocusRequest? = null
    /** Paused by a transient loss or a delayed request: resume when the focus comes back. */
    private var pausedTransient = false
    /** The request was delayed and the focus is not granted yet: nothing may play until AUDIOFOCUS_GAIN. */
    private var delayed = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedTransient = false
                if (isPlaying()) pause()
                abandon()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // set first: the pause reaches onPaused (libVLC Paused event), which must keep the focus
                if (isPlaying()) { pausedTransient = true; pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                delayed = false
                if (pausedTransient) {
                    pausedTransient = false
                    if (canResume()) resume() else deferResume()
                }
            }
        }
    }

    fun onPlaying() {
        // still waiting for a delayed grant (e.g. a call): pause again, AUDIOFOCUS_GAIN resumes
        if (delayed) { pause(); return }
        pausedTransient = false
        if (held) return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()
                .also { request = it }
            when (am.requestAudioFocus(req)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> held = true
                // e.g. during a call: wait for AUDIOFOCUS_GAIN instead of playing over it
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { held = true; delayed = true; pausedTransient = true; pause() }
                else -> pause()
            }
        } else {
            @Suppress("DEPRECATION")
            val granted = am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) held = true else pause()
        }
    }

    /** Playback stopped (user pause, driving, end of stream): give the focus back unless we wait for it. */
    fun onPaused() {
        if (!pausedTransient) abandon()
    }

    fun release() {
        pausedTransient = false
        abandon()
    }

    private fun abandon() {
        if (!held) return
        held = false
        delayed = false
        if (Build.VERSION.SDK_INT >= 26) {
            request?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(listener)
        }
    }
}
