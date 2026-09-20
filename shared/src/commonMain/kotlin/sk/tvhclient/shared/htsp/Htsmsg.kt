package sk.tvhclient.shared.htsp

/**
 * HTSMSG binary serialization (Tvheadend HTSP). Ported from the plugin (htsp.py).
 * Field: [type:1][nameLen:1][dataLen:4 BE] + name + data.
 * Message: [bodyLen:4 BE] + serialized map.
 * Types: MAP=1, S64=2 (int, little-endian min bytes), STR=3, BIN=4, LIST=5.
 */
internal object Htsmsg {
    /**
     * M673: BIN field as a slice of the message body (without a copy). The muxpkt payload (tens to
     * hundreds of kB for an HEVC keyframe) goes into TsMuxer straight from this slice; whoever needs a
     * standalone array (challenge, subtitles, teletext) calls [toByteArray].
     */
    class Bin(val data: ByteArray, val offset: Int, val length: Int) {
        fun toByteArray(): ByteArray = data.copyOfRange(offset, offset + length)
    }

    const val MAP = 1
    const val S64 = 2
    const val STR = 3
    const val BIN = 4
    const val LIST = 5

    /** The value can be: Long/Int, String, ByteArray, Map<String,Any?>, List<Any?>, Boolean. */
    private fun serField(name: String, value: Any?): ByteArray {
        val nb = name.encodeToByteArray()
        val (typ, data) = when (value) {
            is Boolean -> S64 to intMin(if (value) 1L else 0L)
            is Int -> S64 to intMin(value.toLong())
            is Long -> S64 to intMin(value)
            is ByteArray -> BIN to value
            is String -> STR to value.encodeToByteArray()
            is Map<*, *> -> MAP to serMap(value)
            is List<*> -> LIST to value.fold(ByteArray(0)) { acc, v -> acc + serField("", v) }
            else -> STR to (value?.toString() ?: "").encodeToByteArray()
        }
        val header = ByteArray(6)
        header[0] = typ.toByte()
        header[1] = nb.size.toByte()
        header[2] = (data.size ushr 24).toByte()
        header[3] = (data.size ushr 16).toByte()
        header[4] = (data.size ushr 8).toByte()
        header[5] = data.size.toByte()
        return header + nb + data
    }

    private fun serMap(d: Map<*, *>): ByteArray {
        var out = ByteArray(0)
        for ((k, v) in d) out += serField(k.toString(), v)
        return out
    }

    /** The whole message with a 4-byte BE length prefix. */
    fun serialize(msg: Map<String, Any?>): ByteArray {
        val body = serMap(msg)
        val len = ByteArray(4)
        len[0] = (body.size ushr 24).toByte()
        len[1] = (body.size ushr 16).toByte()
        len[2] = (body.size ushr 8).toByte()
        len[3] = body.size.toByte()
        return len + body
    }

    /** Minimal bytes of an integer, little-endian (LSB first) as _int_min. */
    private fun intMin(n: Long): ByteArray {
        if (n == 0L) return byteArrayOf(0)
        val out = ArrayList<Byte>()
        var v = n
        while (v != 0L) {
            out.add((v and 0xFF).toByte())
            v = v ushr 8
        }
        return out.toByteArray()
    }

    /** M454: a number from a range of the array without a copy. */
    private fun bin2intRange(b: ByteArray, off: Int, len: Int): Long {
        var n = 0L
        for (i in (off + len - 1) downTo off) {
            n = (n shl 8) or (b[i].toLong() and 0xFF)
        }
        return n
    }


    /** Deserializes the body of a map (without the length prefix). */
    fun deserializeMap(data: ByteArray): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return deser(data, false) as Map<String, Any?>
    }

    private fun deser(data: ByteArray, isList: Boolean): Any {
        val resMap = LinkedHashMap<String, Any?>()
        val resList = ArrayList<Any?>()
        var pos = 0
        val n = data.size
        while (pos + 6 <= n) {
            val typ = data[pos].toInt() and 0xFF
            val nl = data[pos + 1].toInt() and 0xFF
            // dataLen as a Long (unsigned 32-bit) — via Int the highest bit would give
            // a negative number and break the check -> OOM in copyOfRange (crash on
            // corrupted/desynchronized data, e.g. leftovers from an old connection)
            val dl = (((data[pos + 2].toLong() and 0xFF) shl 24) or
                    ((data[pos + 3].toLong() and 0xFF) shl 16) or
                    ((data[pos + 4].toLong() and 0xFF) shl 8) or
                    (data[pos + 5].toLong() and 0xFF))
            pos += 6
            // Protection: both dl and nl must be non-negative and fit into the remaining data.
            // If not, the message is corrupted/desynchronized -> abort parsing
            // instead of trying to allocate a huge array (previously an OOM crash).
            // nl is 1 byte (0-255), dl up to 4 bytes. Both must be non-negative and
            // fit into the remaining data — otherwise the data is corrupted, abort.
            if (dl < 0 || nl < 0 || nl > n || pos.toLong() + nl + dl > n) break
            val dlInt = dl.toInt()
            val name = data.decodeToString(pos, pos + nl)
            pos += nl
            // M454: we make a copy ONLY where we really need one (the BIN payload
            // goes on into the muxer). STR/S64 are read directly from the original array and
            // nested MAP/LIST are parsed from a range — that saves one copy per
            // field; with muxpkt tens of kB extra went into the Large Object
            // Space and the GC then ran almost a second every 2-3 s.
            val start = pos
            pos += dlInt
            val v: Any? = when (typ) {
                STR -> data.decodeToString(start, start + dlInt)
                BIN -> Bin(data, start, dlInt)   // M673: without a copy — a slice of the message body
                S64 -> bin2intRange(data, start, dlInt)
                MAP -> deser(data.copyOfRange(start, start + dlInt), false)
                LIST -> deser(data.copyOfRange(start, start + dlInt), true)
                else -> data.copyOfRange(start, start + dlInt)
            }
            if (isList) resList.add(v) else resMap[name] = v
        }
        return if (isList) resList else resMap
    }
}
