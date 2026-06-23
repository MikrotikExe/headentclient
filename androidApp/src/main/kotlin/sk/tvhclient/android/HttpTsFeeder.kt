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
 * M253 — premostí HTTP stream (dvrfile/<uuid>) do libVLC cez lokalny pipe.
 * Dovod: libVLC dostava URL s creds ako user:pass@host, co funguje len pre
 * plain/basic auth; digest-only server vrati 401 a archiv nehra. Tu stahuje
 * appka sama cez OkHttp + DigestAuthenticator (rovnako ako picony v M251),
 * takze digest aj basic su pokryte identicky ako curl --digest.
 *
 * Seek: pipe sa neda seekovat, takze pripadny start-offset riesime HTTP
 * Range hlavickou (Tvheadend dvrfile Range podporuje). `startByte` = od ktoreho
 * bajtu zacat (0 = od zaciatku).
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

    /** Kolko bajtov uz preteklo (pre pokracovanie in-progress cez Range). */
    @Volatile var bytesWritten: Long = startByte
        private set

    /** Spusti stahovanie a vrati read FileDescriptor pre Media(libVlc, fd). */
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
                    header("User-Agent", "HeadentClient")
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
                // zrusenie / zlomeny pipe / chyba spojenia
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
