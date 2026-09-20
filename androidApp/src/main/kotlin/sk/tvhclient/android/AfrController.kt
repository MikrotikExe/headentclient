package sk.tvhclient.android

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import org.videolan.libvlc.MediaPlayer

/**
 * M346 / M626: AFR — automatic refresh rate (split out of PlayerActivity).
 *
 * Reads fps from the video track (libVLC frameRateNum/Den) and picks a display mode
 * with the same resolution whose Hz is an integer multiple of the fps (25 fps -> 50 Hz;
 * 60 Hz is rejected, 60/25 = 2.4). Prefers the 2x multiple, then 1x. Off by
 * default (AfrPref), TV/box only. On exit the preference is returned to the system ([clear]).
 *
 * Phone/tablet (M348): Surface.setFrameRate — the system itself decides whether the panel
 * switches (LTPO smoothly, without a resync). TV/box goes the display-mode way; if
 * switching to HDR is disabled (AfrHdrSwitchPref), only a smooth frame-rate request.
 *
 * [player] and [videoLayout] are lambdas, because the player is created and destroyed
 * in PlayerActivity (M539 recreate) — the controller never holds its own reference.
 */
class AfrController(
    private val activity: Activity,
    private val isTvBox: Boolean,
    private val player: () -> MediaPlayer?,
    private val videoLayout: () -> View?
) {
    private var retryPosted = false

    fun apply() {
        if (Build.VERSION.SDK_INT < 23) return
        if (!AfrPref.get(activity)) return
        val mp = player() ?: return
        val vt = runCatching { mp.currentVideoTrack }.getOrNull()
        val num = vt?.frameRateNum ?: 0
        val den = vt?.frameRateDen ?: 0
        if (num <= 0 || den <= 0) {
            // the track is not ready yet — one deferred attempt
            if (!retryPosted) {
                retryPosted = true
                activity.window.decorView.postDelayed({ retryPosted = false; apply() }, 900L)
            }
            return
        }
        val fps = num.toFloat() / den
        if (fps < 10f) return
        if (!isTvBox) {
            if (Build.VERSION.SDK_INT >= 30) {
                findVideoSurface()?.let { surf ->
                    runCatching { surf.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
                }
            }
            return
        }
        val disp = if (Build.VERSION.SDK_INT >= 30) activity.display
            else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        val cur = disp?.mode ?: return
        val candidates = disp.supportedModes.filter {
            it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
        }
        fun score(m: Display.Mode): Int {
            val k = m.refreshRate / fps
            val kr = kotlin.math.round(k)
            if (kr < 1f || kotlin.math.abs(k - kr) > 0.02f * kr) return Int.MIN_VALUE
            return when (kr.toInt()) { 2 -> 3; 1 -> 2; else -> 1 }  // 2x (50 Hz for 25 fps) > 1x > higher
        }
        val best = candidates.maxByOrNull { score(it) } ?: return
        if (score(best) == Int.MIN_VALUE) return
        if (best.modeId == cur.modeId) return
        // Switching to HDR is disabled -> no hard mode switch (that triggers an
        // HDMI re-sync and an HDR flip in the firmware). Instead only a smooth request
        // for the frame rate; the system grants it only if the panel can do it without a re-sync.
        if (!AfrHdrSwitchPref.get(activity)) {
            if (Build.VERSION.SDK_INT >= 31) {
                findVideoSurface()?.let { surf ->
                    runCatching {
                        surf.setFrameRate(
                            fps,
                            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        )
                    }
                }
            }
            return
        }
        runCatching {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = best.modeId
            activity.window.attributes = lp
            // Pause after a mode change (M347, like Kodi): during the HDMI resync the TV
            // shows nothing and plays nothing — the pause prevents a lost start
            // and audio desync. Once it elapses playback resumes by itself.
            val delaySec = AfrDelayPref.get(activity)
            if (delaySec > 0 && mp.isPlaying) {
                mp.pause()
                activity.window.decorView.postDelayed({
                    runCatching { player()?.play() }
                }, delaySec * 1000L)
            }
        }
    }

    /** Finds the video SurfaceView inside VLCVideoLayout (recursively) — for setFrameRate. */
    private fun findVideoSurface(): Surface? {
        fun find(v: View): SurfaceView? {
            if (v is SurfaceView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        val layout = videoLayout() ?: return null
        val sv = find(layout) ?: return null
        val surf = sv.holder.surface
        return if (surf != null && surf.isValid) surf else null
    }

    /** Returns the preferred display mode to the system (when leaving the player). */
    fun clear() {
        if (Build.VERSION.SDK_INT < 23) return
        runCatching {
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                activity.window.attributes = lp
            }
        }
    }
}
