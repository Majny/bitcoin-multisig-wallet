package cz.majny.wallet.gateway.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/* Installs Kotlinx JSON on the server side. ignoreUnknownKeys keeps the gateway
 * resilient to upstream services adding new fields without a coordinated deploy. */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        )
    }
}
