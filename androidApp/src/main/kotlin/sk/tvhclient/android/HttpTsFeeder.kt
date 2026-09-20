package sk.tvhclient.android

import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.net.DigestAuthenticator
import java.io.FileDescriptor
import java.io.OutputStream

/**
 * M253 — bridges the HTTP stream (dvrfile/<uuid>) into libVLC through a local pipe.
 * Reason: libVLC gets the URL with creds as user:pass@host, which only works for
 * plain/basic auth; a digest-only server returns 401 and the archive does not play. Here the
 * app downloads it itself via OkHttp + DigestAuthenticator (the same as the picons in M251),
 * so digest and basic are both covered identically to curl --digest.
 *
 * Seek: a pipe cannot be seeked, so any start offset is handled with the HTTP
 * Range header (Tvheadend dvrfile supports Range). `startByte` = which byte to
 * start from (0 = from the beginning).
 */
class HttpTsFeeder(
    private val server: TvhServer,
    private val url: String,
    private val startByte: Long = 0L
) {

    private var job: Job? = null
    private var readPfd: ParcelFileDescriptor? = null
    private var writePfd: ParcelFileDescriptor? = null
    private var out: OutputStream? = null

    /** How many bytes have already flowed through (for continuing an in-progress one via Range). */
    @Volatile var bytesWritten: Long = startByte
        private set

    /** Total size of the file on the server (from Content-Range "/N" or Content-Length).
     *  With an in-progress recording it grows. 0 = not known yet. Used for the precise
     *  time->byte conversion when seeking (global average bitrate). */
    @Volatile var totalBytes: Long = 0L
        private set

    /** Starts the download and returns the read FileDescriptor for Media(libVlc, fd). */
    fun start(scope: CoroutineScope): FileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        readPfd = read
        writePfd = write
        val os = ParcelFileDescriptor.AutoCloseOutputStream(write)
        out = os

        val hasCreds = server.username.isNotEmpty()
        val preemptiveBasic: String? = if (hasCreds && server.authMode != "digest") {
            "Basic " + Base64.encodeToString(
                "${server.username}:${server.password}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        } else null

        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val r = chain.request().newBuilder().apply {
                    header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                    if (preemptiveBasic != null) header("Authorization", preemptiveBasic)
                }.build()
                chain.proceed(r)
            }
        if (hasCreds && server.authMode != "none") {
            builder.authenticator(DigestAuthenticator(server.username, server.password))
        }
        val ok = builder.build()

        val reqB = Request.Builder().url(url)
        if (startByte > 0) reqB.header("Range", "bytes=$startByte-")
        val req = reqB.build()

        job = scope.launch(Dispatchers.IO) {
            try {
                ok.newCall(req).execute().use { resp ->
                    // total file size: from Content-Range "bytes A-B/TOTAL", otherwise Content-Length
                    val cr = resp.header("Content-Range")
                    val total = cr?.substringAfter('/', "")?.toLongOrNull()
                        ?: resp.header("Content-Length")?.toLongOrNull()
                    if (total != null && total > 0) totalBytes = total
                    val body = resp.body ?: return@use
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        bytesWritten += n
                    }
                }
            } catch (_: Throwable) {
                // cancellation / broken pipe / connection error
            } finally {
                try { os.close() } catch (_: Throwable) {}
            }
        }
        return read.fileDescriptor
    }

    fun stop() {
        job?.cancel()
        job = null
        try { out?.close() } catch (_: Throwable) {}
        try { readPfd?.close() } catch (_: Throwable) {}
        try { writePfd?.close() } catch (_: Throwable) {}
        out = null
        readPfd = null
        writePfd = null
    }
}
