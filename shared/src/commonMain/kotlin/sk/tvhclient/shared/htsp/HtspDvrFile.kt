package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.model.TvhServer

/**
 * M715: a recording read over HTSP (fileOpen / fileRead / fileClose, htsp_server.c) on the shared
 * connection. /dvrfile over HTTP needs the "Web streaming", "Advanced streaming" or "Video recorder"
 * right (webui.c page_dvrfile); an account with only the HTSP rights gets 403 there, although Kodi
 * plays its recordings — Kodi reads them over HTSP. The app does the same through DvrProxy.
 *
 * fileRead takes an offset, so every read is independent (Range requests of libVLC map onto it).
 */
class HtspDvrFile private constructor(
    private val session: HtspSession,
    private val fileId: Long,
    /** The file size when it was opened (an in-progress recording grows afterwards). */
    val size: Long
) {
    /** A slice of a reply buffer (no copy). */
    class Chunk(val data: ByteArray, val offset: Int, val length: Int)

    /** Reads up to [length] bytes from [offset]; null or an empty chunk = the end of the file. */
    suspend fun read(offset: Long, length: Int): Chunk? {
        val r = session.request("fileRead", mapOf(
            "id" to fileId, "offset" to offset, "size" to length.toLong()), timeoutMs = 30_000)
        (r["error"] as? String)?.let { throw IllegalStateException("HTSP fileRead: $it") }
        val b = r["data"] as? Htsmsg.Bin ?: return null
        return Chunk(b.data, b.offset, b.length)
    }

    private var closed = false

    /**
     * Closes the file. [watched] = it was read to the end — the play count goes up like after
     * /dvrfile; otherwise it is kept (a probe or a seek is not a play). HTSP servers older than
     * v27 ignore the flag and always count a play.
     */
    suspend fun close(watched: Boolean = false) {
        if (closed) return
        closed = true
        session.fileClosed()
        runCatching {
            session.request("fileClose", mapOf("id" to fileId,
                "playcount" to if (watched) PLAYCOUNT_INCR else PLAYCOUNT_KEEP), timeoutMs = 5_000)
        }
    }

    companion object {
        /** HTSP_DVR_PLAYCOUNT_KEEP (htsp_server.h) — fileClose would otherwise add a play. */
        private const val PLAYCOUNT_KEEP = 2147483646L
        /** HTSP_DVR_PLAYCOUNT_INCR. */
        private const val PLAYCOUNT_INCR = 2147483647L

        /**
         * Opens the recording [dvrId]: the HTSP numeric id, or the hex uuid used by /dvrfile (looked
         * up in the connection's recordings). Throws when it does not exist or the server refuses it.
         */
        suspend fun open(server: TvhServer, dvrId: String): HtspDvrFile {
            val s = HtspSessions.get(server)
            val numeric = dvrId.takeIf { it.length < 16 }?.toLongOrNull()
                ?: s.dvrIdForUuid(dvrId)
                ?: throw IllegalArgumentException("HTSP: unknown recording $dvrId")
            val r = s.request("fileOpen", mapOf("file" to "dvr/$numeric"))
            (r["error"] as? String)?.let { throw IllegalStateException("HTSP fileOpen: $it") }
            val id = r["id"] as? Long ?: throw IllegalStateException("HTSP fileOpen: no id")
            s.fileOpened()   // keeps the connection from the idle close while open
            return HtspDvrFile(s, id, (r["size"] as? Long) ?: 0L)
        }
    }
}
