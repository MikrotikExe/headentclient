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
 * Overlay nad videom, ktory vykresluje DVB titulky dekódované nami (DvbSubtitleDecoder).
 * libVLC sa titulkov vobec nedotyka — o zobrazeni rozhodujeme len my, takze nic nevypadne.
 *
 * Kazda stranka pride s cielovym casom (ms v osi prehravaca). Tiker cita aktualny cas
 * prehravaca a zobrazi najnovsiu stranku s targetMs <= teraz; prazdna stranka = skry.
 */
class SubtitleOverlayView(context: Context) : View(context) {

    private class Timed(
        val targetMs: Long, val timeoutMs: Int,
        val w: Int, val h: Int, val pixels: IntArray?, val empty: Boolean
    )

    private val queue = ArrayList<Timed>()   // zoradene podla targetMs
    private val lock = Any()
    private var clock: (() -> Long)? = null
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
            handler.postDelayed(this, 80)
        }
    }

    fun start(clockSource: () -> Long) {
        clock = clockSource
        if (!running) { running = true; handler.post(tick) }
    }

    fun stopTicker() { running = false; handler.removeCallbacks(tick) }

    /** Nova dekódovana stranka s cielovym casom (ms v osi prehravaca). */
    fun onPage(page: DvbSubtitleDecoder.DecodedPage, targetMs: Long) {
        val t = Timed(
            targetMs,
            if (page.timeoutMs in 1..30000) page.timeoutMs else 12000,
            page.width, page.height, page.pixels, page.isEmpty
        )
        synchronized(lock) {
            queue.add(t)
            queue.sortBy { it.targetMs }
            while (queue.size > 64) queue.removeAt(0)
        }
    }

    /** Vycisti stav (prepnutie kanala/jazyka, vypnutie titulkov). */
    fun reset() {
        synchronized(lock) { queue.clear() }
        current = null
        bitmap = null
        postInvalidate()
    }

    private fun update() {
        val now = clock?.invoke() ?: return
        var chosen: Timed? = null
        synchronized(lock) {
            for (t in queue) { if (t.targetMs <= now) chosen = t else break }
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
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, src, dst, paint)
    }
}
