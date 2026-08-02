package sk.tvhclient.shared.api

/**
 * M472: vysledok DVR operacie.
 *
 * `error` je text zo servera (HTSP aj HTTP ho posielaju v citatelnej podobe,
 * napr. "User does not have access") — appka ho zobrazi tak, ako prisiel,
 * nech pouzivatel vie, preco to neproslo.
 */
data class DvrResult(
    val success: Boolean,
    val error: String? = null,
    /** ID vytvoreneho zaznamu, ak ho server vratil. */
    val id: String? = null,
    /** M491: server neodpovedal v limite — text hlasky doplni UI (preklad). */
    val timeout: Boolean = false
) {
    companion object {
        val OK = DvrResult(true)
        fun fail(msg: String?, timeout: Boolean = false) =
            DvrResult(false, msg, timeout = timeout)
    }
}

/**
 * Spolocne rozhranie pre nahravanie — implementuje ho HTSP aj HTTP cesta,
 * takze UI nemusi vediet, ktorou sa pouzivatel pripaja.
 */
interface DvrService {
    /** Prava pouzivatela; UI podla nich zobrazi alebo skryje nahravanie. */
    suspend fun access(): DvrAccess

    /** Naplanuje nahravku podla EPG udalosti. */
    suspend fun recordEvent(eventId: Long, configId: String? = null): DvrResult

    /** Naplanuje nahravku podla kanala a casu (ak nie je EPG udalost). */
    suspend fun recordTime(
        channelId: String, start: Long, stop: Long,
        title: String, configId: String? = null
    ): DvrResult

    /** Zrusi naplanovanu/beziacu nahravku, zaznam ostane. */
    suspend fun cancel(id: String): DvrResult

    /** Zmaze nahravku aj so suborom. */
    suspend fun delete(id: String): DvrResult
}

/** M472: DVR profil (konfiguracia nahravania) na serveri. */
data class DvrConfig(val uuid: String, val name: String) {
    /** Prazdny nazov = predvoleny profil. */
    val displayName: String get() = if (name.isBlank()) "Predvolený" else name
}
