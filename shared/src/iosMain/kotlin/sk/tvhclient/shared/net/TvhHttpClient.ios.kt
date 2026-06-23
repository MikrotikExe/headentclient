package sk.tvhclient.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.digest
import kotlinx.serialization.json.Json
import sk.tvhclient.shared.model.TvhServer

/**
 * iOS: Darwin engine + stock Ktor basic/digest (MD5). Pre digest-only servery
 * so SHA-256/512 plati rovnake obmedzenie ako predtym; Android pokryva vsetko.
 */
actual fun tvhHttpClient(server: TvhServer, json: Json): HttpClient {
    return HttpClient(Darwin) {
        tvhCommonConfig(json)
        if (server.username.isNotEmpty() && server.authMode != "none") {
            install(Auth) {
                if (server.authMode == "auto" || server.authMode == "basic") {
                    basic {
                        credentials { BasicAuthCredentials(server.username, server.password) }
                        realm = null
                    }
                }
                if (server.authMode == "auto" || server.authMode == "digest") {
                    digest {
                        credentials { DigestAuthCredentials(server.username, server.password) }
                    }
                }
            }
        }
    }
}
