package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.psbtRoutes() {
    authenticate("auth-jwt") {
        route("/psbt") {
            // POST /psbt - vytvoří nové PSBT (ownership checked via walletId in request)
            post {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val req = call.receive<CreatePsbtRequest>()
                // Verify wallet ownership
                try {
                    val wallet = call.application.deps.registry.getWallet(req.walletId)
                    if (wallet.members.none { it.deviceId == deviceId }) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied to wallet ${req.walletId}"))
                        return@post
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wallet not found"))
                    return@post
                }
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
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                // Verify wallet ownership
                try {
                    val wallet = call.application.deps.registry.getWallet(walletId)
                    if (wallet.members.none { it.deviceId == deviceId }) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                        return@get
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wallet not found"))
                    return@get
                }
                val status = call.request.queryParameters["status"]
                val resp = call.application.deps.psbt.getByWallet(walletId, status)
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
                call.application.log.info("GW sign-trezor id=$id fp=${req.fingerprint} cosignerIdx=${req.cosignerIndex} signerAccountIdx=${req.signerAccountIndex} sigs=${req.signatures.size}")
                val resp = call.application.deps.psbt.signTrezor(id, req)
                call.respond(resp)
            }

            // GET /psbt/{id}/signers - stav podpisů (kdo podepsal, kdo chybí)
            get("/{id}/signers") {
                val id = call.parameters["id"] ?: error("id missing")
                val resp = call.application.deps.psbt.getSigners(id)
                call.respond(resp)
            }
            
            // GET /psbt/verify-address - Trezor Connect getAddress params for on-device verification
            get("/verify-address") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val walletId = call.request.queryParameters["walletId"]
                if (walletId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))
                    return@get
                }
                // Verify wallet ownership
                try {
                    val wallet = call.application.deps.registry.getWallet(walletId)
                    if (wallet.members.none { it.deviceId == deviceId }) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                        return@get
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wallet not found"))
                    return@get
                }
                val index = call.request.queryParameters["index"]?.toIntOrNull() ?: 0
                val cosignerIndex = call.request.queryParameters["cosignerIndex"]?.toIntOrNull() ?: 0
                val resp = call.application.deps.psbt.verifyAddress(walletId, index, cosignerIndex)
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
