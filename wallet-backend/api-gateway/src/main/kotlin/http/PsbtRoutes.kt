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
        // Legacy endpointy pro kompatibilitu
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
        
        // ========== Nové PSBT endpointy ==========
        
        route("/psbt") {
            // POST /psbt - vytvoří nové PSBT
            post {
                val req = call.receive<CreatePsbtRequest>()
                val resp = call.application.deps.psbt.create(req)
                call.respond(resp)
            }
            
            // GET /psbt/{id} - detail PSBT
            get("/{id}") {
                val id = call.parameters["id"] ?: error("id missing")
                val resp = call.application.deps.psbt.getById(id)
                call.respond(resp)
            }
            
            // GET /psbt/wallet/{walletId} - seznam PSBT pro wallet
            get("/wallet/{walletId}") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                val status = call.request.queryParameters["status"]
                val resp = call.application.deps.psbt.getByWallet(walletId, status)
                call.respond(resp)
            }
            
            // POST /psbt/{id}/sign - přidá podpis
            post("/{id}/sign") {
                val id = call.parameters["id"] ?: error("id missing")
                val req = call.receive<AddSignatureRequest>()
                val resp = call.application.deps.psbt.addSignature(id, req)
                call.respond(resp)
            }
            
            // POST /psbt/{id}/combine - kombinuje PSBT
            post("/{id}/combine") {
                val id = call.parameters["id"] ?: error("id missing")
                val req = call.receive<CombinePsbtsRequest>()
                val resp = call.application.deps.psbt.combine(id, req)
                call.respond(resp)
            }
            
            // POST /psbt/{id}/finalize - finalizuje PSBT
            post("/{id}/finalize") {
                val id = call.parameters["id"] ?: error("id missing")
                val resp = call.application.deps.psbt.finalize(id)
                call.respond(resp)
            }
            
            // POST /psbt/{id}/broadcast - broadcastuje transakci
            post("/{id}/broadcast") {
                val id = call.parameters["id"] ?: error("id missing")
                val resp = call.application.deps.psbt.broadcast(id)
                call.respond(resp)
            }

            // POST /psbt/{id}/broadcast-raw - broadcastuje raw signed tx (z Trezor Connect)
            post("/{id}/broadcast-raw") {
                val id = call.parameters["id"] ?: error("id missing")
                val req = call.receive<BroadcastRawTxRequest>()
                val resp = call.application.deps.psbt.broadcastRaw(id, req)
                call.respond(resp)
            }

            // POST /psbt/{id}/sign-trezor - přidá Trezor Connect podpisy (multisig)
            post("/{id}/sign-trezor") {
                val id = call.parameters["id"] ?: error("id missing")
                val req = call.receive<AddTrezorSignaturesRequest>()
                val resp = call.application.deps.psbt.signTrezor(id, req)
                call.respond(resp)
            }

            // GET /psbt/{id}/signers - stav podpisů (kdo podepsal, kdo chybí)
            get("/{id}/signers") {
                val id = call.parameters["id"] ?: error("id missing")
                val resp = call.application.deps.psbt.getSigners(id)
                call.respond(resp)
            }
            
            // DELETE /psbt/{id} - smaže PSBT
            delete("/{id}") {
                val id = call.parameters["id"] ?: error("id missing")
                call.application.deps.psbt.delete(id)
                call.respond(mapOf("deleted" to true))
            }
        }
    }
}
