package cz.majny.wallet.blockchain

import cz.majny.wallet.blockchain.client.MempoolClientImpl
import cz.majny.wallet.blockchain.config.AppConfig
import cz.majny.wallet.blockchain.http.blockchainRoutes
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
import org.slf4j.event.Level

fun main() {
    val cfg = AppConfig.fromEnv()
    
    // Create HTTP client for Mempool.space API
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
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }
    
    val mainnetClient = MempoolClientImpl(
        baseUrl = cfg.mainnetMempoolUrl,
        client = httpClient
    )
    val testnetClient = MempoolClientImpl(
        baseUrl = cfg.testnetMempoolUrl,
        client = httpClient
    )

    embeddedServer(Netty, host = "0.0.0.0", port = cfg.port) {
        // Plugins
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
        
        // Routes
        routing {
            get("/health") { 
                call.respondText("ok") 
            }
            
            blockchainRoutes(mainnetClient, testnetClient)
        }
    }.start(wait = true)
}
