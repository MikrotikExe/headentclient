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
    // M502: empty = the server decides the profile based on the account. "pass" is only added
    // when building the HTTP URL, where it is required; HTSP does not have to send any profile.
    val profile: String = "",
    // M486: name of the DVR profile (recording configuration) on the server; empty =
    // let the server decide based on the account's permissions. We store the NAME, not the uuid —
    // HTSP addDvrEntry takes the name, HTTP looks the uuid up by the name.
    val dvrConfig: String = "",
    // auto = offers both basic and digest (Ktor picks according to the server);
    // basic / digest = forces one; none = no auth (public server)
    val authMode: String = "auto",
    // http = REST API (9981); htsp = the binary protocol (9982) for metadata
    val connectionMode: String = "http",
    val htspPort: Int = 9982
) {
    val baseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            return "$scheme://$host:$port"
        }
}
