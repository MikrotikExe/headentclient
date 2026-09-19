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
 * M346 / M626: AFR — automaticka obnovovacia frekvencia (vyclenene z PlayerActivity).
 *
 * Precita fps z video stopy (libVLC frameRateNum/Den) a vyberie rezim displeja
 * s rovnakym rozlisenim, ktoreho Hz je celociselnym nasobkom fps (25 fps -> 50 Hz;
 * 60 Hz sa odmietne, 60/25 = 2.4). Preferuje 2x nasobok, potom 1x. Predvolene
 * vypnute (AfrPref), len TV/box. Pri odchode sa preferencia vrati systemu ([clear]).
 *
 * Telefon/tablet (M348): Surface.setFrameRate — system sam rozhodne, ci panel
 * prepne (LTPO plynulo, bez resyncu). TV/box ide display-mode cestou; ak je
 * prepnutie do HDR vypnute (AfrHdrSwitchPref), len plynula ziadost o frekvenciu.
 *
 * [player] a [videoLayout] su lambdy, lebo prehravac sa v PlayerActivity
 * vytvara a rusi (M539 recreate) — controller nikdy nedrzi vlastny odkaz.
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
            // stopa este nie je pripravena — jeden odlozeny pokus
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
            return when (kr.toInt()) { 2 -> 3; 1 -> 2; else -> 1 }  // 2x (50 Hz pre 25 fps) > 1x > vyssie
        }
        val best = candidates.maxByOrNull { score(it) } ?: return
        if (score(best) == Int.MIN_VALUE) return
        if (best.modeId == cur.modeId) return
        // Prepnutie do HDR vypnute -> ziadne tvrde prepnutie rezimu (to vyvolava
        // HDMI re-sync a HDR flip firmwaru). Namiesto toho len plynula ziadost
        // o frekvenciu; system ju splni iba ak to panel zvladne bez re-syncu.
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
            // Pauza po zmene rezimu (M347, ako Kodi): pocas HDMI resyncu TV
            // nic neukazuje ani nehra — pauza zabrani stratenemu zaciatku
            // a audio desyncu. Po uplynuti sa prehravanie samo obnovi.
            val delaySec = AfrDelayPref.get(activity)
            if (delaySec > 0 && mp.isPlaying) {
                mp.pause()
                activity.window.decorView.postDelayed({
                    runCatching { player()?.play() }
                }, delaySec * 1000L)
            }
        }
    }

    /** Najde SurfaceView videa vo VLCVideoLayout (rekurzivne) — pre setFrameRate. */
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

    /** Vrati preferovany rezim displeja systemu (pri odchode z prehravaca). */
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
