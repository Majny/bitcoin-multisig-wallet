package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.SignPsbtRequest
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/* Routes for the dedicated signer service. Currently unused at runtime —
 * mobile signing goes through Trezor Suite Mobile via deeplinks — but kept
 * for a possible future direct-USB signing path. */
fun Route.signerRoutes() {
    authenticate("auth-jwt") {
        /* POST /api/v1/hwi/signpsbt — forwards a PSBT to the signer service. */
        post("/hwi/signpsbt") {
            val req = call.receive<SignPsbtRequest>()
            val resp = call.application.deps.signer.signPsbt(req)
            call.respond(resp)
        }
    }
}
