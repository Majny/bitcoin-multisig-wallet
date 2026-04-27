package cz.majny.wallet.gateway.http

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/* Root router. Everything user-facing lives under /api/v1 so the gateway can
 * evolve its public surface without breaking older clients on /api/v0, etc.
 * The /health probe is registered in Main.kt's routing block so the same
 * pattern holds across all services. */
fun Application.configureRouting() {
    routing {
        route("/api/v1") {
            authRoutes()
            walletRoutes()
            explorerRoutes()
            psbtRoutes()
            blockchainRoutes()
            priceRoutes()
            accountDiscoveryRoutes()
        }
    }
}


