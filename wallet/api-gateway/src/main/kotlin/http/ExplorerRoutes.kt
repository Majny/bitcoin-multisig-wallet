package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.explorerRoutes() {
    authenticate("auth-jwt") {
        get("/wallets/{walletId}/utxos") {
            val walletId = call.parameters["walletId"] ?: error("walletId missing")
            val utxos = call.application.deps.explorer.getUtxos(walletId)
            call.respond(utxos)
        }
    }
}
