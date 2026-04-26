package cz.majny.wallet.gateway.http

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/* Root router. Everything user-facing lives under /api/v1 so the gateway can
 * evolve its public surface without breaking older clients on /api/v0, etc.
 * Health endpoint stays at /health for docker-compose probes. */
fun Application.configureRouting() {
    routing {
        get("/health") { call.respondText("ok") }

        route("/api/v1") {
            authRoutes()
            signerRoutes()
            walletRoutes()
            explorerRoutes()
            psbtRoutes()
            blockchainRoutes()
            priceRoutes()
            accountDiscoveryRoutes()
        }
    }
}


