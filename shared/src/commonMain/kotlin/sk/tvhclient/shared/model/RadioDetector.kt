package sk.tvhclient.shared.model

/**
 * ZALOZNE rozpoznanie radia podla nazvov tagov (prebrane z Enigma2 pluginu,
 * _bouquet_tags.py _is_radio_by_tags).
 *
 * M504: hlavna cesta je teraz typ sluzby z DVB tabuliek (Channel.isRadioByService),
 * rovnako ako v Kodi. Tato heuristika sa pouzije len ked server typy neposkytne —
 * je krehka, lebo zavisi od toho, ako si kto tagy pomenoval (nemecke „Hoerfunk"
 * alebo turecke „Radyo" tu nenajde).
 */
object RadioDetector {
    // M504: doplnene dalsie jazyky — zaloha ma zabrat aspon v beznych pripadoch
    private val radioTokens = listOf(
        "radio", "radia", "radia fm", "radio fm",
        "radiostanice", "radiostanica", "rozhlas",
        "radyo", "hoerfunk", "horfunk", "rundfunk", "radioem"
    )

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'ä', 'à', 'â' -> 'a'
                    'č', 'ç' -> 'c'
                    'ď' -> 'd'
                    'é', 'ě', 'è', 'ê' -> 'e'
                    'í', 'ì', 'î' -> 'i'
                    'ľ', 'ĺ' -> 'l'
                    'ň' -> 'n'
                    'ó', 'ô', 'ö' -> 'o'
                    'ŕ', 'ř' -> 'r'
                    'š', 'ś' -> 's'
                    'ť' -> 't'
                    'ú', 'ů', 'ü' -> 'u'
                    'ý' -> 'y'
                    'ž', 'ź' -> 'z'
                    else -> c
                }
            )
        }
        return sb.toString().trim()
    }

    /** Je kanal radio podla nazvov jeho tagov? */
    fun isRadio(tagNames: List<String>): Boolean {
        for (raw in tagNames) {
            val n = normalize(raw)
            if (n.isEmpty()) continue
            for (tok in radioTokens) {
                val t = normalize(tok)
                if (n == t || n.contains(t)) return true
            }
        }
        return false
    }
}
