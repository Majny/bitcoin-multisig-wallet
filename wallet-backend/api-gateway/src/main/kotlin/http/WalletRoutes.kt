package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.walletRoutes() {
    authenticate("auth-jwt") {
        get("/wallets") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val wallets = call.application.deps.registry.listWallets(deviceId)
            call.respond(wallets)
        }
    }
}
