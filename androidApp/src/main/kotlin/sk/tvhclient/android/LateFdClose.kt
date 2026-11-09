package sk.tvhclient.android

import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M759 (audit H5): the read end of a feeder pipe is closed a few seconds AFTER the feeder stops.
 *
 * libVLC gets the pipe as a bare descriptor number ("fd://N") and duplicates it only when its input
 * thread opens the medium (access/file.c: vlc_dup) — a moment after play(). When the feeder was
 * stopped before that (fast zapping), the number was free again and the NEXT pipe could get it:
 * the old input then opened the new channel's pipe and took the first bytes (PAT/PMT) away from the
 * new one. The write end is still closed at once (libVLC sees the end of the stream); only the
 * number stays reserved for a while.
 */
internal object LateFdClose {
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HeadentClient:fdClose").apply { isDaemon = true }
    }

    fun close(pfd: ParcelFileDescriptor) {
        val scheduled = runCatching {
            exec.schedule({ runCatching { pfd.close() } }, 3, TimeUnit.SECONDS)
        }.isSuccess
        if (!scheduled) runCatching { pfd.close() }
    }
}
