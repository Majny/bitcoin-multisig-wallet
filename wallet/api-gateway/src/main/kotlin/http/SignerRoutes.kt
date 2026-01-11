package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.SignPsbtRequest
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.signerRoutes() {
    authenticate("auth-jwt") {
        post("/hwi/signpsbt") {
            val req = call.receive<SignPsbtRequest>()
            val resp = call.application.deps.signer.signPsbt(req)
            call.respond(resp)
        }
    }
}
