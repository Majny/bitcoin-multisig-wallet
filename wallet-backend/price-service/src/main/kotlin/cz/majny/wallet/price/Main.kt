package cz.majny.wallet.price

import cz.majny.wallet.price.client.CoinGeckoClient
import cz.majny.wallet.price.config.AppConfig
import cz.majny.wallet.price.http.priceRoutes
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/*
 * price-service entry point. Thin wrapper around CoinGeckoClient that
 * exposes BTC prices in CZK/USD/EUR and a sats→fiat converter. Caches
 * upstream responses to stay inside CoinGecko's free-tier rate limits.
 */
fun main() {
    val coinGeckoClient = CoinGeckoClient()

    embeddedServer(Netty, port = AppConfig.PORT) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            })
        }

        install(CallLogging)

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.application.environment.log.error("Unhandled exception", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Unknown error"))
                )
            }
        }

        routing {
            get("/health") { call.respondText("ok") }
            priceRoutes(coinGeckoClient)
        }

        // Release the upstream HttpClient on shutdown — otherwise CIO's
        // worker threads keep the JVM from exiting cleanly in tests.
        environment.monitor.subscribe(ApplicationStopped) {
            coinGeckoClient.close()
        }
    }.start(wait = true)
}
