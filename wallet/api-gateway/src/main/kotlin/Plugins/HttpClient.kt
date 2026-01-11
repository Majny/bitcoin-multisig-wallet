package cz.majny.wallet.gateway.plugins

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.util.*
import kotlinx.serialization.json.Json

private val HttpClientKey = AttributeKey<HttpClient>("GatewayHttpClient")

val Application.httpClient: HttpClient
    get() = attributes[HttpClientKey]

fun Application.configureHttpClient() {
    if (attributes.contains(HttpClientKey)) return

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                }
            )
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
    }

    attributes.put(HttpClientKey, client)

    environment.monitor.subscribe(ApplicationStopping) {
        client.close()
    }
}
