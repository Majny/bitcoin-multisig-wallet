package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.psbtRoutes() {
    authenticate("auth-jwt") {
        post("/wallets/{walletId}/tx/prepare") {
            val walletId = call.parameters["walletId"] ?: error("walletId missing")
            val req = call.receive<PreparePsbtBackendRequest>()
            val resp = call.application.deps.psbt.prepare(walletId, req)
            call.respond(resp)
        }

        post("/wallets/{walletId}/tx/submit") {
            val walletId = call.parameters["walletId"] ?: error("walletId missing")
            val req = call.receive<SubmitSignedPsbtBackendRequest>()
            val resp = call.application.deps.psbt.submit(walletId, req)
            call.respond(resp)
        }
    }
}
