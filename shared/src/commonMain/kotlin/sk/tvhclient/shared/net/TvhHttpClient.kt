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
 * M255 — platform factory for the HTTP client for the TVH API.
 * Android: OkHttp engine + DigestAuthenticator (MD5/SHA-256/SHA-512-256) +
 * preemptive Basic, so the API (channel/EPG/DVR lists) works with all of the
 * server's auth configurations, not just MD5 like the stock Ktor digest.
 * iOS: Darwin engine + Ktor basic/digest (MD5).
 */
expect fun tvhHttpClient(server: TvhServer, json: Json): HttpClient

/** Shared configuration independent of the engine (without auth — that is platform specific). */
internal fun HttpClientConfig<*>.tvhCommonConfig(json: Json) {
    expectSuccess = false
    install(UserAgent) { agent = sk.tvhclient.shared.ClientIdent.userAgent }
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
}
