package sk.tvhclient.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import sk.tvhclient.shared.htsp.DvbSubtitleDecoder

/**
 * An overlay above the video that renders DVB subtitles decoded by us (DvbSubtitleDecoder).
 * libVLC does not touch the subtitles at all — we alone decide about display, so nothing gets dropped.
 *
 * Every page arrives with a target time (ms on the player axis). A ticker reads the player's current
 * time and shows the newest page with targetMs <= now; an empty page = hide.
 */
class SubtitleOverlayView(context: Context) : View(context) {

    private class Timed(
        val targetMs: Long, val timeoutMs: Int,
        val w: Int, val h: Int, val pixels: IntArray?, val empty: Boolean
    )

    private val queue = ArrayList<Timed>()   // sorted by targetMs
    private val lock = Any()
    private var clock: (() -> Long)? = null
    private var aspect: (() -> Float)? = null
    private var current: Timed? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            handler.postDelayed(this, 40)
        }
    }

    fun start(clockSource: () -> Long, aspectSource: () -> Float) {
        clock = clockSource
        aspect = aspectSource
        if (!running) { running = true; handler.post(tick) }
    }

    fun stopTicker() { running = false; handler.removeCallbacks(tick) }

    /** A newly decoded page with a target time (ms on the player axis). */
    fun onPage(page: DvbSubtitleDecoder.DecodedPage, targetMs: Long) {
        val t = Timed(
            targetMs,
            if (page.timeoutMs in 1..30000) page.timeoutMs else 12000,
            page.width, page.height, page.pixels, page.isEmpty
        )
        synchronized(lock) {
            queue.add(t)
            queue.sortBy { it.targetMs }
            while (queue.size > 256) queue.removeAt(0)
        }
    }

    /** Clears the state (channel/language switch, subtitles turned off). */
    fun reset() {
        synchronized(lock) { queue.clear() }
        current = null
        bitmap = null
        postInvalidate()
    }

    private fun update() {
        // clock touches mediaPlayer.time; after the player is released getTime() throws
        // IllegalStateException ("can't get VLCObject instance") — it must not bring the app down.
        val now = runCatching { clock?.invoke() }.getOrNull() ?: return
        var chosen: Timed? = null
        synchronized(lock) {
            var idx = -1
            for (k in queue.indices) { if (queue[k].targetMs <= now) idx = k else break }
            if (idx >= 0) {
                chosen = queue[idx]
                repeat(idx) { queue.removeAt(0) }   // discard already past pages (chosen stays at the beginning)
            }
        }
        val c = chosen ?: return
        val expired = now > c.targetMs + c.timeoutMs
        if (c === current) {
            if (expired && bitmap != null) { bitmap = null; invalidate() }
            return
        }
        current = c
        bitmap = if (c.empty || c.pixels == null || expired) {
            null
        } else {
            src.set(0, 0, c.w, c.h)
            runCatching { Bitmap.createBitmap(c.pixels, c.w, c.h, Bitmap.Config.ARGB_8888) }.getOrNull()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        // The subtitle area corresponds to the whole video picture. The video is inserted into the view
        // preserving the aspect ratio (letterbox/pillarbox), so we map the subtitles onto
        // the same rectangle — otherwise vertically they would fall into the black bar.
        val va = (aspect?.invoke() ?: (16f / 9f)).let { if (it > 0f) it else 16f / 9f }
        val vw = width.toFloat()
        val vh = height.toFloat()
        var rw = vw
        var rh = vw / va
        if (rh > vh) { rh = vh; rw = vh * va }
        val ox = ((vw - rw) / 2f).toInt()
        val oy = ((vh - rh) / 2f).toInt()
        dst.set(ox, oy, ox + rw.toInt(), oy + rh.toInt())
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
