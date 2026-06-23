package sk.tvhclient.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import sk.tvhclient.shared.model.TvhServer

/**
 * M255 — platformova tovaren HTTP klienta pre TVH API.
 * Android: OkHttp engine + DigestAuthenticator (MD5/SHA-256/SHA-512-256) +
 * preemptivny Basic, takze API (kanaly/EPG/DVR zoznam) funguje na vsetkych
 * auth konfiguraciach servera, nielen MD5 ako stock Ktor digest.
 * iOS: Darwin engine + Ktor basic/digest (MD5).
 */
expect fun tvhHttpClient(server: TvhServer, json: Json): HttpClient

/** Spolocna konfiguracia nezavisla od enginu (bez auth — to je platformove). */
internal fun HttpClientConfig<*>.tvhCommonConfig(json: Json) {
    expectSuccess = false
    install(UserAgent) { agent = "HeadentClient/1.0.0" }
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
}
