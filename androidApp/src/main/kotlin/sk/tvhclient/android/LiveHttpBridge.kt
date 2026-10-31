package sk.tvhclient.android

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * M754 (performance audit P3): the HTSP live stream handed to libVLC over a loopback HTTP
 * connection instead of a pipe.
 *
 * Through `fd://` libVLC treats the remuxed stream as a FILE it can read at its own pace: no
 * network caching, no clock correction when data arrive late — every hiccup of the network or the
 * server ends in dropped frames (the reason for the PCR work-arounds in TsMuxer). Fetched from
 * `http://127.0.0.1:<port>/live/<token>` it is a live network stream, exactly like the HTTP path:
 * the network buffer from the settings applies and libVLC rebuffers instead of stuttering.
 *
 * One token = one stream = one libVLC connection. The feeder puts TS blocks into the token's
 * queue; the handler of the GET writes them out (back pressure through the socket, like the pipe
 * had). When libVLC drops the connection, the feeder sees the end (like a broken pipe) and the
 * player's usual reconnect logic takes over. Only 127.0.0.1 is bound and the token is random.
 */
object LiveHttpBridge {
    /** The end-of-stream marker (an empty block). */
    private val END = ByteArray(0)

    class Source internal constructor(val token: String) {
        internal val queue = LinkedBlockingQueue<ByteArray>(256)
        @Volatile internal var closed = false
        @Volatile internal var connected = false

        /** Enqueues a TS block; false when the consumer did not drain it in time (10 s). */
        fun offer(bytes: ByteArray): Boolean = !closed && queue.offer(bytes, 10, TimeUnit.SECONDS)

        /** Ends the stream: the handler writes what is queued and closes the connection. */
        fun close() {
            if (closed) return
            closed = true
            queue.offer(END)
            sources.remove(token, this)
        }
    }

    private var socket: ServerSocket? = null
    private val sources = ConcurrentHashMap<String, Source>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "HeadentClient:liveBridge").apply { isDaemon = true }
    }

    @Synchronized
    private fun ensureStarted(): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        socket = s
        Thread({ acceptLoop(s) }, "HeadentClient:liveBridgeAccept").apply { isDaemon = true }.start()
        return s.localPort
    }

    /** Starts the server on a background thread ahead of the first stream (binding is quick, but
     *  this keeps it off the main thread; [open] binds synchronously if nobody warmed it up). */
    fun warmUp() {
        if (socket?.isClosed == false) return
        Thread({ runCatching { ensureStarted() } }, "HeadentClient:liveBridgeWarmUp").apply { isDaemon = true }.start()
    }

    /** Registers a new stream and returns it with the URL libVLC opens. */
    fun open(): Pair<Source, String> {
        val port = ensureStarted()
        val token = UUID.randomUUID().toString().replace("-", "")
        val src = Source(token)
        sources[token] = src
        return src to "http://127.0.0.1:$port/live/$token"
    }

    /** True for a URL this bridge produced. */
    fun isBridgeUrl(url: String?): Boolean {
        val s = socket ?: return false
        return url != null && url.startsWith("http://127.0.0.1:${s.localPort}/live/")
    }

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
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
        }
        if (method != "GET" && method != "HEAD") { simple(405, "Method Not Allowed"); return }
        val token = parts[1].removePrefix("/").removePrefix("live/").substringBefore('?').substringBefore('/')
        val src = sources[token] ?: run { simple(404, "Not Found"); return }
        if (src.connected) { simple(409, "Conflict"); return }   // one consumer per stream
        // no Content-Length and no Accept-Ranges: a live stream until the connection closes
        // (the same answer Tvheadend gives for /stream/channel)
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return
        src.connected = true
        // the socket write blocks while libVLC is not reading — the queue then fills and the feeder
        // waits (back pressure), exactly as with the pipe
        sock.soTimeout = 0
        try {
            while (true) {
                val b = src.queue.poll(15, TimeUnit.SECONDS)
                if (b == null) { if (src.closed) break else continue }
                if (b.isEmpty()) break
                out.write(b)
                if (src.queue.isEmpty()) out.flush()
            }
            out.flush()
        } finally {
            src.closed = true
            sources.remove(token, src)
        }
    }
}
