package cz.majny.wallet.gateway.http

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/health") { call.respondText("ok") }

        route("/api/v1") {
            authRoutes()
            signerRoutes()
            walletRoutes()
            explorerRoutes()
            psbtRoutes()
            nodeProxyRoutes()
            accountDiscoveryRoutes()
        }
    }
}


