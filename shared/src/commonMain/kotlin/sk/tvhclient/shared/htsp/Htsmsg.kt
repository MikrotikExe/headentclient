package sk.tvhclient.shared.htsp

/**
 * HTSMSG binarna serializacia (Tvheadend HTSP). Prenos z pluginu (htsp.py).
 * Pole: [typ:1][nameLen:1][dataLen:4 BE] + name + data.
 * Sprava: [bodyLen:4 BE] + serializovana mapa.
 * Typy: MAP=1, S64=2 (int, little-endian min bytes), STR=3, BIN=4, LIST=5.
 */
internal object Htsmsg {
    const val MAP = 1
    const val S64 = 2
    const val STR = 3
    const val BIN = 4
    const val LIST = 5

    /** Hodnota moze byt: Long/Int, String, ByteArray, Map<String,Any?>, List<Any?>, Boolean. */
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

    /** Cela sprava s 4-bajtovym BE length prefixom. */
    fun serialize(msg: Map<String, Any?>): ByteArray {
        val body = serMap(msg)
        val len = ByteArray(4)
        len[0] = (body.size ushr 24).toByte()
        len[1] = (body.size ushr 16).toByte()
        len[2] = (body.size ushr 8).toByte()
        len[3] = body.size.toByte()
        return len + body
    }

    /** Minimalne bajty integeru, little-endian (LSB prvy) ako _int_min. */
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

    private fun bin2int(b: ByteArray): Long {
        var n = 0L
        for (i in b.indices.reversed()) {
            n = (n shl 8) or (b[i].toLong() and 0xFF)
        }
        return n
    }

    /** Deserializuje telo mapy (bez length prefixu). */
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
            // dataLen ako Long (unsigned 32-bit) — cez Int by najvyssi bit daval
            // zaporne cislo a rozbil kontrolu -> OOM pri copyOfRange (crash pri
            // poskodenych/rozsynchronizovanych datach, napr. zvysky po starom spojeni)
            val dl = (((data[pos + 2].toLong() and 0xFF) shl 24) or
                    ((data[pos + 3].toLong() and 0xFF) shl 16) or
                    ((data[pos + 4].toLong() and 0xFF) shl 8) or
                    (data[pos + 5].toLong() and 0xFF))
            pos += 6
            // Ochrana: dl aj nl musia byt nezaporne a zmestit sa do zvysku dat.
            // Ak nie, sprava je poskodena/rozsynchronizovana -> prerus parsovanie
            // namiesto pokusu alokovat obrovske pole (predtym crash OOM).
            // nl je 1 bajt (0-255), dl az 4 bajty. Oba musia byt nezaporne a
            // zmestit sa do zvysku dat — inak su data poskodene, preruš.
            if (dl < 0 || nl < 0 || nl > n || pos.toLong() + nl + dl > n) break
            val dlInt = dl.toInt()
            val name = data.decodeToString(pos, pos + nl)
            pos += nl
            val pay = data.copyOfRange(pos, pos + dlInt)
            pos += dlInt
            val v: Any? = when (typ) {
                STR -> pay.decodeToString()
                BIN -> pay
                S64 -> bin2int(pay)
                MAP -> deser(pay, false)
                LIST -> deser(pay, true)
                else -> pay
            }
            if (isList) resList.add(v) else resMap[name] = v
        }
        return if (isList) resList else resMap
    }
}
