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

    /** M694: the server refused the stream because of the account's connection limit (see
     *  [isConnLimitResponse]). Set before the pipe is closed, so the player sees it when libVLC
     *  reports the end of the stream. */
    @Volatile var connLimited: Boolean = false
        private set

    /**
     * M713: a copy of every received chunk (on the download thread, after it went to libVLC).
     * Live teletext in HTTP mode reads the teletext PID from here instead of opening a second
     * connection (which an account with a connection limit of 1 does not get). An exception in the
     * callback only switches the tap off, the stream goes on.
     */
    @Volatile var onData: ((ByteArray, Int, Int) -> Unit)? = null

    /**
     * M714: the server did not start this live stream — no free tuner, the source is not
     * broadcasting, or the subscription could not be created. Tvheadend then ends the request
     * without any HTTP response (webui.c http_stream_run: SMT_NOSTART before SMT_START, headers
     * are only sent on SMT_START) or answers 503. Set before the pipe is closed, like [connLimited].
     */
    @Volatile var noStart: Boolean = false
        private set

    /** M714: a live channel (/stream/...), not a recording file. */
    private val isLive: Boolean = url.contains("/stream/")

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
                sk.tvhclient.shared.net.TvhCredEscape.basicPair(server.username, server.password)   /* M712 */.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        } else null

        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val r = chain.request().newBuilder().apply {
                    header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                    if (preemptiveBasic != null && sk.tvhclient.shared.net.AuthSchemeMemo.basicAllowed(server.authMode, chain.request().url))   // M715
                        header("Authorization", preemptiveBasic)
                }.build()
                chain.proceed(r)
            }
        if (hasCreds && server.authMode != "none") {
            builder.authenticator(DigestAuthenticator(server.username, server.password))
        }
        // M714: an empty answer (see [noStart]) must not be retried silently by OkHttp
        if (isLive) builder.retryOnConnectionFailure(false)
        val ok = builder.build()

        val reqB = Request.Builder().url(url)
        if (startByte > 0) reqB.header("Range", "bytes=$startByte-")
        // M714: without it Tvheadend keeps the connection open after an empty answer and waits for
        // the next request (keep-alive) — the client would only give up after its read timeout
        if (isLive) reqB.header("Connection", "close")
        val req = reqB.build()

        job = scope.launch(Dispatchers.IO) {
            var gotResponse = false   // M714
            try {
                val sentAt = android.os.SystemClock.elapsedRealtime()
                ok.newCall(req).execute().use { resp ->
                    gotResponse = true
                    if (isLive && resp.code == 503) {
                        noStart = true   // M714: the subscription could not be created
                        return@use
                    }
                    if (isConnLimitResponse(resp.code, android.os.SystemClock.elapsedRealtime() - sentAt)) {
                        connLimited = true   // M694: do not pass the error page to libVLC
                        return@use
                    }
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
                        onData?.let { cb -> try { cb(buf, 0, n) } catch (_: Throwable) { onData = null } }   // M713
                    }
                }
            } catch (e: Throwable) {
                // cancellation / broken pipe / connection error
                if (isLive && !gotResponse && isNoStartFailure(e)) noStart = true   // M714
            } finally {
                try { os.close() } catch (_: Throwable) {}
            }
        }
        return read.fileDescriptor
    }

    companion object {
        /**
         * M694: Tvheadend's answer to a stream request over the account's connection limit.
         * tcp_connection_launch() holds the request for 5 s (waiting for another connection of
         * the account to end) and then the stream handler returns 405. A 405 that comes
         * immediately is a different refusal (the subscription could not be created), so only
         * a late one counts — otherwise the app would blame the limit for an unrelated error.
         */
        fun isConnLimitResponse(code: Int, elapsedMs: Long): Boolean = code == 405 && elapsedMs >= 4000L

        /**
         * M714: the request reached Tvheadend but no response came: the connection was closed
         * before the status line (OkHttp "unexpected end of stream", EOFException), or nothing came
         * within the read timeout (a profile with "Restart on error" keeps waiting for a tuner).
         * A refused or failed connect (server down) is not this case.
         */
        fun isNoStartFailure(e: Throwable): Boolean {
            if (e !is java.io.IOException) return false
            if (e is java.net.SocketTimeoutException) return e.message?.contains("connect", ignoreCase = true) != true
            return e.message?.startsWith("unexpected end of stream") == true || e.cause is java.io.EOFException
        }
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
