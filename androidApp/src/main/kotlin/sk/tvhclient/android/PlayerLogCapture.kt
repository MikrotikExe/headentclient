package sk.tvhclient.android

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * M730 (issues #25, #37, #38): the verbose player log lands in a FILE the user can send.
 *
 * "Verbose player log" (M448) only switched libVLC to `-vv`, which writes into logcat — a user
 * without adb had nothing to send with the diagnostic log. While the setting is on and the
 * player is open, this reads the app's own logcat lines of libVLC (tag "VLC"; an app may read its
 * own process' log without a permission) and appends them to filesDir/diag/player.log. The
 * file is capped at [MAX_BYTES]: when it grows past that, the older half is dropped. "Send the
 * log" attaches it next to crash.log; "Clear" removes it too.
 */
object PlayerLogCapture {
    private const val DIR = "diag"
    private const val FILE = "player.log"
    private const val MAX_BYTES = 2 * 1024 * 1024

    @Volatile private var proc: Process? = null
    @Volatile private var worker: Thread? = null

    fun logFile(context: Context): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, FILE)
    }

    /** Starts reading when the setting is on (no-op otherwise or when already running). */
    @Synchronized
    fun start(context: Context) {
        if (!VlcVerbosePref.get(context) || worker?.isAlive == true) return
        val app = context.applicationContext
        val f = logFile(app)
        val t = Thread({
            try {
                // -T 1: from now on, not the whole ring buffer; only libVLC's tag
                val p = ProcessBuilder("logcat", "-v", "time", "-T", "1", "VLC:V", "*:S")
                    .redirectErrorStream(true).start()
                proc = p
                runCatching { f.appendText("\n[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] player log capture started\n") }
                val r = BufferedReader(InputStreamReader(p.inputStream), 16 * 1024)
                val buf = StringBuilder()
                var lastFlush = System.currentTimeMillis()
                while (!Thread.currentThread().isInterrupted) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("---")) continue   // "--------- beginning of main"
                    buf.append(line).append('\n')
                    val now = System.currentTimeMillis()
                    if (buf.length > 8 * 1024 || now - lastFlush > 2000) {
                        flush(f, buf); lastFlush = now
                    }
                }
                flush(f, buf)
            } catch (_: Throwable) {
            } finally {
                runCatching { proc?.destroy() }; proc = null
            }
        }, "HeadentClient:vlcLog").apply { isDaemon = true }
        worker = t
        t.start()
    }

    @Synchronized
    fun stop() {
        runCatching { proc?.destroy() }
        proc = null
        worker?.interrupt()
        worker = null
    }

    private fun flush(f: File, buf: StringBuilder) {
        if (buf.isEmpty()) return
        runCatching {
            if (f.exists() && f.length() > MAX_BYTES) {
                // keep the newer half
                val text = f.readText()
                f.writeText("[log rotated]\n" + text.substring(text.length / 2))
            }
            f.appendText(buf.toString())
        }
        buf.setLength(0)
    }

    fun exists(context: Context): Boolean = logFile(context).let { it.exists() && it.length() > 0 }

    fun clear(context: Context) { runCatching { logFile(context).delete() } }
}
