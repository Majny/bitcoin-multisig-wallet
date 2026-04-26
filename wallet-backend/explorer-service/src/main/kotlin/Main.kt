package cz.majny.wallet.explorer

import cz.majny.wallet.explorer.client.BlockchainClient
import cz.majny.wallet.explorer.client.RegistryClient
import cz.majny.wallet.explorer.http.explorerRoutes
import cz.majny.wallet.explorer.service.WalletExplorer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
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
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("ExplorerService")

/*
 * explorer-service entry point. Aggregates wallet-level data by joining
 * registry (addresses, descriptors) with blockchain-service (on-chain
 * balances, UTXOs, transactions). Default port 8083.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8083
    val registryUrl = System.getenv("REGISTRY_URL") ?: "http://localhost:8082"
    val blockchainUrl = System.getenv("BLOCKCHAIN_URL") ?: "http://localhost:8086"

    logger.info("Starting Explorer Service on port {}", port)
    logger.info("Registry: {}, Blockchain: {}", registryUrl, blockchainUrl)

    val httpClient = HttpClient(CIO) {
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
            // blockchain-service batches up to 8 rounds of 5 addresses each (Semaphore 5).
            // Each round can take up to 15 s if Blockstream is slow → 8×15 = 120 s worst case.
            // In practice most rounds finish in 1-3 s, but give 50 s to handle a few slow ones.
            requestTimeoutMillis = 50_000
            connectTimeoutMillis = 10_000
        }
    }

    val registryClient = RegistryClient(registryUrl, httpClient)
    val blockchainClient = BlockchainClient(blockchainUrl, httpClient)
    val walletExplorer = WalletExplorer(registryClient, blockchainClient)

    embeddedServer(Netty, host = "0.0.0.0", port = port) {
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
                logger.error("Unhandled exception", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Unknown error"))
                )
            }
        }

        routing {
            get("/health") {
                call.respond(mapOf("status" to "ok", "service" to "explorer-service"))
            }

            explorerRoutes(walletExplorer, blockchainClient)
        }
    }.start(wait = true)
}
