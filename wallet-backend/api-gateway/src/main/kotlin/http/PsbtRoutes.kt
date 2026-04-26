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

            /* POST /api/v1/psbt — creates a PSBT for the supplied walletId.
             * Ownership is enforced: caller must be a member of that wallet. */
            post {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val req = call.receive<CreatePsbtRequest>()
                if (!verifyWalletAccess(deviceId, req.walletId)) return@post
                val resp = call.application.deps.psbt.create(req)
                call.respond(resp)
            }

            /* GET /api/v1/psbt/{id} — full PSBT detail. */
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val id = call.parameters["id"] ?: error("id missing")
                val resp = call.application.deps.psbt.getById(id)
                if (!verifyWalletAccess(deviceId, resp.walletId)) return@get
                call.respond(resp)
            }

            /* GET /api/v1/psbt/wallet/{walletId} — PSBT list, optional ?status= filter. */
            get("/wallet/{walletId}") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                if (!verifyWalletAccess(deviceId, walletId)) return@get
                val status = call.request.queryParameters["status"]
                val resp = call.application.deps.psbt.getByWallet(walletId, status)
                call.respond(resp)
            }

            /* POST /api/v1/psbt/{id}/broadcast-raw — propagates a complete signed
             * tx (the one Trezor Connect returned as serializedTx) to the network. */
            post("/{id}/broadcast-raw") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val id = call.parameters["id"] ?: error("id missing")
                val psbt = call.application.deps.psbt.getById(id)
                if (!verifyWalletAccess(deviceId, psbt.walletId)) return@post
                val req = call.receive<BroadcastRawTxRequest>()
                val resp = call.application.deps.psbt.broadcastRaw(id, req)
                call.respond(resp)
            }

            /* POST /api/v1/psbt/{id}/sign-trezor — records a cosigner's signatures
             * (multisig path). psbt-service handles BIP-67 placement server-side. */
            post("/{id}/sign-trezor") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val id = call.parameters["id"] ?: error("id missing")
                val psbt = call.application.deps.psbt.getById(id)
                if (!verifyWalletAccess(deviceId, psbt.walletId)) return@post
                val req = call.receive<AddTrezorSignaturesRequest>()
                call.application.log.info("GW sign-trezor id=$id fp=${req.fingerprint} cosignerIdx=${req.cosignerIndex} signerAccountIdx=${req.signerAccountIndex} sigs=${req.signatures.size}")
                val resp = call.application.deps.psbt.signTrezor(id, req)
                call.respond(resp)
            }

            /* GET /api/v1/psbt/{id}/signers — per-cosigner signing state, enriched
             * with the caller's private labels. Label lookup failures are
             * non-fatal: we return the generic response without labels. */
            get("/{id}/signers") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val id = call.parameters["id"] ?: error("id missing")
                val psbt = call.application.deps.psbt.getById(id)
                if (!verifyWalletAccess(deviceId, psbt.walletId)) return@get

                val resp = call.application.deps.psbt.getSigners(id)
                val labels = try {
                    call.application.deps.registry.getCosignerLabels(psbt.walletId, deviceId)
                } catch (e: Exception) {
                    call.application.log.warn("Failed to load cosigner labels: {}", e.message)
                    emptyMap()
                }
                val enriched = resp.copy(
                    signers = resp.signers.map { s -> s.copy(label = labels[s.cosignerIndex]) }
                )
                call.respond(enriched)
            }

            /* GET /api/v1/psbt/verify-address — returns Trezor Connect getAddress
             * params for on-device verification of a receive address (Show On
             * Trezor button). */
            get("/verify-address") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val walletId = call.request.queryParameters["walletId"]
                if (walletId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))
                    return@get
                }
                if (!verifyWalletAccess(deviceId, walletId)) return@get
                val index = call.request.queryParameters["index"]?.toIntOrNull() ?: 0
                val cosignerIndex = call.request.queryParameters["cosignerIndex"]?.toIntOrNull() ?: 0
                val signerAccountIndex = call.request.queryParameters["signerAccountIndex"]?.toIntOrNull()
                val resp = call.application.deps.psbt.verifyAddress(walletId, index, cosignerIndex, signerAccountIndex)
                call.respond(resp)
            }

            /* DELETE /api/v1/psbt/{id} — drops an unsent PSBT and releases its
             * reserved UTXOs back into the available pool. */
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
                val deviceId = principal.payload.getClaim("device_id").asString()
                val id = call.parameters["id"] ?: error("id missing")
                val psbt = call.application.deps.psbt.getById(id)
                if (!verifyWalletAccess(deviceId, psbt.walletId)) return@delete
                call.application.deps.psbt.delete(id)
                call.respond(mapOf("deleted" to true))
            }
        }
    }
}

/* Shared guard for PSBT routes. Confirms the authenticated device is listed
 * as a member of the wallet. Responds 404 (unknown wallet) or 403 (not a
 * member) and returns false so the handler can bail out. */
private suspend fun io.ktor.server.routing.RoutingContext.verifyWalletAccess(
    deviceId: String,
    walletId: String
): Boolean {
    return try {
        val wallet = call.application.deps.registry.getWallet(walletId)
        if (wallet.members.none { it.deviceId == deviceId }) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied to wallet $walletId"))
            false
        } else {
            true
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wallet not found"))
        false
    }
}
