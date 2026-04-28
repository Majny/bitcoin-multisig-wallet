package cz.majny.wallet.blockchain

import cz.majny.wallet.blockchain.client.MempoolClientImpl
import cz.majny.wallet.blockchain.config.AppConfig
import cz.majny.wallet.blockchain.http.blockchainRoutes
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/*
 * blockchain-service entry point. Wires two MempoolClientImpl instances
 * (mainnet = Blockstream, testnet4 = Mempool.space) around a single OkHttp
 * client, and exposes them through blockchainRoutes on port 8086 by default.
 *
 * OkHttp engine was picked over CIO after repeated "Connection reset" errors
 * against mempool.space/testnet4 — see memory/bug #19.
 */
fun main() {
    val cfg = AppConfig.fromEnv()

    // Shared HTTP client for both mainnet and testnet — upstream APIs are
    // Esplora-compatible so only the base URL differs.
    val httpClient = HttpClient(OkHttp) {
        install(ClientContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            // Fail fast when Blockstream/mempool hangs — MempoolClient retries
            // once anyway, so a 15 s ceiling is enough.
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    val mainnetClient = MempoolClientImpl(
        baseUrl = cfg.mainnetMempoolUrl,
        feesUrl = cfg.mainnetFeesUrl,
        client = httpClient
    )
    val testnetClient = MempoolClientImpl(
        baseUrl = cfg.testnetMempoolUrl,
        feesUrl = cfg.testnetFeesUrl,
        client = httpClient
    )

    embeddedServer(Netty, host = "0.0.0.0", port = cfg.port) {
        install(ServerContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }

        install(CallLogging) {
            level = Level.INFO
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Unknown error"))
                )
            }
        }

        routing {
            get("/health") {
                call.respondText("ok")
            }

            blockchainRoutes(mainnetClient, testnetClient)
        }
    }.start(wait = true)
}
