package sk.tvhclient.shared.net

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import sk.tvhclient.shared.model.TvhServer

/**
 * Android: OkHttp engine. Auth riesi rovnaky DigestAuthenticator ako picony a
 * prehravanie — pokryje MD5/SHA-256/SHA-512-256 digest aj basic. Tym API vrstva
 * (kanaly/EPG/DVR zoznam) funguje na vsetkych auth konfiguraciach servera.
 */
actual fun tvhHttpClient(server: TvhServer, json: Json): HttpClient {
    val hasCreds = server.username.isNotEmpty()
    val preemptiveBasic: String? = if (hasCreds && server.authMode != "digest" && server.authMode != "none") {
        "Basic " + Base64.encodeToString(
            "${server.username}:${server.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
    } else null

    return HttpClient(OkHttp) {
        tvhCommonConfig(json)
        engine {
            if (preemptiveBasic != null) {
                addInterceptor(Interceptor { chain ->
                    val r = chain.request()
                    val req = if (r.header("Authorization") == null)
                        r.newBuilder().header("Authorization", preemptiveBasic).build()
                    else r
                    chain.proceed(req)
                })
            }
            if (hasCreds && server.authMode != "none") {
                config {
                    authenticator(DigestAuthenticator(server.username, server.password))
                }
            }
        }
    }
}
