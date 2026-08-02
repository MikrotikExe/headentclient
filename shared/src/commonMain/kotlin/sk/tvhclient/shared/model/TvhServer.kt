package sk.tvhclient.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class TvhServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 9981,
    val useHttps: Boolean = false,
    val username: String = "",
    val password: String = "",
    val profile: String = "pass",
    // M486: nazov DVR profilu (konfiguracie nahravania) na serveri; prazdne =
    // necha rozhodnut server podla prav konta. Ukladame NAZOV, nie uuid —
    // HTSP addDvrEntry berie nazov, HTTP si uuid dohlada podla nazvu.
    val dvrConfig: String = "",
    // auto = ponuka basic aj digest (Ktor vyberie podla servera);
    // basic / digest = vynuti jednu; none = bez auth (verejny server)
    val authMode: String = "auto",
    // http = REST API (9981); htsp = binarny protokol (9982) na metadata
    val connectionMode: String = "http",
    val htspPort: Int = 9982
) {
    val baseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            return "$scheme://$host:$port"
        }
}
