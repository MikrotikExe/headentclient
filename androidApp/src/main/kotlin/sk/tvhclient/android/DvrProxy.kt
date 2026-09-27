package sk.tvhclient.android

import android.os.SystemClock
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.net.DigestAuthenticator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M701 (issue #19): a local HTTP proxy for recordings on digest-only servers.
 *
 * libVLC cannot authenticate with HTTP Digest through the URL, and digest-only is Tvheadend's
 * default. Until now such recordings went through HttpTsFeeder: the app downloaded the file and
 * fed libVLC through a pipe. A pipe cannot seek, so a seek restarted the download at an estimated
 * byte offset. That works for MPEG-TS (the demuxer resyncs on any 188-byte packet), but a Matroska
 * or MP4 recording started in the middle has no header and no index — playback stopped after a
 * forward seek, while the "pass" (TS) profile worked. Exactly what issue #19 describes.
 *
 * Now libVLC gets http://127.0.0.1:<port>/<token>/dvrfile/<id>: it talks plain HTTP to this proxy
 * with its own Range requests (so every container seeks natively, the same path as on a basic-auth
 * server), and the proxy forwards each request to Tvheadend over OkHttp, which does the digest
 * handshake. Only 127.0.0.1 is bound and every path must start with a random per-server token, so
 * other apps on the device cannot use it to reach the server with the user's credentials.
 */
object DvrProxy {
    private var socket: ServerSocket? = null
    private val targets = ConcurrentHashMap<String, TvhServer>()        // token -> server
    private val tokens = ConcurrentHashMap<String, String>()            // server id -> token
    private val clients = ConcurrentHashMap<String, OkHttpClient>()     // server key -> client
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "HeadentClient:dvrProxy").apply { isDaemon = true }
    }

    /** When Tvheadend last refused a proxied request because of the connection limit (elapsedRealtime). */
    @Volatile var lastConnLimitAt = 0L
        private set

    @Synchronized
    private fun ensureStarted(): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        socket = s
        Thread({ acceptLoop(s) }, "HeadentClient:dvrProxyAccept").apply { isDaemon = true }.start()
        return s.localPort
    }

    /** True for a URL this proxy produced. */
    fun isProxyUrl(url: String?): Boolean {
        val s = socket ?: return false
        return url != null && url.startsWith("http://127.0.0.1:${s.localPort}/")
    }

    /**
     * The proxied URL for a recording URL of [server] (creds in the URL are ignored — the proxy
     * authenticates itself). null = cannot be proxied (the caller keeps the old feeder path).
     * Call off the main thread (binds a socket the first time).
     */
    fun urlFor(server: TvhServer, upstreamUrl: String): String? = runCatching {
        val base = server.baseUrl.trimEnd('/')
        val bare = MediaFactory.stripCreds(upstreamUrl)
        if (!bare.startsWith(base + "/")) return null
        val port = ensureStarted()
        val token = tokens.getOrPut(server.id) { UUID.randomUUID().toString().replace("-", "") }
        targets[token] = server   // the latest server object (credentials may have been edited)
        "http://127.0.0.1:$port/$token" + bare.substring(base.length)
    }.getOrNull()

    /** M706: the container of a recording, from its first bytes ([container]). */
    const val MKV = "mkv"
    const val TS = "ts"
    const val OTHER = "other"

    /**
     * M703 / M706: the container of the recording — [MKV] (EBML magic 1A 45 DF A3), [TS] (sync byte
     * 0x47 at 0 and at 188) or [OTHER]. Reads the first 189 bytes with the server's own
     * authentication. null = could not tell (network, rights) — the caller keeps the defaults.
     * Call off the main thread.
     */
    fun container(server: TvhServer, upstreamUrl: String): String? = runCatching {
        val req = Request.Builder().url(MediaFactory.stripCreds(upstreamUrl)).header("Range", "bytes=0-188").build()
        client(server).newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) return@use null
            val b = ByteArray(189)
            val src = resp.body?.byteStream() ?: return@use null
            var n = 0
            while (n < b.size) { val r = src.read(b, n, b.size - n); if (r < 0) break; n += r }
            fun u(i: Int) = b[i].toInt() and 0xFF
            when {
                n >= 4 && u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> MKV
                n >= 189 && u(0) == 0x47 && u(188) == 0x47 -> TS
                n > 0 -> OTHER
                else -> null
            }
        }
    }.getOrNull()

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: Throwable) { break }
            pool.execute { runCatching { handle(c) }; runCatching { c.close() } }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun client(server: TvhServer): OkHttpClient {
        val key = server.id + "|" + server.username + "|" + server.password.hashCode() + "|" + server.authMode
        return clients.getOrPut(key) {
            val hasCreds = server.username.isNotEmpty()
            // the same authentication as HttpTsFeeder (M253): preemptive basic unless the server is
            // set to digest, and a digest authenticator for the 401 challenge
            val preemptiveBasic: String? = if (hasCreds && server.authMode != "digest") {
                "Basic " + Base64.encodeToString(
                    "${server.username}:${server.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } else null
            val b = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val r = chain.request().newBuilder().apply {
                        header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                        // no transparent gzip: it would drop Content-Length and break Range
                        header("Accept-Encoding", "identity")
                        if (preemptiveBasic != null) header("Authorization", preemptiveBasic)
                    }.build()
                    chain.proceed(r)
                }
            if (hasCreds && server.authMode != "none") {
                b.authenticator(DigestAuthenticator(server.username, server.password))
            }
            b.build()
        }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        sock.tcpNoDelay = true
        val input = BufferedInputStream(sock.getInputStream())
        val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        fun simple(code: Int, msg: String) {
            out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val reqLine = readLine(input) ?: return
        val parts = reqLine.split(' ')
        if (parts.size < 2) { simple(400, "Bad Request"); return }
        val method = parts[0].uppercase()
        val target = parts[1]
        var range: String? = null
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("Range", ignoreCase = true)) range = h.substring(i + 1).trim()
        }
        if (method != "GET" && method != "HEAD") { simple(405, "Method Not Allowed"); return }
        val path = target.removePrefix("/")
        val token = path.substringBefore('/')
        val server = targets[token] ?: run { simple(404, "Not Found"); return }
        val rest = "/" + path.substringAfter('/', "")
        val url = server.baseUrl.trimEnd('/') + rest

        val req = Request.Builder().url(url).apply {
            if (method == "HEAD") head() else get()
            if (range != null) header("Range", range!!)
        }.build()
        val sent = SystemClock.elapsedRealtime()
        client(server).newCall(req).execute().use { resp ->
            // M694: a late 405 = the account's connection limit; the player reports it
            if (HttpTsFeeder.isConnLimitResponse(resp.code, SystemClock.elapsedRealtime() - sent)) {
                lastConnLimitAt = SystemClock.elapsedRealtime()
            }
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(resp.code).append(' ').append(resp.message.ifBlank { "OK" }).append("\r\n")
            for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges",
                                "Last-Modified", "ETag", "Content-Disposition")) {
                resp.header(name)?.let { sb.append(name).append(": ").append(it).append("\r\n") }
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "GET") {
                val src = resp.body?.byteStream()
                if (src != null) {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)   // blocks while libVLC is not reading (back pressure)
                    }
                }
            }
            out.flush()
        }
    }
}
