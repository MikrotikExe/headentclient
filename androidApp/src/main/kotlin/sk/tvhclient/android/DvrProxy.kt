package sk.tvhclient.android

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.runBlocking
import sk.tvhclient.shared.htsp.HtspDvrFile
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
    /** M715: tokens whose recordings are read over HTSP instead of /dvrfile. */
    private val htspTokens = ConcurrentHashMap.newKeySet<String>()
    /** M715: server key -> /dvrfile refused with 403 (true) or allowed (false). */
    private val htspFileNeeded = ConcurrentHashMap<String, Boolean>()
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
    fun urlFor(server: TvhServer, upstreamUrl: String, viaHtsp: Boolean = false): String? = runCatching {
        val base = server.baseUrl.trimEnd('/')
        val bare = MediaFactory.stripCreds(upstreamUrl)
        if (!bare.startsWith(base + "/")) return null
        if (viaHtsp && !bare.substring(base.length).startsWith("/dvrfile/")) return null
        val port = ensureStarted()
        // M715: a separate token for the HTSP mode, so one server can use both paths
        val token = tokens.getOrPut(if (viaHtsp) server.id + "|htsp" else server.id) { UUID.randomUUID().toString().replace("-", "") }
        targets[token] = server   // the latest server object (credentials may have been edited)
        if (viaHtsp) htspTokens.add(token)
        "http://127.0.0.1:$port/$token" + bare.substring(base.length)
    }.getOrNull()

    /**
     * M715: an HTSP server whose account may not use /dvrfile (403 — no "Web streaming",
     * "Advanced streaming" or "Video recorder" right, webui.c page_dvrfile). The recording is then
     * read over HTSP ([HtspDvrFile]) behind this proxy. One request of one byte, cached per account.
     * An unreachable HTTP port (only 9982 open) also means HTSP, but is not cached. Call off the main thread.
     */
    fun needsHtspFile(server: TvhServer, upstreamUrl: String): Boolean {
        if (server.connectionMode != "htsp") return false
        val key = server.id + "|" + server.username + "|" + server.password.hashCode()
        htspFileNeeded[key]?.let { return it }
        val code = try {
            val req = Request.Builder().url(MediaFactory.stripCreds(upstreamUrl)).header("Range", "bytes=0-0").build()
            client(server).newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(req).execute().use { it.code }
        } catch (_: java.io.IOException) {
            // the HTTP port cannot be reached (only 9982 open, as for Kodi) — /dvrfile would not
            // work either, HTSP does; remembered like a refusal
            -1
        } catch (_: Throwable) { return false }
        // 403 = logged in without the right; 401 after the authenticator = an anonymous account
        // without it (webui.c http_noaccess_code)
        val needed = code == 403 || code == 401 || code == -1
        // a network error is used for this start only (it may be a short drop), not remembered
        if (code == 403 || code == 401 || code == 200 || code == 206) htspFileNeeded[key] = needed
        return needed
    }

    /** M715: [container] of a recording read over HTSP. */
    fun containerHtsp(server: TvhServer, upstreamUrl: String): String? = runCatching {
        val id = MediaFactory.stripCreds(upstreamUrl).substringAfter("/dvrfile/", "").substringBefore('?')
        runBlocking {
            val f = HtspDvrFile.open(server, id)
            try {
                val bin = f.read(0, 189)
                if (bin == null) null else classify(bin.data, bin.offset, bin.length)
            } finally { f.close() }
        }
    }.getOrNull()

    private fun classify(b: ByteArray, o: Int, n: Int): String? {
        fun u(i: Int) = b[o + i].toInt() and 0xFF
        return when {
            n >= 4 && u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> MKV
            n >= 189 && u(0) == 0x47 && u(188) == 0x47 -> TS
            n > 0 -> OTHER
            else -> null
        }
    }

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
                    sk.tvhclient.shared.net.TvhCredEscape.basicPair(server.username, server.password)   /* M712 */.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } else null
            val b = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val r = chain.request().newBuilder().apply {
                        header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                        // no transparent gzip: it would drop Content-Length and break Range
                        header("Accept-Encoding", "identity")
                        if (preemptiveBasic != null && sk.tvhclient.shared.net.AuthSchemeMemo.basicAllowed(server.authMode, chain.request().url))   // M715
                            header("Authorization", preemptiveBasic)
                    }.build()
                    chain.proceed(r)
                }
            if (hasCreds && server.authMode != "none") {
                b.authenticator(DigestAuthenticator(server.username, server.password))
            }
            b.build()
        }
    }

    /**
     * M715: answers libVLC's request from the recording read over HTSP — a Range request becomes
     * fileRead calls from that offset (206 with Content-Range), without Range the whole file (200).
     */
    private fun serveHtsp(server: TvhServer, rest: String, method: String, range: String?,
                          out: java.io.OutputStream, simple: (Int, String) -> Unit) {
        val id = rest.removePrefix("/").removePrefix("dvrfile/").substringBefore('?')
        val f = try { runBlocking { HtspDvrFile.open(server, id) } } catch (_: Throwable) {
            simple(404, "Not Found"); return
        }
        var opened = f
        var watched = false
        try {
            val size = f.size
            var start = 0L
            var end = size - 1
            val partial = range != null && range.startsWith("bytes=")
            if (!partial && size <= 0L) {
                // a recording that has only just started: an empty but valid answer
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                return
            }
            if (partial) {
                val spec = range!!.removePrefix("bytes=").substringBefore(',').trim()
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-', "").trim()
                if (a.isEmpty()) {
                    start = (size - (b.toLongOrNull() ?: 0L)).coerceAtLeast(0L)   // "bytes=-N" = the last N
                } else {
                    start = a.toLongOrNull() ?: 0L
                    b.toLongOrNull()?.let { end = minOf(it, size - 1) }
                }
            }
            if (start >= size || end < start) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                out.flush()
                return
            }
            val len = end - start + 1
            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: application/octet-stream\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Length: ").append(len).append("\r\n")
            if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "GET") {
                var pos = start
                var file = f
                var reopened = false
                while (pos <= end) {
                    val chunk = minOf(256L * 1024, end - pos + 1).toInt()
                    val bin = try {
                        runBlocking { file.read(pos, chunk) }
                    } catch (e: Throwable) {
                        // the connection was replaced (network change) or the file handle is gone:
                        // fileRead takes an offset, so one fresh open continues from here
                        if (reopened) throw e
                        reopened = true
                        runCatching { runBlocking { file.close() } }
                        file = runBlocking { HtspDvrFile.open(server, id) }
                        opened = file
                        runBlocking { file.read(pos, chunk) }
                    } ?: break
                    if (bin.length <= 0) break
                    out.write(bin.data, bin.offset, bin.length)   // blocks while libVLC is not reading
                    pos += bin.length
                }
                // read to the very end of the recording = watched (like /dvrfile after the last part)
                watched = pos >= size && size > 0 && end == size - 1
            }
            out.flush()
        } finally {
            val w = watched
            runCatching { runBlocking { opened.close(watched = w) } }
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
        if (token in htspTokens) { serveHtsp(server, rest, method, range, out, ::simple); return }   // M715
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
